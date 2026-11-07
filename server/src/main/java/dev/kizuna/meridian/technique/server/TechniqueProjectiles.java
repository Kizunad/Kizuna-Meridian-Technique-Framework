package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ConvergeProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DrawStrengthArm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LaunchProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LobProjectile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_DISTANCE;
import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_MILLIS;
import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_PEAK;
import static dev.kizuna.meridian.technique.server.TechniqueValues.quantity;
import static dev.kizuna.meridian.technique.server.TechniqueValues.positive;

/** 射击算子的声明边界；不读取弹药、实体或世界。 */
final class TechniqueProjectiles {
    private static final double HALF_CIRCLE = 180;
    private static final double RIGHT_ANGLE = 90;
    private static final int MAX_PIERCING = Byte.MAX_VALUE;

    private TechniqueProjectiles() {
        // 射击参数只在目录和宿主效果边界校验，不保留施放状态。
    }

    static void validateEffect(EffectIntent intent) {
        // 绝对身体时钟与剪辑时钟一起下发，承接和迟到 tick 都不改变汇聚参考点。
        var launch = intent.getLaunchProjectile();
        LaunchProjectile projectile = launch.getProjectile();
        validate(projectile);
        long started = launch.getCastStartedMillis();
        long clipStart = launch.getClipStartMillis();
        TechniqueValues.millis(clipStart, "launch_projectile.clip_start_millis");
        if (started < 0 || started > intent.getScheduledMillis()) {
            throw TechniqueErrors.invalid("launch_projectile.cast_started_millis", "起手不能晚于射出");
        }
        if (projectile.hasConverge()) {
            long referenceOffset = projectile.getConverge().getReferenceShotMillis() - clipStart;
            long shotOffset = intent.getScheduledMillis() - started;
            if (!intent.getLockTarget() || referenceOffset <= shotOffset) {
                throw TechniqueErrors.invalid("launch_projectile.converge", "汇聚必须锁定且先于参考平射");
            }
            if (started > Long.MAX_VALUE - referenceOffset) {
                throw TechniqueErrors.invalid("converge.reference_shot_millis", "参考身体时刻溢出");
            }
        }
    }

    static void validate(LaunchProjectile projectile) {
        // 速度、伤害、方向和拉弦手臂必须完整声明，宿主才能保持同一套解释。
        ProjectileWeaponCategory weapon = projectile.getProjectileWeapon();
        if (weapon == ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED
                || weapon == ProjectileWeaponCategory.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("launch_projectile.projectile_weapon", "武器类别必须是弓或弩");
        }
        positive(projectile.getSpeedMultiplier(), Unit.RATIO, MAX_PEAK,
                "launch_projectile.speed_multiplier");
        positive(projectile.getDamageMultiplier(), Unit.RATIO, MAX_PEAK,
                "launch_projectile.damage_multiplier");
        quantity(projectile.getSpread(), Unit.DEGREE, 0, HALF_CIRCLE, "launch_projectile.spread");
        validateFlags(projectile);
        validateDirection(projectile);
        if (projectile.hasTargetSnapAngle()) {
            quantity(projectile.getTargetSnapAngle(), Unit.DEGREE, 0, HALF_CIRCLE,
                    "launch_projectile.target_snap_angle");
        }
        validateDrawArm(projectile);
    }

    private static void validateFlags(LaunchProjectile projectile) {
        // 可改变伤害、穿透、拉弦和盾冷却的开关不能依赖 proto 默认值。
        if (!projectile.hasCritical() || !projectile.hasPiercing()
                || !projectile.hasMultiplyDrawStrength() || !projectile.hasShieldCooldownMillis()
                || !projectile.hasMirrorByHand()) {
            throw TechniqueErrors.invalid(
                    "launch_projectile", "暴击、穿透、拉弦、盾冷却和镜像规则必须显式提供");
        }
        if (projectile.getPiercing() < 0 || projectile.getPiercing() > MAX_PIERCING) {
            throw TechniqueErrors.invalid("launch_projectile.piercing", "穿透等级超出范围");
        }
        TechniqueValues.millis(projectile.getShieldCooldownMillis(),
                "launch_projectile.shield_cooldown_millis");
    }

    private static void validateDirection(LaunchProjectile projectile) {
        // 四种方向互斥；抛射与汇聚的范围由目录显式给出。
        switch (projectile.getDirectionCase()) {
            case LOOK -> {
                if (!projectile.getLook()) {
                    throw TechniqueErrors.invalid("launch_projectile.look", "方向开关必须为 true");
                }
            }
            case LOOK_OFFSET -> quantity(projectile.getLookOffset(), Unit.DEGREE,
                    -HALF_CIRCLE, HALF_CIRCLE, "launch_projectile.look_offset");
            case LOB -> validateLob(projectile.getLob());
            case CONVERGE -> validateConverge(projectile.getConverge());
            default -> throw TechniqueErrors.invalid("launch_projectile.direction", "必须声明射击方向");
        }
        if (projectile.getMirrorByHand() && projectile.getDirectionCase()
                != LaunchProjectile.DirectionCase.LOOK_OFFSET) {
            throw TechniqueErrors.invalid(
                    "launch_projectile.mirror_by_hand", "只有横风方向可以按持弩手镜像");
        }
    }

    private static void validateDrawArm(LaunchProjectile projectile) {
        // 手臂相对起手选定的武器手解释，不依赖宿主缺失的目录槽位索引。
        DrawStrengthArm arm = projectile.getDrawStrengthArm();
        if (arm == DrawStrengthArm.UNRECOGNIZED
                || (projectile.getMultiplyDrawStrength() && arm == DrawStrengthArm.DRAW_STRENGTH_ARM_UNSPECIFIED)
                || (!projectile.getMultiplyDrawStrength() && arm != DrawStrengthArm.DRAW_STRENGTH_ARM_UNSPECIFIED)) {
            throw TechniqueErrors.invalid("launch_projectile.draw_strength_arm", "拉弦开关与出力手臂不一致");
        }
    }

    private static void validateLob(LobProjectile lob) {
        // 落点随机半径不得超出搜索距离，仰角必须指向上半球。
        double distance = positive(lob.getMaximumDistance(), Unit.METER, MAX_DISTANCE, "lob.maximum_distance");
        quantity(lob.getRadius(), Unit.METER, 0, distance, "lob.radius");
        positive(lob.getElevation(), Unit.DEGREE, RIGHT_ANGLE, "lob.elevation");
        positive(lob.getMaximumSpeedMultiplier(), Unit.RATIO, MAX_PEAK, "lob.maximum_speed_multiplier");
    }

    private static void validateConverge(ConvergeProjectile converge) {
        // 到达时间由宿主按参考平射飞行时间推算，不能用固定目录毫秒冒充。
        long reference = converge.getReferenceShotMillis();
        if (reference <= 0 || reference > MAX_MILLIS || !converge.hasArrivalDelayMillis()) {
            throw TechniqueErrors.invalid("converge", "参考射击时刻与延迟必须显式合法");
        }
        TechniqueValues.millis(converge.getArrivalDelayMillis(), "converge.arrival_delay_millis");
        positive(converge.getReferenceSpeedMultiplier(), Unit.RATIO, MAX_PEAK, "converge.reference_speed_multiplier");
        positive(converge.getMinimumElevation(), Unit.DEGREE, RIGHT_ANGLE, "converge.minimum_elevation");
        validateLob(converge.getFallback());
    }

    static void validateConvergence(TechniqueDefinition definition) {
        // 汇聚必须锁定目标并指向同式之后真实存在的平射，承接不会改变原剪辑引用。
        for (TechniqueEffect effect : definition.getEffectsList()) {
            LaunchProjectile projectile = effect.getLaunchProjectile();
            if (!projectile.hasConverge()) {
                continue;
            }
            ConvergeProjectile converge = projectile.getConverge();
            if (!definition.getLockTarget() || converge.getReferenceShotMillis() <= effect.getAtMillis()
                    || !hasReferenceShot(definition, projectile)) {
                throw TechniqueErrors.invalid("converge.reference_shot_millis", "汇聚须锁定并引用后续匹配的平射");
            }
        }
    }

    private static boolean hasReferenceShot(TechniqueDefinition definition, LaunchProjectile projectile) {
        // 武器、初速和出力手臂一致，宿主才能以该平射估算同时到达。
        ConvergeProjectile converge = projectile.getConverge();
        return definition.getEffectsList().stream().anyMatch(effect -> {
            LaunchProjectile reference = effect.getLaunchProjectile();
            return effect.getAtMillis() == converge.getReferenceShotMillis() && reference.getLook()
                    && reference.getProjectileWeapon() == projectile.getProjectileWeapon()
                    && reference.getSpeedMultiplier().equals(converge.getReferenceSpeedMultiplier())
                    && reference.getMultiplyDrawStrength() == projectile.getMultiplyDrawStrength()
                    && reference.getDrawStrengthArm() == projectile.getDrawStrengthArm();
        });
    }
}
