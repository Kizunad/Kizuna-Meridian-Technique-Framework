package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonBundle;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** 从附属已导出的公共 Protobuf 资源建立只读的模板校验上下文。 */
public final class TechniqueAddonResources {
    public static final String RESOURCE_PREFIX = "META-INF/kizuna_meridian/addons/";
    private static final int MAX_BUNDLES = 1024;
    private static final AddonHostProfile VALIDATION_HOST = validationHost();
    private final ClassLoader loader;

    public TechniqueAddonResources(ClassLoader loader) {
        // 仅使用安装模组所在的类加载器，不依赖具体内容附属的 Java 类型。
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public AddonRegistry registry(TechniqueCatalog catalog) {
        // 绑定模板按命名空间定位公共内容包，依赖包也交给 Framework 整批校验。
        var pending = new TreeSet<String>();
        for (int index = 0; index < catalog.getBindingTemplatesCount(); index++) {
            var reference = catalog.getBindingTemplates(index);
            String id = reference.getId();
            TechniqueValues.id(id, "binding_templates[" + index + "].id");
            pending.add(id.substring(0, id.indexOf(':')));
        }
        Map<String, AddonBundle> bundles = bundles(pending);
        AddonRegistration registration = AddonRegistration.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setHost(VALIDATION_HOST)
                .addAllBundles(bundles.values())
                .build();
        return AddonRegistry.load(registration);
    }

    private Map<String, AddonBundle> bundles(TreeSet<String> pending) {
        // 迭代收集避免递归展开依赖；环和版本关系由 AddonRegistry 明确拒绝。
        var bundles = new TreeMap<String, AddonBundle>();
        while (!pending.isEmpty()) {
            String namespace = pending.pollFirst();
            if (bundles.containsKey(namespace)) {
                continue;
            }
            if (bundles.size() >= MAX_BUNDLES) {
                throw TechniqueErrors.invalid(namespace, "模板校验内容包数量超限");
            }
            AddonBundle bundle = read(namespace);
            bundles.put(namespace, bundle);
            bundle.getManifest().getDependenciesList().forEach(dependency -> pending.add(dependency.getNamespace()));
        }
        return bundles;
    }

    private AddonBundle read(String namespace) {
        // 同一路径若被多个 JAR 提供，只接受内容完全相同的副本，避免类路径顺序决定模板。
        String path = RESOURCE_PREFIX + namespace + ".pb";
        try {
            List<URL> resources = Collections.list(loader.getResources(path)).stream()
                    .sorted(Comparator.comparing(URL::toExternalForm))
                    .toList();
            if (resources.isEmpty()) {
                throw TechniqueErrors.invalid(path, "找不到绑定模板内容包");
            }
            AddonBundle first = decode(resources.get(0), namespace);
            for (int index = 1; index < resources.size(); index++) {
                AddonBundle other = decode(resources.get(index), namespace);
                if (!first.equals(other)) {
                    throw TechniqueErrors.invalid(path, "多个资源提供了不同内容：" + resources);
                }
            }
            return first;
        } catch (IOException exception) {
            throw new IllegalArgumentException(path + "：读取绑定模板内容包失败", exception);
        }
    }

    private static AddonBundle decode(URL resource, String namespace) throws IOException {
        // 原始内容包继续使用 Framework 的字节上限、格式和定义校验。
        byte[] bytes;
        try (var stream = resource.openStream()) {
            bytes = stream.readNBytes(MeridianProtocol.MAX_PAYLOAD_BYTES + 1);
        }
        try {
            AddonBundle bundle = AddonProtobuf.decodeBundle(bytes);
            if (!bundle.getManifest().getNamespace().equals(namespace)) {
                throw TechniqueErrors.invalid(namespace, "资源路径与内容包命名空间不一致");
            }
            return bundle;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(resource + "：" + exception.getMessage(), exception);
        }
    }

    private static AddonHostProfile validationHost() {
        // 校验使用 Java 参考核心已实现的能力，不声明 Minecraft 实体宿主已执行世界效果。
        return AddonHostProfile.newBuilder()
                .setHostId("meridian:java_reference")
                .setApiVersion(AddonProtobuf.API_VERSION)
                .addCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addCapabilities(AddonRegistry.ORGAN_RECIPES)
                .addCapabilities(AddonRegistry.ORGAN_INTAKE)
                .addCapabilities(AddonRegistry.ACTIONS)
                .addCapabilities(AddonRegistry.TISSUE_RECOVERY)
                .addCapabilities(AddonRegistry.PASSIVE_QI_FLOW)
                .addCapabilities(AddonRegistry.OPENING_DEFINITIONS)
                .addCapabilities(AddonRegistry.OPENING_RUNTIME)
                .addCapabilities(AddonRegistry.ORGAN_ENDURANCE)
                .build();
    }
}
