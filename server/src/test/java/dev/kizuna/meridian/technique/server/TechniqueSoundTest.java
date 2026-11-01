package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Presentation;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.chain;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.combatHost;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.groupedCatalog;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueSoundTest {
    private static final String SWING = "minecraft:entity.player.attack.sweep";
    private static final String HIT = "minecraft:entity.player.attack.strong";
    private static final String HUM = "sample:technique.hum";
    private static final String TAKEOFF = "sample:technique.takeoff";
    private static final double SWING_VOLUME = 0.8;
    private static final double SWING_PITCH = 0.7;
    private static final double JITTER = 0.05;
    /** 越界样例比边界多出的量。 */
    private static final double OVERSHOOT = 0.01;
    private static final double EPSILON = 0.000001;
    /** 均匀随机数的中点，对应目录音调本身。 */
    private static final double MIDPOINT = 0.5;
    private static final long BEFORE = 1;

    @Test
    public void omittedFieldsUseDefaultsAndJitteredPitchStaysPlayable() {
        // 省略音量音调按 1 播放；越界、错误单位、缺锚点、非法 ID 与抖动越出原版音调区间都在目录边界拒绝。
        SoundCue bare = cue(SWING, SoundAnchor.CASTER);
        assertEquals(TechniqueSoundCues.DEFAULT_VOLUME, TechniqueSoundCues.volume(bare), EPSILON);
        assertEquals(TechniqueSoundCues.DEFAULT_PITCH, TechniqueSoundCues.pitch(bare), EPSILON);
        assertEquals(TechniqueSoundCues.NO_JITTER, TechniqueSoundCues.pitchJitter(bare), EPSILON);
        load(withCaster(bare));
        load(withCaster(swing()));
        List<UnaryOperator<SoundCue.Builder>> invalid = List.of(
                cue -> cue.setVolume(ratio(0)),
                cue -> cue.setVolume(ratio(TechniqueSoundCues.MAX_VOLUME + OVERSHOOT)),
                cue -> cue.setVolume(quantity(SWING_VOLUME, Unit.METER)),
                cue -> cue.setPitch(ratio(TechniqueSoundCues.MIN_PITCH - OVERSHOOT)),
                cue -> cue.setPitch(ratio(TechniqueSoundCues.MAX_PITCH + OVERSHOOT)),
                cue -> cue.setPitchJitter(ratio(TechniqueSoundCues.MAX_PITCH_JITTER + OVERSHOOT)),
                cue -> cue.setPitch(ratio(TechniqueSoundCues.MAX_PITCH - JITTER))
                        .setPitchJitter(ratio(JITTER + OVERSHOOT)),
                cue -> cue.clearAnchor(),
                cue -> cue.setSoundId("Sample:Swing"),
                cue -> cue.setSoundId("swing"));
        for (var change : invalid) {
            SoundCue broken = change.apply(swing().toBuilder()).build();
            assertThrows(IllegalArgumentException.class, () -> load(withCaster(broken)));
        }
    }

    @Test
    public void playedPitchSpansTheJitterWindowLinearly() {
        // 随机数 0 落在下沿，接近 1 时逼近上沿，中点即目录音调。
        SoundCue cue = swing();
        assertEquals(SWING_PITCH - JITTER, TechniqueSoundCues.playedPitch(cue, 0), EPSILON);
        assertEquals(SWING_PITCH, TechniqueSoundCues.playedPitch(cue, MIDPOINT), EPSILON);
        assertTrue(TechniqueSoundCues.playedPitch(cue, Math.nextDown(1.0)) <= SWING_PITCH + JITTER);
    }

    @Test
    public void targetSoundsFoldIntoTheSameTimeStrikeAndCasterSoundsSkipTheHost() {
        // 同刻顺序为打击、施放者声音、目标声音：目标声音随打击下发，施放者声音只给监听者，宿主收不到声音意图。
        TechniqueDefinition chain = chain();
        int strike = strikeIndex(chain);
        long atMillis = chain.getEffects(strike).getAtMillis();
        SoundCue hit = cue(HIT, SoundAnchor.TARGET);
        TechniqueDefinition definition = inserted(inserted(chain, strike + 1, sound(atMillis, swing())),
                strike + 2, sound(atMillis, hit));
        MemoryHost host = combatHost();
        TechniqueService service = service(host, load(definition));
        var heard = new ArrayList<TechniqueService.SoundDue>();
        service.addSoundListener(heard::add);
        service.cast(ENTITY, request(host.current(), "fold"));
        host.nowMillis = atMillis;
        service.tick();
        EffectIntent delivered = host.effects.stream()
                .filter(EffectIntent::hasWeaponStrike)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(hit), delivered.getWeaponStrike().getHitSoundsList());
        assertEquals(List.of(new TechniqueService.SoundDue(ENTITY, swing())), heard);
        assertTrue(host.effects.stream().noneMatch(EffectIntent::hasSound));
    }

    @Test
    public void targetSoundWithoutAPrecedingSameTimeStrikeIsRejected() {
        // 放在打击之前、被其他同刻效果隔开、或该时刻根本没有打击，都无处挂靠。
        TechniqueDefinition chain = chain();
        int strike = strikeIndex(chain);
        long atMillis = chain.getEffects(strike).getAtMillis();
        TechniqueEffect hit = sound(atMillis, cue(HIT, SoundAnchor.TARGET));
        TechniqueEffect push = TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setImpulse(forward())
                .build();
        List<TechniqueDefinition> invalid = List.of(
                inserted(chain, strike, hit),
                inserted(inserted(chain, strike + 1, push), strike + 2, hit),
                inserted(chain, 0, sound(0, cue(HIT, SoundAnchor.TARGET))));
        for (TechniqueDefinition definition : invalid) {
            assertThrows(IllegalArgumentException.class, () -> load(definition));
        }
    }

    @Test
    public void casterSoundIsHeardOnceWhenTheSameBatchIsRetried() {
        // 同刻打击被宿主暂拒后整批重试；已交给监听者的声音按效果身份去重，不再重放。
        TechniqueDefinition chain = chain();
        int strike = strikeIndex(chain);
        long atMillis = chain.getEffects(strike).getAtMillis();
        MemoryHost host = combatHost();
        TechniqueService service = service(host, load(inserted(chain, strike, sound(atMillis, swing()))));
        var heard = new ArrayList<TechniqueService.SoundDue>();
        service.addSoundListener(heard::add);
        service.cast(ENTITY, request(host.current(), "retry"));
        host.nowMillis = atMillis - BEFORE;
        service.tick();
        host.nowMillis = atMillis;
        host.rejectNextEffect();
        service.tick();
        assertEquals(1, heard.size());
        assertTrue(host.effects.stream().noneMatch(EffectIntent::hasWeaponStrike));
        service.tick();
        assertEquals(1, heard.size());
        assertEquals(1, host.effects.stream().filter(EffectIntent::hasWeaponStrike).count());
    }

    @Test
    public void soundsAreCountedApartFromTheOtherEffects() {
        // 非声音效果占满 32 项时仍能再带 64 条声音；任一类多一条都拒绝，报错写清两个上限。
        TechniqueDefinition chain = chain();
        int fillers = TechniqueValues.MAX_EFFECTS - chain.getEffectsCount();
        load(padded(chain, fillers, TechniqueValues.MAX_SOUNDS));
        var tooManySounds = assertThrows(IllegalArgumentException.class,
                () -> load(padded(chain, fillers, TechniqueValues.MAX_SOUNDS + 1)));
        var tooManyOthers = assertThrows(IllegalArgumentException.class,
                () -> load(padded(chain, fillers + 1, 0)));
        for (IllegalArgumentException rejected : List.of(tooManySounds, tooManyOthers)) {
            assertTrue(rejected.getMessage(), rejected.getMessage().contains("最多 " + TechniqueValues.MAX_EFFECTS)
                    && rejected.getMessage().contains("最多 " + TechniqueValues.MAX_SOUNDS));
        }
    }

    @Test
    public void deprecatedPresentationSoundIsRejected() {
        // 声音只有 SoundCue 一套声明，旧的表现字段非空即拒绝。
        Presentation presentation = Presentation.newBuilder()
                .setSoundId(SWING)
                .build();
        TechniqueDefinition definition = chain().toBuilder().setPresentation(presentation).build();
        assertThrows(IllegalArgumentException.class, () -> load(definition));
    }

    @Test
    public void unregisteredIdsAreListedOnceFromEffectsAndTakeoffSounds() {
        // 启动提示收集声音效果与跃步起跳声里不在注册表的 ID，去重后按字典序给出。
        LeapProfile leap = LeapProfile.newBuilder()
                .setTakeoffSound(cue(TAKEOFF, SoundAnchor.CASTER))
                .build();
        StatusBenefit benefit = StatusBenefit.newBuilder()
                .setLeap(leap)
                .build();
        TechniqueEffect takeoff = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setStatusBenefit(benefit)
                .build();
        TechniqueDefinition definition = TechniqueDefinition.newBuilder()
                .addEffects(sound(0, cue(HUM, SoundAnchor.CASTER)))
                .addEffects(sound(0, cue(HUM, SoundAnchor.CASTER)))
                .addEffects(sound(0, swing()))
                .addEffects(takeoff)
                .build();
        List<String> missing = TechniqueSoundCues.unregistered(List.of(definition),
                id -> id.startsWith("minecraft:"));
        assertEquals(List.of(HUM, TAKEOFF), missing);
    }

    private static SoundCue cue(String soundId, SoundAnchor anchor) {
        // 只填必填字段，音量音调走默认值。
        return SoundCue.newBuilder()
                .setSoundId(soundId)
                .setAnchor(anchor)
                .build();
    }

    private static SoundCue swing() {
        // 与破门斩横扫相同量级的完整声音声明。
        return cue(SWING, SoundAnchor.CASTER).toBuilder()
                .setVolume(ratio(SWING_VOLUME))
                .setPitch(ratio(SWING_PITCH))
                .setPitchJitter(ratio(JITTER))
                .build();
    }

    private static TechniqueEffect sound(long atMillis, SoundCue cue) {
        // 声音是普通效果，按声明时刻排在其他效果之间。
        return TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setSound(cue)
                .build();
    }

    private static TechniqueDefinition withCaster(SoundCue cue) {
        // 起手零时刻的施放者声音，不依赖任何打击。
        return inserted(chain(), 0, sound(0, cue));
    }

    private static TechniqueDefinition inserted(TechniqueDefinition definition, int position, TechniqueEffect effect) {
        // 按声明顺序插入；同刻效果的先后决定目标声音挂靠哪一击。
        var effects = new ArrayList<>(definition.getEffectsList());
        effects.add(position, effect);
        return definition.toBuilder()
                .clearEffects()
                .addAllEffects(effects)
                .build();
    }

    private static TechniqueDefinition padded(TechniqueDefinition definition, int impulses, int sounds) {
        // 零时刻补冲量与施放者声音凑数，放在原有效果之前，声明时刻仍然不减。
        var effects = new ArrayList<TechniqueEffect>();
        for (int index = 0; index < impulses; index++) {
            effects.add(TechniqueEffect.newBuilder()
                    .setAtMillis(0)
                    .setImpulse(forward())
                    .build());
        }
        for (int index = 0; index < sounds; index++) {
            effects.add(sound(0, swing()));
        }
        effects.addAll(definition.getEffectsList());
        return definition.toBuilder()
                .clearEffects()
                .addAllEffects(effects)
                .build();
    }

    private static int strikeIndex(TechniqueDefinition definition) {
        // 连斩样例的第一击。
        List<TechniqueEffect> effects = definition.getEffectsList();
        for (int index = 0; index < effects.size(); index++) {
            if (effects.get(index).hasWeaponStrike()) {
                return index;
            }
        }
        throw new AssertionError("样例没有打击");
    }

    private static Impulse forward() {
        // 只为隔开打击与声音的合法冲量。
        return Impulse.newBuilder()
                .setDirection(ImpulseDirection.FORWARD)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
    }

    private static Quantity ratio(double value) {
        // 声音的数值字段都是比例。
        return quantity(value, Unit.RATIO);
    }

    private static TechniqueRegistry load(TechniqueDefinition definition) {
        // 经真实目录边界加载，持物规则沿用连斩样例的书级声明。
        return TechniqueRegistry.load(addons(), List.of(groupedCatalog(definition)));
    }
}
