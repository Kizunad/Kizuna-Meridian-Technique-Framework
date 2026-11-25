package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodyTemplate;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.Comparator;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 定义不可变，加载失败不会发布半份目录。 */
public final class TechniqueRegistry {
    private static final int MAX_DEFINITIONS = 1024;
    private final Map<TechniqueReference, TechniqueDefinition> definitions;
    private final TechniqueGroups groups;
    private final Map<TechniqueReference, Set<BodyTemplate.Reference>> scopes;
    private final List<TechniqueCatalogSource> sources;
    private final TechniqueAddonCostPayers addonCostPayers;

    private TechniqueRegistry(Map<TechniqueReference, TechniqueDefinition> definitions, TechniqueGroups groups,
                              List<TechniqueCatalogSource> sources,
                              Map<TechniqueReference, Set<BodyTemplate.Reference>> scopes,
                              TechniqueAddonCostPayers addonCostPayers) {
        // 生成消息本身不可变，复制索引后无需额外包装定义。
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.groups = groups;
        this.sources = List.copyOf(sources);
        this.scopes = Map.copyOf(scopes);
        this.addonCostPayers = addonCostPayers.frozen();
    }

    public static TechniqueRegistry load(AddonRegistry addons, List<TechniqueCatalog> catalogs) {
        // 既有编程式入口补充目录下标，同样经过带来源的整体校验。
        var sources = new ArrayList<TechniqueCatalogSource>();
        for (int index = 0; index < catalogs.size(); index++) {
            sources.add(new TechniqueCatalogSource("catalogs[" + index + "]", addons, catalogs.get(index)));
        }
        return loadSources(sources);
    }

    public static TechniqueRegistry loadSources(List<TechniqueCatalogSource> candidates) {
        // 独立 Java 宿主从公共资源索引发现绑定组；显式装配可直接提供已校验的组。
        var grouped = candidates.stream().filter(source -> source.catalog().getBindingTemplateGroupsCount() > 0)
                .findFirst();
        var templateGroups = grouped.map(source -> TechniqueTemplateGroups.load(source.addons(),
                        TemplateGroupSource.classpath(TechniqueRegistry.class.getClassLoader())))
                .orElseGet(TechniqueTemplateGroups::empty);
        return loadSources(candidates, templateGroups, TechniqueAddonCostPayers.empty());
    }

    public static TechniqueRegistry loadSources(List<TechniqueCatalogSource> candidates,
                                                TechniqueAddonCostPayers addonCostPayers) {
        // 编程式宿主显式提供硬依赖支付方，再与目录一起完成整体校验。
        var grouped = candidates.stream().filter(source -> source.catalog().getBindingTemplateGroupsCount() > 0)
                .findFirst();
        var templateGroups = grouped.map(source -> TechniqueTemplateGroups.load(source.addons(),
                        TemplateGroupSource.classpath(TechniqueRegistry.class.getClassLoader())))
                .orElseGet(TechniqueTemplateGroups::empty);
        return loadSources(candidates, templateGroups, addonCostPayers);
    }

    public static TechniqueRegistry loadSources(List<TechniqueCatalogSource> candidates,
                                                TechniqueTemplateGroups templateGroups) {
        // 无附属费用的既有调用仍走空支付方索引和硬依赖校验。
        return loadSources(candidates, templateGroups, TechniqueAddonCostPayers.empty());
    }

    public static TechniqueRegistry loadSources(List<TechniqueCatalogSource> candidates,
                                                TechniqueTemplateGroups templateGroups,
                                                TechniqueAddonCostPayers addonCostPayers) {
        // 全部定义收齐后再校验书卷和承接；任何失败都不发布半份目录。
        TechniqueAddonCostPayers payers = addonCostPayers.frozen();
        var scopes = new HashMap<TechniqueReference, Set<BodyTemplate.Reference>>();
        List<TechniqueCatalogSource> sources = List.copyOf(candidates);
        var definitions = new LinkedHashMap<TechniqueReference, TechniqueDefinition>();
        var locations = new LinkedHashMap<TechniqueReference, String>();
        for (TechniqueCatalogSource source : sources) {
            try {
                var binding = templateGroups.bind(source.catalog());
                indexCatalog(source, binding.vocabulary(), definitions, locations, payers);
                var members = binding.members().stream()
                        .map(ref -> new BodyTemplate.Reference(new DefinitionId(ref.getId()), ref.getVersion()))
                        .collect(Collectors.toUnmodifiableSet());
                source.catalog().getDefinitionsList().forEach(definition -> scopes.put(reference(definition), members));
            } catch (IllegalArgumentException error) {
                throw TechniqueErrors.at(source.source(), error);
            }
        }
        TechniqueGroups groups = new TechniqueGroups(sources, definitions, locations);
        TechniqueRegistry registry = new TechniqueRegistry(definitions, groups, sources, scopes, payers);
        registry.validateCombatDefinitions(locations);
        registry.validateBenefitIdentities();
        return registry;
    }

    public boolean applies(TechniqueReference technique, BodyTemplate.Reference template) {
        // 目录范围按式和模板的精确版本索引。
        return scopes.getOrDefault(technique, Set.of()).contains(template);
    }

    private void validateBenefitIdentities() {
        // 原版注册表每个状态身份只能对应一份能力定义，跨目录和跨版本重复也拒绝。
        var identities = new HashSet<String>();
        for (TechniqueDefinition definition : definitions.values()) {
            for (TechniqueEffect effect : definition.getEffectsList()) {
                var strike = effect.getWeaponStrike();
                if (strike.hasSplash()) {
                    var coating = strike.getSplash().getCoating();
                    if (!identities.add(coating.getId())) {
                        throw TechniqueErrors.invalid(definition.getId(), "沉积状态身份重复");
                    }
                }
                if (!effect.hasStatusBenefit()) {
                    continue;
                }
                var benefit = effect.getStatusBenefit();
                if (!identities.add(benefit.getId()) || benefit.hasWeaponHeat()
                        && !identities.add(benefit.getWeaponHeat().getQuenched().getId())) {
                    throw TechniqueErrors.invalid(definition.getId(), "状态增益身份重复");
                }
            }
        }
    }

    public List<TechniqueCatalogSource> sources() {
        // 原始目录供兼容 Java entrypoint 合并使用，不能修改注册表内容。
        return sources;
    }

    public TechniqueAddonCostPayers addonCostPayers() {
        // 返回冻结索引，调用方不能在目录加载后替换支付语义。
        return addonCostPayers;
    }

    private static void indexCatalog(TechniqueCatalogSource source, List<BodyMessages.TemplateReference> vocabulary,
                                     Map<TechniqueReference, TechniqueDefinition> definitions,
                                     Map<TechniqueReference, String> locations,
                                     TechniqueAddonCostPayers addonCostPayers) {
        // 模板与标签按各目录自己的 Framework 注册表校验。
        TechniqueCatalog catalog = source.catalog();
        Map<ModuleKind, Set<String>> tags = catalogTags(source, vocabulary);
        for (int index = 0; index < catalog.getDefinitionsCount(); index++) {
            TechniqueDefinition definition = catalog.getDefinitions(index);
            String id = definition.getId();
            String location = source.location("definitions[" + index + "]");
            try {
                TechniqueDefinitions.validate(source.addons(), tags, vocabulary, definition, addonCostPayers);
                if (!id.startsWith(catalog.getNamespace() + ":")) {
                    throw TechniqueErrors.invalid(id, "功法 ID 不属于目录命名空间");
                }
                TechniqueReference reference = reference(definition);
                String previous = locations.putIfAbsent(reference, location);
                if (previous != null) {
                    throw TechniqueErrors.invalid(id, "功法 ID 和版本重复，首次声明：" + previous);
                }
                definitions.put(reference, definition);
                if (definitions.size() > MAX_DEFINITIONS) {
                    throw TechniqueErrors.invalid("definitions", "功法总数超限");
                }
            } catch (IllegalArgumentException exception) {
                throw TechniqueErrors.at(location, exception);
            }
        }
    }

    private static Map<ModuleKind, Set<String>> catalogTags(TechniqueCatalogSource source,
                                                             List<BodyMessages.TemplateReference> vocabulary) {
        // 目录头和外部模板引用先校验，单式错误另行附加定义下标。
        TechniqueCatalog catalog = source.catalog();
        try {
            TechniqueValues.knownFields(catalog);
            TechniqueValues.localId(catalog.getNamespace(), "namespace");
            if (catalog.getSchemaVersion() != TechniqueValues.SCHEMA_VERSION
                    || catalog.getDefinitionsCount() > MAX_DEFINITIONS) {
                throw TechniqueErrors.invalid("catalog", "版本不支持或功法数量超限");
            }
            return registeredTags(source.addons(), vocabulary);
        } catch (IllegalArgumentException exception) {
            throw TechniqueErrors.at(source.source(), exception);
        }
    }

    private void validateCombatDefinitions(Map<TechniqueReference, String> locations) {
        // 世界持物效果必须有书级规则；承接在所有目录装载后再校验精确引用。
        for (TechniqueDefinition definition : definitions.values()) {
            TechniqueReference reference = reference(definition);
            try {
                TechniqueFollowUps.validate(definition, definitions);
                HeldItemRules heldRules = TechniqueHeldItems.rulesFor(this, definition);
                boolean heldEffects = definition.hasProjectileWeapon()
                        || definition.getEffectsList().stream()
                        .anyMatch(effect -> effect.hasWeaponStrike()
                                || effect.hasBreakBlocks()
                                || effect.hasLaunchProjectile()
                                || effect.hasConsumeHeldItem());
                if (heldEffects && !definition.hasBrewItem()
                        && heldRules.getCategoriesCount() == 0) {
                    throw TechniqueErrors.invalid(definition.getId(), "持物效果必须归属于声明持物规则的书");
                }
                if (definition.hasProjectileWeapon()) {
                    TechniqueHeldItems.validateDefinition(heldRules, definition);
                }
                TechniqueHeldItems.validateConsumption(heldRules, definition);
                validateLimbBook(definition, reference);
            } catch (IllegalArgumentException exception) {
                throw TechniqueErrors.at(locations.get(reference), exception);
            }
        }
    }

    private void validateLimbBook(TechniqueDefinition definition, TechniqueReference reference) {
        // 腿击、肢击要所属书声明对应的肢体规则；肢体书忽略手持物品，所以不能再声明持物规则。
        boolean legs = TechniqueLimbStrikes.usesLegs(definition);
        boolean arms = TechniqueLimbStrikes.usesArms(definition);
        if (!legs && !arms) {
            return;
        }
        TechniqueBook book = bookOf(reference).orElseThrow(
                () -> TechniqueErrors.invalid(definition.getId(), "腿击或拳掌打击没有所属书"));
        if (legs && !book.hasLegRules()) {
            throw TechniqueErrors.invalid(definition.getId(), "腿击效果必须归属于声明腿法规则的书");
        }
        if (arms && !book.hasArmRules()) {
            throw TechniqueErrors.invalid(definition.getId(), "拳掌打击必须归属于声明拳掌规则的书");
        }
        if (book.hasHeldItemRules()) {
            throw TechniqueErrors.invalid(definition.getId(), "腿法书和拳掌书不能声明持物规则");
        }
    }

    public Optional<TechniqueVolume> findVolume(String id) {
        // 学习按卷 ID 精确解析，卷内单式仍使用精确版本。
        return Optional.ofNullable(groups.volumes.get(id));
    }

    public List<TechniqueBook> books() {
        // 书按身份排序，目录展示不依赖加载顺序。
        return groups.books.values().stream().sorted(Comparator.comparing(TechniqueBook::getId)).toList();
    }

    public List<TechniqueVolume> volumes() {
        // 卷列表用于目录展示，前置关系仍由各卷声明。
        return groups.volumes.values().stream().sorted(Comparator.comparing(TechniqueVolume::getId)).toList();
    }

    public Optional<TechniqueBook> bookOf(TechniqueReference reference) {
        // 精确式版本所属卷确定书级规则，未分组的既有功法不要求持物。
        String volumeId = groups.membership.get(reference);
        if (volumeId == null) {
            return Optional.empty();
        }
        return Optional.of(groups.books.get(groups.volumes.get(volumeId).getBookId()));
    }

    public Optional<TechniqueDefinition> find(TechniqueReference reference) {
        // 只按精确版本查找，不以其他版本兜底改变已学记录的含义。
        return Optional.ofNullable(definitions.get(reference));
    }

    public Optional<TechniqueDefinition> findLatest(String id) {
        // 管理命令只接收功法 ID，选择目录中最高版本并保持版本选择可复现。
        TechniqueValues.id(id, "technique.id");
        return definitions.values().stream()
                .filter(definition -> definition.getId().equals(id))
                .max(Comparator.comparingInt(TechniqueDefinition::getVersion));
    }

    public List<TechniqueDefinition> definitions() {
        // 按身份排序，保证不同加载顺序下目录遍历结果一致。
        return definitions.values().stream()
                .sorted(Comparator.comparing(TechniqueDefinition::getId)
                        .thenComparingInt(TechniqueDefinition::getVersion))
                .toList();
    }

    static TechniqueReference reference(TechniqueDefinition definition) {
        // 所有运行入口使用同一对内容身份与版本。
        return TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
    }

    private static Map<ModuleKind, Set<String>> registeredTags(AddonRegistry addons,
                                                                List<BodyMessages.TemplateReference> vocabulary) {
        // Framework 标签是开放字符串；显式模板引用限定本目录允许使用的标签词汇。
        var tags = new HashMap<ModuleKind, Set<String>>();
        tags.put(ModuleKind.MERIDIAN, new HashSet<>());
        tags.put(ModuleKind.TISSUE, new HashSet<>());
        tags.put(ModuleKind.ORGAN, new HashSet<>());
        if (vocabulary.size() > MAX_DEFINITIONS) {
            throw TechniqueErrors.invalid("binding_templates", "模板数量超限");
        }
        for (int index = 0; index < vocabulary.size(); index++) {
            BodyMessages.TemplateReference reference = vocabulary.get(index);
            String id = reference.getId();
            String location = "binding_templates[" + index + "]";
            BodyMessages.BodyTemplate template = addons.findTemplate(id, reference.getVersion())
                    .orElseThrow(() -> TechniqueErrors.invalid(location,
                            "绑定模板未登记或版本不存在：" + id));
            BodyMessages.BodyStructure structure = template.getStructure();
            structure.getMeridiansList().forEach(module -> tags.get(ModuleKind.MERIDIAN)
                    .addAll(module.getTagsList()));
            structure.getTissuesList().forEach(module -> tags.get(ModuleKind.TISSUE).addAll(module.getTagsList()));
            structure.getOrgansList().forEach(module -> tags.get(ModuleKind.ORGAN).addAll(module.getTagsList()));
        }
        return tags;
    }
}
