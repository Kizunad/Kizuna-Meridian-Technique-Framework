package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DurabilityRule;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSelectionCriterion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBook;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.TECHNIQUE_ID;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.catalog;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.LOWER;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.UPPER;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.groupedCatalog;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.rules;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueHeldCatalogTest {
    private static final String OTHER_BOOK = "sample:other_book";
    private static final int UNKNOWN_ENUM = 99;

    @Test
    public void groupedCatalogRoundTripsAndPublishesImmutableExactMembership() {
        // 二进制目录保留书级规则与精确版本归属，调用方不能修改公开分组列表。
        TechniqueDefinition definition = definition();
        TechniqueCatalog catalog = groupedCatalog(definition);
        TechniqueCatalog decoded = TechniqueProtocol.decodeCatalog(catalog.toByteArray());
        assertEquals(catalog, decoded);
        TechniqueRegistry registry = load(decoded);
        assertEquals(catalog.getBooksList(), registry.books());
        assertEquals(catalog.getVolumes(0), registry.findVolume(UPPER).orElseThrow());
        assertEquals(catalog.getBooks(0), registry.bookOf(TechniqueRegistry.reference(definition)).orElseThrow());
        TechniqueReference absentVersion = TechniqueRegistry.reference(definition).toBuilder()
                .setVersion(definition.getVersion() + 1)
                .build();
        assertTrue(registry.bookOf(absentVersion).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.books().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.volumes().clear());
    }

    @Test
    public void rejectsUnknownExactVersionsDuplicateMembershipAndAmbiguousCommandIds() {
        // 缺失版本、多卷归属与书卷式命令身份冲突都必须在目录发布前拒绝。
        TechniqueCatalog catalog = groupedCatalog(definition());
        TechniqueVolume upper = catalog.getVolumes(0);
        TechniqueReference missing = upper.getTechniques(0).toBuilder()
                .setVersion(upper.getTechniques(0).getVersion() + 1)
                .build();
        TechniqueVolume unknownVersion = upper.toBuilder().setTechniques(0, missing).build();
        assertRejected(catalog.toBuilder().setVolumes(0, unknownVersion).build());
        TechniqueVolume duplicate = catalog.getVolumes(1).toBuilder()
                .addTechniques(upper.getTechniques(0))
                .build();
        assertRejected(catalog.toBuilder().setVolumes(1, duplicate).build());
        TechniqueBook conflicting = catalog.getBooks(0).toBuilder().setId(TECHNIQUE_ID).build();
        assertRejected(catalog.toBuilder().addBooks(conflicting).build());
        assertRejected(catalog.toBuilder().addVolumes(upper).build());
    }

    @Test
    public void rejectsCrossBookAndCyclicPrerequisites() {
        // 前置只能在同书内组成无环关系，不允许学习条件永远无法满足。
        TechniqueCatalog catalog = groupedCatalog(definition());
        TechniqueBook other = TechniqueBook.newBuilder().setId(OTHER_BOOK).build();
        TechniqueVolume foreign = catalog.getVolumes(1).toBuilder().setBookId(OTHER_BOOK).build();
        assertRejected(catalog.toBuilder().addBooks(other).setVolumes(1, foreign).build());
        TechniqueVolume cyclic = catalog.getVolumes(0).toBuilder().addPrerequisiteVolumeIds(LOWER).build();
        assertRejected(catalog.toBuilder().setVolumes(0, cyclic).build());
        TechniqueVolume unknown = catalog.getVolumes(1).toBuilder()
                .addPrerequisiteVolumeIds("sample:missing")
                .build();
        assertRejected(catalog.toBuilder().setVolumes(1, unknown).build());
    }

    @Test
    public void rejectsIncompleteMatchersWrongDamageUnitsAndUnknownRuleEnums() {
        // 持物规则是公开输入，兜底、单位与枚举缺失不能留到施放时解释。
        HeldItemRules rules = rules();
        HeldItemRules missingFallback = rules.toBuilder()
                .removeCategories(rules.getCategoriesCount() - 1)
                .build();
        assertRejected(withRules(missingFallback));
        HeldItemCategory wrongUnit = rules.getCategories(0).toBuilder()
                .setBaseDamage(quantity(1, Unit.DAMAGE_POINT))
                .build();
        assertRejected(withRules(rules.toBuilder().setCategories(0, wrongUnit).build()));
        HeldItemCategory unknown = rules.getCategories(0).toBuilder().setInjuryKindValue(UNKNOWN_ENUM).build();
        assertRejected(withRules(rules.toBuilder().setCategories(0, unknown).build()));
        HeldItemRules earlyMain = rules.toBuilder()
                .setSelectionOrder(0, HandSelectionCriterion.PREFER_MAIN_HAND)
                .setSelectionOrder(2, HandSelectionCriterion.CATEGORY_PRIORITY)
                .build();
        assertRejected(withRules(earlyMain));
        DurabilityRule zeroWear = rules.getDurability(0).toBuilder()
                .setMultiplier(quantity(0, Unit.RATIO))
                .build();
        assertRejected(withRules(rules.toBuilder().setDurability(0, zeroWear).build()));
    }

    private static TechniqueCatalog withRules(HeldItemRules rules) {
        // 只替换被测书级规则，身体模板和所有卷引用仍有效。
        TechniqueCatalog catalog = groupedCatalog(definition());
        TechniqueBook book = catalog.getBooks(0).toBuilder().setHeldItemRules(rules).build();
        return catalog.toBuilder().setBooks(0, book).build();
    }

    private static TechniqueRegistry load(TechniqueCatalog catalog) {
        // 使用真实附属登记器校验公开目录，而非单独调用内部校验方法。
        return TechniqueRegistry.load(addons(), List.of(catalog));
    }

    private static void assertRejected(TechniqueCatalog catalog) {
        // 任一无效分组或规则都使整份目录加载失败。
        assertThrows(IllegalArgumentException.class, () -> load(catalog));
    }
}
