package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.*;

public class TechniqueInnatePaymentTest {
    @Test
    public void storedKnowledgeCannotBypassInnateRealm() {
        // 同一份预存的已学记录，境界不足时仍不能列、学、放。
        var base = definition();
        var conditional = base.toBuilder()
                .setInnate(true /* innate */)
                .setInnateUnlock(InnateUnlock.getDefaultInstance())
                .build();
        var registry = registry(conditional);
        var high = body(Shape.PAIRED);
        var low = BodyProtobuf.toMessage(high).toBuilder().setRealm(Realm.WANDERING_QI).build();
        var host = new MemoryHost(BodyProtobuf.fromMessage(low));
        var before = host.current();
        var service = HeldTechniqueFixtures.service(host, registry);
        assertTrue(service.known(ENTITY).isEmpty());
        assertThrows(TechniqueService.InnateLockedException.class, () -> service.learn(ENTITY, TECHNIQUE_ID));
        assertEquals(TechniqueFailure.NOT_KNOWN, service.cast(ENTITY, request(before, "low_realm")).getFailure());
        assertEquals(before, host.current());
        var ref = TechniqueRegistry.reference(conditional);
        assertTrue(TechniqueKnowledge.contains(registry, high, ref));
    }

    @Test
    public void rejectsInvalidUnlockDeclarations() {
        // 普通式、未知经脉标签、重复标签都应在目录发布前报错。
        var unlock = InnateUnlock.newBuilder().addOpenedMeridianTags(SOURCE_TAG).build();
        var base = definition().toBuilder().setInnateUnlock(unlock).build();
        assertThrows(IllegalArgumentException.class, () -> registry(base));
        var innate = base.toBuilder().setInnate(true /* innate */);
        for (var tag : new String[] {SOURCE_TAG, "sample:absent"}) {
            var invalid = unlock.toBuilder().addOpenedMeridianTags(tag).build();
            assertThrows(IllegalArgumentException.class, () -> registry(innate.setInnateUnlock(invalid).build()));
        }
    }

    @Test
    public void rejectsOptionalMultipleAndUntypedOrganPayers() {
        // 已登记的器官标签可以用于普通绑定，付款槽另须指定种类且恰好绑定一个。
        var valid = organTechnique();
        var organ = valid.getSlots(0);
        var invalidSlots = List.of(organ.toBuilder().clearKind().build(),
                organ.toBuilder().setMinimumCount(0).build(), organ.toBuilder().setMaximumCount(2).build());
        for (var invalid : invalidSlots) {
            var candidate = valid.toBuilder().setSlots(0, invalid).build();
            assertThrows(IllegalArgumentException.class, () -> registry(candidate));
        }
        var second = organ.toBuilder().setId("other").build();
        var secondCost = valid.getCost().getQi(0).toBuilder().setSlotId(second.getId()).build();
        var bothFees = valid.getCost().toBuilder().addQi(secondCost).build();
        var twoPayers = valid.toBuilder()
                .addSlots(second)
                .setCost(bothFees)
                .build();
        assertThrows(IllegalArgumentException.class, () -> registry(twoPayers));
    }

    @Test
    public void organFeesReleaseOriginalQiOnceAndRollBackTogether() {
        // 两笔费用共用一个器官；第一笔能付、第二笔不足时也不释放第一笔。
        var base = organTechnique();
        var fee = base.getCost().getQi(0);
        var fees = base.getCost().toBuilder().addQi(fee).build();
        var definition = base.toBuilder().setCost(fees).build();
        var registry = registry(definition);
        var casting = new TechniqueCasting(registry);
        var snapshot = BodyProtobuf.toMessage(body(Shape.PAIRED)).toBuilder();
        var fire = inventory().getEntries(0);
        var buffer = snapshot.getOrganStatesBuilder(0).getBufferBuilder();
        buffer.addEntries(fire.toBuilder().setAmount(COST_QI));
        var poor = new MemoryHost(BodyProtobuf.fromMessage(snapshot.build()));
        var before = poor.current();
        var declined = casting.cast(poor, request(before, "poor"));
        assertEquals(TechniqueFailure.INSUFFICIENT_QI, declined.getFailure());
        assertEquals(before, poor.current());
        assertEquals(0, poor.activitySubmissions);
        assertEquals(0, poor.store.environmentQi().totalQi(), 0);

        buffer.setEntries(0, fire);
        var rich = new MemoryHost(BodyProtobuf.fromMessage(snapshot.build()));
        var request = request(rich.current(), "rich");
        var result = casting.cast(rich, request);
        assertEquals(result.getReason(), CastPhase.WARMUP, result.getPhase());
        var released = rich.store.environmentQi();
        var expected = fire.toBuilder().setAmount(COST_QI + COST_QI).build();
        assertEquals(expected, BodyProtobuf.toInventory(released).getEntries(0));
        assertEquals(expected.getAmount(), released.totalQi(), 0);
        assertTrue(casting.cast(rich, request).getReplayed());
        assertEquals(1, rich.activitySubmissions);
        assertEquals(released, rich.store.environmentQi());
    }

    private static TechniqueDefinition organTechnique() {
        // 同一器官类型用于精确属性费用，保留样本的正式时序和施放身份。
        var base = definition();
        var organ = slot("payer", ModuleKind.ORGAN, ORGAN_TAG).toBuilder().setKind("sample:control").build();
        var fee = base.getCost().getQi(0).toBuilder().setSlotId(organ.getId()).build();
        var cost = TechniqueCost.newBuilder().addQi(fee).build();
        return base.toBuilder()
                .clearSlots()
                .addSlots(organ)
                .setCost(cost)
                .build();
    }
}
