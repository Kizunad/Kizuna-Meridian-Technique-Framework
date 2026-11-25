package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponBind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.Vec3;

/** 一把兵器同时牵制两只手；双方共享同一份关系，效果只承担显示和计时。 */
public final class TechniqueWeaponBinds {
    public interface ArmHost {
        Optional<Arm> inspect(LivingEntity entity, InteractionHand hand);

        void stagger(LivingEntity caster, long millis);
    }

    public record Arm(String partId, double coefficient) { }

    private record End(LivingEntity entity, InteractionHand hand, StatusBenefit status) { }

    private record Grip(String castId, ItemStack blade, double reach, long releaseTick) { }

    private record Link(End caster, End target, String part, Grip grip) {
        boolean intact() {
            // 传送、死亡、卸载或施放者换剑会失去牵制。
            var actor = caster.entity();
            var other = target.entity();
            double distance = grip.reach();
            ItemStack blade = grip.blade();
            return actor.isAlive() && other.isAlive() && actor.level() == other.level()
                    && actor.distanceToSqr(other) <= distance * distance
                    && actor.getItemInHand(caster.hand()) == blade && !blade.isEmpty();
        }
    }

    private static final Map<LivingEntity, Link> links = new IdentityHashMap<>();
    private static final Set<Holder<MobEffect>> pairedEffects = new HashSet<>();
    private static final long MILLIS_PER_TICK = 50;
    private static ArmHost arms;

    private TechniqueWeaponBinds() {
        // 活动关系由服务端世界线程维护。
    }

    public static void register(ArmHost host) {
        // 伤害附属提供权威部位和出力。
        arms = host;
    }

    private static boolean weapon(ItemStack stack) {
        // 近战工具按原版持物攻击属性识别，弓弩沿用原版兵器类型。
        var modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return stack.getItem() instanceof ProjectileWeaponItem || modifiers.modifiers().stream()
                .anyMatch(entry -> entry.attribute().equals(Attributes.ATTACK_DAMAGE));
    }

    static void registerEffect(Holder<MobEffect> effect) {
        // 两种显示状态只随实体间的有效关系存在。
        pairedEffects.add(effect);
    }

    public static void afterLoad(LivingEntity entity) {
        // 卸载会中断施放；存档可能在卸载事件前已经写入，读回时摘掉遗留图标。
        if (!entity.level().isClientSide()) {
            pairedEffects.forEach(entity::removeEffect);
        }
    }

    public static void tick(LivingEntity entity) {
        // 即使两人站着不动，也须在移出距离、换剑或死亡后清掉关系。
        Link link = links.get(entity);
        if (link != null && !valid(link)) {
            clear(entity);
        }
    }

    private static boolean valid(Link link) {
        // 自然到期和末击可能分属同一服务端刻的实体阶段与功法阶段。
        return link.intact() && link.caster().entity().level().getGameTime() <= link.grip().releaseTick() + 1;
    }

    static void begin(LivingEntity actor, InteractionHand selected, EffectIntent effect, List<LivingEntity> targets) {
        // 锁定第一次索敌选中的持兵前臂；已经参与另一段牵制的实体不会被覆盖。
        if (targets.isEmpty() || links.containsKey(actor)) {
            return;
        }
        var target = targets.getFirst();
        var intent = effect.getWeaponStrike();
        WeaponBind bind = intent.getBeginBind();
        double reach = intent.getStrike().getDistance().getValue();
        if (links.containsKey(target) || actor.distanceToSqr(target) > reach * reach
                || immune(target, bind.getImmuneStatusId())) {
            return;
        }
        InteractionHand held;
        if (weapon(target.getMainHandItem())) {
            held = InteractionHand.MAIN_HAND;
        } else if (weapon(target.getOffhandItem())) {
            held = InteractionHand.OFF_HAND;
        } else {
            return;
        }
        var limb = arms.inspect(target, held);
        var grip = arms.inspect(actor, selected);
        if (limb.isEmpty() || grip.isEmpty()) {
            return;
        }
        var arm = limb.orElseThrow();
        if (arm.coefficient() >= grip.orElseThrow().coefficient() * bind.getCounterForceRatio()) {
            arms.stagger(actor, bind.getStaggerMillis());
            return;
        }
        var caster = new End(actor, selected, bind.getCaster());
        var captive = new End(target, held, bind.getTarget());
        long ticks = caster.status().getDurationMillis() / MILLIS_PER_TICK;
        long releaseTick = actor.level().getGameTime() + ticks;
        var blade = new Grip(effect.getCastId(), actor.getItemInHand(selected), reach, releaseTick);
        attach(new Link(caster, captive, arm.partId(), blade));
    }

    private static boolean immune(LivingEntity target, String id) {
        // 读目标已有状态的登记身份，免疫条件由功法目录声明。
        return target.getActiveEffectsMap().keySet().stream()
                .anyMatch(holder -> holder.unwrapKey().orElseThrow().identifier().toString().equals(id));
    }

    private static void attach(Link link) {
        // 显示状态和关系在同一服务端调用中建立；松脱时先摘关系再清效果。
        End caster = link.caster();
        End target = link.target();
        TechniqueStatusBenefits.apply(caster.entity(), caster.status());
        TechniqueStatusBenefits.apply(target.entity(), target.status());
        links.put(caster.entity(), link);
        links.put(target.entity(), link);
        if (target.entity().isUsingItem() && target.entity().getUsedItemHand() == target.hand()) {
            target.entity().stopUsingItem();
        }
    }

    public static Hand boundHand(LivingEntity entity) {
        // 只屏蔽参与牵制的手，另一手仍可持兵。
        Link link = current(entity);
        if (link == null) {
            return Hand.HAND_UNSPECIFIED;
        }
        End end = endFor(link, entity);
        return end.hand() == InteractionHand.MAIN_HAND ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    public static boolean locked(LivingEntity entity, InteractionHand hand) {
        // 原版普攻、挥手和功法世界结算读取同一手位。
        Hand selected = hand == InteractionHand.MAIN_HAND ? Hand.MAIN_HAND : Hand.OFF_HAND;
        return boundHand(entity) == selected;
    }

    private static End endFor(Link link, LivingEntity entity) {
        // 关系两端各有自己的手位和效果。
        End caster = link.caster();
        if (caster.entity() == entity) {
            return caster;
        }
        return link.target();
    }

    public static boolean blocksWeaponUse(LivingEntity entity, InteractionHand hand) {
        // 弓弩走用物入口；同一把武器的蓄力与已上弦发射都受锁手约束。
        return locked(entity, hand) && weapon(entity.getItemInHand(hand));
    }

    public static boolean participates(LivingEntity entity) {
        // 高频移动包先查看小表，普通移动不需要额外的向量运算。
        return current(entity) != null;
    }

    static record Pull(LivingEntity target, String part) { }

    static Optional<Pull> pullFor(LivingEntity actor, WeaponStrike strike, String castId) {
        // 只抽当前这一手实际缠住的前臂，不再按朝向重新选人。
        if (!strike.getReleaseBoundArm() && !strike.getCutBoundArm()) {
            return Optional.empty();
        }
        Link link = current(actor);
        if (link == null || link.caster().entity() != actor || !link.grip().castId().equals(castId)) {
            return Optional.empty();
        }
        if (strike.getReleaseBoundArm()) {
            clear(actor);
        }
        return Optional.of(new Pull(link.target().entity(), link.part()));
    }

    private static Link current(LivingEntity entity) {
        // 客户端只消费同步图标；服务端在读手位、移动和末击前复核脱离条件。
        if (entity.level().isClientSide()) {
            return null;
        }
        Link link = links.get(entity);
        if (link != null && !valid(link)) {
            clear(entity);
            return null;
        }
        return link;
    }

    public static Vec3 limitMovement(LivingEntity entity, Vec3 movement) {
        // 普通行走最多到牵制半径；传送另走位置设置，在下次核对时松脱。
        Link link = current(entity);
        if (link == null) {
            return movement;
        }
        var other = link.caster().entity();
        if (entity == other) {
            other = link.target().entity();
        }
        Vec3 origin = entity.position();
        Vec3 offset = origin.add(movement).subtract(other.position());
        double reach = link.grip().reach();
        if (offset.lengthSqr() <= reach * reach) {
            return movement;
        }
        return other.position().add(offset.normalize().scale(reach)).subtract(origin);
    }

    public static void effectsRemoved(LivingEntity entity, Collection<MobEffectInstance> effects) {
        // 命令驱散立即解开两端；自然到期的同刻允许功法时间线交付抽回。
        if (entity.level().isClientSide()) {
            return;
        }
        Link link = links.get(entity);
        if (link == null) {
            return;
        }
        End end = endFor(link, entity);
        var holder = TechniqueStatusBenefits.get(end.status().getId()).effect();
        for (var effect : effects) {
            if (effect.is(holder) && effect.getDuration() != 0) {
                clear(entity);
                return;
            }
        }
    }

    public static void interruptCaster(LivingEntity entity) {
        // 目标挨打不松缠，持剑的一方受制失手才解开。
        Link link = links.get(entity);
        if (link != null && link.caster().entity() == entity) {
            clear(entity);
        }
    }

    static void finish(LivingEntity actor, String castId) {
        // 宿主取消或替换旧施放时，只释放该次施放自己的牵制。
        Link link = links.get(actor);
        if (link != null && link.caster().entity() == actor && link.grip().castId().equals(castId)) {
            clear(actor);
        }
    }

    public static void clear(LivingEntity entity) {
        // 先移除两端索引，原版效果移除回调可以安全重入。
        if (entity.level().isClientSide()) {
            return;
        }
        Link link = links.remove(entity);
        if (link == null) {
            return;
        }
        End caster = link.caster();
        End target = link.target();
        links.remove(caster.entity());
        links.remove(target.entity());
        caster.entity().removeEffect(TechniqueStatusBenefits.get(caster.status().getId()).effect());
        target.entity().removeEffect(TechniqueStatusBenefits.get(target.status().getId()).effect());
    }

    private record Impact(Pull pull, DamageSource source) { }
    private static final ScopedValue<Impact> impact = ScopedValue.newInstance();

    static void hit(Pull pull, DamageSource source, Runnable strike) {
        // 指定落点只在当前原版伤害调用内有效，盾和护甲照常处理。
        ScopedValue.where(impact, new Impact(pull, source)).run(strike);
    }

    public static Optional<String> aimedPart(LivingEntity target, DamageSource source) {
        // 附伤及反伤不能继承主击的部位。
        if (impact.isBound()) {
            Impact hit = impact.get();
            if (hit.source() == source && hit.pull().target() == target) {
                return Optional.of(hit.pull().part());
            }
        }
        return Optional.empty();
    }
}
