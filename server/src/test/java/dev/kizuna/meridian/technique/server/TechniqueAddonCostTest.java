package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import com.google.protobuf.Int64Value;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AddonCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public final class TechniqueAddonCostTest {
    private static final String PAYER_KEY = "sample:gold";
    private static final String FAILURE_KEY = "sample.gold.insufficient";
    private static final double GOLD_COST = 7;
    private static final long GOLD_BALANCE = 10;
    private static final double QI_TOLERANCE = 1e-9;

    @Test
    public void addonCostAndQiCommitInOneActivity() {
        // 一次功法活动同时提交附属费用进度和真元消耗。
        TechniqueDefinition definition = addonDefinition();
        BodySnapshot before = withGold(body(Shape.PAIRED), GOLD_BALANCE);
        MemoryHost host = new MemoryHost(before);
        TechniqueRegistry registry = registry(definition, payer());

        CastOutcome outcome = new TechniqueCasting(registry).cast(host, request(before, "addon_paid"));

        assertEquals(TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED, outcome.getFailure());
        assertEquals(before.revision() + 1, host.current().revision());
        assertEquals(1, host.activitySubmissions);
        assertEquals(COST_QI, before.totalQi() - host.current().totalQi(), QI_TOLERANCE);
        assertEquals(GOLD_BALANCE - (long) GOLD_COST, gold(host.current()));
        assertEquals(1, host.store.receipts().get(0).getCommand().getExecuteActivity().getAccountsCount());
        assertEquals(2, host.store.receipts().get(0).getCommand().getExecuteActivity().getProgressCount());
    }

    @Test
    public void insufficientAddonCostDoesNotSpendQiOrSubmitActivity() {
        // 附属费用不足时返回拒绝，宿主状态与活动提交数保持不变。
        TechniqueDefinition definition = addonDefinition();
        BodySnapshot before = withGold(body(Shape.PAIRED), (long) GOLD_COST - 1);
        MemoryHost host = new MemoryHost(before);
        TechniqueRegistry registry = registry(definition, payer());

        CastOutcome outcome = new TechniqueCasting(registry).cast(host, request(before, "addon_poor"));

        assertEquals(TechniqueFailure.INSUFFICIENT_ADDON_COST, outcome.getFailure());
        assertEquals(FAILURE_KEY, outcome.getTranslationKey());
        assertEquals("金量不足", outcome.getReason());
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
    }

    @Test
    public void catalogRejectsAddonCostWithoutRegisteredPayer() {
        // 未登记支付方的附属费用目录不能进入注册表。
        TechniqueDefinition definition = addonDefinition();
        TechniqueCatalogSource source = new TechniqueCatalogSource("sample:addon-cost", addons(), catalog(definition));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.loadSources(List.of(source), TechniqueAddonCostPayers.empty()));

        assertTrue(failure.getMessage(), failure.getMessage().contains("支付方未登记"));
    }

    private static TechniqueDefinition addonDefinition() {
        // 构造带附属金量费用的最小功法定义。
        AddonCost addonCost = AddonCost.newBuilder()
                .setKey(PAYER_KEY)
                .setAmount(quantity(GOLD_COST, Unit.GOLD))
                .build();
        TechniqueCost cost = definition().getCost().toBuilder()
                .addAddonCosts(addonCost)
                .build();
        return definition().toBuilder()
                .setCost(cost)
                .build();
    }

    private static TechniqueAddonCostPayers payer() {
        // 测试支付方从身体附属进度读取并计算剩余金量。
        TechniqueAddonCostPayers payers = new TechniqueAddonCostPayers();
        payers.register(new TechniqueAddonCostPayer() {
            @Override
            public String key() {
                // 使用测试目录声明的稳定费用键。
                return PAYER_KEY;
            }

            @Override
            public Payment pay(BodySnapshot body, AddonCost cost) {
                // 金量不足时拒绝，否则返回新的候选金量进度。
                long available = gold(body);
                long requested = (long) cost.getAmount().getValue();
                if (available < requested) {
                    return Payment.rejected(FAILURE_KEY, "金量不足");
                }
                return Payment.accepted(Any.pack(Int64Value.of(available - requested)));
            }
        });
        return payers;
    }

    private static BodySnapshot withGold(BodySnapshot body, long amount) {
        // 仅在测试快照中写入支付方所需的金量进度。
        BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(body).toBuilder()
                .putAddonProgress(PAYER_KEY, Any.pack(Int64Value.of(amount)))
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static long gold(BodySnapshot body) {
        // 解码测试金量，错误载荷应让测试立即失败。
        Any encoded = BodyProtobuf.toMessage(body).getAddonProgressOrDefault(PAYER_KEY, Any.getDefaultInstance());
        try {
            return encoded.unpack(Int64Value.class).getValue();
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new IllegalArgumentException("测试金量进度无效", failure);
        }
    }

    private static TechniqueRegistry registry(TechniqueDefinition definition, TechniqueAddonCostPayers payers) {
        // 使用测试支付方加载单个测试目录。
        TechniqueCatalogSource source = new TechniqueCatalogSource("sample:addon-cost", addons(), catalog(definition));
        return TechniqueRegistry.loadSources(List.of(source), TechniqueTemplateGroups.empty(), payers);
    }
}
