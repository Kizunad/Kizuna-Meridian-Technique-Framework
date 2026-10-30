package dev.kizuna.meridian.technique.fabric;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonHostProfile;
import dev.kizuna.meridian.protocol.pb.AddonMessages.AddonRegistration;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBook;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.server.TechniqueCatalogEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TechniqueCatalogDiscoveryTest {
    private static final String FIRST_PATH = "first.txtpb";
    private static final String SECOND_PATH = "second.binpb";

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();

    @Test
    public void discoversSingleStringAndArrayInModIdThenDeclarationOrder() throws Exception {
        // 模组输入故意反序，数组路径仍保持作者声明顺序。
        Path root = files.getRoot().toPath();
        write(root, FIRST_PATH, catalog("first"));
        write(root, SECOND_PATH, catalog("second"));
        ModContainer alpha = CatalogTestMods.mod("alpha", List.of(SECOND_PATH, FIRST_PATH), root);
        ModContainer zeta = CatalogTestMods.mod("zeta", FIRST_PATH, root);
        var addons = addons();
        TechniqueRegistry registry = TechniqueCatalogDiscovery.discover(List.of(zeta, alpha), List.of(),
                catalog -> addons);
        List<String> sources = registry.sources().stream().map(source -> source.source()).toList();
        assertEquals(List.of("alpha:" + SECOND_PATH, "alpha:" + FIRST_PATH, "zeta:" + FIRST_PATH), sources);
        TechniqueRegistry reordered = TechniqueCatalogDiscovery.discover(List.of(alpha, zeta), List.of(),
                catalog -> addons);
        assertEquals(registry.sources(), reordered.sources());
    }

    @Test
    public void rejectsWrongCustomTypesAndNonStringArrayMembers() {
        // 元数据错误包含所属模组、键名以及错误数组成员的位置。
        Path root = files.getRoot().toPath();
        for (Object value : List.of(1, true /* invalidCustomValue */, List.of(FIRST_PATH, 1))) {
            ModContainer mod = CatalogTestMods.mod("broken", value, root);
            assertFailure(() -> TechniqueCatalogDiscovery.customPaths(mod), "broken:custom.", "字符串");
        }
    }

    @Test
    public void missingResourceAndUnsupportedSuffixIncludeOrigin() {
        // 找不到资源与格式不支持都不能静默跳过声明。
        Path root = files.getRoot().toPath();
        var addons = addons();
        for (String path : List.of("missing.txtpb", "catalog.json")) {
            ModContainer mod = CatalogTestMods.mod("broken", path, root);
            assertFailure(() -> TechniqueCatalogDiscovery.discover(List.of(mod), List.of(), catalog -> addons),
                    "broken:" + path);
        }
    }

    @Test
    public void mergesAllLegacyEntrypointsAndManifestsInModOrder() throws Exception {
        // 多个旧入口不再互斥，并与清单一起参与最终注册表校验。
        Path root = files.getRoot().toPath();
        write(root, FIRST_PATH, bookCatalog("manifest"));
        ModContainer alpha = CatalogTestMods.mod("alpha", FIRST_PATH, root);
        ModContainer zeta = CatalogTestMods.mod("zeta", null, root);
        var calls = new ArrayList<String>();
        var addons = addons();
        var first = entrypoint(alpha, () -> {
            calls.add("alpha");
            return TechniqueRegistry.load(addons, List.of(bookCatalog("alpha")));
        });
        var second = entrypoint(zeta, () -> {
            calls.add("zeta");
            return TechniqueRegistry.load(addons, List.of(bookCatalog("zeta")));
        });
        TechniqueRegistry registry = TechniqueCatalogDiscovery.discover(List.of(zeta, alpha),
                List.of(second, first), catalog -> addons);
        assertEquals(List.of("alpha", "zeta"), calls);
        assertEquals(3, registry.books().size());
        assertEquals(3, registry.sources().size());
    }

    @Test
    public void rejectsManifestAndEntrypointIdentityConflictWithBothSources() throws Exception {
        // 已单独通过校验的入口也不能覆盖清单中的书身份。
        Path root = files.getRoot().toPath();
        TechniqueCatalog catalog = bookCatalog("shared");
        write(root, FIRST_PATH, catalog);
        ModContainer mod = CatalogTestMods.mod("sample", FIRST_PATH, root);
        var addons = addons();
        var provider = entrypoint(mod, () -> TechniqueRegistry.load(addons, List.of(catalog)));
        assertFailure(() -> TechniqueCatalogDiscovery.discover(List.of(mod), List.of(provider), ignored -> addons),
                "sample:" + FIRST_PATH, "sample:entrypoint/meridian_technique_catalog[0]", "books[0]");
    }

    @Test
    public void codeEntrypointCanReferToBookInALaterManifest() throws Exception {
        // 新的收集入口延后引用校验，代码目录可以引用尚未读取的清单。
        Path root = files.getRoot().toPath();
        TechniqueCatalog content = TechniqueCatalogManifest.parseText("fixture", """
                schema_version: 1
                namespace: "sample"
                definitions {
                  id: "sample:technique" version: 1 category: BUFF minimum_realm: TRUE_QI
                  cost {}
                  phases { warmup_millis: 0 active_millis: 1 recovery_millis: 0 interruptible_by_damage: false }
                  cooldown_millis: 0
                }
                volumes {
                  id: "sample:volume" book_id: "sample:book"
                  techniques { id: "sample:technique" version: 1 }
                }
                """);
        write(root, FIRST_PATH, bookCatalog("sample"));
        ModContainer alpha = CatalogTestMods.mod("alpha", null, root);
        ModContainer zeta = CatalogTestMods.mod("zeta", FIRST_PATH, root);
        var addons = addons();
        TechniqueCatalogEntrypoint code = (source, catalogs) -> catalogs.register(source, addons, content);
        var provider = rawEntrypoint(alpha, code);
        TechniqueRegistry registry = TechniqueCatalogDiscovery.discover(List.of(zeta, alpha),
                List.of(provider), ignored -> addons);
        assertTrue(registry.findVolume("sample:volume").isPresent());
        assertTrue(registry.findLatest("sample:technique").isPresent());
    }

    @Test
    public void absentCatalogsDoNotRequireTemplateProviders() {
        // 未安装目录时保持服务不可用，不为了空内容初始化宿主注册表。
        ModContainer mod = CatalogTestMods.mod("empty", null, files.getRoot().toPath());
        assertNull(TechniqueCatalogDiscovery.discover(List.of(mod), List.of(), catalog -> {
            throw new AssertionError("空目录不应请求模板");
        }));
    }

    @Test
    public void earlyCatalogsFreezeOnceAndProduceMatchingScrollsAcrossModOrders() throws Exception {
        // 客户端与服务端给出不同模组枚举顺序时，清单和提前入口仍生成同一组卷轴。
        Path root = files.getRoot().toPath();
        write(root, FIRST_PATH, volumeCatalog("manifest"));
        ModContainer alpha = CatalogTestMods.mod("alpha", FIRST_PATH, root);
        ModContainer zeta = CatalogTestMods.mod("zeta", null, root);
        var addons = addons();
        var calls = new ArrayList<String>();
        TechniqueCatalogEntrypoint early = (source, catalogs) -> {
            calls.add("early");
            catalogs.register(source, addons, volumeCatalog("early"));
        };
        var late = entrypoint(zeta, () -> {
            calls.add("late");
            return TechniqueRegistry.load(addons, List.of(volumeCatalog("late")));
        });
        var providers = List.of(rawEntrypoint(alpha, early), late);
        TechniqueCatalogPlan server = TechniqueCatalogDiscovery.prepare(List.of(zeta, alpha), providers, c -> addons);
        assertEquals(List.of("early"), calls);
        var serverScrolls = new TechniqueScrollCatalog(server.earlySources());
        assertEquals(server.earlySources(), server.earlySources());
        assertEquals(3, server.registry().volumes().size());
        assertEquals(3, server.registry().volumes().size());
        assertEquals(List.of("early", "late", "late"), calls);
        TechniqueCatalogPlan client = TechniqueCatalogDiscovery.prepare(List.of(alpha, zeta), providers, c -> addons);
        var clientScrolls = new TechniqueScrollCatalog(client.earlySources());
        assertEquals(serverScrolls.registrationOrder(), clientScrolls.registrationOrder());
        assertEquals(List.of("manifest:volume_scroll", "early:volume_scroll"),
                serverScrolls.registrationOrder().stream().map(TechniqueScrollCatalog.Scroll::itemId).toList());
        assertEquals(List.of("early", "late", "late", "early"), calls);
    }

    private static TechniqueCatalog volumeCatalog(String namespace) {
        // 独立书卷式身份满足完整校验，目录不依赖游戏世界或特定身体模板。
        return TechniqueCatalogManifest.parseText("fixture", """
                schema_version: 1
                namespace: "%1$s"
                definitions {
                  id: "%1$s:technique" version: 1 category: BUFF minimum_realm: TRUE_QI
                  cost {}
                  phases { warmup_millis: 0 active_millis: 1 recovery_millis: 0 interruptible_by_damage: false }
                  cooldown_millis: 0
                }
                books { id: "%1$s:book" }
                volumes {
                  id: "%1$s:volume" book_id: "%1$s:book"
                  techniques { id: "%1$s:technique" version: 1 }
                }
                """.formatted(namespace));
    }

    private static TechniqueCatalog catalog(String namespace) {
        // 无具体式的目录用于隔离发现顺序与运行规则。
        return TechniqueCatalog.newBuilder().setSchemaVersion(1).setNamespace(namespace).build();
    }

    private static TechniqueCatalog bookCatalog(String namespace) {
        // 各来源使用独立书身份，冲突测试显式复用同一消息。
        TechniqueBook book = TechniqueBook.newBuilder().setId(namespace + ":book").build();
        return catalog(namespace).toBuilder().addBooks(book).build();
    }

    private static void write(Path root, String path, TechniqueCatalog catalog) throws Exception {
        // 写入普通模组资源布局，同时覆盖文本和二进制解析。
        Path file = root.resolve(path);
        if (path.endsWith(".binpb")) {
            Files.write(file, catalog.toByteArray());
        } else {
            Files.writeString(file, TextFormat.printer().printToString(catalog));
        }
    }

    private static AddonRegistry addons() {
        // 无绑定模板的测试目录仍使用真实 Framework 注册表。
        AddonHostProfile host = AddonHostProfile.newBuilder().setHostId("test:server").setApiVersion(1).build();
        AddonRegistration registration = AddonRegistration.newBuilder().setProtocolVersion(1).setHost(host).build();
        return AddonRegistry.load(registration);
    }

    private static EntrypointContainer<Object> entrypoint(
            ModContainer provider, TechniqueRegistryEntrypoint entrypoint) {
        // 仅替换 Fabric 容器，目录提供者和合并逻辑使用正式接口。
        return rawEntrypoint(provider, entrypoint);
    }

    private static EntrypointContainer<Object> rawEntrypoint(ModContainer provider, Object entrypoint) {
        // 两种编程式接口都经过相同元数据键发现。
        return new EntrypointContainer<>() {
            @Override
            public Object getEntrypoint() {
                // 返回调用方提供的真实编程式目录入口。
                return entrypoint;
            }

            @Override
            public ModContainer getProvider() {
                // 来源取真实排序键，不以入口类名决定模组顺序。
                return provider;
            }
        };
    }

    private static void assertFailure(Runnable action, String... fragments) {
        // 定位信息是公开契约的一部分，不能只断言异常类型。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        String message = failure.getMessage();
        for (String fragment : fragments) {
            assertTrue(message, message.contains(fragment));
        }
    }
}
