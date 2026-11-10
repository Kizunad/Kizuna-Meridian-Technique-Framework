package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCostMatching;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiDistribution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueSlot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.INSUFFICIENT_ATTRIBUTE_QI;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.INSUFFICIENT_QI;

import static dev.kizuna.meridian.technique.server.Fixtures.COST_QI;
import static dev.kizuna.meridian.technique.server.Fixtures.GAS_DENSITY;
import static dev.kizuna.meridian.technique.server.Fixtures.LIQUID_DENSITY;
import static dev.kizuna.meridian.technique.server.Fixtures.QI_AMOUNT;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WATER_AMOUNT;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.catalog;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.inventory;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueQiPaymentTest {
    private static final double TOTAL_COST = QI_AMOUNT + COST_QI;
    private static final double FREE_DENSITY = 0.05;
    private static final double TOLERANCE = 1e-9;
    private static final int UNKNOWN_MATCHING = 99;
    private static final int PAIRED_MERIDIANS = 2;
    private static final int PAYMENT_SAMPLES = 8;
    private static final int HAND_MERIDIANS = 6;
    private static final double SMALL_TOTAL_COST = 3;
    private static final double MEDIUM_TOTAL_COST = 8;
    private static final double MINIMUM_FRAGMENT_RATIO = 0.1;
    private static final double MAXIMUM_FRAGMENT_RATIO = 0.9;
    private static final long RANDOM_SEED = 837;
    private static final String FIRE = "meridian:fire";
    private static final String WATER = "meridian:water";
    private static final String METAL = "meridian:metal";
    private static final double RICH_FIRE = 30;
    private static final double POOR_FIRE = 10;
    private static final double BALANCE_COST = 8;
    private static final double RICH_SHARE = 6;
    private static final double POOR_SHARE = 2;

    @Test
    public void flexiblePaymentUsesMixedAttributesAndPhasesAndPreservesReleasedInventory() {
        // 单条库存不足时汇总气态火与液态水，释放仍保留各自原始密度和属性。
        QiCost cost = flexible(QiPhase.GAS, TOTAL_COST);
        BodySnapshot before = body(Shape.TENDRIL);
        MemoryHost host = castSuccessfully(before, withCost(cost));
        Qi fire = new Qi(Qi.QiAttribute.FIRE, QI_AMOUNT, GAS_DENSITY);
        Qi water = new Qi(Qi.QiAttribute.WATER, COST_QI, LIQUID_DENSITY);
        QiInventory expectedRelease = new QiInventory(List.of(fire, water));

        assertEquals(expectedRelease, host.store.environmentQi());
        var after = host.current().meridianStates().get(0);
        assertEquals(WATER_AMOUNT - COST_QI, after.lumenQi().totalQi(), TOLERANCE);
        assertEquals(before.meridianStates().get(0).wallQi(), after.wallQi());
        double accounted = after.lumenQi().totalQi() + host.store.environmentQi().totalQi();
        assertEquals(before.meridianStates().get(0).lumenQi().totalQi(), accounted, TOLERANCE);
    }

    @Test
    public void freeMinimumAcceptsTrueQiWithoutFreeResidualAndEvenChargesOnlyTotal() {
        // 真炁无属性库存不需要补造游离态；多条已开经脉只平摊声明总费用。
        Qi gas = new Qi(Qi.QiAttribute.NONE, QI_AMOUNT, GAS_DENSITY);
        BodySnapshot before = withInventory(body(Shape.PAIRED), new QiInventory(List.of(gas)));
        TechniqueDefinition base = withCost(flexible(QiPhase.FREE, COST_QI));
        TechniqueSlot source = base.getSlots(0).toBuilder()
                .setMinimumCount(PAIRED_MERIDIANS)
                .setMaximumCount(PAIRED_MERIDIANS)
                .build();
        TechniqueDefinition definition = base.toBuilder()
                .setSlots(0, source)
                .build();
        MemoryHost host = castSuccessfully(before, definition);

        assertEquals(COST_QI, host.store.environmentQi().totalQi(), TOLERANCE);
        assertEquals(GAS_DENSITY, host.store.environmentQi().entries().get(0).density(), 0);
        for (var state : host.current().meridianStates()) {
            assertEquals(QI_AMOUNT - COST_QI / PAIRED_MERIDIANS, state.lumenQi().totalQi(), TOLERANCE);
        }
    }

    @Test
    public void paymentOrderDoesNotDependOnInventoryMessageOrder() {
        // 相同库存仅交换消息顺序不会改变耗用的属性、相态或释放顺序。
        BodySnapshot original = body(Shape.TENDRIL);
        List<Qi> entries = original.meridianStates().get(0).lumenQi().entries();
        QiInventory reversed = new QiInventory(List.of(entries.get(1), entries.get(0)));
        BodySnapshot reordered = withInventory(original, reversed);
        TechniqueDefinition definition = withCost(flexible(QiPhase.FREE, TOTAL_COST));
        MemoryHost first = castSuccessfully(original, definition);
        MemoryHost second = castSuccessfully(reordered, definition);

        assertEquals(first.store.environmentQi(), second.store.environmentQi());
        assertEquals(first.current().meridianStates(), second.current().meridianStates());
    }

    @Test
    public void fractionalMixedPaymentAcceptsNonIntegralSharesWithoutRoundingRejection() {
        // 随机分段覆盖六脉平摊的非整数费用，尾项吸收剩余费用而不受重新求和尾差影响。
        var random = new Random(RANDOM_SEED);
        List<Double> totals = List.of(SMALL_TOTAL_COST, MEDIUM_TOTAL_COST, WATER_AMOUNT, COST_QI, TOTAL_COST);
        for (double total : totals) {
            double share = total / HAND_MERIDIANS;
            for (int sample = 0; sample < PAYMENT_SAMPLES; sample++) {
                double fragment = share * random.nextDouble(MINIMUM_FRAGMENT_RATIO, MAXIMUM_FRAGMENT_RATIO);
                Qi fire = new Qi(Qi.QiAttribute.FIRE, fragment, GAS_DENSITY);
                Qi water = new Qi(Qi.QiAttribute.WATER, share, LIQUID_DENSITY);
                QiInventory inventory = new QiInventory(List.of(fire, water));
                BodySnapshot before = withInventory(body(Shape.TENDRIL), inventory);
                MemoryHost host = castSuccessfully(before, withCost(flexible(QiPhase.FREE, share)));
                assertEquals(share, host.store.environmentQi().totalQi(), Math.ulp(share));
                assertEquals(inventory.totalQi(), host.current().meridianStates().get(0).lumenQi().totalQi()
                        + host.store.environmentQi().totalQi(), TOLERANCE);
            }
        }
    }

    @Test
    public void lowerPhaseAndInsufficientAggregateRejectWithoutWriting() {
        // 数量充足但相态过低与合计余额不足都不得扣款、记做功或开始动作。
        Qi free = new Qi(Qi.QiAttribute.NONE, QI_AMOUNT, FREE_DENSITY);
        BodySnapshot freeBody = withInventory(body(Shape.TENDRIL), new QiInventory(List.of(free)));
        assertUnpaid(freeBody, withCost(flexible(QiPhase.GAS, COST_QI)));
        double excessive = QI_AMOUNT + WATER_AMOUNT + COST_QI;
        assertUnpaid(body(Shape.TENDRIL), withCost(flexible(QiPhase.FREE, excessive)));
    }

    @Test
    public void elementMinimumPaysOnlyNamedAttributeAndAcceptsHigherPhase() {
        // 本行模式只扣指定属性；液态水满足气态下限，库存里的别的属性即使够也不抵价。
        MemoryHost fire = castSuccessfully(body(Shape.TENDRIL), withCost(element(FIRE, QiPhase.FREE, COST_QI)));
        Qi paidFire = new Qi(Qi.QiAttribute.FIRE, COST_QI, GAS_DENSITY);
        assertEquals(new QiInventory(List.of(paidFire)), fire.store.environmentQi());
        MemoryHost water = castSuccessfully(body(Shape.TENDRIL), withCost(element(WATER, QiPhase.GAS, COST_QI)));
        Qi paidWater = new Qi(Qi.QiAttribute.WATER, COST_QI, LIQUID_DENSITY);
        assertEquals(new QiInventory(List.of(paidWater)), water.store.environmentQi());
    }

    @Test
    public void elementMinimumRejectsUnattributedOtherElementLowerPhaseAndShortage() {
        // 无属性、别的行、相态不够和本行余额不足都报本行真元不足，且不落任何账。
        Qi unattributed = new Qi(Qi.QiAttribute.NONE, QI_AMOUNT, LIQUID_DENSITY);
        BodySnapshot neutral = withInventory(body(Shape.TENDRIL), new QiInventory(List.of(unattributed)));
        assertUnpaid(neutral, withCost(element(FIRE, QiPhase.FREE, COST_QI)), INSUFFICIENT_ATTRIBUTE_QI);
        List<QiCost> invalid = List.of(
                element(METAL, QiPhase.FREE, COST_QI),
                element(FIRE, QiPhase.LIQUID, COST_QI),
                element(FIRE, QiPhase.FREE, TOTAL_COST));
        for (QiCost cost : invalid) {
            assertUnpaid(body(Shape.TENDRIL), withCost(cost), INSUFFICIENT_ATTRIBUTE_QI);
        }
        assertEquals("身上没有足够的本行真元", TechniqueErrors.playerReason(INSUFFICIENT_ATTRIBUTE_QI));
    }

    @Test
    public void byBalanceSplitsTotalByPayableBalanceAndSkipsEmptyMeridians() {
        // 两脉本行余额 3:1 时按比例分摊；另一脉没有本行真元时由有余额的一脉独付，合计不足时拒绝。
        TechniqueDefinition balanced = withPairedSource(withCost(balance(BALANCE_COST)));
        Qi rich = new Qi(Qi.QiAttribute.FIRE, RICH_FIRE, GAS_DENSITY);
        Qi poor = new Qi(Qi.QiAttribute.FIRE, POOR_FIRE, GAS_DENSITY);
        Qi water = new Qi(Qi.QiAttribute.WATER, WATER_AMOUNT, LIQUID_DENSITY);
        BodySnapshot uneven = withInventories(body(Shape.PAIRED), List.of(rich, water), List.of(poor, water));
        MemoryHost split = castSuccessfully(uneven, balanced);
        assertEquals(RICH_FIRE - RICH_SHARE, fire(split, "a"), TOLERANCE);
        assertEquals(POOR_FIRE - POOR_SHARE, fire(split, "b"), TOLERANCE);
        assertEquals(BALANCE_COST, split.store.environmentQi().totalQi(), TOLERANCE);

        BodySnapshot oneSided = withInventories(body(Shape.PAIRED), List.of(rich, water), List.of(water));
        MemoryHost single = castSuccessfully(oneSided, balanced);
        assertEquals(RICH_FIRE - BALANCE_COST, fire(single, "a"), TOLERANCE);
        assertEquals(WATER_AMOUNT, lumen(single, "b").totalQi(), TOLERANCE);

        BodySnapshot shortage = withInventories(body(Shape.PAIRED), List.of(poor), List.of(water));
        assertUnpaid(shortage, withPairedSource(withCost(balance(RICH_FIRE))), INSUFFICIENT_ATTRIBUTE_QI);
    }

    @Test
    public void defaultExactMatchingKeepsAttributePhaseAndSingleVariantRequirements() {
        // 缺省匹配仍精确支付火气态，不能借用其他属性、更高相态或合计余额。
        QiCost exact = definition().getCost().getQi(0);
        assertEquals(QiCostMatching.EXACT_VARIANT, exact.getMatching());
        MemoryHost paid = castSuccessfully(body(Shape.TENDRIL), withCost(exact));
        Qi expected = new Qi(Qi.QiAttribute.FIRE, COST_QI, GAS_DENSITY);
        assertEquals(new QiInventory(List.of(expected)), paid.store.environmentQi());
        QiCost wrongAttribute = exact.toBuilder().setAttribute("meridian:none").build();
        QiCost wrongPhase = exact.toBuilder().setPhase(QiPhase.FREE).build();
        QiCost excessive = exact.toBuilder().setAmount(quantity(TOTAL_COST, Unit.QI)).build();
        for (QiCost invalid : List.of(wrongAttribute, wrongPhase, excessive)) {
            assertUnpaid(body(Shape.TENDRIL), withCost(invalid));
        }
    }

    @Test
    public void catalogRoundTripValidatesMatchingModeAttributeAndMinimumPhase() {
        // 新字段通过真实 Protobuf 目录边界；未知模式、歧义属性和未知相态均拒绝。
        QiCost flexible = flexible(QiPhase.FREE, COST_QI);
        TechniqueCatalog catalog = catalog(withCost(flexible));
        TechniqueCatalog decoded = TechniqueProtocol.decodeCatalog(catalog.toByteArray());
        assertEquals(catalog, decoded);
        TechniqueRegistry.load(addons(), List.of(decoded));
        QiCost unknown = flexible.toBuilder().setMatchingValue(UNKNOWN_MATCHING).build();
        QiCost ambiguous = flexible.toBuilder().setAttribute("meridian:none").build();
        QiCost missingPhase = flexible.toBuilder().clearPhase().build();
        QiCost missingExactAttribute = definition().getCost().getQi(0).toBuilder().clearAttribute().build();
        for (QiCost invalid : List.of(unknown, ambiguous, missingPhase, missingExactAttribute)) {
            TechniqueCatalog invalidCatalog = catalog(withCost(invalid));
            TechniqueCatalog parsed = TechniqueProtocol.decodeCatalog(invalidCatalog.toByteArray());
            assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(parsed)));
        }
    }

    private static QiCost flexible(QiPhase minimumPhase, double amount) {
        // 费用总额和最低相态显式填写，任意属性模式不携带属性限制。
        return definition().getCost().getQi(0).toBuilder()
                .clearAttribute()
                .setPhase(minimumPhase)
                .setMatching(QiCostMatching.ANY_ATTRIBUTE_AT_LEAST_PHASE)
                .setAmount(quantity(amount, Unit.QI))
                .setDistribution(QiDistribution.EVEN)
                .build();
    }

    private static QiCost element(String attribute, QiPhase minimumPhase, double amount) {
        // 本行模式固定属性并给出最低相态，单脉样本逐脉收取声明费用。
        return definition().getCost().getQi(0).toBuilder()
                .setAttribute(attribute)
                .setPhase(minimumPhase)
                .setMatching(QiCostMatching.ATTRIBUTE_AT_LEAST_PHASE)
                .setAmount(quantity(amount, Unit.QI))
                .build();
    }

    private static QiCost balance(double amount) {
        // 火行游炁以上的总费用按两脉可付余额分摊。
        return element(FIRE, QiPhase.FREE, amount).toBuilder()
                .setDistribution(QiDistribution.BY_BALANCE)
                .build();
    }

    private static TechniqueDefinition withPairedSource(TechniqueDefinition base) {
        // 来源槽绑定两条经脉，才能比较分摊比例。
        TechniqueSlot source = base.getSlots(0).toBuilder()
                .setMinimumCount(PAIRED_MERIDIANS)
                .setMaximumCount(PAIRED_MERIDIANS)
                .build();
        return base.toBuilder()
                .setSlots(0, source)
                .build();
    }

    private static BodySnapshot withInventories(BodySnapshot body, List<Qi> first, List<Qi> second) {
        // 按结构顺序分别替换两条经脉的内腔，外壁和其余状态不变。
        var source = BodyProtobuf.toMessage(body);
        var replacement = source.toBuilder().clearMeridianStates();
        List<List<Qi>> inventories = List.of(first, second);
        for (int index = 0; index < source.getMeridianStatesCount(); index++) {
            var lumen = BodyProtobuf.toInventory(new QiInventory(inventories.get(index)));
            var updated = source.getMeridianStates(index).toBuilder().setLumenQi(lumen).build();
            replacement.addMeridianStates(updated);
        }
        return BodyProtobuf.fromMessage(replacement.build());
    }

    private static QiInventory lumen(MemoryHost host, String meridianId) {
        // 按局部 ID 查找，避免依赖快照列表顺序。
        return host.current().meridianStates().stream()
                .filter(state -> state.id().value().equals(meridianId))
                .findFirst()
                .orElseThrow()
                .lumenQi();
    }

    private static double fire(MemoryHost host, String meridianId) {
        // 只统计火行余额，确认分摊没有动到同脉的其他属性。
        return lumen(host, meridianId).entries().stream()
                .filter(qi -> qi.attribute().equals(Qi.QiAttribute.FIRE))
                .mapToDouble(Qi::amount)
                .sum();
    }

    private static TechniqueDefinition withCost(QiCost qi) {
        // 只替换费用声明，其余槽位和行为沿用公开施放样本。
        TechniqueDefinition base = definition();
        TechniqueCost cost = base.getCost().toBuilder().setQi(0, qi).build();
        return base.toBuilder().setCost(cost).build();
    }

    private static BodySnapshot withInventory(BodySnapshot body, QiInventory inventory) {
        // 通过真实快照校验替换内腔库存，保留其他身体状态与已学记录。
        var source = BodyProtobuf.toMessage(body);
        var replacement = source.toBuilder().clearMeridianStates();
        var lumen = BodyProtobuf.toInventory(inventory);
        for (var state : source.getMeridianStatesList()) {
            var updated = state.toBuilder().setLumenQi(lumen).build();
            replacement.addMeridianStates(updated);
        }
        return BodyProtobuf.fromMessage(replacement.build());
    }

    private static MemoryHost castSuccessfully(BodySnapshot before, TechniqueDefinition definition) {
        // 真实目录、施放编排与 Framework 活动处理器共同验证成功扣款。
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition));
        CastOutcome outcome = casting.cast(host, request(before, "payment"));
        assertEquals(outcome.getReason(), TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED, outcome.getFailure());
        assertEquals(CastPhase.WARMUP, outcome.getPhase());
        assertEquals(1, host.activitySubmissions);
        return host;
    }

    private static void assertUnpaid(BodySnapshot before, TechniqueDefinition definition) {
        // 任意属性与精确模式的余额问题沿用通用真元不足原因。
        assertUnpaid(before, definition, INSUFFICIENT_QI);
    }

    private static void assertUnpaid(BodySnapshot before, TechniqueDefinition definition, TechniqueFailure failure) {
        // 失败须发生在原子提交前，不能留下冷却、做功、释放或部分余额更新。
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition));
        CastOutcome outcome = casting.cast(host, request(before, "unpaid"));
        assertEquals(failure, outcome.getFailure());
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
        assertTrue(host.store.environmentQi().entries().isEmpty());
    }
}
