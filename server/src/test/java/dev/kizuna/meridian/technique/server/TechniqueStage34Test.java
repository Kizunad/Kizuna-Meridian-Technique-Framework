package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueStage34Test {
    private static final double IMPULSE_SPEED = 4;
    private static final double INPUT_X = 3;
    private static final double INPUT_Z = 4;
    private static final double TARGET_DISTANCE_METERS = 4;
    private static final double OUT_OF_RANGE_DISTANCE_METERS = 20;
    private static final double HIGH_PROFICIENCY_RANDOM_TARGET = 0.4;
    private static final long SELECTION_SEED = 19;
    private static final long PRESENTATION_AT_START = 0;
    private static final long PRESENTATION_DURING_WARMUP = WARMUP;
    private static final String TARGET_ID = "body-TENDRIL";
    private static final String PRESENTATION_ID = "sample:stance";

    @Test
    public void impulseIsReturnedAtItsDeclaredBodyTime() {
        // 冲量只生成宿主意图，目标和输入方向沿用原始施放请求。
        PreparedImpulse prepared = prepareImpulse();
        ActiveCast active = prepared.active();
        assertTrue(TechniqueScheduler.effectsAt(prepared.definition(), active,
                active.getStartedMillis()).isEmpty());
        List<EffectIntent> effects = TechniqueScheduler.effectsAt(prepared.definition(),
                active, active.getStartedMillis() + WARMUP);
        assertEquals(1, effects.size());
        EffectIntent returned = effects.get(0);
        assertEquals(prepared.request().getCastId(), returned.getCastId());
        assertEquals(TARGET_ID, returned.getTargetId());
        assertEquals(prepared.request().getDirection(), returned.getDirection());
        assertEquals(prepared.definition().getEffects(0).getImpulse(), returned.getImpulse());
    }

    @Test
    public void advanceReturnsDueEffectsAndRecoveryCommit() {
        // 带上次时刻的状态机返回效果，并在收势边界提交停止动作的活动。
        PreparedImpulse prepared = prepareImpulse();
        MemoryHost host = prepared.host();
        TechniqueCasting casting = prepared.casting();
        ActiveCast active = prepared.active();
        host.nowMillis = WARMUP;
        TechniqueCasting.AdvanceResult dispatched = casting.advance(host,
                active.getStartedMillis() - 1);
        assertEquals(1, dispatched.effects().size());
        assertEquals(WARMUP, dispatched.advancedMillis());
        assertTrue(dispatched.recoveryCommit().isEmpty());
        host.nowMillis = WARMUP + ACTIVE + RECOVERY;
        TechniqueCasting.AdvanceResult finished = casting.advance(host, WARMUP);
        assertEquals(CastPhase.FINISHED, finished.outcome().getPhase());
        assertEquals(WARMUP + ACTIVE + RECOVERY, finished.advancedMillis());
        assertTrue(finished.effects().isEmpty());
        assertTrue(finished.recoveryCommit().isPresent());
    }

    private static PreparedImpulse prepareImpulse() {
        // 测试功法通过真实目录校验，起手请求只携带方向和目标意图。
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(quantity(IMPULSE_SPEED, Unit.METERS_PER_SECOND))
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setImpulse(impulse)
                .build();
        TechniqueDefinition definition = definition().toBuilder()
                .addEffects(effect)
                .build();
        BodySnapshot body = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(body);
        HorizontalDirection direction = HorizontalDirection.newBuilder()
                .setX(INPUT_X)
                .setZ(INPUT_Z)
                .build();
        CastRequest request = request(body, "impulse").toBuilder()
                .setTargetId(TARGET_ID)
                .setDirection(direction)
                .build();
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request).getPhase());
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        return new PreparedImpulse(definition, host, request, active, casting);
    }

    @Test
    public void interruptedCastKeepsSubmittedModifierUntilCurveEnd() {
        // 起手打断只停止动作和进度，已经提交的完整曲线不被提前撤销。
        TechniqueEffect effect = TechniqueRegistryTest.effect(modifier(), 0);
        TechniqueDefinition definition = definition().toBuilder()
                .addEffects(effect)
                .build();
        BodySnapshot body = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(body);
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(body, "interrupt")).getPhase());
        assertEquals(1, host.current().modifiers().size());
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        TechniqueCasting.AdvanceResult interrupted = casting.interruptByDamage(host,
                active.getStartedMillis() - 1);
        assertEquals(CastPhase.INTERRUPTED, interrupted.outcome().getPhase());
        assertEquals(1, host.current().modifiers().size());
        host.nowMillis = CURVE_END;
        assertTrue(host.inspect().modifiers().isEmpty());
        assertEquals(0, host.current().actions().size());
    }

    @Test
    public void earlyTerminationStopsStartedContinuousPresentation() {
        // 伤害、卸载和下线都在已确认 START 后补发同一个表现停止意图。
        for (Termination termination : Termination.values()) {
            TechniqueDefinition definition = presentationDefinition(PRESENTATION_AT_START);
            BodySnapshot body = withProgress(body(Shape.TENDRIL), progress(definition));
            MemoryHost host = new MemoryHost(body);
            TechniqueCasting casting = new TechniqueCasting(registry(definition));
            String castId = termination.name().toLowerCase(java.util.Locale.ROOT);
            assertEquals(CastPhase.WARMUP, casting.cast(host, request(body, castId)).getPhase());
            ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
            TechniqueCasting.AdvanceResult started = casting.advance(host,
                    active.getStartedMillis() - 1);
            assertEquals(1, started.effects().size());
            assertEquals(PresentationAction.START,
                    started.effects().get(0).getPresentation().getAction());

            TechniqueCasting.AdvanceResult ended;
            if (termination == Termination.DAMAGE) {
                ended = casting.interruptByDamage(host, active.getStartedMillis());
            } else if (termination == Termination.UNLOAD) {
                ended = casting.cancelOnUnload(host, active.getStartedMillis());
            } else {
                ended = casting.cancelOnDisconnect(host, active.getStartedMillis());
            }
            assertEquals(1, ended.effects().size());
            assertEquals(PresentationAction.STOP,
                    ended.effects().get(0).getPresentation().getAction());
            assertEquals(PRESENTATION_ID, ended.effects().get(0).getPresentation().getId());
        }
    }

    @Test
    public void earlyTerminationDoesNotStopPresentationBeforeItsStart() {
        // 提前取消不应停止尚未到达生效时刻的持续表现。
        TechniqueDefinition definition = presentationDefinition(PRESENTATION_DURING_WARMUP);
        BodySnapshot body = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(body);
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(body, "not_started")).getPhase());
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();

        TechniqueCasting.AdvanceResult ended = casting.cancelOnUnload(host,
                active.getStartedMillis() - 1);

        assertTrue(ended.effects().isEmpty());
        assertEquals(CastPhase.CANCELLED, ended.outcome().getPhase());
    }

    @Test
    public void selectorUsesStableSeedAndIgnoresZeroWeight() {
        // 同一种子按稳定候选顺序抽样；绑定、费用、冷却和距离任一不满足
        // 都不进入权重池。
        SelectionFixture fixture = selectionFixture();

        assertEquals(TechniqueSelector.choose(fixture.context(), fixture.known(), new Random(SELECTION_SEED)),
                TechniqueSelector.choose(fixture.context(), fixture.known(), new Random(SELECTION_SEED)));
        assertNotEquals(fixture.zeroRef(), TechniqueSelector.choose(fixture.context(), fixture.known(),
                new Random(SELECTION_SEED)).orElseThrow());
    }

    @Test
    public void selectorRejectsUnaffordableAndDistantCandidates() {
        // 真元不足或超出 AI 距离范围时不返回任何功法。
        SelectionFixture fixture = selectionFixture();
        TechniqueSelector.Availability unaffordable = new TechniqueSelector.Availability(
                fixture.availability().bindings(), Set.of(), Map.of());
        TechniqueSelector.Context noQi = new TechniqueSelector.Context(fixture.context().registry(),
                fixture.context().situation(), unaffordable);
        assertTrue(TechniqueSelector.choose(noQi, fixture.known(), new Random(19)).isEmpty());
        TechniqueSelector.Situation distant = new TechniqueSelector.Situation(
                0, 1, 1, OUT_OF_RANGE_DISTANCE_METERS, TechniqueSelector.TargetState.ENGAGED);
        TechniqueSelector.Context outOfRange = new TechniqueSelector.Context(fixture.context().registry(),
                distant, fixture.availability());
        assertTrue(TechniqueSelector.choose(outOfRange, fixture.known(), new Random(19)).isEmpty());
    }

    @Test
    public void selectorAllowsZeroProficiencyAndFavorsHigherProficiency() {
        // 刚学会的功法保留基础权重，熟练度一的候选在相同情境下占更大区间。
        SelectionFixture fixture = selectionFixture();
        KnownTechnique novice = fixture.known().get(0).toBuilder()
                .setProficiency(quantity(0, Unit.RATIO))
                .build();
        KnownTechnique expert = fixture.known().get(1);

        assertEquals(novice.getTechnique(), TechniqueSelector.choose(fixture.context(), List.of(novice),
                fixedRandom(0)).orElseThrow());
        assertEquals(expert.getTechnique(), TechniqueSelector.choose(fixture.context(),
                List.of(novice, expert), fixedRandom(HIGH_PROFICIENCY_RANDOM_TARGET)).orElseThrow());
    }

    private static TechniqueDefinition presentationDefinition(long atMillis) {
        // 持续表现只放进测试功法，用于验证已分发起止意图的生命周期。
        PresentationEffect presentation = PresentationEffect.newBuilder()
                .setId(PRESENTATION_ID)
                .setDuration(PresentationDuration.UNTIL_RECOVERY_END)
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setPresentation(presentation)
                .build();
        return definition().toBuilder()
                .addEffects(effect)
                .build();
    }

    private static Random fixedRandom(double value) {
        // 让测试直接落在权重区间内，避免依赖随机种子碰巧命中边界。
        return new Random() {
            @Override
            public double nextDouble() {
                // 选择器只需一个可复现的 [0, 1) 抽样值。
                return value;
            }
        };
    }

    private static SelectionFixture selectionFixture() {
        // 三个候选共用同一身体绑定结果，只有零权重候选在抽样时被排除。
        TechniqueDefinition first = definition().toBuilder()
                .setId("sample:first")
                .setAiHints(hints(0, 16, 0, 0, 10))
                .build();
        TechniqueDefinition second = definition().toBuilder()
                .setId("sample:second")
                .setAiHints(hints(0, 16, 0, 0, 10))
                .build();
        TechniqueDefinition zeroWeight = definition().toBuilder()
                .setId("sample:zero")
                .setAiHints(hints(0, 16, 0, 0, 0))
                .build();
        TechniqueCatalog catalog = catalog(first).toBuilder()
                .addDefinitions(second)
                .addDefinitions(zeroWeight)
                .build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(catalog));
        BodySnapshot body = body(Shape.TENDRIL);
        TechniqueReference firstRef = TechniqueRegistry.reference(first);
        TechniqueReference secondRef = TechniqueRegistry.reference(second);
        TechniqueReference zeroRef = TechniqueRegistry.reference(zeroWeight);
        TechniqueSelector.Availability availability = new TechniqueSelector.Availability(
                Map.of(firstRef, TechniqueBinder.bind(body, first),
                        secondRef, TechniqueBinder.bind(body, second),
                        zeroRef, TechniqueBinder.bind(body, zeroWeight)),
                Set.of(firstRef, secondRef, zeroRef),
                Map.of());
        TechniqueSelector.Context context = new TechniqueSelector.Context(registry,
                new TechniqueSelector.Situation(0, 1, 1, TARGET_DISTANCE_METERS,
                        TechniqueSelector.TargetState.ENGAGED),
                availability);
        List<KnownTechnique> known = List.of(known(first), known(second), known(zeroWeight));
        return new SelectionFixture(context, availability, known, zeroRef);
    }

    private static KnownTechnique known(TechniqueDefinition definition) {
        // 测试只把熟练度设为已练成，确保选择结果由随机权重决定。
        return progress(definition).getKnown(0).toBuilder()
                .setProficiency(quantity(1, Unit.RATIO))
                .build();
    }

    private static AiHints hints(double minimumDistance, double maximumDistance,
                                 double maximumHp, double minimumQi, double weight) {
        // AI 提示在测试目录中完整提供单位，生产代码不携带正式功法内容。
        return AiHints.newBuilder()
                .setMinimumDistance(quantity(minimumDistance, Unit.METER))
                .setMaximumDistance(quantity(maximumDistance, Unit.METER))
                .setMaximumHpRatio(quantity(maximumHp, Unit.RATIO))
                .setMinimumQiRatio(quantity(minimumQi, Unit.RATIO))
                .setCategoryWeight(quantity(weight, Unit.RATIO))
                .build();
    }

    private record PreparedImpulse(TechniqueDefinition definition, MemoryHost host,
                                   CastRequest request, ActiveCast active, TechniqueCasting casting) { }

    private record SelectionFixture(TechniqueSelector.Context context,
                                    TechniqueSelector.Availability availability,
                                    List<KnownTechnique> known,
                                    TechniqueReference zeroRef) { }

    private enum Termination { DAMAGE, UNLOAD, DISCONNECT }
}
