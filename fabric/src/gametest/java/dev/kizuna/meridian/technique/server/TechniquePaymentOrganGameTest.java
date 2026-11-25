package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

import static dev.kizuna.meridian.technique.server.Fixtures.*;

public final class TechniquePaymentOrganGameTest implements TechniqueAddonCostEntrypoint {
    @Override
    public void registerPayers(TechniqueAddonCostPayers payers) {
        // 仅对成对构型登记付款器官，触须构型继续由经脉供元。
        payers.registerQiPaymentOrgan(templateReference(Shape.PAIRED).getId(), CONTROL_ORGAN);
    }

    @GameTest
    public void registeredBodyDebitsOrgan(GameTestHelper test) {
        // 两处库存都充足，回执与余额共同表明服务端只动用了器官。
        var before = fundedOrgan();
        var result = cast(test, before);
        test.assertTrue(result.outcome().getPhase() == CastPhase.WARMUP, "器官付费起手失败 " + result.outcome());
        var after = result.body();
        test.assertTrue(before.organQiTotal() - after.organQiTotal() == COST_QI
                && before.meridianStates().equals(after.meridianStates()), "未由登记器官独付");
        test.succeed();
    }

    @GameTest
    public void emptyRegisteredOrganRefusesFundedLumen(GameTestHelper test) {
        // 经脉足额，空器官仍应整笔拒绝并保留身体和冷却。
        var before = body(Shape.PAIRED);
        var result = cast(test, before);
        test.assertTrue(result.outcome().getFailure() == TechniqueFailure.INSUFFICIENT_QI
                && before.equals(result.body()), "空器官错误起手或改变身体 " + result.outcome());
        test.succeed();
    }

    @GameTest
    public void unregisteredBodyDebitsBoundLumen(GameTestHelper test) {
        // 相同功法在未登记模板上使用原经脉库存。
        var before = body(Shape.TENDRIL);
        var result = cast(test, before);
        test.assertTrue(result.outcome().getPhase() == CastPhase.WARMUP, "原经脉付费失败 " + result.outcome());
        var after = result.body();
        test.assertTrue(before.lumenQiTotal() - after.lumenQiTotal() == COST_QI
                && before.organQiTotal() == after.organQiTotal(), "未登记身体的付款账户错误");
        test.succeed();
    }

    private static Receipt cast(GameTestHelper test, BodySnapshot before) {
        // 通过正式 Fabric 服务、宿主字节接口及 Framework 权威身体活动提交。
        var entity = test.spawnWithNoFreeWill(EntityTypes.PIG, 1, 1, 1);
        var id = entity.getUUID();
        var host = new MemoryHost(before);
        TechniqueGameTestHost.bind(id, host);
        var service = TechniqueFabric.service();
        try {
            var outcome = service.cast(id, request(before, "payment_organ"));
            return new Receipt(outcome, host.current());
        } finally {
            service.cancelOnUnload(id);
            TechniqueGameTestHost.unbind(id);
        }
    }

    private record Receipt(CastOutcome outcome, BodySnapshot body) { }
}
