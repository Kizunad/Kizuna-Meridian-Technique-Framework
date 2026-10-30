package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 施放预报：按目录时刻算出当前施放的阶段、起手剩余和尚未命中的打击，供宿主 AI 预判；只读，不推进状态。 */
public final class TechniqueForecasts {
    private TechniqueForecasts() {
        // 纯函数集合，不持有目录或身体。
    }

    /** 一次尚未命中的打击：距命中毫秒与目录打击形状。 */
    public record StrikeWindow(long millisUntilHit, WeaponStrike strike) { }

    /** 当前施放的预报；施放记录带式、目标、选定手与持物规则，只有起手期且目录允许时可被僵直打断。 */
    public record CastForecast(ActiveCast active, CastPhase phase, long millisUntilWarmupEnd, boolean interruptible,
                               List<StrikeWindow> strikes) {
        public CastForecast {
            // 打击列表只读。
            strikes = List.copyOf(strikes);
        }
    }

    /** 普通起手后的第一次打击：相对起手的毫秒与打击形状。 */
    public record FirstStrike(long millis, WeaponStrike strike) { }

    public static Optional<CastForecast> forecast(TechniqueRegistry registry, TechniqueProgress progress,
                                                  long nowMillis) {
        // 没有施放、已过收势或该版本已不在目录中时没有预报。
        Objects.requireNonNull(registry);
        if (!progress.hasActiveCast()) {
            return Optional.empty();
        }
        ActiveCast active = progress.getActiveCast();
        CastPhase phase = TechniqueScheduler.phase(active, nowMillis);
        Optional<TechniqueDefinition> definition = registry.find(active.getRequest().getTechnique());
        if (phase == CastPhase.FINISHED || definition.isEmpty()) {
            return Optional.empty();
        }
        long warmupLeft = Math.max(0, active.getWarmupEndMillis() - nowMillis);
        boolean interruptible = active.getInterruptibleByDamage() && phase == CastPhase.WARMUP;
        var strikes = strikes(definition.get(), active, nowMillis);
        return Optional.of(new CastForecast(active, phase, warmupLeft, interruptible, strikes));
    }

    public static Optional<FirstStrike> firstStrike(TechniqueDefinition definition) {
        // 不含打击的式没有首击；承接剪辑只会让首击更早，按普通起手估是保守值。
        return definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike)
                .min(Comparator.comparingLong(TechniqueEffect::getAtMillis))
                .map(effect -> new FirstStrike(effect.getAtMillis(), effect.getWeaponStrike()));
    }

    private static List<StrikeWindow> strikes(TechniqueDefinition definition, ActiveCast active, long nowMillis) {
        // 与调度器同一投影：剪辑起点前的打击不发，命中时刻为起手时刻加偏移减剪辑起点，只保留未来的。
        long clipStart = active.getClipStartMillis();
        var windows = new ArrayList<StrikeWindow>();
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (!effect.hasWeaponStrike() || effect.getAtMillis() < clipStart) {
                continue;
            }
            long hitMillis = active.getStartedMillis() + effect.getAtMillis() - clipStart;
            if (hitMillis > nowMillis) {
                windows.add(new StrikeWindow(hitMillis - nowMillis, effect.getWeaponStrike()));
            }
        }
        return windows;
    }
}
