package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** 目录声音的取值规则：校验、省略默认值、目标声音的归属与音调抖动，调度、播放与测试共用一份。 */
public final class TechniqueSoundCues {
    static final double DEFAULT_VOLUME = 1;
    static final double MAX_VOLUME = 4;
    static final double DEFAULT_PITCH = 1;
    /** 原版声音引擎把音调夹在 [0.5, 2]，抖动后的两端也必须落在这里。 */
    static final double MIN_PITCH = 0.5;
    static final double MAX_PITCH = 2;
    static final double NO_JITTER = 0;
    static final double MAX_PITCH_JITTER = 0.5;

    private TechniqueSoundCues() {
        // 纯规则工具，不持有状态，也不接触世界。
    }

    public static double volume(SoundCue cue) {
        // 省略音量按原版默认 1 播放；写了的数值已经过目录校验。
        if (!cue.hasVolume()) {
            return DEFAULT_VOLUME;
        }
        return cue.getVolume().getValue();
    }

    public static double pitch(SoundCue cue) {
        // 省略音调按原版默认 1 播放；写了的数值已经过目录校验。
        if (!cue.hasPitch()) {
            return DEFAULT_PITCH;
        }
        return cue.getPitch().getValue();
    }

    public static double pitchJitter(SoundCue cue) {
        // 省略抖动表示每次都用同一音调；写了的数值已经过目录校验。
        if (!cue.hasPitchJitter()) {
            return NO_JITTER;
        }
        return cue.getPitchJitter().getValue();
    }

    public static double playedPitch(SoundCue cue, double roll) {
        // roll 是 [0, 1) 的均匀随机数，线性落到 pitch ± pitch_jitter 区间。
        double pitch = pitch(cue);
        double jitter = pitchJitter(cue);
        double lowest = pitch - jitter;
        double highest = pitch + jitter;
        return lowest + roll * (highest - lowest);
    }

    public static boolean targetAnchored(TechniqueEffect effect) {
        // 目标锚点的声音不单独成为意图，随它所跟的持物或腿法打击下发。
        return effect.hasSound() && effect.getSound().getAnchor() == SoundAnchor.TARGET;
    }

    public static List<String> unregistered(Collection<TechniqueDefinition> definitions,
                                            Predicate<String> registered) {
        // 列出不在服务端声音注册表里的 ID；客户端资源可另行声明这些事件，所以只提示不拒绝。
        return definitions.stream()
                .flatMap(definition -> definition.getEffectsList().stream())
                .flatMap(effect -> cues(effect).stream())
                .map(SoundCue::getSoundId)
                .filter(registered.negate())
                .distinct()
                .sorted()
                .toList();
    }

    static void validate(SoundCue cue, String path) {
        // ID 只校验语法、不要求已注册；数值范围与抖动后的两端都要落在原版可播放区间。
        TechniqueValues.id(cue.getSoundId(), path + ".sound_id");
        if (cue.getAnchorValue() <= 0 || cue.getAnchor() == SoundAnchor.UNRECOGNIZED) {
            throw TechniqueErrors.invalid(path + ".anchor", "缺少或未知声音锚点");
        }
        if (cue.hasVolume()) {
            TechniqueValues.positive(cue.getVolume(), Unit.RATIO, MAX_VOLUME, path + ".volume");
        }
        if (cue.hasPitch()) {
            TechniqueValues.quantity(cue.getPitch(), Unit.RATIO, MIN_PITCH, MAX_PITCH, path + ".pitch");
        }
        if (cue.hasPitchJitter()) {
            TechniqueValues.quantity(cue.getPitchJitter(), Unit.RATIO, NO_JITTER, MAX_PITCH_JITTER,
                    path + ".pitch_jitter");
        }
        double pitch = pitch(cue);
        double jitter = pitchJitter(cue);
        if (pitch - jitter < MIN_PITCH || pitch + jitter > MAX_PITCH) {
            throw TechniqueErrors.invalid(path + ".pitch_jitter",
                    "抖动后的音调超出 [" + MIN_PITCH + ", " + MAX_PITCH + "]");
        }
    }

    static void validateAnchor(SoundCue cue, SoundAnchor expected, String path) {
        // 意图与跃步里的声音锚点固定：独立意图与起跳声对准施放者，打击里的声音对准目标。
        validate(cue, path);
        if (cue.getAnchor() != expected) {
            throw TechniqueErrors.invalid(path + ".anchor", "此处声音的锚点必须是 " + expected);
        }
    }

    static void validateTargets(TechniqueDefinition definition) {
        // 目标声音必须跟在同刻的武器或腿法打击之后，中间只能隔同刻的其他声音。
        List<TechniqueEffect> effects = definition.getEffectsList();
        for (int index = 0; index < effects.size(); index++) {
            if (targetAnchored(effects.get(index)) && !followsStrike(effects, index)) {
                throw TechniqueErrors.invalid(definition.getId(), "目标声音之前没有同一时刻的武器打击");
            }
        }
    }

    static List<SoundCue> hitSounds(TechniqueDefinition definition, int strikeIndex) {
        // 打击之后紧接的同刻声音里，目标锚点的并入该击；遇到其他效果或下一时刻即止。
        List<TechniqueEffect> effects = definition.getEffectsList();
        long atMillis = effects.get(strikeIndex).getAtMillis();
        var sounds = new ArrayList<SoundCue>();
        for (int next = strikeIndex + 1; next < effects.size(); next++) {
            TechniqueEffect effect = effects.get(next);
            if (effect.getAtMillis() != atMillis || !effect.hasSound()) {
                break;
            }
            if (targetAnchored(effect)) {
                sounds.add(effect.getSound());
            }
        }
        return List.copyOf(sounds);
    }

    private static boolean followsStrike(List<TechniqueEffect> effects, int index) {
        // 向前跳过同刻声音，先遇到同刻打击才算有归属；其他效果或更早时刻都说明无处挂靠。
        long atMillis = effects.get(index).getAtMillis();
        for (int previous = index - 1; previous >= 0; previous--) {
            TechniqueEffect effect = effects.get(previous);
            if (effect.getAtMillis() != atMillis) {
                return false;
            }
            if (effect.hasWeaponStrike() || effect.hasLegStrike()) {
                return true;
            }
            if (!effect.hasSound()) {
                return false;
            }
        }
        return false;
    }

    private static List<SoundCue> cues(TechniqueEffect effect) {
        // 目录里的声音只在两处声明：声音效果本身，以及跃步状态的起跳声。
        if (effect.hasSound()) {
            return List.of(effect.getSound());
        }
        var leap = effect.getStatusBenefit().getLeap();
        if (leap.hasTakeoffSound()) {
            return List.of(leap.getTakeoffSound());
        }
        return List.of();
    }
}
