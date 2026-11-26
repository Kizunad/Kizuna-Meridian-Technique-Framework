package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BaseDamageSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocksIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ShieldInteraction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponHeatProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages;
import dev.kizuna.meridian.technique.server.TechniqueStrikeTargets;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/** 宿主主动调用的世界执行工具；功法服务自身不持有或修改 Minecraft 世界。 */
public final class TechniqueWorldEffects {
    public enum Policy { PLAYER, PLAYER_NO_BREAK, MOB }

    @FunctionalInterface
    public interface StrikeWork {
        void execute(LivingEntity actor, DamageSource source, Runnable strike);
    }

    private static final long MILLIS_PER_TICK = 50;
    private static final double MILLIS_PER_SECOND = 1000;
    /** 定距短移能走的距离不到这么多就算被堵住，不为贴墙的一小段挪动。 */
    private static final double MINIMUM_STEP = 0.3;
    private static final float SHIELD_BREAK_VOLUME = 0.8F;
    private static final float SHIELD_BREAK_PITCH = 0.8F;
    private static final float SHIELD_BREAK_PITCH_SPREAD = 0.4F;
    private final LivingEntity actor;
    private final Supplier<Policy> policy;
    private final Predicate<LivingEntity> hostile;
    private final StrikeWork work;
    private final String damageNamespace;
    private final Map<String, CastHits> casts = new HashMap<>();

    public TechniqueWorldEffects(LivingEntity actor, Supplier<Policy> policy, Predicate<LivingEntity> hostile,
                                 StrikeWork work, String damageNamespace) {
        // 宿主提供阵营、世界权限与 Damage 的一次出手边界，不复制身体做功算法。
        this.actor = actor;
        this.policy = policy;
        this.hostile = hostile;
        this.work = work;
        this.damageNamespace = damageNamespace;
    }

    public void strike(EffectIntent effect) {
        // 只固定起手选定的手；每击重新读取这只手此刻的物品和另一手状态。
        InteractionHand hand = hand(effect);
        ItemStack stack = actor.getItemInHand(hand);
        WeaponStrikeIntent intent = effect.getWeaponStrike();
        WeaponStrike strike = intent.getStrike();
        if (intent.hasBeginBind()) {
            beginBind(effect, stack, hand);
            return;
        }
        var pull = TechniqueWeaponBinds.pullFor(actor, strike, effect.getCastId());
        if (strike.getCutBoundArm() && pull.isEmpty()) {
            return;
        }
        if (!strike.getCutBoundArm() && !strike.getReleaseBoundArm() && TechniqueWeaponBinds.locked(actor, hand)) {
            return;
        }
        var heat = TechniqueWeaponHeat.active(actor, hand, strike.getHeatStatusId());
        CastHits hits = casts.computeIfAbsent(effect.getCastId(), id -> new CastHits());
        if (hits.strikesCancelled) {
            return;
        }
        if (strike.getPinTarget()) {
            pin(strike, hits);
            return;
        }
        hits.lastWeapon = stack.copy();
        hits.lastStrikeMillis = effect.getScheduledMillis();
        if (!strike.getCutBoundArm()) {
            actor.swing(hand);
        }
        var rules = intent.getHeldItemRules();
        if (!TechniqueWorldItems.matches(rules.getRequiredItemTagsList(), stack, actor)) {
            return;
        }
        HeldItemCategory category = TechniqueWorldItems.category(rules, stack, actor);
        StrikeData data = strikeData(hand, stack, category, strike, heat);
        if (!TechniqueWorldItems.usable(stack)) {
            work.execute(actor, data.source(), () -> { /* 空手或装物容器只记本次挥手做功。 */ });
            return;
        }
        if (strike.hasSplash()) {
            releaseSplash(effect, hand, data, hits);
            return;
        }
        strikeTargets(effect, hits, data, pull);
        if (heat != null || strike.getLastStrike()) {
            consume(stack, hand, intent, heat);
        }
    }

    private void strikeTargets(EffectIntent effect, CastHits hits, StrikeData data,
                               Optional<TechniqueWeaponBinds.Pull> pull) {
        // 抽回仅结算已锁定的人与部位；中途脱离仍按挥空结算末击磨损。
        WeaponStrike strike = data.strike();
        List<LivingEntity> candidates;
        if (strike.getReleaseBoundArm() || strike.getCutBoundArm()) {
            candidates = pull.stream().map(TechniqueWeaponBinds.Pull::target).toList();
        } else {
            candidates = facingCandidates(strike, hits);
        }
        List<String> selected = selectTargets(strike, hits, candidates);
        List<SoundCue> hitSounds = effect.getWeaponStrike().getHitSoundsList();
        List<String> struck = candidates.stream()
                .map(LivingEntity::getStringUUID)
                .filter(selected::contains)
                .toList();
        var scope = new TechniqueStrikeScope.Strike(effect, data.stack(), struck, data.source());
        Runnable hitWork = () -> hitTargets(candidates, selected, hits, data, hitSounds);
        Runnable strikeWork = () -> work.execute(actor, data.source(),
                () -> TechniqueStrikeScope.run(scope, hitWork));
        if (pull.isPresent()) {
            TechniqueWeaponBinds.hit(pull.orElseThrow(), data.source(), strikeWork);
            if (strike.getReleaseBoundArm()) {
                TechniqueFabric.service().recordBindRelease(actor.getUUID(), effect.getCastId());
            }
        } else {
            strikeWork.run();
        }
    }

    private List<LivingEntity> facingCandidates(WeaponStrike strike, CastHits hits) {
        // 本次施放钉过目标或走过定距短移的，扇区沿当时冻结的朝向；查钉的一击只留下原目标。
        float facing = actor.getYRot();
        if (hits.facing != null) {
            facing = hits.facing;
        }
        List<LivingEntity> inSector = TechniqueWorldTargets.candidates(actor, strike, hostile, facing);
        if (!strike.hasPinnedDrift()) {
            return inSector;
        }
        LivingEntity pinned = hits.pinned;
        if (pinned == null || !inSector.contains(pinned)) {
            return List.of();
        }
        Vec3 drift = pinned.position().subtract(hits.pinnedAt).multiply(1, 0, 1);
        double limit = strike.getPinnedDrift().getValue();
        if (drift.lengthSqr() > limit * limit) {
            return List.of();
        }
        return List.of(pinned);
    }

    private void pin(WeaponStrike strike, CastHits hits) {
        // 只记人和位置并冻结朝向；扇区里没有人时照样冻结朝向，随后查钉的一击自然挥空。
        hits.facing = actor.getYRot();
        List<LivingEntity> candidates = TechniqueWorldTargets.candidates(actor, strike, hostile);
        if (!candidates.isEmpty()) {
            hits.pinned = candidates.getFirst();
            hits.pinnedAt = hits.pinned.position();
        }
    }

    public void step(EffectIntent effect, Vec3 direction) {
        // 先量出这个方向上能站住的最远处，再按这段距离给初速度；此后各击沿这一刻的朝向。
        var impulse = effect.getImpulse();
        CastHits hits = casts.computeIfAbsent(effect.getCastId(), id -> new CastHits());
        hits.facing = actor.getYRot();
        double maximum = impulse.getMaximumDistance().getValue();
        Vec3 heading = direction;
        double reach = TechniqueWorldSteps.reach(actor, heading, maximum);
        var input = effect.getDirection();
        boolean freeSide = impulse.getDirection() == ImpulseDirection.SIDE && input.getX() == 0 && input.getZ() == 0;
        if (reach < MINIMUM_STEP && freeSide) {
            // 没有指定哪一侧时，缺省的一侧站不住就换另一侧。
            heading = direction.reverse();
            reach = TechniqueWorldSteps.reach(actor, heading, maximum);
        }
        if (reach < MINIMUM_STEP) {
            hits.strikesCancelled = impulse.getBlockedCancelsStrikes();
            return;
        }
        double capPerTick = impulse.getSpeed().getValue() * MILLIS_PER_TICK / MILLIS_PER_SECOND;
        Vec3 velocity = TechniqueWorldSteps.velocity(heading, reach, capPerTick);
        TechniqueWorldImpulses.apply(actor, impulse.getDirection(), velocity);
    }

    private void beginBind(EffectIntent effect, ItemStack stack, InteractionHand hand) {
        // 建缠独立索敌，不占用随后六次割击各自的命中预算。
        var intent = effect.getWeaponStrike();
        if (TechniqueWorldItems.usable(stack)
                && TechniqueWorldItems.matches(intent.getHeldItemRules().getRequiredItemTagsList(), stack, actor)) {
            var candidates = TechniqueWorldTargets.candidates(actor, intent.getStrike(), hostile);
            TechniqueWeaponBinds.begin(actor, hand, effect, candidates);
        }
    }

    private static List<String> selectTargets(WeaponStrike strike, CastHits hits, List<LivingEntity> candidates) {
        // 普通击、牵制与泼金均按本次施放的命中预算筛选各自的候选目标。
        return TechniqueStrikeTargets.select(strike, hits.groups,
                candidates.stream().map(LivingEntity::getStringUUID).toList());
    }

    private void releaseSplash(EffectIntent effect, InteractionHand hand, StrikeData data, CastHits hits) {
        // 热兵绑定和整叠消耗在同一次宿主效果回执内提交；挥空也结束流金。
        WeaponStrike strike = data.strike();
        List<LivingEntity> candidates = TechniqueWorldTargets.candidates(actor, strike, hostile);
        List<String> selected = selectTargets(strike, hits, candidates);
        var heat = ((TechniqueWeaponHeat.Access) actor).meridian$weaponHeat();
        var rules = effect.getWeaponStrike().getHeldItemRules();
        if (!heat.release(actor, hand, rules.getRequiredHeatStatus())) {
            return;
        }
        if (!(actor instanceof ServerPlayer player && player.isCreative())) {
            actor.setItemInHand(hand, ItemStack.EMPTY);
        }
        TechniqueWeaponSplash.RELEASED.invoker().released(actor, effect.getTechnique());
        TechniqueWeaponSplash.show(actor, strike);
        work.execute(actor, data.source(), () -> splashTargets(candidates, selected, data));
    }

    private void splashTargets(List<LivingEntity> candidates, List<String> selected, StrikeData data) {
        // 稳定的近远顺序分配同一把兵器的回收额，额度用完仍能命中。
        var splash = data.strike().getSplash();
        int remaining = splash.getDropLimit();
        for (var target : candidates) {
            if (!selected.contains(target.getStringUUID())) {
                continue;
            }
            double dose = baseDamage(data.stack(), data.category(), target, data.source()) * data.multiplier();
            if (TechniqueWeaponSplash.HIT.invoker().hit(target, data.source(), dose, splash)) {
                TechniqueStatusBenefits.apply(target, splash.getCoating());
                int count = Math.min(remaining, splash.getDropsPerTarget());
                if (count > 0) {
                    TechniqueSplashDrops.schedule(target, splash, count);
                    remaining -= count;
                }
            }
        }
    }

    private StrikeData strikeData(InteractionHand hand, ItemStack stack, HeldItemCategory category,
                                  WeaponStrike strike, WeaponHeatProfile heat) {
        // 双手只改变剂量和伤害类型标签，身体系数及做功由 Damage 读取；26.2 的伤害类型按注册表查找项取。
        boolean twoHanded = twoHanded(actor, hand, strike);
        String side = "main_hand";
        if (twoHanded) {
            side = "two_handed";
        } else if (hand == InteractionHand.OFF_HAND) {
            side = "off_hand";
        }
        String kind = "cutting";
        if (injury(category, strike) == InjuryKind.BLUNT) {
            kind = "blunt";
        }
        Identifier defaultType = Identifier.fromNamespaceAndPath(damageNamespace, kind + "_" + side);
        Identifier damageType = strikeType(hand, stack, strike, heat, defaultType);
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, damageType);
        var type = actor.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
        double multiplier = strike.getMultiplier().getValue() * gripMultiplier(strike, twoHanded);
        DamageSource source;
        if (heat == null) {
            source = new DamageSource(type, actor);
        } else {
            source = new TechniqueWeaponHeat.HeatedStrike(type, actor, heat);
        }
        return new StrikeData(stack, category, strike, multiplier, source);
    }

    private Identifier strikeType(InteractionHand hand, ItemStack stack, WeaponStrike strike,
                                  WeaponHeatProfile heat, Identifier defaultType) {
        // 热兵、材质和普通打击沿同一选手与伤害标签路径结算。
        if (heat != null) {
            if (hand == InteractionHand.OFF_HAND) {
                return Identifier.parse(strike.getHeatedOffHandDamageTypeId());
            }
            return Identifier.parse(strike.getHeatedMainHandDamageTypeId());
        }
        String requiredTag = strike.getDamageTypeItemTag();
        if (!requiredTag.isEmpty() && !TechniqueWeaponHeat.matches(actor, stack, requiredTag)) {
            return defaultType;
        }
        return damageType(strike, hand, defaultType);
    }

    static Identifier damageType(WeaponStrike strike, InteractionHand hand, Identifier defaultType) {
        // 目录声明完整主副手身份时直接读取，不拼接宿主命名空间。
        if (strike.getMainHandDamageTypeId().isEmpty()) {
            return defaultType;
        }
        if (hand == InteractionHand.OFF_HAND) {
            return Identifier.parse(strike.getOffHandDamageTypeId());
        }
        return Identifier.parse(strike.getMainHandDamageTypeId());
    }

    public static Optional<DoseEstimate> estimate(LivingEntity actor, TechniqueMessages.Hand selected,
                                                  HeldItemRules rules, WeaponStrike strike, LivingEntity target) {
        // 与命中同一算法估算护甲前剂量；空手或装物容器不会命中。不含肌力系数、护甲和部位，由调用方按 Damage 规则补。
        // 26.2 的附魔加成要一个伤害来源；估算没有功法的伤害类型，用生物近战来源，原版伤害附魔只看目标。
        InteractionHand hand = hand(selected);
        ItemStack stack = actor.getItemInHand(hand);
        if (rules.getCategoriesCount() == 0 || !TechniqueWorldItems.usable(stack)) {
            return Optional.empty();
        }
        HeldItemCategory category = TechniqueWorldItems.category(rules, stack, actor);
        boolean twoHanded = twoHanded(actor, hand, strike);
        double grip = gripMultiplier(strike, twoHanded);
        DamageSource source = actor.damageSources().mobAttack(actor);
        double dose = baseDamage(stack, category, target, source) * strike.getMultiplier().getValue() * grip;
        return Optional.of(new DoseEstimate(dose, injury(category, strike), twoHanded));
    }

    private static boolean twoHanded(LivingEntity actor, InteractionHand hand, WeaponStrike strike) {
        // 允许双手且另一只手空着才算双手握持。
        InteractionHand other = InteractionHand.OFF_HAND;
        if (hand == InteractionHand.OFF_HAND) {
            other = InteractionHand.MAIN_HAND;
        }
        return strike.getCanTwoHand() && actor.getItemInHand(other).isEmpty();
    }

    private static double gripMultiplier(WeaponStrike strike, boolean twoHanded) {
        // 单手倍率为一。
        if (twoHanded) {
            return strike.getTwoHandMultiplier().getValue();
        }
        return 1;
    }

    private static InjuryKind injury(HeldItemCategory category, WeaponStrike strike) {
        // 打击声明的致伤类型优先于物品类别。
        if (strike.hasInjuryOverride()) {
            return strike.getInjuryOverride();
        }
        return category.getInjuryKind();
    }

    private void hitTargets(List<LivingEntity> candidates, List<String> selected, CastHits hits, StrikeData data,
                            List<SoundCue> hitSounds) {
        // 先登记本次尝试命中，盾挡或目标免疫也不能让同组再击中同一目标；目标声音只在真正受伤的目标处响。
        // 26.2 的服务端受伤入口是 hurtServer，击退也带上本次来源和剂量。
        Set<String> group = hits.groups.computeIfAbsent(data.strike().getDedupGroup(), id -> new HashSet<>());
        group.addAll(selected);
        for (LivingEntity target : candidates) {
            if (!selected.contains(target.getStringUUID())) {
                continue;
            }
            boolean blocked = target.isBlocking() && TechniqueWorldBlocking.facesSource(target, data.source());
            if (blocked && data.strike().getBreakShield()
                    && data.category().getShieldInteraction() == ShieldInteraction.BREAK_BEFORE_HIT) {
                disableShield(target, data.strike().getShieldBreakMillis());
            }
            float dose = (float) (baseDamage(data.stack(), data.category(), target, data.source()) * data.multiplier());
            boolean damaged = target.hurtServer((ServerLevel) target.level(), data.source(), dose);
            if (blocked && data.strike().getBreakShield()
                    && data.category().getShieldInteraction() == ShieldInteraction.COOLDOWN_AFTER_BLOCK) {
                disableShield(target, data.strike().getShieldCooldownMillis());
            }
            if (damaged && data.strike().getKnockback().getValue() > 0) {
                Vec3 delta = actor.position().subtract(target.position());
                target.knockback(data.strike().getKnockback().getValue(), delta.x, delta.z, data.source(), dose);
            }
            if (damaged) {
                TechniqueSounds.playAll(actor, target, hitSounds);
                hitParticle(target, data.strike().getHitParticleId());
            }
        }
    }

    private static double baseDamage(ItemStack stack, HeldItemCategory category, LivingEntity target,
                                     DamageSource source) {
        // 物品按放在主手时的属性计算，忽略玩家总攻击属性、暴击与原版攻击冷却。
        // 26.2 的物品属性在组件里，只取物品自身的修饰；附魔加成由原版在总值上叠加，原版伤害附魔都是加法。
        if (category.getBaseDamageSource() == BaseDamageSource.FIXED_DAMAGE) {
            return category.getBaseDamage().getValue();
        }
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .forEach(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                    if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                        modifiers.add(modifier);
                    }
                });
        double attack = 1;
        for (AttributeModifier modifier : modifiers) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                attack += modifier.amount();
            }
        }
        double scaledBase = attack;
        for (AttributeModifier modifier : modifiers) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                scaledBase += attack * modifier.amount();
            }
        }
        double total = scaledBase;
        for (AttributeModifier modifier : modifiers) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                total *= 1 + modifier.amount();
            }
        }
        double enchanted = EnchantmentHelper.modifyDamage((ServerLevel) target.level(), stack, target, source,
                (float) total);
        return enchanted * category.getBaseDamage().getValue();
    }

    private static void hitParticle(LivingEntity target, String id) {
        // 只在造成伤害的那个目标身上播放目录声明的简单粒子。
        if (id.isEmpty()) {
            return;
        }
        if (BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(id)) instanceof SimpleParticleType particle) {
            Vec3 center = target.getBoundingBox().getCenter();
            ((ServerLevel) target.level()).sendParticles(particle, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        }
    }

    private static void disableShield(LivingEntity target, long millis) {
        // 玩家冷却同步交给原版物品冷却器，其他生物至少停止当前格挡。
        // 26.2 没有盾破实体事件，改由服务端在目标处播放同一盾破声，音量与音高取原事件的取值。
        if (target instanceof ServerPlayer player) {
            int ticks = (int) ((millis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK);
            player.getCooldowns().addCooldown(Items.SHIELD.getDefaultInstance(), ticks);
        }
        target.releaseUsingItem();
        float pitch = SHIELD_BREAK_PITCH + target.level().getRandom().nextFloat() * SHIELD_BREAK_PITCH_SPREAD;
        target.level().playSound(null /* except */, target.getX(), target.getY(), target.getZ(),
                SoundEvents.SHIELD_BREAK, target.getSoundSource(), SHIELD_BREAK_VOLUME, pitch);
    }

    private void consume(ItemStack stack, InteractionHand hand, WeaponStrikeIntent intent,
                         WeaponHeatProfile heat) {
        // 生物与创造玩家不磨损；末击挥空仍消耗，耐久附魔和无法破坏由原版处理。
        // 26.2 的磨损入口按手自行广播损坏；不可磨损物品照原版磨坏的顺序，先减少再广播损坏。
        if (!(actor instanceof ServerPlayer player) || player.isCreative()) {
            return;
        }
        if (stack.getMaxDamage() > 0) {
            int amount = 0;
            if (intent.getStrike().getLastStrike()) {
                double multiplier = TechniqueWorldItems.durabilityMultiplier(intent.getHeldItemRules(), stack, actor);
                amount = (int) Math.ceil(intent.getStrike().getDurabilityBase() * multiplier);
            }
            if (heat != null) {
                amount += heat.getExtraDurability();
            }
            stack.hurtAndBreak(amount, actor, hand);
        } else {
            var item = stack.getItem();
            stack.shrink(1);
            actor.onEquippedItemBroken(item, hand.asEquipmentSlot());
        }
    }

    public void breakBlocks(EffectIntent effect) {
        // 同一时刻先打击后扣耐久也能劈门，使用该击实际使用的物品类别。
        ItemStack stack = doorWeapon(effect);
        if (!TechniqueWorldItems.usable(stack)) {
            return;
        }
        BreakBlocksIntent intent = effect.getBreakBlocks();
        HeldItemCategory category = TechniqueWorldItems.category(intent.getHeldItemRules(), stack);
        if (category.getCanBreakDoors()) {
            TechniqueWorldDoors.execute(actor, intent.getBlocks(), policy.get());
        }
    }

    private ItemStack doorWeapon(EffectIntent effect) {
        // 同刻末击可能已经磨坏工具，劈门仍使用这次打击前的物品事实。
        CastHits hits = casts.get(effect.getCastId());
        if (hits != null && hits.lastWeapon != null && hits.lastStrikeMillis == effect.getScheduledMillis()) {
            return hits.lastWeapon;
        }
        return actor.getItemInHand(hand(effect));
    }

    public void finish(String castId) {
        // 解锁时释放整次施放的命中预算，并重置玩家的原版攻击冷却。
        TechniqueWeaponBinds.finish(actor, castId);
        casts.remove(castId);
        if (actor instanceof ServerPlayer player) {
            player.resetAttackStrengthTicker();
        }
    }

    private static InteractionHand hand(EffectIntent effect) {
        // 未声明选手的效果不会进入打击或劈门入口。
        return hand(effect.getSelectedHand());
    }

    private static InteractionHand hand(TechniqueMessages.Hand selected) {
        // 只有副手映射为原版副手，其余按主手。
        if (selected == TechniqueMessages.Hand.OFF_HAND) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    private static final class CastHits {
        private final Map<String, Set<String>> groups = new HashMap<>();
        private ItemStack lastWeapon;
        private long lastStrikeMillis;
        private Float facing;
        private LivingEntity pinned;
        private Vec3 pinnedAt;
        private boolean strikesCancelled;
    }

    /** 护甲前剂量预估：剂量、致伤种类与是否双手握持。 */
    public record DoseEstimate(double dose, InjuryKind injury, boolean twoHanded) { }

    private record StrikeData(ItemStack stack, HeldItemCategory category, WeaponStrike strike,
                              double multiplier, DamageSource source) { }
}
