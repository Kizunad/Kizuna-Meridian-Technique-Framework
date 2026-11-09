package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 处理器声明、动作速度和周期投影的公开契约回归。 */
public class TechniqueBrewEffectsTest {
    private static final long INTERVAL = 1_000;
    private static final long DURATION = 30_000;
    private static final long END = DURATION + INTERVAL;
    private static final double SLOW = 0.5;
    private static final double SLOWEST = 0.3;
    private static final double FAST = 0.8;
    private static final int EVENT_COUNT = 31;
    private static final int INVALID_QUALITY = 4;
    private static final long ROUNDED_INTERVAL = 3_334;
    private static final double TOLERANCE = 1e-9;

    @Test
    public void brewRequirementCannotBypassWeaponRules() {
        // 单式药瓶门禁不能替代弓弩或近战规则，重复形态也不能通过校验。
        var requirement = BrewItemRequirement.newBuilder()
                .addKindIds("sample:medicine")
                .addPhysicalUses(BrewUseMethod.SPLASH)
                .build();
        var definition = TechniqueDefinition.newBuilder()
                .setBrewItem(requirement)
                .setProjectileWeapon(ProjectileWeaponCategory.BOW)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueBrewEffects.validateDefinition(definition));
        var duplicated = requirement.toBuilder().addPhysicalUses(BrewUseMethod.SPLASH).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueBrewEffects.validateRequirement(duplicated));
    }

    @Test
    public void techniqueBrewParametersRejectUnsupportedMethodAndNonFiniteStrength() {
        // 通用 use_brew 允许旧物品用法，功法参数只允许已经实现的五种加成方式。
        var parameters = BrewTechniqueParameters.newBuilder()
                .setMethod(BrewUseMethod.DRINK)
                .setStrengthMultiplier(1)
                .setDurationMultiplier(1)
                .setMaximumQualityMultiplier(1)
                .build();
        var brew = UseBrew.newBuilder()
                .setProcessorId("sample:brew")
                .addBrewKindIds("sample:medicine")
                .addUseMethods(BrewUseMethod.DRINK)
                .setTechnique(parameters)
                .build();
        TechniqueBrewEffects.validateUseBrew(brew);
        var unsupported = parameters.toBuilder().setMethod(BrewUseMethod.SPLASH).build();
        var nonFinite = parameters.toBuilder().setStrengthMultiplier(Double.NaN).build();
        for (var invalid : List.of(unsupported, nonFinite)) {
            var declaration = brew.toBuilder().setTechnique(invalid).build();
            assertThrows(IllegalArgumentException.class, () -> TechniqueBrewEffects.validateUseBrew(declaration));
        }
    }

    @Test
    public void scaleRoundsUpPreservesZeroAndRejectsInvalidOrOverflow() {
        // 非整除的速度也不能提前发事件，非法时间或倍率不能进入调度器。
        assertEquals(ROUNDED_INTERVAL, TechniqueTempo.scale(INTERVAL, SLOWEST));
        assertEquals(0, TechniqueTempo.scale(0, SLOW));
        assertEquals(INTERVAL, TechniqueTempo.scale(INTERVAL, 1));
        assertThrows(IllegalArgumentException.class, () -> TechniqueTempo.scale(-1, 1));
        for (double tempo : List.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueTempo.scale(0, tempo));
        }
        assertThrows(IllegalArgumentException.class, () -> TechniqueTempo.scale(Long.MAX_VALUE, SLOW));
        assertEquals(1, TechniqueTempo.multiplier(ActiveCast.getDefaultInstance()), 0);
    }

    @Test
    public void slowestBoundTissueWinsAndMissingTempoPeakIsIdentity() {
        // 两块组织各有不同迟滞，只绑定其中一块时不受另一块拖慢；旧修正不改变动作速度。
        BodySnapshot original = body(Shape.PAIRED);
        var modified = BodyProtobuf.toMessage(original).toBuilder()
                .addModifiers(tempoModifier("a", FAST))
                .addModifiers(tempoModifier("b", SLOWEST))
                .build();
        BodySnapshot slowed = BodyProtobuf.fromMessage(modified);
        BoundSlot both = bound("a", "b");
        assertEquals(SLOWEST, TechniqueTempo.multiplier(slowed, List.of(both)), TOLERANCE);
        assertEquals(FAST, TechniqueTempo.multiplier(slowed, List.of(bound("a"))), TOLERANCE);
        assertEquals(1, TechniqueTempo.multiplier(slowed, List.of()), 0);
        BodyMessages.CapabilityModifier legacy = tempoModifier("a", SLOW).toBuilder().clearTempoPeak().build();
        var legacyBody = BodyProtobuf.toMessage(original).toBuilder().addModifiers(legacy).build();
        assertEquals(1, TechniqueTempo.multiplier(BodyProtobuf.fromMessage(legacyBody), List.of(both)), 0);
    }

    @Test
    public void periodicProjectionKeepsCountAndIdentityAcrossTempoAndBatching() {
        // 逐个原始边界缩放，0.3 倍速的向上取整不能丢掉第 31 次事件。
        TechniqueDefinition definition = blockDefinition();
        ActiveCast normal = active(definition, 1);
        ActiveCast slow = active(definition, SLOWEST);
        List<EffectIntent> normalEvents = blockEvents(definition, normal);
        List<EffectIntent> slowEvents = blockEvents(definition, slow);
        assertEquals(EVENT_COUNT, normalEvents.size());
        assertEquals(EVENT_COUNT, slowEvents.size());
        assertEquals(normalEvents.stream().map(EffectIntent::getEffectId).toList(),
                slowEvents.stream().map(EffectIntent::getEffectId).toList());
        var separately = new ArrayList<EffectIntent>();
        long previous = -1;
        for (int index = 0; index < EVENT_COUNT; index++) {
            EffectIntent event = slowEvents.get(index);
            long expected = TechniqueTempo.scale(INTERVAL * (index + 1), SLOWEST);
            assertEquals(expected, event.getScheduledMillis());
            assertEquals(ROUNDED_INTERVAL, event.getBlockWork().getIntervalMillis());
            assertEquals(TechniqueTempo.scale(DURATION, SLOWEST), event.getBlockWork().getDurationMillis());
            separately.addAll(TechniqueScheduler.due(definition, slow, previous, expected).effects());
            previous = expected;
        }
        assertEquals(slowEvents, separately);
    }

    @Test
    public void invalidBlockWorkAndUseBrewDeclarationsAreRejected() {
        // 处理器身份、目标、时间和用法的非法声明在加载前拒绝。
        BlockWork valid = blockWork();
        List<BlockWork> invalidWork = List.of(
                valid.toBuilder().clearProcessorId().build(),
                valid.toBuilder().clearBlockTags().build(),
                valid.toBuilder().addBlockTags("sample:cauldrons").build(),
                valid.toBuilder().setMaximumDistance(quantity(0, Unit.METER)).build(),
                valid.toBuilder().setIntervalMillis(0).build(),
                valid.toBuilder().setDurationMillis(INTERVAL - 1).build());
        for (BlockWork invalid : invalidWork) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueBrewEffects.validateBlockWork(invalid));
        }
        UseBrew brew = UseBrew.newBuilder()
                .setProcessorId("sample:brew")
                .addBrewKindIds("sample:medicine")
                .addUseMethods(BrewUseMethod.DRINK)
                .build();
        List<UseBrew> invalidBrews = List.of(
                brew.toBuilder().clearProcessorId().build(),
                brew.toBuilder().clearBrewKindIds().build(),
                brew.toBuilder().addBrewKindIds("sample:medicine").build(),
                brew.toBuilder().clearUseMethods().build(),
                brew.toBuilder().addUseMethods(BrewUseMethod.DRINK).build(),
                brew.toBuilder().setUseMethods(0, BrewUseMethod.BREW_USE_METHOD_UNSPECIFIED).build());
        for (UseBrew invalid : invalidBrews) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueBrewEffects.validateUseBrew(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> registry(definition().toBuilder()
                .addEffects(blockEffect()).build()));
    }

    @Test
    public void offHandRulesAndHeldBrewFactsRequireCompleteDeclarations() {
        // 副手开关与标签必须成对出现，药水事实不得缺字段或出现在空手上。
        TechniqueDefinition base = definition();
        List<TechniqueDefinition> invalidDefinitions = List.of(
                base.toBuilder().setAllowOffHandUseWhileCasting(true /* allow */).build(),
                base.toBuilder().addOffHandUseBlockTags("sample:cauldrons").build(),
                base.toBuilder().setAllowOffHandUseWhileCasting(true /* allow */)
                        .addOffHandUseBlockTags("sample:cauldrons")
                        .addOffHandUseBlockTags("sample:cauldrons").build());
        for (TechniqueDefinition invalid : invalidDefinitions) {
            assertThrows(IllegalArgumentException.class, () -> registry(invalid));
        }
        HeldItem brew = HeldItem.newBuilder()
                .setItemId("minecraft:potion")
                .setBrewKindId("sample:medicine")
                .setBrewQuality(0)
                .setBrewUse(BrewUseMethod.DRINK)
                .build();
        List<HeldItem> invalidItems = List.of(
                brew.toBuilder().clearItemId().build(),
                brew.toBuilder().clearBrewKindId().build(),
                brew.toBuilder().clearBrewQuality().build(),
                brew.toBuilder().setBrewQuality(INVALID_QUALITY).build(),
                brew.toBuilder().clearBrewUse().build(),
                brew.toBuilder().setBrewUse(BrewUseMethod.BREW_USE_METHOD_UNSPECIFIED).build());
        for (HeldItem invalid : invalidItems) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueBrewEffects.validateHeldBrewFacts(invalid));
        }
    }

    @Test
    public void projectedModifierDefaultsTempoPeakToOne() {
        // 旧目录的四项能力修正通过实际起手投影后也必须显式保存动作速度 1。
        TechniqueEffect effect = TechniqueRegistryTest.effect(modifier(), WARMUP);
        TechniqueDefinition definition = definition().toBuilder().addEffects(effect).build();
        ActiveCast active = active(definition, 1);
        List<BodyMessages.CapabilityModifier> modifiers = TechniqueScheduler.modifiers(definition, active);
        assertFalse(modifiers.isEmpty());
        assertTrue(modifiers.stream().allMatch(value -> value.hasTempoPeak() && value.getTempoPeak() == 1));
    }

    static TechniqueDefinition blockDefinition() {
        // 使用完整起手窗口容纳周期事件，其余费用与槽位复用真实协议样本。
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(END)
                .setActiveMillis(0)
                .setRecoveryMillis(INTERVAL)
                .setInterruptibleByDamage(true /* interruptible */)
                .build();
        return definition().toBuilder().setPhases(phases).addEffects(blockEffect()).build();
    }

    private static TechniqueEffect blockEffect() {
        // 首个事件接管目标，后续三十次各推进一秒。
        return TechniqueEffect.newBuilder().setAtMillis(INTERVAL).setBlockWork(blockWork()).build();
    }

    private static BlockWork blockWork() {
        // 目录事件由独立处理器声明限定目标和推进周期。
        return BlockWork.newBuilder()
                .setProcessorId("sample:block_work")
                .addBlockTags("sample:cauldrons")
                .setMaximumDistance(quantity(1, Unit.METER))
                .setIntervalMillis(INTERVAL)
                .setDurationMillis(DURATION)
                .build();
    }

    private static ActiveCast active(TechniqueDefinition definition, double tempo) {
        // 先经正式起手取得绑定，再缩放测试时序，保持施放与效果身份一致。
        MemoryHost host = new MemoryHost(withProgress(body(Shape.PAIRED), progress(definition)));
        new TechniqueCasting(registry(definition)).cast(host, request(host.current(), "brew_projection"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        return active.toBuilder()
                .setTempoMultiplier(tempo)
                .setWarmupEndMillis(TechniqueTempo.scale(active.getWarmupEndMillis(), tempo))
                .setActiveEndMillis(TechniqueTempo.scale(active.getActiveEndMillis(), tempo))
                .setRecoveryEndMillis(TechniqueTempo.scale(active.getRecoveryEndMillis(), tempo))
                .build();
    }

    private static List<EffectIntent> blockEvents(TechniqueDefinition definition, ActiveCast active) {
        // 排除表现与移动效果，只比较方块推进本身。
        return TechniqueScheduler.due(definition, active, -1, active.getRecoveryEndMillis()).effects().stream()
                .filter(EffectIntent::hasBlockWork).toList();
    }

    private static BoundSlot bound(String... ids) {
        // 测试明确绑定组织，不借助身体部位名的特殊规则。
        return BoundSlot.newBuilder()
                .setSlotId("actuator")
                .setModule(ModuleKind.TISSUE)
                .addAllModuleIds(List.of(ids))
                .build();
    }

    private static BodyMessages.CapabilityModifier tempoModifier(String tissueId, double tempo) {
        // 从当前时刻到远端边界保持恒定速度，隔离最慢组织选择与曲线插值。
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId("sample:tempo_" + tissueId)
                .setSource(TechniqueHost.CALLER_ID)
                .setTissueId(tissueId)
                .setStrengthPeak(1)
                .setBurstPeak(1)
                .setEndurancePeak(1)
                .setRecoveryPeak(1)
                .setTempoPeak(tempo)
                .addCurve(point(0, 1))
                .addCurve(point(DURATION, 1))
                .build();
    }
}
