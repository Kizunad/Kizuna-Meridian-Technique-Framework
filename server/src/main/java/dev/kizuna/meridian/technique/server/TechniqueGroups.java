package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBook;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 目录中的书卷引用及学习前置关系，加载完成后不可变。 */
final class TechniqueGroups {
    private static final int MAX_GROUPS = 1024;
    final Map<String, TechniqueBook> books;
    final Map<String, TechniqueVolume> volumes;
    final Map<TechniqueReference, String> membership;
    private final Map<String, String> locations = new HashMap<>();

    TechniqueGroups(List<TechniqueCatalogSource> sources,
                    Map<TechniqueReference, TechniqueDefinition> definitions,
                    Map<TechniqueReference, String> definitionLocations) {
        // 先建立全部身份，再解析跨目录引用，避免加载顺序改变结果。
        var bookIndex = new LinkedHashMap<String, TechniqueBook>();
        var volumeIndex = new LinkedHashMap<String, TechniqueVolume>();
        var identities = new HashMap<String, String>();
        definitionLocations.forEach((reference, location) -> identities.putIfAbsent(reference.getId(), location));
        for (TechniqueCatalogSource source : sources) {
            indexBooks(source, bookIndex, identities);
            indexVolumes(source, volumeIndex, identities);
        }
        this.books = Map.copyOf(bookIndex);
        this.volumes = Collections.unmodifiableMap(volumeIndex);
        this.membership = validateVolumes(definitions);
        validateAcyclic();
    }

    private void indexBooks(TechniqueCatalogSource source, Map<String, TechniqueBook> index,
                            Map<String, String> identities) {
        // 书的身份与持物规则在建立索引时一起校验。
        TechniqueCatalog catalog = source.catalog();
        for (int position = 0; position < catalog.getBooksCount(); position++) {
            TechniqueBook book = catalog.getBooks(position);
            String id = book.getId();
            String location = source.location("books[" + position + "]");
            try {
                validateIdentity(source, id, identities, location);
                if (book.hasHeldItemRules()) {
                    TechniqueHeldItems.validateRules(book.getHeldItemRules());
                }
                validateName(book.getDisplayName());
                index.put(id, book);
                validateCount(index.size());
            } catch (IllegalArgumentException exception) {
                throw TechniqueErrors.at(location, exception);
            }
        }
    }

    private void indexVolumes(TechniqueCatalogSource source, Map<String, TechniqueVolume> index,
                              Map<String, String> identities) {
        // 卷先登记身份，前置卷与所属书在完整目录上解析。
        TechniqueCatalog catalog = source.catalog();
        for (int position = 0; position < catalog.getVolumesCount(); position++) {
            TechniqueVolume volume = catalog.getVolumes(position);
            String id = volume.getId();
            String location = source.location("volumes[" + position + "]");
            try {
                validateIdentity(source, id, identities, location);
                validateName(volume.getDisplayName());
                index.put(id, volume);
                validateCount(index.size());
            } catch (IllegalArgumentException exception) {
                throw TechniqueErrors.at(location, exception);
            }
        }
    }

    private void validateIdentity(TechniqueCatalogSource source, String id,
                                  Map<String, String> identities, String location) {
        // 书、卷与单式共用命令参数，身份不得重复或跨命名空间。
        TechniqueValues.id(id, "group.id");
        if (!id.startsWith(source.catalog().getNamespace() + ":")) {
            throw TechniqueErrors.invalid(id, "书卷身份不属于目录命名空间");
        }
        String previous = identities.putIfAbsent(id, location);
        if (previous != null) {
            throw TechniqueErrors.invalid(id, "书卷身份重复或与单式冲突，首次声明：" + previous);
        }
        locations.put(id, location);
    }

    private static void validateCount(int count) {
        // 书和卷分别限制总量，超限时由调用方补充当前条目的来源。
        if (count > MAX_GROUPS) {
            throw TechniqueErrors.invalid("groups", "书或卷数量超限");
        }
    }

    private static void validateName(String name) {
        // 名称可缺省，以稳定 ID 展示；提供时沿用功法说明长度上限。
        if (name.length() > TechniqueValues.MAX_DESCRIPTION) {
            throw TechniqueErrors.invalid("display_name", "名称超长");
        }
    }

    private Map<TechniqueReference, String> validateVolumes(Map<TechniqueReference, TechniqueDefinition> definitions) {
        // 每个精确式版本只属于一卷，书级持物规则因此只有一个确定来源。
        var members = new HashMap<TechniqueReference, String>();
        for (TechniqueVolume volume : volumes.values()) {
            try {
                validateVolume(volume, definitions, members);
                validatePrerequisites(volume);
            } catch (IllegalArgumentException exception) {
                throw TechniqueErrors.at(locations.get(volume.getId()), exception);
            }
        }
        return Map.copyOf(members);
    }

    private void validateVolume(TechniqueVolume volume, Map<TechniqueReference, TechniqueDefinition> definitions,
                                Map<TechniqueReference, String> members) {
        // 卷的书引用与单式引用在全部候选登记后检查。
        String bookId = volume.getBookId();
        String volumeId = volume.getId();
        TechniqueValues.id(bookId, "volume.book_id");
        int count = volume.getTechniquesCount();
        if (!books.containsKey(bookId) || count == 0 || count > MAX_GROUPS) {
            throw TechniqueErrors.invalid(volumeId, "书不存在或卷内式数量非法");
        }
        for (int index = 0; index < count; index++) {
            TechniqueReference reference = volume.getTechniques(index);
            TechniqueProtocol.validateReference(reference);
            String previous = members.putIfAbsent(reference, volumeId);
            if (!definitions.containsKey(reference) || previous != null) {
                throw TechniqueErrors.invalid("techniques[" + index + "]", "单式版本不存在、重复或同时属于多卷");
            }
        }
    }

    private void validatePrerequisites(TechniqueVolume volume) {
        // 前置卷只能来自同一本书，重复、自引用与未知身份均在加载时拒绝。
        List<String> prerequisites = volume.getPrerequisiteVolumeIdsList();
        String volumeId = volume.getId();
        String bookId = volume.getBookId();
        int count = prerequisites.size();
        if (count > MAX_GROUPS || new HashSet<>(prerequisites).size() != count) {
            throw TechniqueErrors.invalid(volumeId, "前置卷重复或数量超限");
        }
        for (int index = 0; index < count; index++) {
            String id = prerequisites.get(index);
            TechniqueValues.id(id, "prerequisite_volume_ids");
            TechniqueVolume prerequisite = volumes.get(id);
            if (prerequisite == null || id.equals(volumeId)
                    || !prerequisite.getBookId().equals(bookId)) {
                throw TechniqueErrors.invalid("prerequisite_volume_ids[" + index + "]",
                        "前置卷不存在、自引用或不属于同书：" + id);
            }
        }
    }

    private void validateAcyclic() {
        // 逐轮移除所有前置已就绪的卷，剩余节点无进展时说明存在学习环。
        var pending = new HashSet<>(volumes.keySet());
        var ready = new HashSet<String>();
        while (!pending.isEmpty()) {
            List<String> resolved = pending.stream()
                    .filter(id -> ready.containsAll(volumes.get(id).getPrerequisiteVolumeIdsList()))
                    .toList();
            if (resolved.isEmpty()) {
                List<String> cycle = pending.stream().sorted().map(locations::get).toList();
                throw TechniqueErrors.invalid(cycle.toString(), "prerequisite_volume_ids：前置卷关系存在环");
            }
            pending.removeAll(resolved);
            ready.addAll(resolved);
        }
    }
}
