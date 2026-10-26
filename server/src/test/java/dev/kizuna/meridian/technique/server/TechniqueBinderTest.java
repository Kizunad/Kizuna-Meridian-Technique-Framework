package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;
import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueBinderTest {
    private static final long CHANGED_REVISION = 17;
    private static final double WEAK_STRENGTH = 0.5;

    @Test
    public void bindsDifferentBodiesDeterministicallyWithRevision() {
        // 同一功法选择不同构型中的实际模块，且结果固定为读取时的身体版本。
        TechniqueDefinition definition = definition();
        for (Shape shape : Shape.values()) {
            BodySnapshot body = body(shape);
            BindingOutcome outcome = TechniqueBinder.bind(body, definition);
            assertTrue(outcome.getReason(), outcome.hasBinding());
            String selected = "a";
            if (shape == Shape.TENDRIL) {
                selected = "tendril";
            }
            assertEquals(body.revision(), outcome.getBinding().getRevision());
            assertEquals(body.id().value(), outcome.getBinding().getBodyId());
            assertEquals(List.of(selected), outcome.getBinding().getSlots(0).getModuleIdsList());
            assertEquals(List.of(selected), outcome.getBinding().getSlots(1).getModuleIdsList());
        }
    }

    @Test
    public void bindingReportsUnopenedUnavailableAndRealm() {
        // 开拓、失能和境界不足各有原因码和中文说明。
        BodySnapshot body = body(Shape.TENDRIL);
        var message = BodyProtobuf.toMessage(body);
        MeridianOpening unopened = MeridianOpening.newBuilder()
                .setStage(MeridianOpeningStage.UNOPENED)
                .setProgress(0)
                .build();
        MeridianState closedState = message.getMeridianStates(0).toBuilder().setOpening(unopened).build();
        BodySnapshot closed = BodyProtobuf.fromMessage(message.toBuilder().setMeridianStates(0, closedState).build());
        assertFailure(closed, MERIDIAN_NOT_OPENED, "经脉未通");
        TissueState disabledState = message.getTissueStates(0).toBuilder().setHp(0).build();
        BodySnapshot disabled = BodyProtobuf.fromMessage(message.toBuilder().setTissueStates(0, disabledState).build());
        assertFailure(disabled, MODULE_UNAVAILABLE, "身体尚未准备好");
        BodySnapshot lowRealm = BodyProtobuf.fromMessage(message.toBuilder().setRealm(Realm.WANDERING_QI).build());
        assertFailure(lowRealm, REALM_TOO_LOW, "修为尚浅");
        TissueCapacity weakCapacity = message.getStructure().getTissues(0).getCapacity().toBuilder()
                .setStrengthNewtons(WEAK_STRENGTH)
                .build();
        TissueDefinition weakTissue = message.getStructure().getTissues(0).toBuilder()
                .setCapacity(weakCapacity)
                .build();
        BodyStructure weakStructure = message.getStructure().toBuilder().setTissues(0, weakTissue).build();
        BodySnapshot weak = BodyProtobuf.fromMessage(message.toBuilder().setStructure(weakStructure).build());
        assertFailure(weak, INSUFFICIENT_CAPABILITY, "过于疲惫");
    }

    @Test
    public void rebindsChangedRevisionAndSupportsOptionalSlots() {
        // 损伤后的身体重新选择仍可用候选，可选器官缺失不会阻止主体槽位绑定。
        TechniqueDefinition base = definition();
        var original = BodyProtobuf.toMessage(body(Shape.PAIRED));
        TissueState disabled = original.getTissueStates(0).toBuilder().setHp(0).build();
        var changed = original.toBuilder()
                .setRevision(CHANGED_REVISION)
                .setTissueStates(0, disabled)
                .build();
        BindingOutcome result = TechniqueBinder.bind(BodyProtobuf.fromMessage(changed), base);
        assertEquals(CHANGED_REVISION, result.getBinding().getRevision());
        assertEquals(List.of("b"), result.getBinding().getSlots(1).getModuleIdsList());
        TechniqueSlot optional = base.getSlots(2).toBuilder()
                .clearTags()
                .setKind("sample:control")
                .setMinimumCount(0)
                .build();
        TechniqueCost cost = base.getCost().toBuilder().removeWork(1).build();
        TechniqueDefinition definition = base.toBuilder()
                .setSlots(2, optional)
                .setCost(cost)
                .build();
        TechniqueDefinition loaded = registry(definition).find(TechniqueRegistry.reference(definition)).orElseThrow();
        var withoutOrgan = original.toBuilder().clearOrganStates();
        BodyStructure structure = original.getStructure().toBuilder().clearOrgans().build();
        withoutOrgan.setStructure(structure);
        BindingOutcome missing = TechniqueBinder.bind(BodyProtobuf.fromMessage(withoutOrgan.build()), loaded);
        assertTrue(missing.hasBinding());
        assertEquals(0, missing.getBinding().getSlots(2).getModuleIdsCount());
    }

    private static void assertFailure(BodySnapshot body, TechniqueFailure code, String reason) {
        // 绑定失败不能留下部分成功槽位，避免调用方误施放。
        BindingOutcome result = TechniqueBinder.bind(body, definition());
        assertFalse(result.hasBinding());
        assertEquals(code, result.getFailure());
        assertTrue(result.getReason(), result.getReason().contains(reason));
    }
}
