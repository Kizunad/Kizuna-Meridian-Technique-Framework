package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ElementalInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InfusionCounter;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 附行的取值规则：目录与存档校验、身体状态的生成、按主人身体算出一记附伤。伤害附属、调度与测试共用一份。 */
public final class TechniqueInfusions {
    /** 某属性真元占内腔总量（含无属性）不低于这个比例，才算目标的主属性。 */
    public static final double PRIMARY_SHARE_THRESHOLD = 0.25;
    static final int MAX_TICKS = 1_200;
    static final double MAX_RATIO = 10;
    static final double MAX_PITCH_OFFSET = 1.5;
    static final int MAX_COUNTERS = 16;
    private static final int MIN_CURVE_POINTS = 2;
    private static final int MAX_CURVE_POINTS = 16;
    private static final String NO_ATTRIBUTE = Qi.QiAttribute.NONE.id().value();

    /** 一记附伤：伤害附属照此打出本行伤害、做原版附带并播命中声。damageHp 是原版 HP。 */
    public record InfusionStrike(String damageTypeId, double damageHp, double multiplier, int igniteTicks,
                                 int freezeTicks, double extraKnockback, Optional<SoundCue> sound) {
    }

    private TechniqueInfusions() {
        // 纯规则工具，不持有状态，也不接触宿主。
    }

    public static Optional<InfusionStrike> strike(BodySnapshot owner, Optional<BodySnapshot> target, double doseHp) {
        // 主人身体死了、没有附行或已到期都不附伤；剂量取主击过肌肉、未过护甲的原版伤害。
        if (owner.vitality().dead() || !(doseHp > 0)) {
            return Optional.empty();
        }
        TechniqueProgress progress = TechniqueState.read(owner);
        if (!progress.hasActiveInfusion()) {
            return Optional.empty();
        }
        ActiveInfusion active = progress.getActiveInfusion();
        ElementalInfusion infusion = active.getInfusion();
        double curve = curveValue(active, owner.settledAt().elapsedMillis());
        if (curve <= 0) {
            return Optional.empty();
        }
        Optional<InfusionCounter> counter = target.flatMap(TechniqueInfusions::primaryAttribute)
                .flatMap(attribute -> counter(infusion, attribute));
        double multiplier = counter.map(found -> found.getDamageMultiplier().getValue()).orElse(1.0);
        double damage = doseHp * infusion.getRatio().getValue() * curve * multiplier;
        Optional<SoundCue> sound = Optional.empty();
        if (infusion.hasHitSound()) {
            sound = Optional.of(adjustedSound(infusion.getHitSound(), counter));
        }
        return Optional.of(new InfusionStrike(infusion.getDamageTypeId(), damage, multiplier,
                infusion.getIgniteTicks(), infusion.getFreezeTicks(), extraKnockback(infusion), sound));
    }

    public static long remainingMillis(BodySnapshot body) {
        // 剩余时长按身体时钟算到曲线末点；死体和没有附行都按 0。
        if (body.vitality().dead()) {
            return 0;
        }
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveInfusion()) {
            return 0;
        }
        ActiveInfusion active = progress.getActiveInfusion();
        long endMillis = active.getStartedMillis() + curveEndMillis(active.getInfusion());
        return Math.max(0, endMillis - body.settledAt().elapsedMillis());
    }

    public static Optional<String> primaryAttribute(BodySnapshot body) {
        // 经脉内腔按属性加总，不算无属性；最多的那一行并列或占比不到门槛时都没有主属性。
        Map<String, Double> totals = lumenTotals(body);
        double all = totals.values().stream().mapToDouble(Double::doubleValue).sum();
        String primary = null;
        double most = 0;
        int leaders = 0;
        for (var entry : totals.entrySet()) {
            if (entry.getKey().equals(NO_ATTRIBUTE)) {
                continue;
            }
            if (entry.getValue() > most) {
                primary = entry.getKey();
                most = entry.getValue();
                leaders = 1;
            } else if (entry.getValue() == most) {
                leaders++;
            }
        }
        if (primary == null || leaders > 1 || most < all * PRIMARY_SHARE_THRESHOLD) {
            return Optional.empty();
        }
        return Optional.of(primary);
    }

    static ActiveInfusion begin(String castId, String techniqueId, long startedMillis, ElementalInfusion infusion) {
        // 定义整份快照进身体，目录之后改动或卸载都不影响已生效的附行。
        return ActiveInfusion.newBuilder()
                .setCastId(castId)
                .setTechniqueId(techniqueId)
                .setStartedMillis(startedMillis)
                .setInfusion(infusion)
                .build();
    }

    static void validateCount(TechniqueDefinition definition) {
        // 一身只有一个附行，同一式里写两个附行只会让后一个立刻覆盖前一个。
        long infusions = definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasElementalInfusion)
                .count();
        if (infusions > 1) {
            throw TechniqueErrors.invalid(definition.getId(), "一式最多一个附行效果");
        }
    }

    static void validate(ElementalInfusion infusion) {
        // 目录与存档共用：属性、伤害类型、曲线、附带与相克都要可解释。
        TechniqueValues.id(infusion.getAttribute(), "elemental_infusion.attribute");
        TechniqueValues.id(infusion.getDamageTypeId(), "elemental_infusion.damage_type_id");
        TechniqueValues.positive(infusion.getRatio(), Unit.RATIO, MAX_RATIO, "elemental_infusion.ratio");
        validateCurve(infusion.getCurveList());
        validateTicks(infusion.getIgniteTicks(), "elemental_infusion.ignite_ticks");
        validateTicks(infusion.getFreezeTicks(), "elemental_infusion.freeze_ticks");
        if (infusion.hasExtraKnockback()) {
            TechniqueValues.quantity(infusion.getExtraKnockback(), Unit.RATIO, 0, MAX_RATIO,
                    "elemental_infusion.extra_knockback");
        }
        validateCounters(infusion.getCountersList());
        if (infusion.hasHitSound()) {
            TechniqueSoundCues.validateAnchor(infusion.getHitSound(), SoundAnchor.TARGET,
                    "elemental_infusion.hit_sound");
        }
    }

    static void validateActive(ActiveInfusion active) {
        // 存档里的附行要能找回施放身份和起点，定义快照照目录规则复核。
        TechniqueValues.castId(active.getCastId());
        TechniqueValues.id(active.getTechniqueId(), "active_infusion.technique_id");
        if (active.getStartedMillis() < 0) {
            throw TechniqueErrors.invalid("active_infusion.started_millis", "附行起点不能为负");
        }
        validate(active.getInfusion());
    }

    private static double curveValue(ActiveInfusion active, long nowMillis) {
        // 起点以前和末点以后都按 0；中间在相邻拐点间线性插值。
        long elapsed = nowMillis - active.getStartedMillis();
        List<CurvePoint> curve = active.getInfusion().getCurveList();
        if (elapsed < 0 || elapsed >= curve.getLast().getOffsetMillis()) {
            return 0;
        }
        return TechniqueStatusCurves.valueAt(curve, elapsed);
    }

    private static long curveEndMillis(ElementalInfusion infusion) {
        // 曲线末点就是附行到期，校验保证至少两个点。
        return infusion.getCurve(infusion.getCurveCount() - 1).getOffsetMillis();
    }

    private static Optional<InfusionCounter> counter(ElementalInfusion infusion, String targetAttribute) {
        // 相克表由内容附属写在目录里，未列出的目标属性按 ×1。
        return infusion.getCountersList().stream()
                .filter(counter -> counter.getTargetAttribute().equals(targetAttribute))
                .findFirst();
    }

    private static double extraKnockback(ElementalInfusion infusion) {
        // 省略的追加击退按 0，不把 optional 的默认值当成已填写。
        if (!infusion.hasExtraKnockback()) {
            return 0;
        }
        return infusion.getExtraKnockback().getValue();
    }

    private static SoundCue adjustedSound(SoundCue cue, Optional<InfusionCounter> counter) {
        // 相克只调音量和音调；结果仍落在原版可播放区间，抖动后的两端也不越界。
        if (counter.isEmpty()) {
            return cue;
        }
        InfusionCounter found = counter.get();
        double volume = TechniqueSoundCues.volume(cue) * ratioOr(found.getSoundVolumeMultiplier(), 1);
        double pitch = TechniqueSoundCues.pitch(cue) + ratioOr(found.getSoundPitchOffset(), 0);
        double jitter = TechniqueSoundCues.pitchJitter(cue);
        double lowest = TechniqueSoundCues.MIN_PITCH + jitter;
        double highest = TechniqueSoundCues.MAX_PITCH - jitter;
        return cue.toBuilder()
                .setVolume(ratio(Math.min(volume, TechniqueSoundCues.MAX_VOLUME)))
                .setPitch(ratio(Math.clamp(pitch, lowest, highest)))
                .build();
    }

    private static double ratioOr(Quantity quantity, double fallback) {
        // 相克里省略的声音调整按不变处理。
        if (!quantity.hasValue()) {
            return fallback;
        }
        return quantity.getValue();
    }

    private static Quantity ratio(double value) {
        // 调整后的声音数值沿用目录的无量纲单位。
        return Quantity.newBuilder().setValue(value).setUnit(Unit.RATIO).build();
    }

    private static Map<String, Double> lumenTotals(BodySnapshot body) {
        // 只看经脉内腔，不算管壁、器官与在途真元。
        var totals = new HashMap<String, Double>();
        for (MeridianState state : body.meridianStates()) {
            for (Qi qi : state.lumenQi().entries()) {
                totals.merge(qi.attribute().id().value(), qi.amount(), Double::sum);
            }
        }
        return totals;
    }

    private static void validateCurve(List<CurvePoint> curve) {
        // 2–16 点，首点偏移 0，首尾值 0，偏移严格递增，值在 [0, 1]，末点不超过曲线上限。
        if (curve.size() < MIN_CURVE_POINTS || curve.size() > MAX_CURVE_POINTS
                || curve.getFirst().getOffsetMillis() != 0 || curve.getFirst().getValue() != 0
                || curve.getLast().getValue() != 0
                || curve.getLast().getOffsetMillis() > TechniqueValues.MAX_CURVE_MILLIS) {
            throw TechniqueErrors.invalid("elemental_infusion.curve",
                    "曲线必须 2–16 点，首点偏移 0、首尾值 0，末点不超过 " + TechniqueValues.MAX_CURVE_MILLIS + " ms");
        }
        long previous = -1;
        for (CurvePoint point : curve) {
            double value = point.getValue();
            if (point.getOffsetMillis() <= previous || !Double.isFinite(value) || value < 0 || value > 1) {
                throw TechniqueErrors.invalid("elemental_infusion.curve", "偏移必须严格递增且曲线值位于 [0, 1]");
            }
            previous = point.getOffsetMillis();
        }
    }

    private static void validateTicks(int ticks, String path) {
        // 原版附带的刻数有界，0 表示不做这项附带。
        if (ticks < 0 || ticks > MAX_TICKS) {
            throw TechniqueErrors.invalid(path, "刻数必须位于 [0, " + MAX_TICKS + "]");
        }
    }

    private static void validateCounters(List<InfusionCounter> counters) {
        // 最多 16 条，免得附行写进身体进度时超限；同一目标属性只能有一条倍率，声音调整省略时不改。
        if (counters.size() > MAX_COUNTERS) {
            throw TechniqueErrors.invalid("elemental_infusion.counters", "相克最多 " + MAX_COUNTERS + " 条");
        }
        var attributes = new HashSet<String>();
        for (InfusionCounter counter : counters) {
            TechniqueValues.id(counter.getTargetAttribute(), "elemental_infusion.counters.target_attribute");
            if (!attributes.add(counter.getTargetAttribute())) {
                throw TechniqueErrors.invalid("elemental_infusion.counters", "相克的目标属性重复");
            }
            TechniqueValues.positive(counter.getDamageMultiplier(), Unit.RATIO, MAX_RATIO,
                    "elemental_infusion.counters.damage_multiplier");
            if (counter.hasSoundVolumeMultiplier()) {
                TechniqueValues.positive(counter.getSoundVolumeMultiplier(), Unit.RATIO,
                        TechniqueSoundCues.MAX_VOLUME, "elemental_infusion.counters.sound_volume_multiplier");
            }
            if (counter.hasSoundPitchOffset()) {
                TechniqueValues.quantity(counter.getSoundPitchOffset(), Unit.RATIO, -MAX_PITCH_OFFSET,
                        MAX_PITCH_OFFSET, "elemental_infusion.counters.sound_pitch_offset");
            }
        }
    }
}
