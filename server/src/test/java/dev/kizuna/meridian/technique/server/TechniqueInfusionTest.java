package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ElementalInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InfusionCounter;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueInfusions.InfusionStrike;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.COOLDOWN;
import static dev.kizuna.meridian.technique.server.Fixtures.GAS_DENSITY;
import static dev.kizuna.meridian.technique.server.Fixtures.HIT_STAGGER;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.TECHNIQUE_ID;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.point;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 附行由本库在效果时刻写进身体，按主人身体时钟到期；附伤数值只由主人进度和目标内腔决定。 */
public final class TechniqueInfusionTest {
    private static final String CAST = "infuse";
    private static final String SECOND_CAST = "infuse_again";
    private static final String THIRD_CAST = "infuse_third";
    private static final String FIRE = "meridian:fire";
    private static final String METAL = "meridian:metal";
    private static final String WATER = "meridian:water";
    private static final String NONE = "meridian:none";
    private static final String WITHERING = "qi_variants:withering";
    private static final String DAMAGE_TYPE = "sample:fire_infusion";
    private static final String HIT_SOUND = "sample:hit_fire";
    private static final long RAMP = 500;
    private static final long PLATEAU = 5_000;
    private static final long PLATEAU_END = 15_000;
    private static final long CURVE_LENGTH = 18_000;
    private static final long FINISHED = WARMUP + ACTIVE + RECOVERY;
    private static final long LATE = 50;
    private static final long SECOND_START = FINISHED + COOLDOWN;
    private static final long STAGGER_AT = SECOND_START + WARMUP / 2;
    private static final long THIRD_START = STAGGER_AT + COOLDOWN;
    private static final long SAVED_START = 1_000;
    private static final double RATIO = 0.25;
    private static final double HALF = 0.5;
    private static final double DOSE = 6;
    private static final int IGNITE_TICKS = 20;
    private static final double COUNTER = 1.5;
    private static final double COUNTERED = 0.5;
    private static final double COUNTER_VOLUME = 1.5;
    private static final double COUNTERED_VOLUME = 0.6;
    private static final double COUNTERED_PITCH = 0.2;
    private static final double HIT_VOLUME = 0.5;
    private static final double LOUD_VOLUME = 3;
    private static final double HIGH_PITCH = 1.9;
    private static final double JITTER = 0.1;
    private static final double PRIMARY_AMOUNT = 10;
    private static final double OTHER_AMOUNT = 15;
    private static final double NONE_AMOUNT = 20;
    private static final double SHARE_PART = 5;
    private static final double SHARE_REST = 15;
    private static final double EPSILON = 1e-9;

    @Test
    public void catalogRejectsMalformedInfusions() {
        // 合法附行能加载；曲线、比例、刻数、击退、相克条目与条数、命中声锚点任一越界，或一式写两个附行，都在加载时拒绝。
        assertTrue(registry(infused()).find(TechniqueRegistry.reference(infused())).isPresent());
        ElementalInfusion valid = infusion();
        SoundCue casterSound = valid.getHitSound().toBuilder().setAnchor(SoundAnchor.CASTER).build();
        List<ElementalInfusion> invalid = List.of(
                valid.toBuilder().setCurve(0, point(1, 0)).build(),
                valid.toBuilder().setCurve(0, point(0, 1)).build(),
                valid.toBuilder().setCurve(3, point(CURVE_LENGTH, 1)).build(),
                valid.toBuilder().setCurve(2, point(RAMP, 1)).build(),
                valid.toBuilder().setCurve(1, point(RAMP, 2)).build(),
                valid.toBuilder().setCurve(3, point(TechniqueValues.MAX_CURVE_MILLIS + 1, 0)).build(),
                valid.toBuilder().clearCurve().addCurve(point(0, 0)).build(),
                valid.toBuilder().clearRatio().build(),
                valid.toBuilder().setRatio(quantity(0, Unit.RATIO)).build(),
                valid.toBuilder().setIgniteTicks(-1).build(),
                valid.toBuilder().setFreezeTicks(TechniqueInfusions.MAX_TICKS + 1).build(),
                valid.toBuilder().setExtraKnockback(quantity(-1, Unit.RATIO)).build(),
                valid.toBuilder().addCounters(counter(METAL, COUNTER, 1, 0)).build(),
                valid.toBuilder().setCounters(0, counter(METAL, 0, 1, 0)).build(),
                valid.toBuilder().clearCounters().addAllCounters(tooManyCounters()).build(),
                valid.toBuilder().setHitSound(casterSound).build(),
                valid.toBuilder().clearDamageTypeId().build());
        for (ElementalInfusion infusion : invalid) {
            assertThrows(IllegalArgumentException.class, () -> registry(withInfusion(infusion)));
        }
        TechniqueDefinition twice = infused().toBuilder().addEffects(infusionEffect(valid)).build();
        assertThrows(IllegalArgumentException.class, () -> registry(twice));
    }

    @Test
    public void savedInfusionMustBeExplainable() {
        // 存档里的附行能原样往返；缺施放身份、缺功法、起点为负或定义快照越界都拒绝写入。
        ActiveInfusion valid = saved(infusion());
        TechniqueProgress progress = progress(infused()).toBuilder().setActiveInfusion(valid).build();
        byte[] encoded = TechniqueState.pack(progress).getValue().toByteArray();
        assertEquals(progress, TechniqueState.decode(encoded));
        ElementalInfusion uncurved = valid.getInfusion().toBuilder().clearCurve().build();
        List<ActiveInfusion> invalid = List.of(
                valid.toBuilder().clearCastId().build(),
                valid.toBuilder().clearTechniqueId().build(),
                valid.toBuilder().setStartedMillis(-1).build(),
                valid.toBuilder().setInfusion(uncurved).build());
        for (ActiveInfusion active : invalid) {
            TechniqueProgress broken = progress.toBuilder().setActiveInfusion(active).build();
            assertThrows(IllegalArgumentException.class, () -> TechniqueState.pack(broken));
        }
    }

    @Test
    public void infusionStartsAtEffectTimeEvenWhenTickIsLate() {
        // 起手结束前没有附行；迟到的 tick 仍按排定时刻起算，附行不产生世界效果意图。
        MemoryHost host = host(infused());
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(infused()));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), CAST)).getPhase());
        host.nowMillis = WARMUP - 1;
        service.tick();
        assertFalse(TechniqueState.read(host.current()).hasActiveInfusion());
        host.nowMillis = WARMUP + LATE;
        service.tick();
        ActiveInfusion active = TechniqueState.read(host.current()).getActiveInfusion();
        assertEquals(CAST, active.getCastId());
        assertEquals(TECHNIQUE_ID, active.getTechniqueId());
        assertEquals(WARMUP, active.getStartedMillis());
        assertEquals(infusion(), active.getInfusion());
        assertTrue(host.effects.isEmpty());
        assertEquals(CURVE_LENGTH - LATE, service.infusionRemainingMillis(ENTITY));
        assertEquals(0, service.infusionRemainingMillis(UUID.randomUUID()));
    }

    @Test
    public void interruptedWarmupKeepsOldInfusionAndNextCastReplacesIt() {
        // 新一式起手被打断时旧附行不动；下一式起手结束才整份覆盖，起点换成新的排定时刻。
        MemoryHost host = host(infused());
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(infused()));
        finishCast(service, host, CAST);
        host.nowMillis = SECOND_START;
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), SECOND_CAST)).getPhase());
        host.nowMillis = STAGGER_AT;
        service.stagger(ENTITY, HIT_STAGGER);
        service.tick();
        TechniqueProgress interrupted = TechniqueState.read(host.current());
        assertFalse(interrupted.hasActiveCast());
        assertEquals(SECOND_CAST, interrupted.getLastCast().getCastId());
        assertEquals(CAST, interrupted.getActiveInfusion().getCastId());
        assertEquals(WARMUP, interrupted.getActiveInfusion().getStartedMillis());
        host.nowMillis = THIRD_START;
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), THIRD_CAST)).getPhase());
        host.nowMillis = THIRD_START + WARMUP;
        service.tick();
        ActiveInfusion replaced = TechniqueState.read(host.current()).getActiveInfusion();
        assertEquals(THIRD_CAST, replaced.getCastId());
        assertEquals(THIRD_START + WARMUP, replaced.getStartedMillis());
    }

    @Test
    public void infusionOutlivesRecoveryAndLapsesAtCurveEnd() {
        // 收势不清附行；过了曲线末点查询就当没有，进度里的记录不必主动删除。
        MemoryHost host = host(infused());
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(infused()));
        finishCast(service, host, CAST);
        assertEquals(WARMUP + CURVE_LENGTH - FINISHED, service.infusionRemainingMillis(ENTITY));
        host.nowMillis = WARMUP + CURVE_LENGTH - 1;
        assertTrue(TechniqueInfusions.strike(host.inspect().body(), Optional.empty(), DOSE).isPresent());
        host.nowMillis = WARMUP + CURVE_LENGTH;
        BodySnapshot lapsed = host.inspect().body();
        assertTrue(TechniqueState.read(lapsed).hasActiveInfusion());
        assertEquals(0, TechniqueInfusions.remainingMillis(lapsed));
        assertTrue(TechniqueInfusions.strike(lapsed, Optional.empty(), DOSE).isEmpty());
    }

    @Test
    public void deathRecoveryClearsInfusionWithOrWithoutActiveCast() {
        // 死体时钟停住、附行不会自己到期，恢复入口要清掉它；其余进度原样保留，重复恢复不再提交。
        MemoryHost idle = finishedHost();
        idle.die();
        TechniqueProgress before = TechniqueState.read(idle.current());
        long revision = idle.current().revision();
        recoveryService().recoverOnLoad(ENTITY, idle);
        assertEquals(before.toBuilder().clearActiveInfusion().build(), TechniqueState.read(idle.current()));
        assertEquals(revision + 1, idle.current().revision());
        recoveryService().recoverOnLoad(ENTITY, idle);
        assertEquals(revision + 1, idle.current().revision());

        MemoryHost casting = finishedHost();
        startSecondCast(casting);
        casting.die();
        recoveryService().recoverOnLoad(ENTITY, casting);
        TechniqueProgress stopped = TechniqueState.read(casting.current());
        assertFalse(stopped.hasActiveCast());
        assertFalse(stopped.hasActiveInfusion());
    }

    @Test
    public void livingUnloadRecoveryKeepsInfusion() {
        // 活体卸载只结束残留施放，附行留着按身体时钟到期。
        MemoryHost host = finishedHost();
        startSecondCast(host);
        MemoryHost restored = host.restore();
        recoveryService().recoverOnLoad(ENTITY, restored);
        TechniqueProgress recovered = TechniqueState.read(restored.current());
        assertFalse(recovered.hasActiveCast());
        assertEquals(CAST, recovered.getActiveInfusion().getCastId());
    }

    @Test
    public void strikeScalesDoseByRatioCurveAndCounter() {
        // 附伤 = 剂量 × 比例 × 曲线值 × 相克倍率；爬坡、平台、回落各取一点，起点前、零剂量与死体都没有附伤。
        assertDamage(DOSE * RATIO * HALF, SAVED_START + RAMP / 2, Optional.empty());
        assertDamage(DOSE * RATIO, SAVED_START + PLATEAU, Optional.empty());
        assertDamage(DOSE * RATIO * HALF, SAVED_START + (PLATEAU_END + CURVE_LENGTH) / 2, Optional.empty());
        assertDamage(DOSE * RATIO * COUNTER, SAVED_START + PLATEAU, Optional.of(target(METAL)));
        assertDamage(DOSE * RATIO * COUNTERED, SAVED_START + PLATEAU, Optional.of(target(WATER)));
        assertDamage(DOSE * RATIO, SAVED_START + PLATEAU, Optional.of(target(FIRE)));
        BodySnapshot owner = ownerAt(SAVED_START + PLATEAU, infusion());
        InfusionStrike strike = TechniqueInfusions.strike(owner, Optional.empty(), DOSE).orElseThrow();
        assertEquals(DAMAGE_TYPE, strike.damageTypeId());
        assertEquals(1, strike.multiplier(), 0);
        assertEquals(IGNITE_TICKS, strike.igniteTicks());
        assertEquals(0, strike.freezeTicks());
        assertEquals(0, strike.extraKnockback(), 0);
        assertTrue(TechniqueInfusions.strike(owner, Optional.empty(), 0).isEmpty());
        BodySnapshot early = ownerAt(SAVED_START - 1, infusion());
        assertTrue(TechniqueInfusions.strike(early, Optional.empty(), DOSE).isEmpty());
        MemoryHost dying = ownerHost(infusion());
        dying.nowMillis = SAVED_START + PLATEAU;
        assertTrue(TechniqueInfusions.strike(dying.inspect().body(), Optional.empty(), DOSE).isPresent());
        dying.die();
        assertTrue(TechniqueInfusions.strike(dying.current(), Optional.empty(), DOSE).isEmpty());
        assertEquals(0, TechniqueInfusions.remainingMillis(dying.current()));
    }

    @Test
    public void counterAdjustsHitSoundWithinPlayableRange() {
        // 相克放大音量，被克压低音量并抬高音调；结果夹在原版可播放区间里，抖动两端也不越界。
        BodySnapshot owner = ownerAt(SAVED_START + PLATEAU, infusion());
        assertEquals(infusion().getHitSound(), hitSound(owner, Optional.empty()));
        SoundCue countering = hitSound(owner, Optional.of(target(METAL)));
        assertEquals(HIT_VOLUME * COUNTER_VOLUME, TechniqueSoundCues.volume(countering), EPSILON);
        assertEquals(1, TechniqueSoundCues.pitch(countering), EPSILON);
        SoundCue countered = hitSound(owner, Optional.of(target(WATER)));
        assertEquals(HIT_VOLUME * COUNTERED_VOLUME, TechniqueSoundCues.volume(countered), EPSILON);
        assertEquals(1 + COUNTERED_PITCH, TechniqueSoundCues.pitch(countered), EPSILON);
        SoundCue loud = infusion().getHitSound().toBuilder()
                .setVolume(quantity(LOUD_VOLUME, Unit.RATIO))
                .setPitch(quantity(HIGH_PITCH, Unit.RATIO))
                .setPitchJitter(quantity(JITTER, Unit.RATIO))
                .build();
        ElementalInfusion loudInfusion = infusion().toBuilder()
                .setHitSound(loud)
                .setCounters(0, counter(METAL, COUNTER, COUNTER_VOLUME, COUNTERED_PITCH))
                .build();
        SoundCue clamped = hitSound(ownerAt(SAVED_START + PLATEAU, loudInfusion), Optional.of(target(METAL)));
        assertEquals(TechniqueSoundCues.MAX_VOLUME, TechniqueSoundCues.volume(clamped), EPSILON);
        assertEquals(TechniqueSoundCues.MAX_PITCH - JITTER, TechniqueSoundCues.pitch(clamped), EPSILON);
        assertEquals(List.of(HIT_SOUND), TechniqueSoundCues.unregistered(List.of(infused()), id -> false));
    }

    @Test
    public void primaryAttributeNeedsSingleLeaderAboveShare() {
        // 只看经脉内腔：多条经脉按属性加总，无属性不参选但计入总量；并列或不到门槛都没有主属性，枯按 ×1。
        assertEquals(TechniqueInfusions.PRIMARY_SHARE_THRESHOLD, SHARE_PART / (SHARE_PART + SHARE_REST), 0);
        assertEquals(Optional.of(METAL), TechniqueInfusions.primaryAttribute(target(METAL)));
        BodySnapshot summed = target(List.of(qi(FIRE, PRIMARY_AMOUNT), qi(METAL, OTHER_AMOUNT)),
                List.of(qi(FIRE, PRIMARY_AMOUNT)));
        assertEquals(Optional.of(FIRE), TechniqueInfusions.primaryAttribute(summed));
        BodySnapshot tied = target(List.of(qi(FIRE, PRIMARY_AMOUNT), qi(METAL, PRIMARY_AMOUNT)), List.of());
        assertEquals(Optional.empty(), TechniqueInfusions.primaryAttribute(tied));
        BodySnapshot plain = target(List.of(qi(NONE, NONE_AMOUNT)), List.of());
        assertEquals(Optional.empty(), TechniqueInfusions.primaryAttribute(plain));
        BodySnapshot atShare = target(List.of(qi(FIRE, SHARE_PART), qi(NONE, SHARE_REST)), List.of());
        assertEquals(Optional.of(FIRE), TechniqueInfusions.primaryAttribute(atShare));
        BodySnapshot belowShare = target(List.of(qi(FIRE, SHARE_PART), qi(NONE, SHARE_REST + 1)), List.of());
        assertEquals(Optional.empty(), TechniqueInfusions.primaryAttribute(belowShare));
        BodySnapshot withered = target(WITHERING);
        assertEquals(Optional.of(WITHERING), TechniqueInfusions.primaryAttribute(withered));
        BodySnapshot owner = ownerAt(SAVED_START + PLATEAU, infusion());
        InfusionStrike strike = TechniqueInfusions.strike(owner, Optional.of(withered), DOSE).orElseThrow();
        assertEquals(1, strike.multiplier(), 0);
    }

    @Test
    public void quietCheckYieldsAtInfusionMoment() {
        // 节流试算遇到到期的附行要让给正常推进去写；写过以后，同一施放后面的区间又能跳过。
        MemoryHost plainHost = host(definition());
        TechniqueCasting plain = new TechniqueCasting(registry(definition()));
        assertEquals(CastPhase.WARMUP, plain.cast(plainHost, request(plainHost.current(), CAST)).getPhase());
        assertTrue(plain.quietAt(plainHost.current(), 0, WARMUP + LATE));
        MemoryHost host = host(infused());
        TechniqueCasting casting = new TechniqueCasting(registry(infused()));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(host.current(), CAST)).getPhase());
        assertFalse(casting.quietAt(host.current(), 0, WARMUP + LATE));
        host.nowMillis = WARMUP + LATE;
        assertEquals(CastPhase.EFFECTIVE, casting.advance(host).getPhase());
        assertTrue(casting.quietAt(host.current(), WARMUP + LATE, WARMUP + LATE + LATE));
    }

    private static ElementalInfusion infusion() {
        // 0.5 s 爬满、15 s 起回落、18 s 到期；火克金、水克火，命中声对准目标。
        SoundCue hit = SoundCue.newBuilder()
                .setSoundId(HIT_SOUND)
                .setVolume(quantity(HIT_VOLUME, Unit.RATIO))
                .setAnchor(SoundAnchor.TARGET)
                .build();
        return ElementalInfusion.newBuilder()
                .setAttribute(FIRE)
                .setDamageTypeId(DAMAGE_TYPE)
                .setRatio(quantity(RATIO, Unit.RATIO))
                .addCurve(point(0, 0))
                .addCurve(point(RAMP, 1))
                .addCurve(point(PLATEAU_END, 1))
                .addCurve(point(CURVE_LENGTH, 0))
                .setIgniteTicks(IGNITE_TICKS)
                .addCounters(counter(METAL, COUNTER, COUNTER_VOLUME, 0))
                .addCounters(counter(WATER, COUNTERED, COUNTERED_VOLUME, COUNTERED_PITCH))
                .setHitSound(hit)
                .build();
    }

    private static InfusionCounter counter(String attribute, double damage, double volume, double pitchOffset) {
        // 相克条目的三个调整都显式填写，省略时的默认值另由生产校验覆盖。
        return InfusionCounter.newBuilder()
                .setTargetAttribute(attribute)
                .setDamageMultiplier(quantity(damage, Unit.RATIO))
                .setSoundVolumeMultiplier(quantity(volume, Unit.RATIO))
                .setSoundPitchOffset(quantity(pitchOffset, Unit.RATIO))
                .build();
    }

    private static List<InfusionCounter> tooManyCounters() {
        // 目标属性互不相同、比上限多一条的相克表。
        return IntStream.rangeClosed(0, TechniqueInfusions.MAX_COUNTERS)
                .mapToObj(index -> counter("sample:attribute_" + index, COUNTER, 1, 0))
                .toList();
    }

    private static TechniqueEffect infusionEffect(ElementalInfusion infusion) {
        // 附行在起手结束那一刻生效。
        return TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setElementalInfusion(infusion)
                .build();
    }

    private static TechniqueDefinition withInfusion(ElementalInfusion infusion) {
        // 测试功法只多一个附行效果，其余费用和阶段沿用通用样例。
        return definition().toBuilder().addEffects(infusionEffect(infusion)).build();
    }

    private static TechniqueDefinition infused() {
        // 默认附行的测试功法。
        return withInfusion(infusion());
    }

    private static ActiveInfusion saved(ElementalInfusion infusion) {
        // 存档里已生效的附行，起点固定，便于按身体时钟取曲线上的点。
        return TechniqueInfusions.begin(CAST, TECHNIQUE_ID, SAVED_START, infusion);
    }

    private static MemoryHost host(TechniqueDefinition definition) {
        // 单经脉样本已学会给定功法，施放与推进都走真实 Framework 处理器。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
    }

    private static MemoryHost ownerHost(ElementalInfusion infusion) {
        // 主人身体直接带一个已生效的附行，时钟由测试宿主推进。
        TechniqueProgress progress = progress(infused()).toBuilder().setActiveInfusion(saved(infusion)).build();
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress));
    }

    private static BodySnapshot ownerAt(long clockMillis, ElementalInfusion infusion) {
        // 把主人身体时钟结到指定时刻，附伤按这一刻的曲线值算。
        MemoryHost host = ownerHost(infusion);
        host.nowMillis = clockMillis;
        return host.inspect().body();
    }

    private static void finishCast(TechniqueService service, MemoryHost host, String castId) {
        // 从当前时刻起手并一次推进到收势结束，附行在途中按排定时刻写入。
        long startMillis = host.nowMillis;
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), castId)).getPhase());
        host.nowMillis = startMillis + FINISHED;
        service.tick();
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
    }

    private static MemoryHost finishedHost() {
        // 真实起手与推进写出附行并走完收势，存档形状来自生产写入器。
        MemoryHost host = host(infused());
        TechniqueCasting casting = new TechniqueCasting(registry(infused()));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(host.current(), CAST)).getPhase());
        host.nowMillis = FINISHED;
        assertEquals(CastPhase.FINISHED, casting.advance(host).getPhase());
        assertEquals(CAST, TechniqueState.read(host.current()).getActiveInfusion().getCastId());
        return host;
    }

    private static void startSecondCast(MemoryHost host) {
        // 冷却结束后再起一式，停在起手中段，身体上同时有残留施放和前一式的附行。
        host.nowMillis = SECOND_START;
        TechniqueCasting casting = new TechniqueCasting(registry(infused()));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(host.current(), SECOND_CAST)).getPhase());
        host.nowMillis = STAGGER_AT;
        assertEquals(CastPhase.WARMUP, casting.advance(host).getPhase());
    }

    private static TechniqueService recoveryService() {
        // 恢复入口不依赖实体已被宿主目录认领。
        return new TechniqueService(registry(infused()), new TechniqueHostDirectory(List.of()));
    }

    private static BodySnapshot target(String attribute) {
        // 一条经脉内腔里某属性占三分之一、其余是无属性，过主属性门槛。
        return target(List.of(qi(attribute, PRIMARY_AMOUNT), qi(NONE, NONE_AMOUNT)), List.of());
    }

    private static BodySnapshot target(List<Qi> firstLumen, List<Qi> secondLumen) {
        // 双经脉样本只换内腔；外壁仍是夹具里的火、水真元，主属性不该看到它们。
        var message = BodyProtobuf.toMessage(body(Shape.PAIRED)).toBuilder();
        MeridianState first = message.getMeridianStates(0);
        MeridianState second = message.getMeridianStates(1);
        message.setMeridianStates(0, withLumen(first, firstLumen));
        message.setMeridianStates(1, withLumen(second, secondLumen));
        return BodyProtobuf.fromMessage(message.build());
    }

    private static MeridianState withLumen(MeridianState state, List<Qi> entries) {
        // 内腔整份替换，外壁和开脉状态不变。
        QiInventory lumen = QiInventory.newBuilder().addAllEntries(entries).build();
        return state.toBuilder().setLumenQi(lumen).build();
    }

    private static Qi qi(String attribute, double amount) {
        // 统一用气态密度，体积远小于样本内腔容量。
        return Qi.newBuilder()
                .setAttribute(attribute)
                .setAmount(amount)
                .setDensity(GAS_DENSITY)
                .build();
    }

    private static void assertDamage(double expected, long clockMillis, Optional<BodySnapshot> target) {
        // 每次按指定时钟重新结时，附伤只由主人身体与目标内腔决定。
        BodySnapshot owner = ownerAt(clockMillis, infusion());
        InfusionStrike strike = TechniqueInfusions.strike(owner, target, DOSE).orElseThrow();
        assertEquals(expected, strike.damageHp(), EPSILON);
    }

    private static SoundCue hitSound(BodySnapshot owner, Optional<BodySnapshot> target) {
        // 默认附行带命中声，调整后的声音随附伤一起给出。
        return TechniqueInfusions.strike(owner, target, DOSE).orElseThrow().sound().orElseThrow();
    }
}
