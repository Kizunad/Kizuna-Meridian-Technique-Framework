package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ElementalInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InfusionStatus;
import dev.kizuna.meridian.technique.server.TechniqueCatalogSource;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 附行的图标：身体上的附行（{@code ActiveInfusion}）是准，状态效果只是它的显示，两边一起起、一起没。
 *
 * <ul>
 *   <li>附行写进身体：服务发事件，这里摘掉别的附行图标、挂上这一个，时长取附行剩下的时间。</li>
 *   <li>图标被牛奶、指令清掉：这里让服务把身体上的附行也结束，不留下看不见的附行。</li>
 *   <li>别处直接给图标（{@code /effect give}）：拒绝，图标只跟着附行出现。</li>
 *   <li>实体带着图标装载：按身体上的附行重摆一次。</li>
 * </ul>
 *
 * <p>全部由事件触发，平时不读身体。只在服务端做事。</p>
 */
public final class TechniqueInfusionStatuses {
    private static final long MILLIS_PER_TICK = 50;
    private static final Map<String, Holder<MobEffect>> statuses = new LinkedHashMap<>();
    private static final Map<String, Integer> colors = new LinkedHashMap<>();
    /** 这里自己在挂、摘图标：钩子不再回头去动身体。只在服务线程读写。 */
    private static boolean placing;

    private TechniqueInfusionStatuses() {
        // 注册表在两个逻辑端以同一目录固定。
    }

    public static void register(Iterable<TechniqueCatalogSource> sources) {
        // 目录里每个带图标的附行注册一个不出粒子的有益效果；几式共用一个图标时颜色要一致。
        for (var source : sources) {
            for (var technique : source.catalog().getDefinitionsList()) {
                for (var effect : technique.getEffectsList()) {
                    if (effect.hasElementalInfusion() && effect.getElementalInfusion().hasStatus()) {
                        register(effect.getElementalInfusion().getStatus());
                    }
                }
            }
        }
    }

    private static void register(InfusionStatus status) {
        // 同一身份只注册一次。
        Integer known = colors.get(status.getId());
        if (known != null) {
            if (known != status.getColorRgb()) {
                throw new IllegalArgumentException("附行图标 " + status.getId() + " 在目录里有两种颜色");
            }
            return;
        }
        Holder<MobEffect> effect = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
                Identifier.parse(status.getId()),
                new TechniqueQuietEffect(MobEffectCategory.BENEFICIAL, status.getColorRgb()));
        statuses.put(status.getId(), effect);
        colors.put(status.getId(), status.getColorRgb());
    }

    /** 已注册的附行图标；没有这个身份是接线错误。 */
    public static Holder<MobEffect> get(String id) {
        Holder<MobEffect> effect = statuses.get(id);
        if (effect == null) {
            throw new IllegalArgumentException("未注册的附行图标：" + id);
        }
        return effect;
    }

    /** 这个效果实例是哪个附行图标；不是附行图标为空。 */
    public static Optional<String> statusId(MobEffectInstance instance) {
        // 图标一共没几个，逐个比。
        for (var entry : statuses.entrySet()) {
            if (instance.is(entry.getValue())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public static Consumer<TechniqueService.InfusionBegan> listener(MinecraftServer server) {
        // 服务只记实体 UUID；实体不在已加载的世界里就不挂，装载时会再对一次。
        return event -> {
            LivingEntity entity = TechniqueStaggers.find(server, event.entityUuid());
            if (entity != null) {
                show(entity, event.infusion(), event.remainingMillis());
            }
        };
    }

    /** 外面直接给的附行图标不收：图标只跟着身体上的附行出现。 */
    public static boolean rejects(LivingEntity entity, MobEffectInstance incoming) {
        return !entity.level().isClientSide() && !placing && statusId(incoming).isPresent();
    }

    /** 图标被清掉（牛奶、指令、图腾）时结束身体上的附行；自然到期和这里自己摘的不算。 */
    public static void removed(LivingEntity entity, Collection<MobEffectInstance> effects) {
        if (entity.level().isClientSide() || placing || !TechniqueFabric.running()) {
            return;
        }
        for (MobEffectInstance effect : effects) {
            boolean expired = !effect.isInfiniteDuration() && effect.getDuration() <= 0;
            if (!expired) {
                statusId(effect).ifPresent(id -> TechniqueFabric.service().endInfusion(entity.getUUID(), id));
            }
        }
    }

    /** 这个实体身上有没有附行图标。 */
    public static boolean shown(LivingEntity entity) {
        return statuses.values().stream().anyMatch(entity::hasEffect);
    }

    /**
     * 实体装载后按身体上的附行重摆图标。
     *
     * @return 对过了；身体宿主还没认领这个实体时返回 false，调用方下一 tick 再试
     */
    public static boolean reconcile(LivingEntity entity) {
        if (!TechniqueFabric.running() || TechniqueFabric.service().statusHost(entity.getUUID()).isEmpty()) {
            return false;
        }
        Optional<TechniqueService.InfusionBegan> active = TechniqueFabric.service().activeInfusion(entity.getUUID());
        if (active.isPresent()) {
            show(entity, active.get().infusion(), active.get().remainingMillis());
        } else {
            clear(entity);
        }
        return true;
    }

    private static void show(LivingEntity entity, ElementalInfusion infusion, long remainingMillis) {
        // 一身只有一个附行：先摘掉所有附行图标，再挂这一个；没声明图标的附行什么都不挂。
        placing = true;
        try {
            statuses.values().forEach(entity::removeEffect);
            int ticks = Math.toIntExact((remainingMillis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK);
            if (infusion.hasStatus() && ticks > 0) {
                entity.addEffect(new MobEffectInstance(get(infusion.getStatus().getId()), ticks, 0,
                        true /* ambient */, false /* showParticles */, true /* showIcon */));
            }
        } finally {
            placing = false;
        }
    }

    private static void clear(LivingEntity entity) {
        // 身体上已经没有附行：摘掉图标，不回头再去结束附行。
        placing = true;
        try {
            statuses.values().forEach(entity::removeEffect);
        } finally {
            placing = false;
        }
    }
}
