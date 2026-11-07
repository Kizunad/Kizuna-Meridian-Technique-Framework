package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;

/** 射击回归使用的独立目录与宿主观察，不进入正式资源。 */
final class ProjectileTechniqueFixtures {
    static final String BOOK = "sample:archery";
    static final String VOLUME = "sample:archery_volume";
    static final String BOW_TAG = "sample:bows";
    static final String CROSSBOW_TAG = "sample:crossbows";
    static final String AMMO_TAG = "sample:ammo";
    static final String ARROW_TAG = "sample:arrows";
    static final String MAIN_DAMAGE = "sample:bow_main";
    static final String OFF_DAMAGE = "sample:bow_off";
    static final double SPEED = 1.2;
    static final double DAMAGE = 1.5;
    static final double SPREAD = 3;
    static final double OFFSET = 45;
    static final double SNAP = 9;
    static final double RANGE = 32;
    static final double ELEVATION = 50;
    static final double LOB_SPEED = 1.1;
    static final long SHIELD_COOLDOWN = 3000;
    static final long REFERENCE_SHOT = WARMUP + ACTIVE;
    static final long ARRIVAL_DELAY = 100;
    static final long CLIP_SKIP = 10;
    static final long WINDOW_AFTER = 500;
    static final double WINDOW_RATIO = 0.5;
    private static final int OTHER_PRIORITY = 2;

    private ProjectileTechniqueFixtures() {
        // 测试声明共用具名参数，非法输入由各用例单独构造。
    }

    static LaunchProjectile projectile(ProjectileWeaponCategory weapon) {
        // 直射保留完整显式开关，方向变体只替换互斥字段。
        return LaunchProjectile.newBuilder()
                .setProjectileWeapon(weapon)
                .setSpeedMultiplier(quantity(SPEED, Unit.RATIO))
                .setDamageMultiplier(quantity(DAMAGE, Unit.RATIO))
                .setCritical(true /* critical */)
                .setPiercing(1)
                .setSpread(quantity(SPREAD, Unit.DEGREE))
                .setLook(true /* look */)
                .setMultiplyDrawStrength(true /* multiplyDrawStrength */)
                .setDrawStrengthArm(DrawStrengthArm.OTHER_ARM)
                .setShieldCooldownMillis(SHIELD_COOLDOWN)
                .setMirrorByHand(false /* mirrorByHand */)
                .build();
    }

    static LobProjectile lob() {
        // 普通抛射携带落点半径、仰角与速度上限，宿主无须识别具体功法 ID。
        return LobProjectile.newBuilder()
                .setMaximumDistance(quantity(RANGE, Unit.METER))
                .setRadius(quantity(SPREAD, Unit.METER))
                .setElevation(quantity(ELEVATION, Unit.DEGREE))
                .setMaximumSpeedMultiplier(quantity(LOB_SPEED, Unit.RATIO))
                .build();
    }

    static ConvergeProjectile converge() {
        // 汇聚引用同式的后续平射，延迟明确以毫秒表示。
        return ConvergeProjectile.newBuilder()
                .setReferenceShotMillis(REFERENCE_SHOT)
                .setReferenceSpeedMultiplier(quantity(SPEED, Unit.RATIO))
                .setArrivalDelayMillis(ARRIVAL_DELAY)
                .setFallback(lob())
                .setMinimumElevation(quantity(OFFSET, Unit.DEGREE))
                .build();
    }

    static TechniqueDefinition ranged(ProjectileWeaponCategory weapon) {
        // 每个式仍经真实 Framework 支付真元和做功，射击只增加世界效果。
        return definition().toBuilder()
                .setProjectileWeapon(weapon)
                .addEffects(shot(projectile(weapon), WARMUP))
                .build();
    }

    static TechniqueEffect shot(LaunchProjectile projectile, long atMillis) {
        // 效果时刻保持原剪辑坐标，承接由真实调度器投影。
        return TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setLaunchProjectile(projectile)
                .build();
    }

    static TechniqueCatalog archery(TechniqueDefinition... definitions) {
        // 一书一卷可容纳弓弩混合式，全部定义都经过目录注册校验。
        TechniqueBook book = TechniqueBook.newBuilder()
                .setId(BOOK)
                .setDisplayName("测试射法")
                .setHeldItemRules(rules())
                .build();
        var volume = TechniqueVolume.newBuilder()
                .setId(VOLUME)
                .setBookId(BOOK);
        for (TechniqueDefinition definition : definitions) {
            volume.addTechniques(TechniqueRegistry.reference(definition));
        }
        return catalog(definitions[0]).toBuilder()
                .clearDefinitions()
                .addAllDefinitions(List.of(definitions))
                .addBooks(book)
                .addVolumes(volume.build())
                .build();
    }

    static HeldItemRules rules() {
        // 弓只接受箭标签，弩使用书级弹药标签；末项仅供普通持物规则兜底。
        HeldItemCategory bow = category("bow", BOW_TAG, 0).toBuilder()
                .setProjectileWeapon(ProjectileWeaponCategory.BOW)
                .addAmmunitionTags(ARROW_TAG)
                .build();
        HeldItemCategory crossbow = category("crossbow", CROSSBOW_TAG, 1).toBuilder()
                .setProjectileWeapon(ProjectileWeaponCategory.CROSSBOW)
                .build();
        HeldItemCategory other = category("other", "", OTHER_PRIORITY);
        DurabilityRule durability = DurabilityRule.newBuilder()
                .setMultiplier(quantity(1, Unit.RATIO))
                .build();
        return HeldItemRules.newBuilder()
                .addAllCategories(List.of(bow, crossbow, other))
                .addDurability(durability)
                .addSelectionOrder(HandSelectionCriterion.CATEGORY_PRIORITY)
                .addSelectionOrder(HandSelectionCriterion.DURABILITY_MULTIPLIER)
                .addSelectionOrder(HandSelectionCriterion.PREFER_MAIN_HAND)
                .addAmmunitionTags(AMMO_TAG)
                .setRequireAmmunition(true /* requireAmmunition */)
                .setOtherHandEmptyOrAmmunition(true /* otherHandEmptyOrAmmunition */)
                .build();
    }

    private static HeldItemCategory category(String id, String tag, int priority) {
        // 类别填写完整持物契约，具体武器与弹药身份由调用处声明。
        var builder = HeldItemCategory.newBuilder()
                .setId(id)
                .setSelectionPriority(priority)
                .setBaseDamageSource(BaseDamageSource.FIXED_DAMAGE)
                .setBaseDamage(quantity(1, Unit.DAMAGE_POINT))
                .setInjuryKind(InjuryKind.BLUNT)
                .setShieldInteraction(ShieldInteraction.NO_SHIELD_BREAK)
                .setCanBreakDoors(false /* canBreakDoors */);
        if (!tag.isEmpty()) {
            builder.addItemTags(tag);
        }
        return builder.build();
    }

    static HeldItem item(String id, String... tags) {
        // 有物品时显式提供耐久与容器事实。
        return HeldItem.newBuilder()
                .setItemId(id)
                .addAllTags(List.of(tags))
                .setDamageable(true /* damageable */)
                .setContainsItems(false /* containsItems */)
                .build();
    }

    static HeldItems ready(ProjectileWeaponCategory weapon, Hand hand) {
        // 副手场景同时交换物品和武器类别，主手侧保持右侧以便检查相对手臂。
        HeldItem item;
        if (weapon == ProjectileWeaponCategory.BOW) {
            item = item("sample:bow", BOW_TAG);
        } else {
            item = item("sample:crossbow", CROSSBOW_TAG);
        }
        var builder = HeldItems.newBuilder()
                .setMainHand(HeldItem.getDefaultInstance())
                .setOffHand(HeldItem.getDefaultInstance())
                .setMainHandSide(HandSide.RIGHT)
                .setMainHandProjectileWeapon(ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED)
                .setOffHandProjectileWeapon(ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED)
                .setCanFindAmmunition(true /* canFindAmmunition */);
        if (hand == Hand.MAIN_HAND) {
            builder.setMainHand(item);
            builder.setMainHandProjectileWeapon(weapon);
        } else {
            builder.setOffHand(item);
            builder.setOffHandProjectileWeapon(weapon);
        }
        return builder.build();
    }
}
