package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueStatusCurves.Phase;
import java.util.List;
import org.junit.Test;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 真实 Framework 活动验证状态归属、容量、刷新与旧曲线兼容。 */
public final class TechniqueStatusTissuesTest {
    private static final String STATUS = "sample:status";
    private static final int TOTAL_TICKS = 20;
    private static final int PEAK_REMAINING = 10;
    private static final int LONG_TICKS = 12000;
    private static final double EPSILON = 0.000001;
    private static final TissueBenefitLease EMPTY = TissueBenefitLease.getDefaultInstance();

    @Test
    public void refreshRestartsWithoutStackingAndRemovalPreservesLegacyCurve() {
        // 旧组与新组同笔替换，撤销状态后旧施法曲线仍按原相位运行。
        var host = new MemoryHost(body(Shape.TENDRIL));
        addLegacy(host, "meridian_technique:cast/61/0");
        TissueBenefitLease first = replace(host, EMPTY, new Phase(TOTAL_TICKS, TOTAL_TICKS));
        host.nowMillis = CURVE_MIDDLE;
        assertEquals(STRENGTH * PEAK * PEAK, strength(host), EPSILON);
        TissueBenefitLease refreshed = replace(host, first, new Phase(TOTAL_TICKS, TOTAL_TICKS));
        assertEquals(2, host.current().modifiers().size());
        assertEquals(STRENGTH * PEAK, strength(host), EPSILON);
        TechniqueStatusTissues.remove(host, refreshed);
        TechniqueStatusTissues.remove(host, refreshed);
        assertEquals(1, host.current().modifiers().size());
        assertEquals(STRENGTH * PEAK, strength(host), EPSILON);
    }

    @Test
    public void restoreUsesRemainingPhaseWithoutPayingOrReplaying() {
        // 卸载撤销后身体时钟可以推进，恢复只登记剩余半条曲线。
        var host = new MemoryHost(body(Shape.TENDRIL));
        var before = host.current();
        TissueBenefitLease lease = replace(host, EMPTY, new Phase(TOTAL_TICKS, TOTAL_TICKS));
        TechniqueStatusTissues.remove(host, lease);
        host.nowMillis = CURVE_END;
        TissueBenefitLease restored = replace(host, lease, new Phase(TOTAL_TICKS, PEAK_REMAINING));
        assertEquals(STRENGTH * PEAK, strength(host), EPSILON);
        assertEquals(before.totalQi(), host.current().totalQi(), EPSILON);
        assertTrue(host.effects.isEmpty());
        assertTrue(host.current().actions().isEmpty());
        host.nowMillis += CURVE_MIDDLE;
        assertEquals(STRENGTH, strength(host), EPSILON);
        TechniqueStatusTissues.remove(host, restored);
        assertTrue(host.current().modifiers().isEmpty());
    }

    @Test
    public void capacityAndRevisionRejectionLeaveExistingStateIntact() {
        // 32 条已满时新状态失败；替换自有组不额外占容量，宿主拒绝不发布局部结果。
        var host = new MemoryHost(body(Shape.TENDRIL));
        for (int index = 0; index < CapabilityModifier.MAX_PER_BODY; index++) {
            addLegacy(host, "meridian_technique:cast/62/" + index);
        }
        var full = host.current();
        assertThrows(TechniqueErrors.HostRejected.class,
                () -> replace(host, EMPTY, new Phase(TOTAL_TICKS, TOTAL_TICKS)));
        assertEquals(full, host.current());
        var fresh = new MemoryHost(body(Shape.TENDRIL));
        TissueBenefitLease lease = replace(fresh, EMPTY, new Phase(TOTAL_TICKS, TOTAL_TICKS));
        var before = fresh.current();
        fresh.rejectNextActivity();
        assertThrows(TechniqueErrors.HostRejected.class,
                () -> replace(fresh, lease, new Phase(TOTAL_TICKS, TOTAL_TICKS)));
        assertEquals(before, fresh.current());
        BodyActivity activity = BodyActivity.newBuilder().addAllRemoveModifiers(lease.getModifierIdsList()).build();
        assertThrows(TechniqueErrors.HostRejected.class,
                () -> fresh.submit(new TechniqueHost.Submission("stale", before.revision() - 1, activity)));
        assertEquals(before, fresh.current());
    }

    @Test
    public void protocolRejectsOldCurveRemovalAndForgedLease() {
        // 新生命周期不能成为提前撤销旧曲线或借用其他状态归属的入口。
        BodyActivity oldRemoval = BodyActivity.newBuilder()
                .addRemoveModifiers("meridian_technique:cast/61/0")
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueStatusTissues.validateRemovals(oldRemoval));
        var host = new MemoryHost(body(Shape.TENDRIL));
        TissueBenefitLease lease = replace(host, EMPTY, new Phase(TOTAL_TICKS, TOTAL_TICKS));
        TissueBenefitLease forged = lease.toBuilder().setStatusId("sample:other").build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueStatusTissues.remove(host, forged));
        assertEquals(1, host.current().modifiers().size());
    }

    @Test
    public void validatesAbilityKindSlotsAndCurveAtCatalogBoundary() {
        // 原轻身仍走跃步分支；组织状态的空描述、混合能力和不存在的槽必须在载入时拒绝。
        StatusBenefit valid = benefit();
        assertEquals(1, registry(statusDefinition(valid)).definitions().size());
        StatusBenefit empty = valid.toBuilder().clearTissue().build();
        StatusBenefit both = valid.toBuilder().setLeap(LeapProfile.getDefaultInstance()).build();
        assertThrows(IllegalArgumentException.class, () -> registry(statusDefinition(empty)));
        assertThrows(IllegalArgumentException.class, () -> registry(statusDefinition(both)));
        TissueModifier missing = modifier().toBuilder().setSlotId("missing").build();
        TissueBenefitProfile invalidProfile = TissueBenefitProfile.newBuilder().addModifiers(missing).build();
        StatusBenefit invalid = valid.toBuilder().setTissue(invalidProfile).build();
        assertThrows(IllegalArgumentException.class, () -> registry(statusDefinition(invalid)));
        TechniqueDefinition definition = statusDefinition(valid);
        TechniqueDefinition repeated = definition.toBuilder()
                .addEffects(definition.getEffects(0))
                .build();
        assertThrows(IllegalArgumentException.class, () -> registry(repeated));
    }

    @Test
    public void longCommandCurveContinuesAcrossBoundedWindows() {
        // 超过 Framework 单条上限的命令效果按相位分段，第二窗口不会重新从零爬升。
        var host = new MemoryHost(body(Shape.TENDRIL));
        TissueBenefitLease first = replace(host, EMPTY, new Phase(LONG_TICKS, LONG_TICKS));
        long boundary = CapabilityModifier.MAX_CURVE_DURATION_MILLIS;
        assertEquals(boundary, host.current().modifiers().getFirst().curve().endMillis());
        host.nowMillis = boundary;
        int remaining = LONG_TICKS - Math.toIntExact(boundary / TechniqueStatusCurves.MILLIS_PER_TICK);
        replace(host, first, new Phase(LONG_TICKS, remaining));
        assertEquals(STRENGTH * PEAK, strength(host), EPSILON);
    }

    private static TissueBenefitLease replace(MemoryHost host, TissueBenefitLease previous, Phase phase) {
        // 测试直接使用公开状态生命周期，所有写入仍由 MemoryHost 的真实 handleActivity 执行。
        return TechniqueStatusTissues.replace(host, definition(), benefit(), previous, phase);
    }

    private static StatusBenefit benefit() {
        // 把既有测试曲线包装成一种无具体内容身份的组织能力。
        TissueBenefitProfile profile = TissueBenefitProfile.newBuilder().addModifiers(modifier()).build();
        return StatusBenefit.newBuilder()
                .setId(STATUS)
                .setDurationMillis(CURVE_END)
                .setTissue(profile)
                .build();
    }

    private static TechniqueDefinition statusDefinition(StatusBenefit benefit) {
        // 状态在起手成功结束后分发。
        TechniqueEffect effect = TechniqueEffect.newBuilder().setAtMillis(WARMUP).setStatusBenefit(benefit).build();
        return definition().toBuilder().addEffects(effect).build();
    }

    private static double strength(MemoryHost host) {
        // 读取 Framework 的有效能力，断言公开行为而非计划字段。
        return TissueCapabilities.available(host.inspect().body(), new Anatomy.TissueId("tendril"))
                .strengthNewtons();
    }

    private static void addLegacy(MemoryHost host, String id) {
        // 构造旧来源曲线模拟已经存在的存档，后续必须完整保留。
        var modifier = BodyMessages.CapabilityModifier.newBuilder()
                .setId(id)
                .setSource(TechniqueHost.CALLER_ID)
                .setTissueId("tendril")
                .setStartMillis(host.nowMillis)
                .addAllCurve(modifier().getCurveList())
                .setStrengthPeak(PEAK)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .build();
        BodyActivity activity = BodyActivity.newBuilder().addAddModifiers(modifier).build();
        host.submit(new TechniqueHost.Submission(id.replace(':', '_'), host.current().revision(), activity));
    }
}
