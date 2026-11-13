package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Presentation;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 功法书详情页用的明细：卷轴那三行之外，还要有境界、承接关系和要用的部位。 */
public final class TechniqueReferenceTextTest {
    @Test
    public void linesKeepScrollRowsAndAddRealmAndBothDirectionsOfFollowUp() {
        // 前式写「后招」，只能承接的后式写「只能接在…之后」；式名取目录里的显示名。
        TechniqueCatalog base = CombatTechniqueFixtures.combatCatalog();
        TechniqueDefinition first = named(base.getDefinitions(0), "前式");
        TechniqueDefinition next = named(base.getDefinitions(1), "后式");
        TechniqueRegistry registry = TechniqueRegistry.load(addons(),
                List.of(base.toBuilder().setDefinitions(0, first).setDefinitions(1, next).build()));

        List<String> firstLines = TechniqueReferenceText.lines(registry, first);
        assertEquals(TechniqueScrollText.details(first), firstLines.subList(0, 3));
        assertTrue(firstLines.toString(), firstLines.contains("境界：真炁以上"));
        assertTrue(firstLines.toString(), firstLines.contains("后招：收势时可接后式"));
        assertTrue(firstLines.stream().noneMatch(line -> line.startsWith("承接：")));

        List<String> nextLines = TechniqueReferenceText.lines(registry, next);
        assertTrue(nextLines.toString(), nextLines.contains("承接：只能接在前式的收势后，不能单独起手"));
        assertTrue(nextLines.stream().noneMatch(line -> line.startsWith("后招：")));

        TechniqueDefinition optional = next.toBuilder()
                .setFollowUp(next.getFollowUp().toBuilder().setOnlyFollowUp(false /* onlyFollowUp */)).build();
        assertTrue(TechniqueReferenceText.lines(registry, optional).contains("承接：可以接在前式的收势后"));
    }

    @Test
    public void modulesNameWhatThisBodyBindsByKind() {
        // 绑定到的经脉、筋肉、器官各一行，名字来自身体结构，同名只写一次。
        var bodySnapshot = body(Shape.PAIRED);
        var outcome = TechniqueBinder.bind(bodySnapshot, definition());
        assertTrue(outcome.toString(), outcome.hasBinding());
        List<String> lines = TechniqueReferenceText.modules(bodySnapshot, outcome.getBinding());
        assertEquals(List.of("经脉：测试经脉", "筋肉：测试组织", "器官：测试器官"), lines);
    }

    private static TechniqueDefinition named(TechniqueDefinition definition, String name) {
        // 只换显示名，其余照夹具。
        Presentation presentation = definition.getPresentation().toBuilder().setDisplayName(name).build();
        return definition.toBuilder().setPresentation(presentation).build();
    }
}
