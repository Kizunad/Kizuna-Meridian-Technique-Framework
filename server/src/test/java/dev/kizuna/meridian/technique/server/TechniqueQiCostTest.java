package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueQiCostTest {
    private static final int MERIDIAN_COUNT = 4;
    private static final double MINIMUM_TOTAL = MERIDIAN_COUNT * QiAccounting.MIN_TRANSFER_QI;
    private static final double FUNDED_BALANCE = MINIMUM_TOTAL * 2;
    private static final List<String> MODULE_IDS = List.of("d", "b", "a", "c");

    @Test
    public void fourMeridiansEachPayOneMinimumUnit() {
        // 每条经脉恰好只有一个最小单位，起手必须成功且逐条扣空，释放总量不变。
        TechniqueDefinition definition = evenDefinition(MINIMUM_TOTAL, MERIDIAN_COUNT);
        MemoryHost host = castMinimumCost(definition, MERIDIAN_COUNT, QiAccounting.MIN_TRANSFER_QI);
        for (var state : host.current().meridianStates()) {
            assertTrue(state.lumenQi().entries().isEmpty());
        }
    }

    @Test
    public void adjacentHigherTotalStillPaysAllFourMeridians() {
        // 相邻可表示总额保留尾差，每条扣费仍通过 Framework 最小单位校验。
        double total = Math.nextUp(MINIMUM_TOTAL);
        TechniqueDefinition definition = evenDefinition(total, MERIDIAN_COUNT);
        castMinimumCost(definition, MERIDIAN_COUNT, FUNDED_BALANCE);
    }

    @Test
    public void adjacentLowerTotalIsRejectedBeforeCasting() {
        // 四条经脉的总额不足四个最小单位时，在目录入口拒绝而非起手时失败。
        TechniqueDefinition definition = evenDefinition(Math.nextDown(MINIMUM_TOTAL), MERIDIAN_COUNT);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> registry(definition));
        assertTrue(error.getMessage().contains("最小可流转单位"));
    }

    @Test
    public void everyAllowedBindingCountUsesValidatedDistribution() {
        // 同一个目录声明允许一至四条经脉，实际绑定数量变化仍应成功并守恒。
        TechniqueDefinition definition = evenDefinition(MINIMUM_TOTAL, 1);
        for (int count = 1; count <= MERIDIAN_COUNT; count++) {
            castMinimumCost(definition, count, FUNDED_BALANCE);
        }
    }

    private static MemoryHost castMinimumCost(TechniqueDefinition definition, int count, double balance) {
        // 使用真实 Framework 活动处理器，核对每条账户变化与环境释放总额。
        BodySnapshot before = bodyWithMeridians(count, balance);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition));
        CastOutcome outcome = casting.cast(host, request(before, "minimum_even"));
        assertEquals(outcome.getReason(), TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED, outcome.getFailure());
        assertEquals(CastPhase.WARMUP, outcome.getPhase());
        double total = definition.getCost().getQi(0).getAmount().getValue();
        double remaining = 0;
        for (var state : host.current().meridianStates()) {
            double amount = state.lumenQi().totalQi();
            assertTrue("每条经脉都须实际扣费", amount < balance);
            remaining += amount;
        }
        assertEquals(total, host.store.environmentQi().totalQi(), 0);
        double initial = count * balance;
        assertEquals(initial, remaining + total, Math.ulp(initial));
        var activity = host.store.receipts().get(0).getCommand().getExecuteActivity();
        assertEquals(count, activity.getAccountsCount());
        return host;
    }

    private static TechniqueDefinition evenDefinition(double amount, int minimumCount) {
        // 费用只使用经脉，组织和器官做功不会掩盖最小流转边界。
        TechniqueDefinition base = definition();
        TechniqueSlot source = base.getSlots(0).toBuilder()
                .setMinimumCount(minimumCount)
                .setMaximumCount(MERIDIAN_COUNT)
                .build();
        Quantity quantity = quantity(amount, Unit.QI);
        QiCost qi = base.getCost().getQi(0).toBuilder()
                .setDistribution(QiDistribution.EVEN)
                .setAmount(quantity)
                .build();
        TechniqueCost cost = base.getCost().toBuilder()
                .clearWork()
                .setQi(0, qi)
                .build();
        return base.toBuilder()
                .setSlots(0, source)
                .setCost(cost)
                .build();
    }

    private static BodySnapshot bodyWithMeridians(int count, double balance) {
        // 倒序插入模块，避免分配结果偶然依赖身体定义中的原始排列。
        var message = BodyProtobuf.toMessage(body(Shape.PAIRED));
        var structure = message.getStructure().toBuilder().clearMeridians();
        var body = message.toBuilder().clearMeridianStates();
        BodyMessages.Qi entry = message.getMeridianStates(0).getLumenQi().getEntries(0).toBuilder()
                .setAmount(balance)
                .build();
        BodyMessages.QiInventory lumen = BodyMessages.QiInventory.newBuilder().addEntries(entry).build();
        for (String id : MODULE_IDS.subList(0, count)) {
            var definition = message.getStructure().getMeridians(0).toBuilder().setId(id).build();
            var state = message.getMeridianStates(0).toBuilder()
                    .setId(id)
                    .setLumenQi(lumen)
                    .build();
            structure.addMeridians(definition);
            body.addMeridianStates(state);
        }
        BodyMessages.BodyStructure builtStructure = structure.build();
        return BodyProtobuf.fromMessage(body.setStructure(builtStructure).build());
    }
}
