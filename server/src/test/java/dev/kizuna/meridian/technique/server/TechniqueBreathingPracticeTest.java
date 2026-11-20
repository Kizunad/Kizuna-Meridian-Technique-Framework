package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BreathingMessages.BreathingFactors;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.server.BreathingModifiers;
import dev.kizuna.meridian.technique.protocol.pb.MaintainedBreathingMessages.BreathingPractice;
import dev.kizuna.meridian.technique.protocol.pb.MaintainedBreathingMessages.MaintainedBreathing;
import org.junit.Test;

import static dev.kizuna.meridian.technique.server.TechniqueBreathingPractice.Settlement.END;
import static dev.kizuna.meridian.technique.server.TechniqueBreathingPractice.Settlement.MAINTAIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueBreathingPracticeTest {
    private static final double RATE = 0.005;
    private static final double INTAKE = 1.5;
    private static final double DISCOUNT = 0.5;
    private static final double EPSILON = 1e-12;
    private static final long HALF_SECOND = 500;
    private static final long MINUTE = 60_000;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final String CAST = "first";

    @Test
    public void closedMeridiansPayToTheMillisecondAndOneInstantIsOnlyPaidOnce() {
        // 没开脉也能付款。空转一分钟再半秒手收，同时扣元、记游标、关收益。
        var fresh = fresh(1);
        var active = start(fresh);
        var before = at(active, MINUTE);
        var fee = TechniqueBreathingPractice.settle(before, MAINTAIN).orElseThrow();
        var paid = apply(before, fee);
        assertEquals(RATE * SECONDS_PER_MINUTE, BodyProtobuf.fromInventory(fee.getReleasedQi()).totalQi(), EPSILON);
        assertEquals(fresh.totalQi() - RATE * SECONDS_PER_MINUTE, paid.totalQi(), EPSILON);
        assertTrue(TechniqueBreathingPractice.settle(paid, MAINTAIN).isEmpty());
        var releaseAt = at(paid, MINUTE + HALF_SECOND);
        var ended = apply(releaseAt, TechniqueBreathingPractice.settle(releaseAt, END).orElseThrow());
        assertEquals(paid.totalQi() - RATE / 2, ended.totalQi(), EPSILON);
        assertTrue(TechniqueBreathingPractice.active(ended).isEmpty());
        assertEquals(BreathingModifiers.NORMAL, BreathingModifiers.read(ended));
        assertTrue(TechniqueBreathingPractice.begin(ended, CAST, Fixtures.TECHNIQUE_ID, definition()).isEmpty());
    }

    @Test
    public void mixedPhasesPayByVolumeAndAStalePaymentCannotAdvanceTheCursor() {
        // 两侧内腔都有两相真元。旧报价遇到余额变化先被拒绝，再依据新快照收同一段费用。
        var active = at(start(Fixtures.body(Fixtures.Shape.PAIRED)), HALF_SECOND);
        var oldQuote = TechniqueBreathingPractice.settle(active, MAINTAIN).orElseThrow();
        var updated = BodyProtobuf.toMessage(active).toBuilder();
        updated.getMeridianStatesBuilder(0).getLumenQiBuilder().removeEntries(0);
        var changed = BodyProtobuf.fromMessage(updated.build());
        assertThrows(IllegalArgumentException.class, () -> apply(changed, oldQuote));
        assertEquals(0, TechniqueBreathingPractice.active(changed).orElseThrow().getPaidMillis());
        var newQuote = TechniqueBreathingPractice.settle(changed, MAINTAIN).orElseThrow();
        var paid = apply(changed, newQuote);
        double totalVolume = changed.meridianStates().stream().mapToDouble(state -> state.lumenQi().volume()).sum();
        double cost = RATE / 2;
        for (var change : newQuote.getAccountsList()) {
            var before = BodyProtobuf.fromInventory(change.getExpected());
            var after = BodyProtobuf.fromInventory(change.getReplacement());
            assertEquals(cost * before.volume() / totalVolume, before.totalQi() - after.totalQi(), EPSILON);
        }
        assertEquals(cost, changed.totalQi() - paid.totalQi(), EPSILON);
        assertTrue(TechniqueBreathingPractice.settle(paid, MAINTAIN).isEmpty());
    }

    @Test
    public void insufficientOrExactBalanceClosesInThePaymentActivity() throws Exception {
        // 刚好够一秒、只够半秒都用尽真实内腔；外壁留存不允许垫付。
        for (double available : new double[] {RATE, RATE / 2}) {
            var before = at(start(fresh(available)), 2 * HALF_SECOND);
            var activity = TechniqueBreathingPractice.settle(before, MAINTAIN).orElseThrow();
            var ended = apply(before, activity);
            var paid = activity.getProgressOrThrow(TechniqueBreathingPractice.KEY).unpack(BreathingPractice.class);
            assertEquals(Math.round(2 * HALF_SECOND * available / RATE), paid.getPaidMillis());
            assertEquals(available, BodyProtobuf.fromInventory(activity.getReleasedQi()).totalQi(), EPSILON);
            assertEquals(before.meridianStates().getFirst().wallQi(), ended.meridianStates().getFirst().wallQi());
            assertEquals(0, ended.meridianStates().getFirst().lumenQi().totalQi(), 0);
            assertTrue(TechniqueBreathingPractice.active(ended).isEmpty());
            assertEquals(BreathingModifiers.NORMAL, BreathingModifiers.read(ended));
            assertThrows(TechniqueErrors.Rejected.class, () ->
                    TechniqueBreathingPractice.begin(ended, "next", Fixtures.TECHNIQUE_ID, definition()));
        }
    }

    private static BodySnapshot fresh(double balance) {
        // 用唯一的未开内腔放可付余额，并单独保留外壁库存。
        var body = BodyProtobuf.toMessage(Fixtures.body(Fixtures.Shape.TENDRIL)).toBuilder();
        var state = body.getMeridianStatesBuilder(0);
        var entry = state.getLumenQi().getEntries(0).toBuilder().setAmount(balance).build();
        var lumen = BodyMessages.QiInventory.newBuilder().addEntries(entry).build();
        state.setWallQi(lumen);
        state.setLumenQi(lumen);
        state.getOpeningBuilder()
                .setStage(BodyMessages.MeridianOpeningStage.UNOPENED)
                .setProgress(0);
        return BodyProtobuf.fromMessage(body.build());
    }

    private static BodySnapshot start(BodySnapshot body) {
        // 起式只挂因子；首次时间间隔才收费。
        var activity = TechniqueBreathingPractice.begin(body, CAST, Fixtures.TECHNIQUE_ID, definition()).orElseThrow();
        var active = apply(body, activity);
        assertEquals(INTAKE, BreathingModifiers.read(active).intake(), 0);
        assertEquals(body.totalQi(), active.totalQi(), 0);
        return active;
    }

    private static MaintainedBreathing definition() {
        // 测试内容使用与实战相同的目录协议。
        var factors = BreathingFactors.newBuilder()
                .setIntakeMultiplier(INTAKE)
                .setRespiratoryWorkMultiplier(DISCOUNT)
                .build();
        return MaintainedBreathing.newBuilder()
                .setEffectId("sample:breathing")
                .setFactors(factors)
                .setQiPerSecond(RATE)
                .build();
    }

    private static BodySnapshot at(BodySnapshot body, long millis) {
        // 独立推进身体时间，分清费用与调用次数。
        var snapshot = BodyProtobuf.toMessage(body).toBuilder();
        snapshot.getSettledAtBuilder().setElapsedMillis(millis);
        return BodyProtobuf.fromMessage(snapshot.build());
    }

    private static BodySnapshot apply(BodySnapshot body, BodyActivity activity) {
        // 框架实际检验版本候选、内腔余额和环境释放守恒。
        return BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
    }
}
