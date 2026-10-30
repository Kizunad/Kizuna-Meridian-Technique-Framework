package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BaseDamageSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocksIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ShieldInteraction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages;
import dev.kizuna.meridian.technique.server.TechniqueStrikeTargets;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 宿主主动调用的世界执行工具；功法服务自身不持有或修改 Minecraft 世界。 */
public final class TechniqueWorldEffects {
    public enum Policy { PLAYER, PLAYER_NO_BREAK, MOB }

    @FunctionalInterface
    public interface StrikeWork {
        void execute(LivingEntity actor, DamageSource source, Runnable strike);
    }

    private static final long MILLIS_PER_TICK = 50;
    private static final byte SHIELD_BREAK_STATUS = 30;
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
        Hand hand = hand(effect);
        ItemStack stack = actor.getStackInHand(hand);
        WeaponStrikeIntent intent = effect.getWeaponStrike();
        WeaponStrike strike = intent.getStrike();
        CastHits hits = casts.computeIfAbsent(effect.getCastId(), id -> new CastHits());
        hits.lastWeapon = stack.copy();
        hits.lastStrikeMillis = effect.getScheduledMillis();
        actor.swingHand(hand);
        HeldItemCategory category = TechniqueWorldItems.category(intent.getHeldItemRules(), stack);
        StrikeData data = strikeData(hand, stack, category, strike);
        if (!TechniqueWorldItems.usable(stack)) {
            work.execute(actor, data.source(), () -> { /* 空手或装物容器只记本次挥手做功。 */ });
            return;
        }
        List<LivingEntity> candidates = TechniqueWorldTargets.candidates(actor, strike, hostile);
        List<String> selected = TechniqueStrikeTargets.select(strike, hits.groups,
                candidates.stream().map(LivingEntity::getUuidAsString).toList());
        List<SoundCue> hitSounds = intent.getHitSoundsList();
        work.execute(actor, data.source(), () -> hitTargets(candidates, selected, hits, data, hitSounds));
        if (strike.getLastStrike()) {
            consume(stack, hand, intent);
        }
    }

    private StrikeData strikeData(Hand hand, ItemStack stack, HeldItemCategory category, WeaponStrike strike) {
        // 双手只改变剂量和伤害类型标签，身体系数及做功由 Damage 读取。
        boolean twoHanded = twoHanded(actor, hand, strike);
        String side = "main_hand";
        if (twoHanded) {
            side = "two_handed";
        } else if (hand == Hand.OFF_HAND) {
            side = "off_hand";
        }
        String kind = "cutting";
        if (injury(category, strike) == InjuryKind.BLUNT) {
            kind = "blunt";
        }
        var key = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, new Identifier(damageNamespace, kind + "_" + side));
        var type = actor.getWorld().getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(key);
        double multiplier = strike.getMultiplier().getValue() * gripMultiplier(strike, twoHanded);
        return new StrikeData(stack, category, strike, multiplier, new DamageSource(type, actor));
    }

    public static Optional<DoseEstimate> estimate(LivingEntity actor, TechniqueMessages.Hand selected,
                                                  HeldItemRules rules, WeaponStrike strike, LivingEntity target) {
        // 与命中同一算法估算护甲前剂量；空手或装物容器不会命中。不含肌力系数、护甲和部位，由调用方按 Damage 规则补。
        Hand hand = hand(selected);
        ItemStack stack = actor.getStackInHand(hand);
        if (rules.getCategoriesCount() == 0 || !TechniqueWorldItems.usable(stack)) {
            return Optional.empty();
        }
        HeldItemCategory category = TechniqueWorldItems.category(rules, stack);
        boolean twoHanded = twoHanded(actor, hand, strike);
        double grip = gripMultiplier(strike, twoHanded);
        double dose = baseDamage(stack, category, target) * strike.getMultiplier().getValue() * grip;
        return Optional.of(new DoseEstimate(dose, injury(category, strike), twoHanded));
    }

    private static boolean twoHanded(LivingEntity actor, Hand hand, WeaponStrike strike) {
        // 允许双手且另一只手空着才算双手握持。
        Hand other = Hand.OFF_HAND;
        if (hand == Hand.OFF_HAND) {
            other = Hand.MAIN_HAND;
        }
        return strike.getCanTwoHand() && actor.getStackInHand(other).isEmpty();
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
        Set<String> group = hits.groups.computeIfAbsent(data.strike().getDedupGroup(), id -> new HashSet<>());
        group.addAll(selected);
        for (LivingEntity target : candidates) {
            if (!selected.contains(target.getUuidAsString())) {
                continue;
            }
            boolean blocked = target.isBlocking() && facesAttacker(target);
            if (blocked && data.strike().getBreakShield()
                    && data.category().getShieldInteraction() == ShieldInteraction.BREAK_BEFORE_HIT) {
                disableShield(target, data.strike().getShieldBreakMillis());
            }
            float dose = (float) (baseDamage(data.stack(), data.category(), target) * data.multiplier());
            boolean damaged = target.damage(data.source(), dose);
            if (blocked && data.strike().getBreakShield()
                    && data.category().getShieldInteraction() == ShieldInteraction.COOLDOWN_AFTER_BLOCK) {
                disableShield(target, data.strike().getShieldCooldownMillis());
            }
            if (damaged && data.strike().getKnockback().getValue() > 0) {
                Vec3d delta = actor.getPos().subtract(target.getPos());
                target.takeKnockback(data.strike().getKnockback().getValue(), delta.x, delta.z);
            }
            if (damaged) {
                TechniqueSounds.playAll(target, hitSounds);
            }
        }
    }

    private static double baseDamage(ItemStack stack, HeldItemCategory category, LivingEntity target) {
        // 物品按放在主手时的属性计算，忽略玩家总攻击属性、暴击与原版攻击冷却。
        if (category.getBaseDamageSource() == BaseDamageSource.FIXED_DAMAGE) {
            return category.getBaseDamage().getValue();
        }
        double attack = 1;
        var modifiers = stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        for (EntityAttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == EntityAttributeModifier.Operation.ADDITION) {
                attack += modifier.getValue();
            }
        }
        double scaledBase = attack;
        for (EntityAttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == EntityAttributeModifier.Operation.MULTIPLY_BASE) {
                scaledBase += attack * modifier.getValue();
            }
        }
        double total = scaledBase;
        for (EntityAttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == EntityAttributeModifier.Operation.MULTIPLY_TOTAL) {
                total *= 1 + modifier.getValue();
            }
        }
        double enchanted = total + EnchantmentHelper.getAttackDamage(stack, target.getGroup());
        return enchanted * category.getBaseDamage().getValue();
    }

    private boolean facesAttacker(LivingEntity target) {
        // 原版盾只保护正面；不能因为背后攻击而触发盾冷却。
        Vec3d toward = actor.getPos().subtract(target.getPos()).multiply(1, 0, 1).normalize();
        return target.getRotationVec(1).multiply(1, 0, 1).dotProduct(toward) > 0;
    }

    private static void disableShield(LivingEntity target, long millis) {
        // 玩家冷却同步交给原版物品冷却器，其他生物至少停止当前格挡。
        if (target instanceof ServerPlayerEntity player) {
            int ticks = (int) ((millis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK);
            player.getItemCooldownManager().set(Items.SHIELD, ticks);
        }
        target.stopUsingItem();
        target.getWorld().sendEntityStatus(target, SHIELD_BREAK_STATUS);
    }

    private void consume(ItemStack stack, Hand hand, WeaponStrikeIntent intent) {
        // 生物与创造玩家不磨损；末击挥空仍消耗，耐久附魔和无法破坏由原版处理。
        if (!(actor instanceof ServerPlayerEntity player) || player.isCreative()) {
            return;
        }
        if (stack.getMaxDamage() > 0) {
            double multiplier = TechniqueWorldItems.durabilityMultiplier(intent.getHeldItemRules(), stack);
            int amount = (int) Math.ceil(intent.getStrike().getDurabilityBase() * multiplier);
            stack.damage(amount, actor, entity -> entity.sendToolBreakStatus(hand));
        } else {
            actor.sendToolBreakStatus(hand);
            stack.decrement(1);
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
        return actor.getStackInHand(hand(effect));
    }

    public void finish(String castId) {
        // 解锁时释放整次施放的命中预算，并重置玩家的原版攻击冷却。
        casts.remove(castId);
        if (actor instanceof ServerPlayerEntity player) {
            player.resetLastAttackedTicks();
        }
    }

    private static Hand hand(EffectIntent effect) {
        // 未声明选手的效果不会进入打击或劈门入口。
        return hand(effect.getSelectedHand());
    }

    private static Hand hand(TechniqueMessages.Hand selected) {
        // 只有副手映射为原版副手，其余按主手。
        if (selected == TechniqueMessages.Hand.OFF_HAND) {
            return Hand.OFF_HAND;
        }
        return Hand.MAIN_HAND;
    }

    private static final class CastHits {
        private final Map<String, Set<String>> groups = new HashMap<>();
        private ItemStack lastWeapon;
        private long lastStrikeMillis;
    }

    /** 护甲前剂量预估：剂量、致伤种类与是否双手握持。 */
    public record DoseEstimate(double dose, InjuryKind injury, boolean twoHanded) { }

    private record StrikeData(ItemStack stack, HeldItemCategory category, WeaponStrike strike,
                              double multiplier, DamageSource source) { }
}
