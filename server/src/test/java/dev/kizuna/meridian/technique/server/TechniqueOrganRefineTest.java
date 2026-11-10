package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefine;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefineIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueSlot;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.HIT_STAGGER;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 器官炼化只由功法库排定起批时刻；取料与加工的身体规则由宿主测试覆盖。 */
public final class TechniqueOrganRefineTest {
    private static final long START = 1_000;
    private static final long BATCH = 2_000;
    private static final long WINDOW = 30_000;
    private static final long WARMUP = START + WINDOW;
    private static final long RECOVERY = 1_000;
    private static final long END = WARMUP + RECOVERY;
    private static final long COOLDOWN = 30_000;
    private static final int BATCHES = 15;
    private static final double HALF_SPEED = 0.5;
    private static final long INTERRUPT_AT = START + BATCH;
    private static final String FAMILY = "sample:refine";

    @Test
    public void batchesStartEveryIntervalInsideWindowAndCarryBinding() {
        // 30 s 窗口按 2 s 起批共 15 批，窗口终点不再起批；每批都带起手绑定的器官与经脉。
        TechniqueDefinition definition = refining();
        ActiveCast active = active(definition, 1);
        List<EffectIntent> batches = batches(definition, active);
        assertEquals(BATCHES, batches.size());
        var ids = new HashSet<String>();
        for (int index = 0; index < BATCHES; index++) {
            EffectIntent batch = batches.get(index);
            assertEquals(START + BATCH * index, batch.getScheduledMillis());
            OrganRefineIntent intent = batch.getOrganRefine();
            assertEquals(List.of("control"), intent.getOrganIdsList());
            assertEquals(boundMeridians(active), intent.getMeridianIdsList());
            assertEquals(FAMILY, intent.getRecipeFamily());
            assertEquals(BATCH, intent.getBatchMillis());
            assertEquals(WARMUP, intent.getWindowEndMillis());
            assertTrue(ids.add(batch.getEffectId()));
        }
    }

    @Test
    public void slowTempoStretchesIntervalAndWindowWithoutChangingCount() {
        // 半速时批次间隔与窗口终点一起加倍，批数和效果身份不变。
        TechniqueDefinition definition = refining();
        List<EffectIntent> normal = batches(definition, active(definition, 1));
        List<EffectIntent> slow = batches(definition, active(definition, HALF_SPEED));
        assertEquals(normal.stream().map(EffectIntent::getEffectId).toList(),
                slow.stream().map(EffectIntent::getEffectId).toList());
        for (int index = 0; index < BATCHES; index++) {
            OrganRefineIntent intent = slow.get(index).getOrganRefine();
            assertEquals(TechniqueTempo.scale(START + BATCH * index, HALF_SPEED), slow.get(index).getScheduledMillis());
            assertEquals(TechniqueTempo.scale(BATCH, HALF_SPEED), intent.getBatchMillis());
            assertEquals(TechniqueTempo.scale(WARMUP, HALF_SPEED), intent.getWindowEndMillis());
        }
    }

    @Test
    public void staggerStopsLaterBatchesAndPublishesCastEndOnce() {
        // 受击打断后已交付的批次留给宿主做完，功法库不再交付后续批次，并通知一次结束。
        TechniqueDefinition definition = refining();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        var delivered = new ArrayList<EffectIntent>();
        service.setEffectHandler(event -> {
            delivered.add(event.intent());
            return EffectHandling.handled();
        });
        var ended = new ArrayList<TechniqueCastEnded>();
        service.addCastEndListener(ended::add);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "refine")).getPhase());
        host.nowMillis = INTERRUPT_AT;
        service.tick();
        int beforeInterrupt = delivered.size();
        assertEquals(2, beforeInterrupt);
        service.stagger(ENTITY, HIT_STAGGER);
        host.nowMillis = END;
        service.tick();
        assertEquals(beforeInterrupt, delivered.size());
        assertEquals(1, ended.size());
        assertEquals(TechniqueCastEndReason.STAGGERED, ended.getFirst().reason());
        assertEquals("refine", ended.getFirst().castId());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
    }

    @Test
    public void invalidDeclarationsAndIntentsAreRejected() {
        // 无界批次、错槽位、未要求开脉的经脉槽和越过施放时长的窗口都在加载时拒绝。
        OrganRefine valid = refine();
        List<OrganRefine> invalidRefines = List.of(
                valid.toBuilder().setBatchMillis(0).build(),
                valid.toBuilder().setDurationMillis(BATCH - 1).build(),
                valid.toBuilder().clearRecipeFamily().build(),
                valid.toBuilder().setOrganSlotId("source").build(),
                valid.toBuilder().setMeridianSlotId("organ").build(),
                valid.toBuilder().setDurationMillis(END).build());
        for (OrganRefine invalid : invalidRefines) {
            TechniqueDefinition definition = refining().toBuilder().setEffects(0, effect(invalid)).build();
            assertThrows(IllegalArgumentException.class, () -> registry(definition));
        }
        TechniqueSlot unopened = refining().getSlots(0).toBuilder().setRequireOpened(false).build();
        TechniqueDefinition unopenedDefinition = refining().toBuilder().setSlots(0, unopened).build();
        assertThrows(IllegalArgumentException.class, () -> registry(unopenedDefinition));

        OrganRefineIntent intent = batches(refining(), active(refining(), 1)).getFirst().getOrganRefine();
        List<OrganRefineIntent> invalidIntents = List.of(
                intent.toBuilder().clearOrganIds().build(),
                intent.toBuilder().addOrganIds("control").build(),
                intent.toBuilder().clearMeridianIds().build(),
                intent.toBuilder().setBatchMillis(0).build(),
                intent.toBuilder().setWindowEndMillis(-1).build());
        for (OrganRefineIntent invalid : invalidIntents) {
            EffectIntent effect = EffectIntent.newBuilder()
                    .setCastId("refine")
                    .setEffectId("organ_refine_0_0")
                    .setOrganRefine(invalid)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(effect));
        }
    }

    private static TechniqueDefinition refining() {
        // 31 s 起手容纳整段起批窗口，器官与经脉槽沿用测试功法的真实绑定规则。
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(WARMUP)
                .setActiveMillis(0)
                .setRecoveryMillis(RECOVERY)
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .build();
        return definition().toBuilder()
                .setPhases(phases)
                .setCooldownMillis(COOLDOWN)
                .addEffects(effect(refine()))
                .build();
    }

    private static OrganRefine refine() {
        // 器官取料并送回来源经脉，配方族由宿主按器官种类与相态解析。
        return OrganRefine.newBuilder()
                .setOrganSlotId("organ")
                .setMeridianSlotId("source")
                .setRecipeFamily(FAMILY)
                .setBatchMillis(BATCH)
                .setDurationMillis(WINDOW)
                .build();
    }

    private static TechniqueEffect effect(OrganRefine refine) {
        // 第一批在起手第 1 s 交付。
        return TechniqueEffect.newBuilder()
                .setAtMillis(START)
                .setOrganRefine(refine)
                .build();
    }

    private static ActiveCast active(TechniqueDefinition definition, double tempo) {
        // 先经正式起手取得绑定，再按测试速度缩放阶段边界。
        MemoryHost host = new MemoryHost(withProgress(body(Shape.PAIRED), progress(definition)));
        new TechniqueCasting(registry(definition)).cast(host, request(host.current(), "refine_projection"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        return active.toBuilder()
                .setTempoMultiplier(tempo)
                .setWarmupEndMillis(TechniqueTempo.scale(active.getWarmupEndMillis(), tempo))
                .setActiveEndMillis(TechniqueTempo.scale(active.getActiveEndMillis(), tempo))
                .setRecoveryEndMillis(TechniqueTempo.scale(active.getRecoveryEndMillis(), tempo))
                .build();
    }

    private static List<EffectIntent> batches(TechniqueDefinition definition, ActiveCast active) {
        // 只比较炼化批次，声音和表现效果不参与计数。
        return TechniqueScheduler.due(definition, active, -1, active.getRecoveryEndMillis()).effects().stream()
                .filter(EffectIntent::hasOrganRefine)
                .toList();
    }

    private static List<String> boundMeridians(ActiveCast active) {
        // 起手绑定决定送回的经脉，批次不重新选择。
        return active.getBinding().getSlotsList().stream()
                .filter(slot -> slot.getSlotId().equals("source"))
                .flatMap(slot -> slot.getModuleIdsList().stream())
                .toList();
    }
}
