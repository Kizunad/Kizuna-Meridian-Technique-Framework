package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** ai_hints.support 的注册校验，以及辅助式不进打击选式但仍可判定能否施放。 */
public class TechniqueAiSupportTest {
    private static final double HINT_DISTANCE = 8;
    private static final double TARGET_DISTANCE = 4;
    private static final int TOO_LATE_ORDER = 101;
    private static final double TOO_TIRED = 1.5;
    private static final long REFRESH_MILLIS = 3000;

    @Test
    public void validSupportHintsLoad() {
        // 增益类可标准备、机动，恢复类可标恢复；各可选字段在范围内即可加载。
        SupportAiHints prepare = SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_PREPARE)
                .setOrder(1)
                .setRequiresMeleeWeapon(true /* requiresMeleeWeapon */)
                .build();
        SupportAiHints reposition = SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_REPOSITION)
                .setRefreshBeforeExpiryMillis(REFRESH_MILLIS)
                .build();
        SupportAiHints recover = SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_RECOVER)
                .setMinimumFatigueRatio(quantity(1, Unit.RATIO))
                .build();
        registry(supported(prepare));
        registry(supported(reposition));
        registry(supported(recover).toBuilder().setCategory(Category.RECOVERY).build());
    }

    @Test
    public void invalidSupportHintsAreRejected() {
        // 缺角色、类别不符、顺序越界、疲劳比越界、补放毫秒为负都在目录加载时拒绝。
        var role = SupportAiHints.newBuilder();
        var recoverOnBuff = SupportAiHints.newBuilder().setRole(AiRole.AI_ROLE_RECOVER);
        var order = SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_PREPARE)
                .setOrder(TOO_LATE_ORDER);
        var fatigue = SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_PREPARE)
                .setMinimumFatigueRatio(quantity(TOO_TIRED, Unit.RATIO));
        var refresh = SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_REPOSITION)
                .setRefreshBeforeExpiryMillis(-1);
        for (var invalid : List.of(role, recoverOnBuff, order, fatigue, refresh)) {
            TechniqueDefinition definition = supported(invalid.build());
            assertThrows(IllegalArgumentException.class, () -> registry(definition));
        }
        TechniqueDefinition prepareOnRecovery = supported(SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_PREPARE)
                .build()).toBuilder().setCategory(Category.RECOVERY).build();
        assertThrows(IllegalArgumentException.class, () -> registry(prepareOnRecovery));
    }

    @Test
    public void supportTechniqueIsCastableButNeverScored() {
        // 同一式不标角色时有分；标了角色后打击选式给零分，castable 仍按绑定、费用和冷却判为可施放。
        TechniqueDefinition striking = definition().toBuilder()
                .setAiHints(hints())
                .build();
        TechniqueDefinition support = supported(SupportAiHints.newBuilder()
                .setRole(AiRole.AI_ROLE_PREPARE)
                .build());
        assertTrue(score(striking) > 0);
        assertEquals(0, score(support), 0);
        TechniqueRegistry registry = registry(support);
        KnownTechnique known = progress(support).getKnown(0);
        assertTrue(TechniqueSelector.castable(context(registry, support), known, support));
    }

    private static double score(TechniqueDefinition definition) {
        // 已学、可绑定、付得起且距离在提示区间内。
        TechniqueRegistry registry = registry(definition);
        KnownTechnique known = progress(definition).getKnown(0);
        return TechniqueSelector.score(context(registry, definition), known);
    }

    private static TechniqueSelector.Context context(TechniqueRegistry registry, TechniqueDefinition definition) {
        // 身体绑定来自测试身体，费用与冷却直接给出已满足的结果。
        TechniqueReference reference = TechniqueRegistry.reference(definition);
        BindingOutcome binding = TechniqueBinder.bind(body(Shape.TENDRIL), definition);
        var availability = new TechniqueSelector.Availability(Map.of(reference, binding), Set.of(reference),
                Map.of());
        var situation = new TechniqueSelector.Situation(0, 1, 1, TARGET_DISTANCE,
                TechniqueSelector.TargetState.ENGAGED);
        return new TechniqueSelector.Context(registry, situation, availability);
    }

    private static TechniqueDefinition supported(SupportAiHints support) {
        // 测试功法本是增益类，挂上带辅助角色的提示。
        var aiHints = hints()
                .setSupport(support);
        return definition().toBuilder()
                .setAiHints(aiHints)
                .build();
    }

    private static AiHints.Builder hints() {
        // 距离、比例与类别权重都在合法区间。
        return AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(HINT_DISTANCE, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO));
    }
}
