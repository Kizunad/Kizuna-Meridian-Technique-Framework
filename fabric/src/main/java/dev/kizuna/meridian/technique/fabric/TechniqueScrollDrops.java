package dev.kizuna.meridian.technique.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ScrollDrop;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemKilledByPlayerCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;

/**
 * 卷目录 {@code scroll_drops} 声明的生存掉落：被玩家杀死、死者会本卷全部式时按概率掉一份本卷卷轴。
 *
 * <p>只往实体自带的掉落表加池，数据包替换过的表不动；会不会这一卷问功法服务，死的那一刻身体还在。</p>
 */
public final class TechniqueScrollDrops {
    public static final Identifier KNOWS_VOLUME_ID = Identifier.fromNamespaceAndPath("meridian_technique",
            "knows_volume");

    private TechniqueScrollDrops() {
        // 只在初始化期登记事件，运行时不持有状态。
    }

    static void register() {
        // 条件类型在注册表冻结前登记；掉落池在每次读取掉落表时按卷轴清单加入。
        Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, KNOWS_VOLUME_ID, KnowsVolume.CODEC);
        LootTableEvents.MODIFY.register((key, table, source, registries) -> {
            if (source.isBuiltin()) {
                addPools(key, table);
            }
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> requireRegisteredEntities());
    }

    public static List<Drop> drops() {
        // 全部卷的掉落声明连同对应卷轴物品，测试和 Wiki 校验共用。
        var drops = new ArrayList<Drop>();
        TechniqueScrollItems.scrolls().forEach((itemId, item) -> {
            for (ScrollDrop drop : item.scroll().volume().getScrollDropsList()) {
                drops.add(new Drop(Identifier.parse(drop.getEntityType()), item, drop.getChance()));
            }
        });
        return List.copyOf(drops);
    }

    private static void addPools(ResourceKey<LootTable> key, LootTable.Builder table) {
        // 实体自带掉落表与 key 相同时才加池；每份声明一个池，概率彼此独立。
        for (Drop drop : drops()) {
            Optional<ResourceKey<LootTable>> entityTable = BuiltInRegistries.ENTITY_TYPE.getOptional(drop.entityType())
                    .flatMap(EntityType::getDefaultLootTable);
            if (entityTable.isPresent() && entityTable.get().equals(key)) {
                LootPool.Builder pool = LootPool.lootPool()
                        .when(LootItemKilledByPlayerCondition.killedByPlayer())
                        .when(LootItemRandomChanceCondition.randomChance((float) drop.chance()))
                        .when(() -> new KnowsVolume(drop.scroll().scroll().volume().getId()))
                        .add(LootItem.lootTableItem(drop.scroll()));
                table.withPool(pool);
            }
        }
    }

    private static void requireRegisteredEntities() {
        // 声明了不存在的实体说明内容写错或缺了依赖，启动时直接报出来，不静默失效。
        for (Drop drop : drops()) {
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(drop.entityType())) {
                throw new IllegalStateException("卷轴掉落声明了未注册的实体：" + drop.entityType()
                        + "（" + drop.scroll().scroll().volume().getId() + "）");
            }
        }
    }

    /** 一份掉落声明：哪种实体、掉哪卷卷轴、概率多少。 */
    public record Drop(Identifier entityType, TechniqueScrollItem scroll, double chance) { }

    /** 掉落条件：死者会这一卷的全部式。没有功法宿主的实体一律不算会。 */
    public record KnowsVolume(String volume) implements LootItemCondition {
        static final MapCodec<KnowsVolume> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("volume").forGetter(KnowsVolume::volume)).apply(instance, KnowsVolume::new));

        @Override
        public MapCodec<KnowsVolume> codec() {
            // 数据包也能写 {"condition": "meridian_technique:knows_volume", "volume": "<卷 ID>"}。
            return CODEC;
        }

        @Override
        public Set<ContextKey<?>> getReferencedContextParams() {
            // 只读死者本身。
            return Set.of(LootContextParams.THIS_ENTITY);
        }

        @Override
        public boolean test(LootContext context) {
            // 死者不是生物时不掉；是生物就看它会不会这一卷。
            return context.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof LivingEntity entity
                    && knows(entity);
        }

        public boolean knows(LivingEntity entity) {
            // 按卷 ID 找卷轴里的式表，再问功法服务这只生物学会了哪些式；未知卷或服务没起来都算不会。
            Optional<TechniqueVolume> volume = TechniqueScrollItems.scrolls().values().stream()
                    .map(item -> item.scroll().volume())
                    .filter(candidate -> candidate.getId().equals(this.volume))
                    .findFirst();
            if (volume.isEmpty() || !TechniqueFabric.running()) {
                return false;
            }
            Set<TechniqueReference> known = TechniqueFabric.service().known(entity.getUUID()).stream()
                    .map(TechniqueService.KnownEntry::technique)
                    .collect(Collectors.toSet());
            return known.containsAll(volume.get().getTechniquesList());
        }
    }
}
