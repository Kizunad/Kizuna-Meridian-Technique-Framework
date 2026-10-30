package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBook;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 两端共用的卷轴身份与展示声明，不访问 Minecraft 注册表或身体。 */
public final class TechniqueScrollCatalog {
    private final List<Scroll> registrationOrder;
    private final List<Scroll> creativeOrder;

    public TechniqueScrollCatalog(List<TechniqueCatalogSource> sources) {
        // 先收集显示引用，再保持原清单顺序生成每卷唯一身份。
        DisplayIndex display = displayIndex(sources);
        this.registrationOrder = scrolls(sources, display);
        this.creativeOrder = registrationOrder.stream()
                .sorted(Comparator.comparingInt(scroll -> display.bookOrder().get(scroll.volume().getBookId())))
                .toList();
    }

    public List<Scroll> registrationOrder() {
        // 物品数值身份始终按照模组和清单的发现顺序登记。
        return registrationOrder;
    }

    public List<Scroll> creativeOrder() {
        // 同书集中显示，书内保持卷声明顺序，不按上中下的字符串字典序重排。
        return creativeOrder;
    }

    private static DisplayIndex displayIndex(List<TechniqueCatalogSource> sources) {
        // 显示文字可跨清单引用；所有语义关系仍由服务器完整注册表最终校验。
        var definitions = new HashMap<TechniqueReference, TechniqueDefinition>();
        var books = new HashMap<String, TechniqueBook>();
        var volumes = new HashMap<String, TechniqueVolume>();
        var bookOrder = new LinkedHashMap<String, Integer>();
        for (TechniqueCatalogSource source : sources) {
            TechniqueCatalog catalog = source.catalog();
            for (TechniqueBook book : catalog.getBooksList()) {
                bookOrder.putIfAbsent(book.getId(), bookOrder.size());
                books.putIfAbsent(book.getId(), book);
            }
            for (TechniqueDefinition definition : catalog.getDefinitionsList()) {
                definitions.putIfAbsent(TechniqueRegistry.reference(definition), definition);
            }
            for (TechniqueVolume volume : catalog.getVolumesList()) {
                volumes.putIfAbsent(volume.getId(), volume);
            }
        }
        indexUnresolvedBooks(sources, bookOrder);
        return new DisplayIndex(definitions, books, volumes, bookOrder);
    }

    private static void indexUnresolvedBooks(List<TechniqueCatalogSource> sources, Map<String, Integer> bookOrder) {
        // 书由晚到入口提供时仍给提前卷轴确定位置，服务端合并后再校验引用。
        for (TechniqueCatalogSource source : sources) {
            for (TechniqueVolume volume : source.catalog().getVolumesList()) {
                bookOrder.putIfAbsent(volume.getBookId(), bookOrder.size());
            }
        }
    }

    private static List<Scroll> scrolls(List<TechniqueCatalogSource> sources, DisplayIndex display) {
        // 先校验全部候选物品身份，冲突不会留下已注册的部分卷轴。
        var scrolls = new ArrayList<Scroll>();
        var identities = new HashMap<String, String>();
        for (TechniqueCatalogSource source : sources) {
            TechniqueCatalog catalog = source.catalog();
            for (int index = 0; index < catalog.getVolumesCount(); index++) {
                TechniqueVolume volume = catalog.getVolumes(index);
                String location = source.location("volumes[" + index + "]");
                String volumeId = volume.getId();
                TechniqueValues.id(volumeId, location + ".id");
                String itemId = volumeId + "_scroll";
                String previous = identities.putIfAbsent(itemId, location);
                if (previous != null) {
                    throw TechniqueErrors.invalid(location, "卷轴身份重复，首次声明：" + previous);
                }
                scrolls.add(scroll(itemId, volume, display));
            }
        }
        return List.copyOf(scrolls);
    }

    private static Scroll scroll(String itemId, TechniqueVolume volume, DisplayIndex display) {
        // 展示列表固定于初始化目录，客户端只展示，不据此判定能否学习。
        List<NamedTechnique> techniques = volume.getTechniquesList().stream()
                .map(reference -> namedTechnique(reference, display.definitions().get(reference)))
                .toList();
        List<String> prerequisites = volume.getPrerequisiteVolumeIdsList().stream()
                .map(id -> volumeName(id, display.volumes().get(id)))
                .toList();
        String bookId = volume.getBookId();
        String bookName = bookName(bookId, display.books().get(bookId));
        String name = volumeName(volume.getId(), volume);
        return new Scroll(itemId, volume, bookName, name, techniques, prerequisites);
    }

    private static NamedTechnique namedTechnique(TechniqueReference reference, TechniqueDefinition definition) {
        // 保留完整的只读定义，阅读数值与服务端施放共用同一份目录。
        if (definition == null) {
            return new NamedTechnique(reference, TechniqueDefinition.getDefaultInstance());
        }
        return new NamedTechnique(reference, definition);
    }

    private static String bookName(String id, TechniqueBook book) {
        // 晚到引用尚无书名时使用中性名称，不把资源身份暴露为标题。
        if (book == null || book.getDisplayName().isBlank()) {
            return "未命名功法";
        }
        return TechniqueScrollText.safeName(book.getDisplayName(), "未命名功法");
    }

    private static String volumeName(String id, TechniqueVolume volume) {
        // 未解析卷保持可读名称，资源身份仅用于注册与学习。
        if (volume == null) {
            return "未命名卷";
        }
        return TechniqueScrollText.safeName(volume.getDisplayName(), "未命名卷");
    }

    public record Scroll(String itemId, TechniqueVolume volume, String bookName, String name,
                         List<NamedTechnique> techniques, List<String> prerequisites) {
        public Scroll(String itemId, TechniqueVolume volume, String bookName, String name,
                      List<NamedTechnique> techniques, List<String> prerequisites) {
            // 物品持有不可变列表，外部调用不能改变其学习身份或说明。
            this.itemId = itemId;
            this.volume = volume;
            this.bookName = bookName;
            this.name = name;
            this.techniques = List.copyOf(techniques);
            this.prerequisites = List.copyOf(prerequisites);
        }

        public String displayName() {
            // 目录可把书名重复写在卷名中；展示时只保留一次书名。
            if (name.equals(bookName) || name.startsWith(bookName + "·")) {
                return name;
            }
            if (bookName.isBlank()) {
                return name;
            }
            return bookName + "·" + name;
        }
    }

    public record NamedTechnique(TechniqueReference reference, TechniqueDefinition definition) {
        public String fallbackName() {
            // 旧目录省略新字段时使用可读占位，不能再退回 ns:id。
            return TechniqueScrollText.safeName(definition.getPresentation().getDisplayName(), "未命名招式");
        }

        public String description() {
            // 说明与名称独立，长说明不会被当作式名。
            return definition.getPresentation().getDescription();
        }
    }

    private record DisplayIndex(Map<TechniqueReference, TechniqueDefinition> definitions,
                                Map<String, TechniqueBook> books,
                                Map<String, TechniqueVolume> volumes, Map<String, Integer> bookOrder) {}
}
