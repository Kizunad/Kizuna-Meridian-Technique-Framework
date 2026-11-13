package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 擒、摔两种附属效果：声明边界、书级拳掌规则、调度意图和施放速度缩放。 */
public final class TechniqueGrappleTest {
    private static final String THROW_ID = "sample:shoulder_throw";
    private static final String BOOK_ID = "sample:grapple_book";
    private static final String VOLUME_ID = "sample:grapple_volume";
    private static final String PROCESSOR_ID = "sample:grapple";
    private static final String DAMAGE_TYPE_ID = "sample:throw";
    private static final long WARMUP = 1_200;
    private static final long ACTIVE = 1_400;
    private static final long RECOVERY = 3_400;
    private static final long THROW_AT = 2_200;
    private static final long HOLD_MILLIS = THROW_AT - WARMUP;
    private static final long LEAN_DELAY = 300;
    private static final long FLIGHT = 400;
    private static final long DOWN = 1_500;
    private static final long DOWN_IMMUNITY = 3_000;
    private static final double REACH = 2;
    private static final double HOLD_DISTANCE = 1;
    private static final double LANDING = 2;
    private static final double APEX = 0.5;
    private static final double THROW_MULTIPLIER = 2;
    private static final double LEAN_MULTIPLIER = 0.4;
    private static final double LEAN_PUSH = 1;
    private static final double BASE_DAMAGE = 1.5;
    private static final double SLOW = 0.5;

    @Test
    public void grappleAndThrowCarryBookArmRulesToTheProcessor() {
        // 擒在起手结束那一刻、摔在 2.20 各发一个意图，都带起手保存的拳掌规则，并通过意图校验。
        TechniqueDefinition definition = throwDefinition();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(throwCatalog(definition, book())));
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(prepared);
        CastOutcome started = new TechniqueCasting(registry).cast(host, request(definition, prepared, "throw"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        assertEquals(CastPhase.WARMUP, started.getPhase());
        assertEquals(armRules(), active.getArmRules());
        EffectIntent grapple = only(TechniqueScheduler.effectsAt(definition, active, WARMUP));
        assertEquals(grapple(), grapple.getGrapple().getGrapple());
        assertEquals(armRules(), grapple.getGrapple().getArmRules());
        TechniqueProtocol.validateEffect(grapple);
        EffectIntent toss = only(TechniqueScheduler.effectsAt(definition, active, THROW_AT));
        assertEquals(toss(), toss.getThrow().getThrow());
        assertEquals(armRules(), toss.getThrow().getArmRules());
        TechniqueProtocol.validateEffect(toss);
        EffectIntent noRules = toss.toBuilder()
                .setThrow(toss.getThrow().toBuilder().clearArmRules())
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(noRules));
    }

    @Test
    public void grappleNeedsAnArmBookAndMustFitInTheCast() {
        // 没有拳掌规则的书不能装擒；擒的时长超出整式也在加载时拒绝。
        TechniqueDefinition definition = throwDefinition();
        assertLoadFails(throwCatalog(definition, book().toBuilder().clearArmRules().build()));
        long tooLong = ACTIVE + RECOVERY + 1;
        Grapple longHold = grapple().toBuilder()
                .setDurationMillis(tooLong)
                .build();
        TechniqueEffect longEffect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setGrapple(longHold)
                .build();
        TechniqueDefinition invalid = definition.toBuilder()
                .setEffects(0, longEffect)
                .build();
        assertLoadFails(throwCatalog(invalid, book()));
    }

    @Test
    public void invalidGrappleAndThrowDeclarationsAreRejected() {
        // 处理器、距离、时长、一靠和落地数值逐项越界都拒绝。
        Grapple grapple = grapple();
        TechniqueGrapples.validate(grapple);
        List<Grapple> invalidGrapples = List.of(
                grapple.toBuilder().clearProcessorId().build(),
                grapple.toBuilder().setMaximumDistance(quantity(0, Unit.METER)).build(),
                grapple.toBuilder().setHoldDistance(quantity(REACH + 1, Unit.METER)).build(),
                grapple.toBuilder().setDurationMillis(0).build(),
                grapple.toBuilder().clearLean().build(),
                grapple.toBuilder().setLean(lean().toBuilder().setDelayMillis(HOLD_MILLIS + 1)).build(),
                grapple.toBuilder().setLean(lean().toBuilder().clearDamageTypeId()).build(),
                grapple.toBuilder().setLean(lean().toBuilder().setPush(quantity(-1, Unit.RATIO))).build());
        for (Grapple invalid : invalidGrapples) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueGrapples.validate(invalid));
        }
        Throw toss = toss();
        TechniqueGrapples.validate(toss);
        List<Throw> invalidThrows = List.of(
                toss.toBuilder().clearProcessorId().build(),
                toss.toBuilder().setFlightMillis(0).build(),
                toss.toBuilder().setLandingDistance(quantity(0, Unit.METER)).build(),
                toss.toBuilder().setApexHeight(quantity(-1, Unit.METER)).build(),
                toss.toBuilder().setMultiplier(quantity(0, Unit.RATIO)).build(),
                toss.toBuilder().setDownMillis(0).build(),
                toss.toBuilder().setDownImmunityMillis(-1).build());
        for (Throw invalid : invalidThrows) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueGrapples.validate(invalid));
        }
    }

    @Test
    public void slowCastsHoldLongerButDownTimeBelongsToTheTarget() {
        // 施放放慢时擒的时长、一靠时刻和飞行跟着拉长；倒地和免摔属于被摔的人，不变。
        Grapple slowGrapple = TechniqueGrapples.scale(grapple(), SLOW);
        assertEquals(TechniqueTempo.scale(HOLD_MILLIS, SLOW), slowGrapple.getDurationMillis());
        assertEquals(TechniqueTempo.scale(LEAN_DELAY, SLOW), slowGrapple.getLean().getDelayMillis());
        Throw slowThrow = TechniqueGrapples.scale(toss(), SLOW);
        assertEquals(TechniqueTempo.scale(FLIGHT, SLOW), slowThrow.getFlightMillis());
        assertEquals(DOWN, slowThrow.getDownMillis());
        assertEquals(DOWN_IMMUNITY, slowThrow.getDownImmunityMillis());
    }

    private static EffectIntent only(List<EffectIntent> effects) {
        // 该时刻只该有一个世界意图。
        assertEquals(1, effects.size());
        return effects.get(0);
    }

    private static void assertLoadFails(TechniqueCatalog catalog) {
        // 目录加载整体拒绝。
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(catalog)));
    }

    private static Grapple grapple() {
        // 2 格内抓一个，拉到身前 1 格，擒 1 s；摔不动时 0.3 s 后一靠。
        return Grapple.newBuilder()
                .setProcessorId(PROCESSOR_ID)
                .setMaximumDistance(quantity(REACH, Unit.METER))
                .setHoldDistance(quantity(HOLD_DISTANCE, Unit.METER))
                .setDurationMillis(HOLD_MILLIS)
                .setLean(lean())
                .build();
    }

    private static ThrowLean lean() {
        // 一靠：×0.4，推开 1.0。
        return ThrowLean.newBuilder()
                .setDelayMillis(LEAN_DELAY)
                .setDamageTypeId(DAMAGE_TYPE_ID)
                .setMultiplier(quantity(LEAN_MULTIPLIER, Unit.RATIO))
                .setPush(quantity(LEAN_PUSH, Unit.RATIO))
                .build();
    }

    private static Throw toss() {
        // 抛 0.4 s，落在身后 2 格，倒地 1.5 s，起来后 3 s 不再被摔。
        return Throw.newBuilder()
                .setProcessorId(PROCESSOR_ID)
                .setFlightMillis(FLIGHT)
                .setLandingDistance(quantity(LANDING, Unit.METER))
                .setApexHeight(quantity(APEX, Unit.METER))
                .setDamageTypeId(DAMAGE_TYPE_ID)
                .setMultiplier(quantity(THROW_MULTIPLIER, Unit.RATIO))
                .setDownMillis(DOWN)
                .setDownImmunityMillis(DOWN_IMMUNITY)
                .build();
    }

    private static ArmRules armRules() {
        // 拳掌书固定忽略手里的东西、要求着地。
        return ArmRules.newBuilder()
                .setIgnoreHeldItems(true /* ignoreHeldItems */)
                .setRequireGrounded(true /* requireGrounded */)
                .setBaseDamage(quantity(BASE_DAMAGE, Unit.DAMAGE_POINT))
                .build();
    }

    private static TechniqueDefinition throwDefinition() {
        // 背摔的时间轴：起手 1.20 擒，2.20 摔；费用和组织槽位沿用测试功法。
        CastPhases phases = definition().getPhases().toBuilder()
                .setWarmupMillis(WARMUP)
                .setActiveMillis(ACTIVE)
                .setRecoveryMillis(RECOVERY)
                .build();
        TechniqueEffect grapple = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setGrapple(grapple())
                .build();
        TechniqueEffect toss = TechniqueEffect.newBuilder()
                .setAtMillis(THROW_AT)
                .setThrow(toss())
                .build();
        return definition().toBuilder()
                .setId(THROW_ID)
                .setPhases(phases)
                .addEffects(grapple)
                .addEffects(toss)
                .build();
    }

    private static CastRequest request(TechniqueDefinition definition, BodySnapshot body, String castId) {
        // 请求只带精确功法引用和施放身份。
        return CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(TechniqueRegistry.reference(definition))
                .setCastId(castId)
                .build();
    }

    private static TechniqueBook book() {
        // 只声明拳掌规则的书。
        return TechniqueBook.newBuilder()
                .setId(BOOK_ID)
                .setArmRules(armRules())
                .build();
    }

    private static TechniqueCatalog throwCatalog(TechniqueDefinition definition, TechniqueBook book) {
        // 一书一卷一式，经完整目录加载。
        TechniqueVolume volume = TechniqueVolume.newBuilder()
                .setId(VOLUME_ID)
                .setBookId(BOOK_ID)
                .addTechniques(TechniqueRegistry.reference(definition))
                .build();
        return catalog(definition).toBuilder()
                .addBooks(book)
                .addVolumes(volume)
                .build();
    }
}
