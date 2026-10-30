package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;

/** 目录、施放快照与宿主意图共用的倍率边界。 */
public final class TechniqueMovement {
    private TechniqueMovement() {
        // 不持有身体或世界状态。
    }

    public static double multiplier(TechniqueDefinition definition) {
        // optional 区分缺省 1 和非法的显式 0。
        if (!definition.hasMovementSpeedMultiplier()) {
            return 1;
        }
        return definition.getMovementSpeedMultiplier();
    }

    public static double multiplier(ActiveCast active) {
        // 存档中的倍率固定于起手，目录卸载后仍能完整清理。
        if (!active.hasMovementSpeedMultiplier()) {
            return 1;
        }
        return active.getMovementSpeedMultiplier();
    }

    public static double multiplier(MovementIntent intent) {
        // 宿主只把倍率施加到主动移动属性，冲量执行器不读取本值。
        if (!intent.hasMovementSpeedMultiplier()) {
            return 1;
        }
        return intent.getMovementSpeedMultiplier();
    }

    public static void validateMultiplier(double multiplier) {
        // NaN、无穷、零、负数和加速均不是本契约支持的限速。
        if (!Double.isFinite(multiplier) || multiplier <= 0 || multiplier > 1) {
            throw TechniqueErrors.invalid("movement_speed_multiplier", "移动倍率必须在 (0,1] 内");
        }
    }

    static void validateIntent(MovementIntent intent) {
        // 清理操作不携带新倍率，稳定身份重试必须保持同一载荷。
        MovementAction action = intent.getAction();
        if (action != MovementAction.LOCK && action != MovementAction.UNLOCK) {
            throw TechniqueErrors.invalid("movement.action", "未知移动限速操作");
        }
        if (action == MovementAction.UNLOCK && intent.hasMovementSpeedMultiplier()) {
            throw TechniqueErrors.invalid("movement", "移除限速不能携带倍率");
        }
        validateMultiplier(multiplier(intent));
    }
}
