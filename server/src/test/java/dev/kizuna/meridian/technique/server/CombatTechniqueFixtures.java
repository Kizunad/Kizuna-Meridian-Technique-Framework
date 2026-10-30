package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiHints;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocks;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CombatAiHints;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.modifier;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.AXES;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.IRON_AXES;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.SECOND;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.durableItem;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.groupedCatalog;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.hands;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.presentationEffect;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.rules;

/** 持物打击和承接样例只进入测试源码集。 */
final class CombatTechniqueFixtures {
    static final double MOVEMENT_MULTIPLIER = 0.4;
    static final long CLIP_OFFSET = 50;
    static final long PREPARATION_EFFECT = 25;
    static final long FOLLOW_MODIFIER = 75;
    static final long SECOND_STRIKE = 200;
    static final long CAST_END = WARMUP + ACTIVE + RECOVERY;
    static final long WINDOW_START = WARMUP + ACTIVE + RECOVERY / 2;
    static final long AFTER_WINDOW = 500;
    static final double RECOVERY_START_RATIO = 0.5;
    static final int TARGET_LIMIT = 5;
    static final int DOOR_LIMIT = 4;
    static final int DURABILITY = 3;
    static final double REAR_FACING = 180;
    static final double SINGLE_TARGET_ANGLE = 60;
    static final double STRIKE_DISTANCE = 3;
    static final double STRIKE_MULTIPLIER = 1.6;
    static final double TWO_HAND_MULTIPLIER = 1.2;
    static final double KNOCKBACK = 0.4;
    static final long SHIELD_BREAK = 5000;
    static final long SHIELD_COOLDOWN = 2000;
    static final double CONTEXT_WEIGHT = 4;
    static final double FAR_DISTANCE = 10;
    static final String SWEEP_GROUP = "sweep";
    static final int CHAIN_HIT_COUNT = 3;
    static final long CHAIN_HIT_INTERVAL = 50;

    enum Hit { INTERMEDIATE, LAST }

    private CombatTechniqueFixtures() {
        // 样例无任何正式内容身份或 Minecraft 执行器。
    }

    static WeaponStrike strike(Hit hit) {
        // 两半圈共享五个目标，只有后半圈的最后一击扣耐久。
        return WeaponStrike.newBuilder()
                .setDedupGroup(SWEEP_GROUP)
                .setShape(StrikeShape.SECTOR)
                .setDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setAngle(quantity(REAR_FACING, Unit.DEGREE))
                .setFacingOffset(quantity(0, Unit.DEGREE))
                .setMultiplier(quantity(STRIKE_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(TARGET_LIMIT)
                .setCastTargetLimit(TARGET_LIMIT)
                .setKnockback(quantity(KNOCKBACK, Unit.RATIO))
                .setCanTwoHand(true /* canTwoHand */)
                .setTwoHandMultiplier(quantity(TWO_HAND_MULTIPLIER, Unit.RATIO))
                .setBreakShield(true /* breakShield */)
                .setInjuryOverride(InjuryKind.BLUNT)
                .setLastStrike(hit == Hit.LAST)
                .setDurabilityBase(DURABILITY)
                .setShieldCooldownMillis(SHIELD_COOLDOWN)
                .setShieldBreakMillis(SHIELD_BREAK)
                .build();
    }

    static TechniqueDefinition chain() {
        // 三次单体打击各自去重，仅最后一次扣耐久，测试内容不进入正式目录。
        var definition = combatDefinition().toBuilder();
        for (int index = 0; index < CHAIN_HIT_COUNT; index++) {
            Hit hit;
            if (index == CHAIN_HIT_COUNT - 1) {
                hit = Hit.LAST;
            } else {
                hit = Hit.INTERMEDIATE;
            }
            WeaponStrike strike = strike(hit).toBuilder()
                    .setDedupGroup("chain_" + index)
                    .setShape(StrikeShape.SINGLE_TARGET)
                    .setAngle(quantity(SINGLE_TARGET_ANGLE, Unit.DEGREE))
                    .setMaximumTargets(1)
                    .setCastTargetLimit(1)
                    .build();
            TechniqueEffect effect = TechniqueEffect.newBuilder()
                    .setAtMillis(WARMUP + index * CHAIN_HIT_INTERVAL)
                    .setWeaponStrike(strike)
                    .build();
            definition.addEffects(effect);
        }
        return definition.build();
    }

    static BreakBlocks blocks() {
        // 门标签由测试提供，宿主合并上下两半并复核物品类别和权限。
        return BreakBlocks.newBuilder()
                .setForwardDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setWidth(quantity(STRIKE_DISTANCE, Unit.METER))
                .addBlockTags("sample:breakable_doors")
                .setMaximumDoors(DOOR_LIMIT)
                .build();
    }

    static TechniqueDefinition first() {
        // 原式从零提交曲线，承接提前结束收势时也必须保留到自然到期。
        TechniqueEffect modifier = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setTissueModifier(modifier())
                .build();
        return combatDefinition().toBuilder()
                .addEffects(modifier)
                .addAllEffects(worldEffects())
                .build();
    }

    static TechniqueDefinition next() {
        // 后式跳过预备段冲量，生效点后的曲线仍完整提交。
        FollowUp transition = FollowUp.newBuilder()
                .setPrevious(TechniqueRegistry.reference(first()))
                .setRecoveryStartRatio(quantity(RECOVERY_START_RATIO, Unit.RATIO))
                .setAfterRecoveryMillis(AFTER_WINDOW)
                .setStartMillis(CLIP_OFFSET)
                .build();
        FollowUpRules rules = FollowUpRules.newBuilder()
                .addTransitions(transition)
                .setOnlyFollowUp(true /* onlyFollowUp */)
                .build();
        TechniqueEffect modifier = TechniqueEffect.newBuilder()
                .setAtMillis(FOLLOW_MODIFIER)
                .setTissueModifier(modifier())
                .build();
        List<TechniqueEffect> effects = worldEffects();
        return combatDefinition().toBuilder()
                .setId(SECOND)
                .setFollowUp(rules)
                .addEffects(effects.get(0))
                .addEffects(effects.get(1))
                .addEffects(modifier)
                .addAllEffects(effects.subList(2, effects.size()))
                .build();
    }

    private static List<TechniqueEffect> worldEffects() {
        // 两个打击分别覆盖前后半圈，劈门与末击共享生效时刻。
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.FORWARD)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
        TechniqueEffect preparation = TechniqueEffect.newBuilder()
                .setAtMillis(PREPARATION_EFFECT)
                .setImpulse(impulse)
                .build();
        TechniqueEffect front = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setWeaponStrike(strike(Hit.INTERMEDIATE))
                .build();
        WeaponStrike rear = strike(Hit.LAST).toBuilder()
                .setFacingOffset(quantity(REAR_FACING, Unit.DEGREE))
                .build();
        TechniqueEffect back = TechniqueEffect.newBuilder()
                .setAtMillis(SECOND_STRIKE)
                .setWeaponStrike(rear)
                .build();
        TechniqueEffect doors = TechniqueEffect.newBuilder()
                .setAtMillis(SECOND_STRIKE)
                .setBreakBlocks(blocks())
                .build();
        return List.of(presentationEffect(0), preparation, front, back, doors);
    }

    private static TechniqueDefinition combatDefinition() {
        // 提示参数公开可配置，宿主只提供观察事实而不代写偏好分数。
        CombatAiHints combat = CombatAiHints.newBuilder()
                .setTargetShieldingWeight(quantity(CONTEXT_WEIGHT, Unit.RATIO))
                .setSurroundedWeight(quantity(CONTEXT_WEIGHT, Unit.RATIO))
                .setUnderAttackWeight(quantity(CONTEXT_WEIGHT, Unit.RATIO))
                .setPursuedWeight(quantity(CONTEXT_WEIGHT, Unit.RATIO))
                .setMaximumWarmupUnderAttackMillis(CLIP_OFFSET)
                .setLongWarmupMillis(WARMUP)
                .setMaximumLongWarmupDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .build();
        AiHints hints = AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(FAR_DISTANCE, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO))
                .setCombat(combat)
                .build();
        return definition().toBuilder()
                .setMovementSpeedMultiplier(MOVEMENT_MULTIPLIER)
                .setAiHints(hints)
                .build();
    }

    static TechniqueCatalog combatCatalog() {
        // 复用持物书卷测试声明，后式的精确版本由独立承接定义替换。
        return groupedCatalog(first()).toBuilder()
                .setDefinitions(1, next())
                .build();
    }

    static TechniqueRegistry combatRegistry() {
        // 所有样例通过真实目录边界，不能绕过算子参数或跨式引用校验。
        return TechniqueRegistry.load(addons(), List.of(combatCatalog()));
    }

    static MemoryHost combatHost() {
        // 身体只预置已学第一式，服务测试再通过可信入口学会后式。
        MemoryHost host = new MemoryHost(body(Shape.TENDRIL));
        host.heldItems = hands(durableItem("minecraft:iron_axe", AXES, IRON_AXES),
                HeldItem.getDefaultInstance());
        return host;
    }

    static CastRequest nextRequest(MemoryHost host, String id) {
        // 显式承接入口可验证窗外拒绝；AUTO 的降级语义另行测试。
        return request(host.current(), id).toBuilder()
                .setTechnique(TechniqueRegistry.reference(next()))
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
    }
}
