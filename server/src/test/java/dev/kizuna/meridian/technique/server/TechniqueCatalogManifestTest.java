package dev.kizuna.meridian.technique.server;

import com.google.protobuf.TextFormat;
import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBook;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.catalog;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TechniqueCatalogManifestTest {
    private static final String SOURCE = "sample:data/sample/catalog.txtpb";
    private static final String SECOND_SOURCE = "other:data/sample/second.binpb";
    private static final String BOOK_ID = "sample:book";
    private static final String VOLUME_ID = "sample:volume";
    private static final int UNKNOWN_FIELD = 99;
    private static final int UNSUPPORTED_VERSION = 2;

    @Rule
    public final TemporaryFolder files = new TemporaryFolder();

    @Test
    public void readsTextAndBinaryCatalogsWithoutChangingMessages() throws Exception {
        // 两种文件格式得到同一不可变目录，并保留已有 schema_version 语义。
        TechniqueCatalog catalog = catalog(definition());
        Path text = files.newFile("catalog.txtpb").toPath();
        Path binary = files.newFile("catalog.binpb").toPath();
        Files.writeString(text, TextFormat.printer().printToString(catalog));
        Files.write(binary, catalog.toByteArray());
        assertEquals(catalog, TechniqueCatalogManifest.read(SOURCE, text));
        assertEquals(catalog, TechniqueCatalogManifest.read(SECOND_SOURCE, binary));
    }

    @Test
    public void rejectsUnknownFieldsWithSourceAndNestedPosition() {
        // 文本保留行列号，二进制保留嵌套消息的字段路径。
        assertFailure(() -> TechniqueCatalogManifest.parseText(SOURCE,
                "schema_version: 1\nnamespace: 'sample'\nunknown_field: 1"), SOURCE, "3:1");
        UnknownFieldSet.Field value = UnknownFieldSet.Field.newBuilder().addVarint(1).build();
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder().addField(UNKNOWN_FIELD, value).build();
        TechniqueDefinition definition = definition().toBuilder().setUnknownFields(unknown).build();
        TechniqueCatalog catalog = catalog(definition);
        assertFailure(() -> TechniqueCatalogManifest.parseBinary(SECOND_SOURCE, catalog.toByteArray()),
                SECOND_SOURCE, "definitions[0]");
    }

    @Test
    public void rejectsMissingOrUnsupportedSchemaVersionInBothFormats() {
        // 目录格式版本必须显式为当前值，不能把缺省零当成第一版。
        for (int version : List.of(0, UNSUPPORTED_VERSION)) {
            TechniqueCatalog catalog = catalog(definition()).toBuilder().setSchemaVersion(version).build();
            String text = TextFormat.printer().printToString(catalog);
            assertFailure(() -> TechniqueCatalogManifest.parseText(SOURCE, text), SOURCE, "schema_version");
            assertFailure(() -> TechniqueCatalogManifest.parseBinary(SECOND_SOURCE, catalog.toByteArray()),
                    SECOND_SOURCE, "schema_version");
        }
    }

    @Test
    public void rejectsOversizedFilesAndDirectPayloadsWithSource() throws Exception {
        // 文件路径与直接解析均执行相同大小上限。
        int excessiveLength = TechniqueCatalogManifest.MAX_BYTES + 1;
        String text = " ".repeat(excessiveLength);
        byte[] bytes = new byte[excessiveLength];
        Path file = files.newFile("large.txtpb").toPath();
        Files.writeString(file, text);
        assertFailure(() -> TechniqueCatalogManifest.read(SOURCE, file), SOURCE, "大小");
        assertFailure(() -> TechniqueCatalogManifest.parseText(SOURCE, text), SOURCE, "大小");
        assertFailure(() -> TechniqueCatalogManifest.parseBinary(SECOND_SOURCE, bytes), SECOND_SOURCE, "大小");
    }

    @Test
    public void rejectsDuplicateDefinitionsBooksAndVolumesWithinOneCatalog() {
        // 三种身份重复分别定位第二个条目，并指明首次声明。
        TechniqueCatalog catalog = groupedCatalog();
        assertRejected(catalog.toBuilder().addDefinitions(catalog.getDefinitions(0)).build(),
                "definitions[1]", "definitions[0]");
        assertRejected(catalog.toBuilder().addBooks(catalog.getBooks(0)).build(), "books[1]", "books[0]");
        assertRejected(catalog.toBuilder().addVolumes(catalog.getVolumes(0)).build(), "volumes[1]", "volumes[0]");
    }

    @Test
    public void rejectsCrossSourceDuplicatesAndMissingPrerequisiteWithBothOrigins() {
        // 跨模组冲突不能后加载覆盖；前置缺失应定位提出引用的卷。
        TechniqueCatalog catalog = groupedCatalog();
        TechniqueCatalog empty = catalog.toBuilder().clearDefinitions().clearBooks().clearVolumes().build();
        for (TechniqueCatalog duplicate : List.of(
                empty.toBuilder().addDefinitions(catalog.getDefinitions(0)).build(),
                empty.toBuilder().addBooks(catalog.getBooks(0)).build(),
                empty.toBuilder().addVolumes(catalog.getVolumes(0)).build())) {
            var candidates = candidates(catalog, duplicate);
            assertFailure(candidates::build, SOURCE, SECOND_SOURCE);
        }
        TechniqueVolume missing = catalog.getVolumes(0).toBuilder()
                .addPrerequisiteVolumeIds("sample:missing")
                .build();
        assertRejected(catalog.toBuilder().setVolumes(0, missing).build(),
                "volumes[0]", "prerequisite_volume_ids[0]");
    }

    @Test
    public void mergesBeforeResolvingCrossCatalogBooksVolumesAndPrerequisites() {
        // 后提供的书、卷和式仍能被先提供的目录引用，不要求单份目录自包含。
        TechniqueCatalog first = groupedCatalog();
        TechniqueDefinition next = definition().toBuilder().setId("sample:next").build();
        TechniqueVolume volume = first.getVolumes(0).toBuilder()
                .setId("sample:next_volume")
                .clearTechniques()
                .addTechniques(TechniqueRegistry.reference(next))
                .addPrerequisiteVolumeIds(VOLUME_ID)
                .build();
        TechniqueCatalog second = catalog(next).toBuilder().addVolumes(volume).build();
        TechniqueRegistry registry = candidates(second, first).build();
        assertEquals(2, registry.definitions().size());
        assertEquals(2, registry.volumes().size());
        assertEquals(BOOK_ID, registry.bookOf(TechniqueRegistry.reference(next)).orElseThrow().getId());
        assertThrows(UnsupportedOperationException.class, () -> registry.sources().clear());
    }

    @Test
    public void preservesMultipleVersionsOfAnExistingTechniqueId() {
        // 精确版本是既有身份契约，同 ID 不同版本不应误判为重复。
        TechniqueDefinition first = definition();
        TechniqueDefinition second = first.toBuilder().setVersion(UNSUPPORTED_VERSION).build();
        TechniqueRegistry registry = candidates(catalog(first), catalog(second)).build();
        assertEquals(2, registry.definitions().size());
    }

    private static TechniqueCatalog groupedCatalog() {
        // 测试分组只引用一式，避免依赖具体玩法内容。
        TechniqueDefinition definition = definition();
        TechniqueBook book = TechniqueBook.newBuilder().setId(BOOK_ID).build();
        TechniqueVolume volume = TechniqueVolume.newBuilder()
                .setId(VOLUME_ID)
                .setBookId(BOOK_ID)
                .addTechniques(TechniqueRegistry.reference(definition))
                .build();
        return catalog(definition).toBuilder().addBooks(book).addVolumes(volume).build();
    }

    private static TechniqueCatalogs candidates(TechniqueCatalog first, TechniqueCatalog second) {
        // 每份候选保留自己的来源，最终统一进行语义校验。
        var catalogs = new TechniqueCatalogs();
        var addons = addons();
        catalogs.register(SOURCE, addons, first);
        catalogs.register(SECOND_SOURCE, addons, second);
        return catalogs;
    }

    private static void assertRejected(TechniqueCatalog catalog, String position, String reason) {
        // 同一清单的诊断同时检查来源、条目及原始错误位置。
        var catalogs = new TechniqueCatalogs();
        catalogs.register(SOURCE, addons(), catalog);
        assertFailure(catalogs::build, SOURCE, position, reason);
    }

    private static void assertFailure(Runnable action, String... fragments) {
        // 断言公开错误文本，不依赖实现内部的校验调用顺序。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        String message = failure.getMessage();
        for (String fragment : fragments) {
            assertTrue(message, message.contains(fragment));
        }
    }
}
