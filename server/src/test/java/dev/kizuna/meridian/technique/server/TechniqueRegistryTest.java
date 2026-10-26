package dev.kizuna.meridian.technique.server;

import com.google.protobuf.UnknownFieldSet;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueRegistryTest {
    private static final int UNKNOWN_FIELD = 99;
    private static final double EXCESS_SPEED = 101;

    @Test
    public void rejectsMissingUnits() {
        // 数值正确但单位缺省仍必须拒绝，不能从字段位置猜测单位。
        TechniqueDefinition base = definition();
        Quantity missingUnit = Quantity.newBuilder().setValue(COST_QI).build();
        QiCost qi = base.getCost().getQi(0).toBuilder().setAmount(missingUnit).build();
        TechniqueCost cost = base.getCost().toBuilder().setQi(0, qi).build();
        assertRejected(base.toBuilder().setCost(cost).build(), "单位");
        Quantity missingValue = Quantity.newBuilder().setUnit(Unit.QI).build();
        QiCost missing = qi.toBuilder().setAmount(missingValue).build();
        TechniqueCost missingCost = cost.toBuilder().setQi(0, missing).build();
        assertRejected(base.toBuilder().setCost(missingCost).build(), "缺少数值");
    }

    @Test
    public void rejectsOutOfRangeParameters() {
        // 非有限值、负费用和冲量上限都在目录边界拒绝。
        TechniqueDefinition base = definition();
        for (double amount : new double[] {-1, 0, Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
                TechniqueValues.MAX_QUANTITY + 1}) {
            Quantity quantity = quantity(amount, Unit.QI);
            QiCost qi = base.getCost().getQi(0).toBuilder().setAmount(quantity).build();
            TechniqueCost cost = base.getCost().toBuilder().setQi(0, qi).build();
            TechniqueDefinition invalid = base.toBuilder().setCost(cost).build();
            assertThrows(IllegalArgumentException.class, () -> registry(invalid));
        }
        Quantity speed = quantity(EXCESS_SPEED, Unit.METERS_PER_SECOND);
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(speed)
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setImpulse(impulse)
                .build();
        assertRejected(base.toBuilder().addEffects(effect).build(), "数值");
        assertRejected(base.toBuilder().setCooldownMillis(-1).build(), "毫秒");
    }

    @Test
    public void rejectsUnknownOperatorsIncludingWireFields() {
        // 未知 oneof 字段与未设置算子分别拒绝，二进制往返不能丢掉未知算子后放行。
        TechniqueDefinition base = definition();
        TechniqueEffect empty = TechniqueEffect.newBuilder().setAtMillis(0).build();
        assertRejected(base.toBuilder().addEffects(empty).build(), "算子");
        UnknownFieldSet.Field unknownValue = UnknownFieldSet.Field.newBuilder().addVarint(1).build();
        UnknownFieldSet fields = UnknownFieldSet.newBuilder().addField(UNKNOWN_FIELD, unknownValue).build();
        TechniqueEffect unknown = empty.toBuilder().setUnknownFields(fields).build();
        TechniqueCatalog catalog = catalog(base.toBuilder().addEffects(unknown).build());
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(catalog)));
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.decodeCatalog(catalog.toByteArray()));
    }

    @Test
    public void rejectsUnknownAndWrongModuleTags() {
        // 标签必须在对应种类的已登记模板中出现，格式正确并不表示已知。
        TechniqueDefinition base = definition();
        for (String tag : List.of("sample:missing", MUSCLE_TAG)) {
            TechniqueSlot slot = base.getSlots(0).toBuilder().clearTags().addTags(tag).build();
            assertRejected(base.toBuilder().setSlots(0, slot).build(), "未知标签");
        }
    }

    @Test
    public void rejectsCurvesEndingAfterCooldownIncludingDelayAndEveryModifier() {
        // 等于冷却边界合法；第二条曲线或生效延迟超出边界都必须被逐项发现。
        TechniqueDefinition base = definition();
        TissueModifier curve = modifier();
        TechniqueEffect atBoundary = effect(curve, COOLDOWN - CURVE_END);
        // 让施放持续到效果时刻，以便只检验冷却约束。
        CastPhases phases = base.getPhases().toBuilder().setRecoveryMillis(COOLDOWN).build();
        TechniqueDefinition valid = base.toBuilder().setPhases(phases).addEffects(atBoundary).build();
        assertTrue(registry(valid).find(TechniqueRegistry.reference(valid)).isPresent());
        TechniqueEffect delayed = atBoundary.toBuilder().setAtMillis(atBoundary.getAtMillis() + 1).build();
        assertRejected(valid.toBuilder().addEffects(delayed).build(), "晚于冷却");
        CurvePoint lateEnd = point(COOLDOWN + 1, 0);
        TissueModifier late = curve.toBuilder().setCurve(curve.getCurveCount() - 1, lateEnd).build();
        TechniqueEffect lateEffect = effect(late, 0);
        assertRejected(base.toBuilder().addEffects(lateEffect).build(), "晚于冷却");
    }

    @Test
    public void requiresCompleteCurveAndNeverAllowsRemovalInstructions() {
        // 功法目录遵循首尾归零的曲线约束，且协议没有提前撤销修正的算子。
        TechniqueDefinition base = definition();
        TissueModifier curve = modifier();
        CurvePoint peakAtStart = point(0, 1);
        TissueModifier nonzeroStart = curve.toBuilder().setCurve(0, peakAtStart).build();
        assertRejected(base.toBuilder().addEffects(effect(nonzeroStart, 0)).build(), "首尾");
        TissueModifier reversed = curve.toBuilder().setCurve(1, peakAtStart).build();
        assertRejected(base.toBuilder().addEffects(effect(reversed, 0)).build(), "严格递增");
        assertNull(TechniqueEffect.getDescriptor().findFieldByName("remove_modifiers"));
        assertNull(TechniqueEffect.getDescriptor().findFieldByName("restore_fatigue"));
        assertNull(TechniqueEffect.getDescriptor().findFieldByName("heal"));
        assertNull(TechniqueEffect.getDescriptor().findFieldByName("strike"));
        assertNull(TissueModifier.getDescriptor().findFieldByName("remove_modifiers"));
    }

    @Test
    public void validatesReferencesOpenedCostsAndImmutableCatalog() {
        // 未登记模板、错误类型与未开拓扣费声明都不能进入可用目录。
        TechniqueDefinition base = definition();
        TechniqueCatalog catalog = catalog(base);
        TechniqueCatalog missingTemplate = catalog.toBuilder().clearBindingTemplates().build();
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.load(addons(), List.of(missingTemplate)));
        TechniqueSlot closed = base.getSlots(0).toBuilder().setRequireOpened(false /* requireOpened */).build();
        assertRejected(base.toBuilder().setSlots(0, closed).build(), "已开拓");
        TechniqueSlot wrongKind = base.getSlots(1).toBuilder().setKind("sample:control").build();
        assertRejected(base.toBuilder().setSlots(1, wrongKind).build(), "种类");
        TechniqueRegistry registry = registry(base);
        assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
        TechniqueCatalog duplicate = catalog.toBuilder().addDefinitions(base).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(duplicate)));
    }

    static TechniqueEffect effect(TissueModifier modifier, long atMillis) {
        // 效果声明只用于校验，不接入 Framework 修正能力。
        return TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setTissueModifier(modifier)
                .build();
    }

    private static void assertRejected(TechniqueDefinition definition, String reason) {
        // 校验错误包含可直接定位的中文原因。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> registry(definition));
        assertTrue(failure.getMessage(), failure.getMessage().contains(reason));
    }
}
