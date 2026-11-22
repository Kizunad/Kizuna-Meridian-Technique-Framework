package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import dev.kizuna.meridian.technique.server.TechniqueCatalogEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueCatalogs;
import dev.kizuna.meridian.technique.server.TechniqueCatalogPhase;
import dev.kizuna.meridian.technique.server.TechniqueCatalogSource;
import dev.kizuna.meridian.technique.server.TechniqueAddonCostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueAddonCostPayers;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueTemplateGroups;
import dev.kizuna.meridian.technique.server.TemplateGroupSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 按模组 ID 发现清单与 Java 入口，全部内容共用同一注册表。 */
public final class TechniqueCatalogDiscovery {
    public static final String CUSTOM_KEY = "meridian_technique:catalogs";

    private TechniqueCatalogDiscovery() {
        // Fabric 只提供模组来源，目录解析与校验留在纯 Java 服务端库。
    }

    public static TechniqueRegistry discover(AddonRegistry addons) {
        // 显式宿主可直接复用自己的已校验模板注册表。
        return discover(catalog -> addons);
    }

    public static TechniqueRegistry discover(Function<TechniqueCatalog, AddonRegistry> addons) {
        // 使用真实 Fabric 容器解析模组资源，不从工作目录或任意文件系统扫描。
        return prepare(addons).registry();
    }

    public static TechniqueCatalogPlan prepare(Function<TechniqueCatalog, AddonRegistry> addons) {
        // 两端在模组初始化期读取相同的声明清单，晚到入口保留确定位置。
        FabricLoader loader = FabricLoader.getInstance();
        var entrypoints = loader.getEntrypointContainers(
                TechniqueFabric.REGISTRY_ENTRYPOINT, Object.class);
        var addonCostEntrypoints = loader.getEntrypointContainers(
                TechniqueAddonCostEntrypoint.ENTRYPOINT_ID, Object.class);
        return prepare(loader.getAllMods(), entrypoints, addonCostEntrypoints, addons);
    }

    static TechniqueRegistry discover(Collection<ModContainer> installed,
            List<EntrypointContainer<Object>> entrypoints,
            Function<TechniqueCatalog, AddonRegistry> addons) {
        // 同一模组先按声明顺序收集清单，再按声明顺序收集 Java 入口。
        return prepare(installed, entrypoints, addons).registry();
    }

    static TechniqueCatalogPlan prepare(Collection<ModContainer> installed,
            List<EntrypointContainer<Object>> entrypoints,
            Function<TechniqueCatalog, AddonRegistry> addons) {
        // 旧发现测试不声明支付方，仍使用空索引校验不含附属费用的目录。
        return prepare(installed, entrypoints, List.of(), addons);
    }

    static TechniqueCatalogPlan prepare(Collection<ModContainer> installed,
            List<EntrypointContainer<Object>> entrypoints,
            List<EntrypointContainer<Object>> addonCostEntrypoints,
            Function<TechniqueCatalog, AddonRegistry> addons) {
        // 按来源排序后固定每个贡献者的位置，不按初始化回调完成顺序登记。
        List<ModContainer> mods = installed.stream()
                .sorted(Comparator.comparing(mod -> mod.getMetadata().getId()))
                .toList();
        TechniqueTemplateGroups groups = templateGroups(addons);
        TechniqueAddonCostPayers payers = addonCostPayers(mods, addonCostEntrypoints);
        var contributions = new ArrayList<TechniqueCatalogPlan.Contribution>();
        for (ModContainer mod : mods) {
            String modId = mod.getMetadata().getId();
            for (String path : customPaths(mod)) {
                String source = modId + ":" + path;
                contributions.add(TechniqueCatalogPlan.Contribution.prepare(
                        TechniqueCatalogPhase.MOD_INITIALIZATION,
                        () -> manifest(mod, path, source, catalog -> addons.apply(groups.validationContext(catalog)))));
            }
            collectEntrypoints(modId, entrypoints, contributions);
        }
        return new TechniqueCatalogPlan(contributions, groups, payers);
    }

    private static TechniqueAddonCostPayers addonCostPayers(List<ModContainer> mods,
            List<EntrypointContainer<Object>> entrypoints) {
        // 所有附属支付方在任何目录加载前登记，缺失键会由完整目录校验明确拒绝。
        var payers = new TechniqueAddonCostPayers();
        for (ModContainer mod : mods) {
            String modId = mod.getMetadata().getId();
            int index = 0;
            for (EntrypointContainer<Object> container : entrypoints) {
                if (!container.getProvider().getMetadata().getId().equals(modId)) {
                    continue;
                }
                String source = modId + ":entrypoint/" + TechniqueAddonCostEntrypoint.ENTRYPOINT_ID
                        + "[" + index + "]";
                Object entrypoint = container.getEntrypoint();
                if (!(entrypoint instanceof TechniqueAddonCostEntrypoint provider)) {
                    throw new IllegalArgumentException(source + "：费用入口必须实现 TechniqueAddonCostEntrypoint");
                }
                try {
                    provider.registerPayers(payers);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(source + "：" + failure.getMessage(), failure);
                }
                index++;
            }
        }
        return payers.frozen();
    }

    private static TechniqueTemplateGroups templateGroups(Function<TechniqueCatalog, AddonRegistry> addons) {
        // 即使没有目录引用该组，错误声明和追加项也会阻止启动。
        var sources = TemplateGroupSource.classpath(TechniqueCatalogDiscovery.class.getClassLoader());
        if (sources.isEmpty()) {
            return TechniqueTemplateGroups.empty();
        }
        var context = TechniqueTemplateGroups.templateContext(sources);
        return TechniqueTemplateGroups.load(addons.apply(context), sources);
    }

    private static List<TechniqueCatalogSource> manifest(ModContainer mod, String path, String source,
            Function<TechniqueCatalog, AddonRegistry> addons) {
        // 模板资源与目录一起固定，初始化后不重读文件。
        TechniqueCatalog catalog = TechniqueCatalogManifest.read(source, resource(mod, path));
        try {
            return List.of(new TechniqueCatalogSource(source, addons.apply(catalog), catalog));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(source + "：模板注册表不可用："
                    + exception.getMessage(), exception);
        }
    }

    private static void collectEntrypoints(String modId, List<EntrypointContainer<Object>> entrypoints,
            List<TechniqueCatalogPlan.Contribution> contributions) {
        // 旧入口默认晚到，明确声明可提前求值的代码目录也能生成卷轴。
        int index = 0;
        for (var container : entrypoints) {
            if (!container.getProvider().getMetadata().getId().equals(modId)) {
                continue;
            }
            String source = modId + ":entrypoint/" + TechniqueFabric.REGISTRY_ENTRYPOINT + "[" + index + "]";
            try {
                Object provider = container.getEntrypoint();
                contributions.add(TechniqueCatalogPlan.Contribution.prepare(
                        phase(provider), () -> codeCatalogs(source, provider)));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(source + "：" + exception.getMessage(), exception);
            }
            index++;
        }
    }

    private static TechniqueCatalogPhase phase(Object entrypoint) {
        // 未实现公开接口的声明仍直接拒绝，不能被解释为没有目录。
        if (entrypoint instanceof TechniqueCatalogEntrypoint provider) {
            return Objects.requireNonNull(provider.phase(), "catalog phase");
        }
        if (entrypoint instanceof TechniqueRegistryEntrypoint provider) {
            return Objects.requireNonNull(provider.phase(), "catalog phase");
        }
        throw new IllegalArgumentException("目录入口必须实现 TechniqueCatalogEntrypoint"
                + " 或 TechniqueRegistryEntrypoint");
    }

    private static List<TechniqueCatalogSource> codeCatalogs(String source, Object entrypoint) {
        // 两种代码接口都收集成原始候选，并由服务器启动统一校验。
        var catalogs = new TechniqueCatalogs();
        try {
            if (entrypoint instanceof TechniqueCatalogEntrypoint provider) {
                provider.registerCatalogs(source, catalogs);
            } else if (entrypoint instanceof TechniqueRegistryEntrypoint provider) {
                catalogs.register(source, provider.registry());
            }
            return catalogs.sources();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(source + "：" + exception.getMessage(), exception);
        }
    }

    static List<String> customPaths(ModContainer mod) {
        // 单字符串与字符串数组等价；其他值类型及非字符串成员直接报错。
        var metadata = mod.getMetadata();
        CustomValue value = metadata.getCustomValue(CUSTOM_KEY);
        if (value == null) {
            return List.of();
        }
        String location = metadata.getId() + ":custom." + CUSTOM_KEY;
        if (value.getType() == CustomValue.CvType.STRING) {
            return List.of(value.getAsString());
        }
        if (value.getType() != CustomValue.CvType.ARRAY) {
            throw new IllegalArgumentException(location + "：必须为字符串或字符串数组");
        }
        var paths = new ArrayList<String>();
        for (CustomValue element : value.getAsArray()) {
            if (element.getType() != CustomValue.CvType.STRING) {
                throw new IllegalArgumentException(location + "[" + paths.size() + "]：必须为字符串");
            }
            paths.add(element.getAsString());
        }
        return List.copyOf(paths);
    }

    private static Path resource(ModContainer mod, String path) {
        // 路径仅指向所属模组资源；后缀明确决定解析格式。
        String source = mod.getMetadata().getId() + ":" + path;
        List<String> parts = Arrays.asList(path.split("/"));
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || parts.contains("..")
                || !(path.endsWith(TechniqueCatalogManifest.TEXT_SUFFIX)
                || path.endsWith(TechniqueCatalogManifest.BINARY_SUFFIX))) {
            throw new IllegalArgumentException(source + "：清单必须为模组内的 .txtpb 或 .binpb"
                    + " 相对路径");
        }
        return mod.findPath(path)
                .orElseThrow(() -> new IllegalArgumentException(source + "：模组内找不到清单文件"));
    }
}
