package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BoundSlot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 施放时序使用的组织动作速度；只读取起手快照，不持有身体状态。 */
public final class TechniqueTempo {
    private static final double IDENTITY = 1.0;

    private TechniqueTempo() {
        // 时序缩放是纯计算，不保存跨施放游标。
    }

    public static double multiplier(BodySnapshot body, Iterable<BoundSlot> binding) {
        // 绑定组织中最慢的一块决定整段功法的起手、效果和收势速度。
        double slowest = Double.POSITIVE_INFINITY;
        for (BoundSlot slot : binding) {
            if (slot.getModule() != ModuleKind.TISSUE) {
                continue;
            }
            for (String tissueId : slot.getModuleIdsList()) {
                double tempo = TissueCapabilities.tempoMultiplier(body,
                        new Anatomy.TissueId(tissueId));
                slowest = Math.min(slowest, tempo);
            }
        }
        if (slowest == Double.POSITIVE_INFINITY) {
            return IDENTITY;
        }
        return slowest;
    }

    public static double multiplier(ActiveCast active) {
        // 活动存档冻结起手倍率；旧存档缺字段时保持未迟滞的默认速度。
        if (!active.hasTempoMultiplier()) {
            return IDENTITY;
        }
        return active.getTempoMultiplier();
    }

    public static long scale(long originalMillis, double tempo) {
        // 向上取整保证放慢后的事件不会早于其原始时刻，零时长仍保持零。
        if (originalMillis < 0 || !Double.isFinite(tempo) || tempo <= 0) {
            throw new IllegalArgumentException("动作速度或时长非法");
        }
        if (originalMillis == 0) {
            return 0;
        }
        BigDecimal duration = BigDecimal.valueOf(originalMillis)
                .divide(BigDecimal.valueOf(tempo), 0, RoundingMode.CEILING);
        try {
            return duration.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("放慢后的时长超出身体时钟", exception);
        }
    }

    public static BlockWork scale(BlockWork work, double tempo) {
        // block_work 的间隔和持续时间共同拉长，处理器收到的每次事件仍按原序号幂等。
        return work.toBuilder()
                .setIntervalMillis(scale(work.getIntervalMillis(), tempo))
                .setDurationMillis(scale(work.getDurationMillis(), tempo))
                .build();
    }
}
