package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponHeatProfile;
import dev.kizuna.meridian.technique.server.EffectHandling;
import dev.kizuna.meridian.technique.server.TechniqueEffectEvent;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 原版效果负责计时，另记起手时的手和实际物品栈，供换物、磨损和伤害共用。 */
public final class TechniqueWeaponHeat {
    public interface Access {
        Lease meridian$weaponHeat();
    }

    public interface GripBurn {
        void apply(LivingEntity entity, InteractionHand hand, WeaponHeatProfile profile);
    }

    public static final Event<GripBurn> GRIP_BURN = EventFactory.createArrayBacked(GripBurn.class,
            callbacks -> (entity, hand, profile) -> {
                for (GripBurn callback : callbacks) {
                    callback.apply(entity, hand, profile);
                }
            });
    private static final int TICKS_PER_SECOND = 20;
    private static final String SAVED_HAND = "meridian_technique:heated_hand";
    private static final Map<Holder<MobEffect>, StatusBenefit> hot = new HashMap<>();
    private static final Map<Holder<MobEffect>, String> cooled = new HashMap<>();

    private TechniqueWeaponHeat() {
        // 注册项由启动目录决定，物品绑定属于各自实体。
    }

    static void register(TechniqueStatusBenefits.Registered heated, TechniqueStatusBenefits.Registered quenched) {
        // 两种效果持有不同身份，淬硬只屏蔽本配置里的软剑标签。
        hot.put(heated.effect(), heated.benefit());
        cooled.put(quenched.effect(), heated.benefit().getWeaponHeat().getSoftItemTag());
    }

    public static EffectHandling apply(MinecraftServer server, TechniqueEffectEvent event) {
        // 只认起手选定手，接收已经通过施放去重的意图。
        var intent = event.intent();
        LivingEntity actor = TechniqueStaggers.find(server, event.entityUuid());
        if (actor == null || intent.getSelectedHand() == Hand.HAND_UNSPECIFIED) {
            return EffectHandling.rejected("已失去所持兵器");
        }
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (intent.getSelectedHand() == Hand.OFF_HAND) {
            hand = InteractionHand.OFF_HAND;
        }
        StatusBenefit benefit = intent.getStatusBenefit();
        String tag = benefit.getWeaponHeat().getSoftItemTag();
        if (!matches(actor, actor.getItemInHand(hand), tag)) {
            return EffectHandling.rejected("已失去所持兵器");
        }
        Lease lease = ((Access) actor).meridian$weaponHeat();
        lease.bind(actor, hand, benefit);
        TechniqueStatusBenefits.apply(actor, benefit);
        lease.check(actor);
        return EffectHandling.handled();
    }

    static boolean matches(LivingEntity actor, ItemStack stack, String tag) {
        // 软硬判定在起手、命中和磨损时均检查当前状态。
        return stack.is(TagKey.create(Registries.ITEM, Identifier.parse(tag))) && !suppressed(actor, tag);
    }

    static boolean suppressed(LivingEntity actor, String tag) {
        // 普通物品标签不受淬硬影响，持两把软剑时两手都按硬剑处理。
        return cooled.entrySet().stream().anyMatch(entry -> entry.getValue().equals(tag)
                && actor.hasEffect(entry.getKey()));
    }

    static WeaponHeatProfile active(LivingEntity actor, InteractionHand hand, String statusId) {
        // 每击先核换物与进水，再允许本式使用相应的热兵效果。
        if (statusId.isEmpty()) {
            return null;
        }
        Lease lease = ((Access) actor).meridian$weaponHeat();
        lease.check(actor);
        if (lease.benefit == null || lease.hand != hand || !lease.benefit.getId().equals(statusId)) {
            return null;
        }
        return lease.benefit.getWeaponHeat();
    }

    public static final class HeatedStrike extends DamageSource {
        private final WeaponHeatProfile profile;

        HeatedStrike(Holder<DamageType> type, LivingEntity actor, WeaponHeatProfile profile) {
            // 主击保留落点、出手侧和绕盾标签；命中后由伤害宿主读取附伤参数。
            super(type, actor);
            this.profile = profile;
        }

        public WeaponHeatProfile profile() {
            // 该次出手的配置不会随结算期间的换物改变。
            return profile;
        }
    }

    public static final class Lease {
        private InteractionHand hand;
        private ItemStack stack;
        private StatusBenefit benefit;

        private void bind(LivingEntity actor, InteractionHand selected, StatusBenefit status) {
            // 记录栈本身，换上同材质同组件的另一把剑仍能被识别。
            hand = selected;
            stack = actor.getItemInHand(selected);
            benefit = status;
        }

        public void tick(LivingEntity actor) {
            // 根据原版剩余时间在每个完整秒末烫手，到期时不额外淬硬。
            check(actor);
            if (benefit != null) {
                var instance = actor.getEffect(TechniqueStatusBenefits.get(benefit.getId()).effect());
                if (instance != null && instance.getDuration() % TICKS_PER_SECOND == 1) {
                    GRIP_BURN.invoker().apply(actor, hand, benefit.getWeaponHeat());
                }
            }
        }

        void check(LivingEntity actor) {
            // 读档和命令添加时从实体当前效果恢复；原版到期或被单独移除即释放绑定。
            if (benefit == null) {
                for (var entry : actor.getActiveEffectsMap().keySet()) {
                    StatusBenefit status = hot.get(entry);
                    if (status != null) {
                        restore(actor, status);
                        break;
                    }
                }
            }
            if (benefit == null) {
                return;
            }
            var heated = TechniqueStatusBenefits.get(benefit.getId()).effect();
            if (!actor.hasEffect(heated)) {
                clear();
            } else if (actor.isInWaterOrRain() || actor.getItemInHand(hand) != stack || stack.isEmpty()
                    || !matches(actor, stack, benefit.getWeaponHeat().getSoftItemTag())) {
                StatusBenefit quenched = benefit.getWeaponHeat().getQuenched();
                actor.removeEffect(heated);
                clear();
                TechniqueStatusBenefits.apply(actor, quenched);
            }
        }

        private void restore(LivingEntity actor, StatusBenefit status) {
            // 存档带手位；直接给予原版效果时才按主手优先绑定。
            if (hand != null) {
                bind(actor, hand, status);
                return;
            }
            boolean main = matches(actor, actor.getMainHandItem(), status.getWeaponHeat().getSoftItemTag());
            bind(actor, main ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, status);
        }

        private void clear() {
            // 丢弃已到期的原版效果引用，避免随后误用旧手位。
            benefit = null;
            hand = null;
            stack = null;
        }

        public void save(ValueOutput output) {
            // 物品和效果由原版存储，只额外写绑定手位。
            if (hand != null) {
                output.putString(SAVED_HAND, hand.name());
            }
        }

        public void load(ValueInput input) {
            // 读档时背包尚可能在加载，等第一次服务端检查时绑定物品实例。
            clear();
            String saved = input.getStringOr(SAVED_HAND, "");
            if (!saved.isEmpty()) {
                hand = InteractionHand.valueOf(saved);
            }
        }
    }
}
