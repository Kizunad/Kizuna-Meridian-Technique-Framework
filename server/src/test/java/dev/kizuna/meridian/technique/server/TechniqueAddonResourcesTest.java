package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonBundle;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonDependency;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonManifest;
import dev.kizuna.meridian.protocol.pb.AddonMessages.ContentVersionRange;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TemplateReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TechniqueAddonResourcesTest {
    private static final String RESOURCE = TechniqueAddonResources.RESOURCE_PREFIX + "sample.pb";
    private static final int SECOND_VERSION = 2;

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();

    @Test
    public void resolvesDeclaredDependenciesThroughFramework() throws Exception {
        // 导出的包按依赖闭包装配，Framework 负责版本和环校验。
        Path root = files.newFolder("mods").toPath();
        AddonDependency dependency = AddonDependency.newBuilder()
                .setNamespace("dependency")
                .setVersions(versions())
                .build();
        AddonBundle base = bundle("sample");
        AddonManifest manifest = base.getManifest().toBuilder().addDependencies(dependency).build();
        write(root, RESOURCE, base.toBuilder().setManifest(manifest).build());
        write(root, TechniqueAddonResources.RESOURCE_PREFIX + "dependency.pb", bundle("dependency"));
        try (var loader = loader(root)) {
            assertNotNull(new TechniqueAddonResources(loader).registry(catalog()));
        }
    }

    @Test
    public void missingBundleAndNamespaceMismatchReportResource() throws Exception {
        // 缺包与资源身份不符均不能变成空模板注册表。
        Path root = files.newFolder("mods").toPath();
        try (var loader = loader(root)) {
            var resources = new TechniqueAddonResources(loader);
            assertFailure(() -> resources.registry(catalog()), RESOURCE);
            write(root, RESOURCE, bundle("other"));
            assertFailure(() -> resources.registry(catalog()), RESOURCE, "命名空间");
        }
    }

    @Test
    public void acceptsIdenticalCopiesAndRejectsDifferentCopies() throws Exception {
        // 同一路径不会按类路径先后覆盖，内容相同的打包副本可共享。
        Path first = files.newFolder("first").toPath();
        Path second = files.newFolder("second").toPath();
        AddonBundle bundle = bundle("sample");
        write(first, RESOURCE, bundle);
        write(second, RESOURCE, bundle);
        try (var loader = loader(first, second)) {
            var resources = new TechniqueAddonResources(loader);
            assertNotNull(resources.registry(catalog()));
            AddonManifest changed = bundle.getManifest().toBuilder().setContentVersion(SECOND_VERSION).build();
            write(second, RESOURCE, bundle.toBuilder().setManifest(changed).build());
            assertFailure(() -> resources.registry(catalog()), RESOURCE, "不同内容");
        }
    }

    private static AddonBundle bundle(String namespace) {
        // 空定义包用于验证资源发现和依赖，不包含具体身体内容。
        AddonManifest manifest = AddonManifest.newBuilder()
                .setNamespace(namespace)
                .setContentVersion(1)
                .setApiVersions(versions())
                .addSupportedHosts("meridian:java_reference")
                .build();
        return AddonBundle.newBuilder().setSchemaVersion(1).setManifest(manifest).build();
    }

    private static ContentVersionRange versions() {
        // 测试依赖只支持一个确定的内容修订。
        return ContentVersionRange.newBuilder().setMinimum(1).setMaximum(1).build();
    }

    private static TechniqueCatalog catalog() {
        // 这里只检查包上下文发现；不存在的模板还须由目录注册表拒绝。
        TemplateReference reference = TemplateReference.newBuilder().setId("sample:template").setVersion(1).build();
        return TechniqueCatalog.newBuilder()
                .setSchemaVersion(1)
                .setNamespace("sample")
                .addBindingTemplates(reference)
                .build();
    }

    private static void write(Path root, String name, AddonBundle bundle) throws Exception {
        // 使用真实 Framework 编码器写出类路径资源。
        Path resource = root.resolve(name);
        Files.createDirectories(resource.getParent());
        Files.write(resource, AddonProtobuf.encodeBundle(bundle));
    }

    private static URLClassLoader loader(Path... roots) throws Exception {
        // 测试类路径只包含当前隔离目录，避免宿主资源影响结果。
        URL[] urls = new URL[roots.length];
        for (int index = 0; index < roots.length; index++) {
            urls[index] = roots[index].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    private static void assertFailure(Runnable action, String... fragments) {
        // 资源路径必须随错误返回到声明该目录的调用方。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        String message = failure.getMessage();
        for (String fragment : fragments) {
            assertTrue(message, message.contains(fragment));
        }
    }
}
