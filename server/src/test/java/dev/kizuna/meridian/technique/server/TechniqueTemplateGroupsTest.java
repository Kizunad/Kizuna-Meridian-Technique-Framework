package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyTemplate;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TemplateReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.*;

public class TechniqueTemplateGroupsTest {
    private static final String GROUP = "sample:humanoid";
    private static final String INNATE = "sample:innate";
    private static final TemplateReference BASE = templateReference(Shape.PAIRED);
    private static final TemplateReference MEMBER = reference("sample:member");
    private static final TemplateReference OUTSIDE = templateReference(Shape.TENDRIL);
    private static final TemplateReference BROKEN = reference("sample:broken");
    private static final String GROUP_INDEX = "META-INF/kizuna_meridian/technique_template_groups.list";
    private static final String GROUP_PATH = "data/sample/groups.txtpb";

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();

    @Test
    public void samePathInTwoJarsReportsTheOwnerOfTheInvalidManifest() throws IOException {
        // 两个包使用相同资源路径；第二份清单的错误必须定位到第二个 JAR。
        URL valid = groupJar("valid.jar", "schema_version: 1");
        URL broken = groupJar("broken.jar", "unknown_field: 1");
        try (var loader = new URLClassLoader(new URL[] {valid, broken}, null)) {
            var error = assertThrows(IllegalArgumentException.class, () -> TemplateGroupSource.classpath(loader));
            String message = error.getMessage();
            assertTrue(message, message.contains("broken.jar!/" + GROUP_PATH));
            assertTrue(message, message.contains("unknown_field"));
        }
    }

    private URL groupJar(String name, String content) throws IOException {
        // 索引和清单均写入包内，类加载器只读取测试给出的 JAR。
        Path path = files.newFile(name).toPath();
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry(GROUP_INDEX));
            jar.write(GROUP_PATH.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(GROUP_PATH));
            jar.write(content.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return path.toUri().toURL();
    }

    @Test
    public void secondMemberCanLearnCastAndKnowsInnate() {
        // 即使追加项先于声明加载，新成员也按同一份图纸正常学式起式。
        var registry = registry(groupCatalog());
        var host = host(MEMBER, TechniqueState.empty());
        var service = HeldTechniqueFixtures.service(host, registry);
        assertEquals(List.of(INNATE), known(service));
        service.learn(ENTITY, TECHNIQUE_ID);
        assertEquals(1, TechniqueState.read(host.current()).getKnownCount());
        var result = service.cast(ENTITY, request(host.current(), "member_cast"));
        assertEquals(result.getReason(), TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED, result.getFailure());
        assertTrue(TechniqueState.read(host.current()).hasActiveCast());
    }

    @Test
    public void outsideCannotLearnCastOrKnowInnateEvenWithAStoredRecord() {
        // 换身只改变知识投影。拒绝学和放后原始记录及 revision 都应完全相同。
        var catalog = groupCatalog();
        checkExcluded(registry(catalog), OUTSIDE);
    }

    @Test
    public void directBindingDoesNotIncludeAnotherGroupMember() {
        // 与组基准同名的第二具身体仍在直绑目录的适用范围之外。
        var direct = groupCatalog().toBuilder()
                .clearBindingTemplateGroups()
                .addBindingTemplates(BASE)
                .build();
        checkExcluded(registry(direct), MEMBER);
    }

    private static void checkExcluded(TechniqueRegistry registry, TemplateReference template) {
        // 先用空进度验证学不了，再预置可信记录验证不能绕过起式准入。
        var host = host(template, TechniqueState.empty());
        var service = HeldTechniqueFixtures.service(host, registry);
        assertTrue(known(service).isEmpty());
        assertThrows(TechniqueService.BodyTemplateMismatchException.class, () -> service.learn(ENTITY, TECHNIQUE_ID));
        assertEquals(0, host.activitySubmissions);
        var learned = host(template, progress(definition()));
        var retained = learned.current();
        var restored = HeldTechniqueFixtures.service(learned, registry);
        assertTrue(known(restored).isEmpty());
        var result = restored.cast(ENTITY, request(retained, "excluded_cast"));
        assertEquals(TechniqueFailure.BODY_TEMPLATE_MISMATCH, result.getFailure());
        assertEquals(retained, learned.current());
        assertEquals(1, TechniqueState.read(learned.current()).getKnownCount());
    }

    @Test
    public void snapshotTemplateDoesNotAlterStoredProgress() {
        // 同一份进度的查询随身体改变，写回字节与旧存档相同。
        var original = progress(definition());
        var bytes = TechniqueState.pack(original);
        var registry = registry(groupCatalog());
        var member = host(MEMBER, original).current();
        var outside = host(OUTSIDE, original).current();
        assertFalse(TechniqueKnowledge.references(registry, member).isEmpty());
        assertTrue(TechniqueKnowledge.references(registry, outside).isEmpty());
        assertEquals(bytes, TechniqueState.pack(TechniqueState.read(member)));
        assertEquals(bytes, TechniqueState.pack(TechniqueState.read(outside)));
    }

    @Test
    public void rejectsMissingMeridianWithMemberAndTag() {
        // 保留同一图纸的其他字段，单独移走基准要求的经脉标签。
        var base = sampleTemplate();
        var structure = base.getStructure().toBuilder();
        for (int index = 0; index < structure.getMeridiansCount(); index++) {
            structure.getMeridiansBuilder(index).clearTags();
        }
        var broken = base.toBuilder().setReference(BROKEN).setStructure(structure).build();
        var addons = addons(List.of(broken));
        var exception = assertThrows(IllegalArgumentException.class,
                () -> TechniqueTemplateGroups.load(addons, List.of(declaration(), addition(GROUP, BROKEN))));
        assertTrue(exception.getMessage(), exception.getMessage().contains(BROKEN.getId()));
        assertTrue(exception.getMessage(), exception.getMessage().contains(SOURCE_TAG));
    }

    @Test
    public void rejectsUnknownGroupDuplicateDeclarationAndDuplicateMember() {
        // 三种登记错误各有独立的诊断，不能被忽略或覆盖。
        assertInvalid(List.of(addition("sample:missing", BASE)), "未声明");
        assertInvalid(List.of(declaration(), declaration()), "重复声明");
        assertInvalid(List.of(declaration(), addition(GROUP, BASE)), "成员重复");
        assertInvalid(List.of(declaration(), addition(GROUP, BROKEN)), BROKEN.getId());
    }

    private static void assertInvalid(List<TemplateGroupSource> sources, String reason) {
        // 错误应在构造可发布的注册表前出现。
        var error = assertThrows(IllegalArgumentException.class, () -> TechniqueTemplateGroups.load(addons(), sources));
        assertTrue(error.getMessage(), error.getMessage().contains(reason));
    }

    private static TechniqueCatalog groupCatalog() {
        // 两式同属一个组，天生式无卷也无学习记录。
        var base = definition();
        var innate = base.toBuilder().setId(INNATE).setInnate(true /* innate */).build();
        return catalog(base).toBuilder()
                .clearBindingTemplates()
                .addBindingTemplateGroups(GROUP)
                .addDefinitions(innate)
                .build();
    }

    private static TechniqueRegistry registry(TechniqueCatalog catalog) {
        // 模板与目录都由生产注册表验证，顺序故意让追加项在前。
        var member = sampleTemplate().toBuilder().setReference(MEMBER).build();
        var addons = addons(List.of(member));
        var groups = TechniqueTemplateGroups.load(addons, List.of(addition(GROUP, MEMBER), declaration()));
        return TechniqueRegistry.loadSources(List.of(new TechniqueCatalogSource("sample", addons, catalog)), groups);
    }

    private static BodyTemplate sampleTemplate() {
        // 第二个成员的身体结构来自基准模板。
        return addons().findTemplate(BASE.getId(), BASE.getVersion()).orElseThrow();
    }

    private static TemplateGroupSource declaration() {
        // 基准同时登记为组内可用成员。
        var group = TemplateGroup.newBuilder().setId(GROUP).setBase(BASE).addMembers(BASE).build();
        var manifest = TemplateGroupManifest.newBuilder().setSchemaVersion(1).addGroups(group).build();
        return new TemplateGroupSource("base", manifest);
    }

    private static TemplateGroupSource addition(String id, TemplateReference member) {
        // 附属只追加成员，不重新声明基准。
        var addition = TemplateGroupMembers.newBuilder().setGroupId(id).addMembers(member).build();
        var manifest = TemplateGroupManifest.newBuilder().setSchemaVersion(1).addAdditions(addition).build();
        return new TemplateGroupSource("addition", manifest);
    }

    private static TemplateReference reference(String id) {
        // 样例均使用首次修订。
        return TemplateReference.newBuilder().setId(id).setVersion(1).build();
    }

    private static MemoryHost host(TemplateReference template, TechniqueProgress progress) {
        // 组外身体也有可绑定的槽，排除结构绑定失败造成的假阳性。
        var shape = Shape.PAIRED;
        if (template.equals(OUTSIDE)) {
            shape = Shape.TENDRIL;
        }
        var snapshot = BodyProtobuf.toMessage(body(shape)).toBuilder().setTemplate(template).build();
        return new MemoryHost(withProgress(BodyProtobuf.fromMessage(snapshot), progress));
    }

    private static List<String> known(TechniqueService service) {
        // 知识按正常服务入口读取。
        return service.known(ENTITY).stream().map(entry -> entry.technique().getId()).toList();
    }
}
