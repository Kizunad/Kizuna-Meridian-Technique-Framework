package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BaseDamageSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DurabilityRule;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSelectionCriterion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ShieldInteraction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBook;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.catalog;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;

/** 持物、书卷和字节宿主样例仅用于公开流程回归。 */
final class HeldTechniqueFixtures {
    static final double MOVEMENT_MULTIPLIER = 0.4;
    static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000006");
    static final String BOOK = "sample:book";
    static final String UPPER = "sample:upper";
    static final String LOWER = "sample:lower";
    static final String SECOND = "sample:second";
    static final String THIRD = "sample:third";
    static final String AXES = "sample:axes";
    static final String TOOLS = "sample:tools";
    static final String BLOCKS = "sample:blocks";
    static final String IRON_AXES = "sample:iron_axes";
    static final double OTHER_AXE_WEAR = 2;
    static final double FALLBACK_WEAR = 3;
    static final int TOOL_PRIORITY = 1;
    static final int BLOCK_PRIORITY = 2;
    static final int OTHER_PRIORITY = 3;
    static final double TOOL_DAMAGE_RATIO = 0.8;
    static final double OTHER_DAMAGE = 0.5;
    static final long CLIP_START = 1500;

    private HeldTechniqueFixtures() {
        // 正式目录中不提供任何测试书、式或物品标签。
    }

    static TechniqueDefinition movementDefinition() {
        // 持续表现与移动锁都在起手触发，取消时必须清理两种宿主约束。
        return definition().toBuilder()
                .setMovementSpeedMultiplier(MOVEMENT_MULTIPLIER)
                .addEffects(presentationEffect(CLIP_START))
                .build();
    }

    static TechniqueEffect presentationEffect(long clipStart) {
        // 剪辑时刻独立于生效时刻，起手立即从指定剪辑位置播放。
        PresentationEffect presentation = PresentationEffect.newBuilder()
                .setId("sample:clip")
                .setDuration(PresentationDuration.UNTIL_RECOVERY_END)
                .setStartMillis(clipStart)
                .build();
        return TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setPresentation(presentation)
                .build();
    }

    static TechniqueCatalog groupedCatalog(TechniqueDefinition first) {
        // 上卷含两式，下卷含一式且以前者为前置。
        TechniqueDefinition second = first.toBuilder().setId(SECOND).build();
        TechniqueDefinition third = first.toBuilder().setId(THIRD).build();
        TechniqueBook book = TechniqueBook.newBuilder()
                .setId(BOOK)
                .setDisplayName("测试书")
                .setHeldItemRules(rules())
                .build();
        TechniqueVolume upper = TechniqueVolume.newBuilder()
                .setId(UPPER)
                .setBookId(BOOK)
                .addTechniques(TechniqueRegistry.reference(first))
                .addTechniques(TechniqueRegistry.reference(second))
                .build();
        TechniqueVolume lower = TechniqueVolume.newBuilder()
                .setId(LOWER)
                .setBookId(BOOK)
                .addTechniques(TechniqueRegistry.reference(third))
                .addPrerequisiteVolumeIds(UPPER)
                .build();
        return catalog(first).toBuilder()
                .addDefinitions(second)
                .addDefinitions(third)
                .addBooks(book)
                .addVolumes(upper)
                .addVolumes(lower)
                .build();
    }

    static HeldItemRules rules() {
        // 类别优先于耐久；末项匹配任何其他物品，主手只打破最终平局。
        HeldItemCategory axe = weaponCategory("axe", AXES, 0);
        HeldItemCategory tool = weaponCategory("tool", TOOLS, TOOL_PRIORITY).toBuilder()
                .setBaseDamage(quantity(TOOL_DAMAGE_RATIO, Unit.RATIO))
                .setShieldInteraction(ShieldInteraction.COOLDOWN_AFTER_BLOCK)
                .build();
        HeldItemCategory block = fixedCategory("block", BLOCK_PRIORITY, 1).toBuilder()
                .addItemTags(BLOCKS)
                .build();
        HeldItemCategory other = fixedCategory("other", OTHER_PRIORITY, OTHER_DAMAGE);
        return HeldItemRules.newBuilder()
                .addCategories(axe)
                .addCategories(tool)
                .addCategories(block)
                .addCategories(other)
                .addDurability(wear(List.of(IRON_AXES), 1))
                .addDurability(wear(List.of(AXES), OTHER_AXE_WEAR))
                .addDurability(wear(List.of(), FALLBACK_WEAR))
                .addSelectionOrder(HandSelectionCriterion.CATEGORY_PRIORITY)
                .addSelectionOrder(HandSelectionCriterion.DURABILITY_MULTIPLIER)
                .addSelectionOrder(HandSelectionCriterion.PREFER_MAIN_HAND)
                .build();
    }

    private static HeldItemCategory weaponCategory(String id, String tag, int priority) {
        // 武器基础伤害只存来源和倍率，测试不读取 Minecraft 攻击属性。
        return HeldItemCategory.newBuilder()
                .setId(id)
                .addItemTags(tag)
                .setSelectionPriority(priority)
                .setBaseDamageSource(BaseDamageSource.ITEM_ATTACK_DAMAGE)
                .setBaseDamage(quantity(1, Unit.RATIO))
                .setInjuryKind(InjuryKind.CUTTING)
                .setShieldInteraction(ShieldInteraction.BREAK_BEFORE_HIT)
                .setCanBreakDoors(true /* canBreakDoors */)
                .build();
    }

    private static HeldItemCategory fixedCategory(String id, int priority, double damage) {
        // 方块和杂物以固定伤害表达，不能劈门或破盾。
        return HeldItemCategory.newBuilder()
                .setId(id)
                .setSelectionPriority(priority)
                .setBaseDamageSource(BaseDamageSource.FIXED_DAMAGE)
                .setBaseDamage(quantity(damage, Unit.DAMAGE_POINT))
                .setInjuryKind(InjuryKind.BLUNT)
                .setShieldInteraction(ShieldInteraction.NO_SHIELD_BREAK)
                .setCanBreakDoors(false /* canBreakDoors */)
                .build();
    }

    private static DurabilityRule wear(List<String> tags, double multiplier) {
        // 耐久只影响候选排序，实际扣减由未来宿主执行器负责。
        return DurabilityRule.newBuilder()
                .addAllItemTags(tags)
                .setMultiplier(quantity(multiplier, Unit.RATIO))
                .build();
    }

    static HeldItem durableItem(String id, String... tags) {
        // 有耐久样例显式声明空容器，与无耐久和装物品样例区分。
        return HeldItem.newBuilder()
                .setItemId(id)
                .addAllTags(List.of(tags))
                .setDamageable(true /* damageable */)
                .setContainsItems(false /* containsItems */)
                .build();
    }

    static HeldItems hands(HeldItem main, HeldItem off) {
        // 左撇子仍以主副手身份选手，物理左右由宿主根据主手侧解释。
        return HeldItems.newBuilder()
                .setMainHand(main)
                .setOffHand(off)
                .setMainHandSide(HandSide.LEFT)
                .build();
    }

    static TechniqueService service(MemoryHost host, TechniqueRegistry registry) {
        // 服务发现、inspect 和效果提交全部经过字节协议。
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(endpoint(host)));
        return new TechniqueService(registry, directory);
    }

    private static TechniqueHostEntrypoint endpoint(MemoryHost host) {
        // 仅认领测试 UUID，Framework 拒绝保留结构化失败码。
        return payload -> {
            TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
            var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
            if (!ENTITY.toString().equals(request.getEntityUuid())) {
                return TechniqueProtocol.encodeHostResponse(response.setUnbound(true /* unbound */).build());
            }
            try {
                route(host, request, response);
            } catch (TechniqueErrors.HostRejected exception) {
                RequestFailure failure = RequestFailure.newBuilder().setCode(exception.failure()).build();
                response.setFailure(failure);
            }
            return TechniqueProtocol.encodeHostResponse(response.build());
        };
    }

    private static void route(MemoryHost host, TechniqueHostRequest request, TechniqueHostResponse.Builder response) {
        // 三种操作都返回同一宿主的真实身体或幂等回执。
        switch (request.getOperationCase()) {
            case INSPECT -> {
                TechniqueHost.Inspection inspection = host.inspect();
                response.setBody(BodyProtobuf.toMessage(inspection.body()));
                response.setHeldItems(inspection.heldItems());
                response.setInspectFacts(inspection.facts());
            }
            case ACTIVITY -> {
                TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(request.getRequestId(),
                        request.getExpectedRevision(), request.getActivity()));
                TechniqueActivityCommit activity = TechniqueActivityCommit.newBuilder()
                        .setRevision(commit.revision())
                        .setActivity(commit.activity())
                        .build();
                response.setActivity(activity);
            }
            case EFFECT -> {
                host.submitEffect(new TechniqueHost.EffectSubmission(request.getRequestId(), request.getEffect()));
                response.setEffectAccepted(true /* effectAccepted */);
            }
            default -> throw new IllegalArgumentException("缺少宿主操作");
        }
    }
}
