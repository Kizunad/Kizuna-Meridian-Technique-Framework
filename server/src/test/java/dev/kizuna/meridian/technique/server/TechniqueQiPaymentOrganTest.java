package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import java.util.List;
import org.junit.Test;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public final class TechniqueQiPaymentOrganTest {
    private static final String TEMPLATE = templateReference(Shape.PAIRED).getId();
    private static final int CHANNELS = 2;

    @Test
    public void chargesOriginalTotalOnceForAllDistributions() {
        // 原绑定两条经脉，逐脉收两份；均摊与按余额分摊都只付一份总价。
        for (var distribution : List.of(QiDistribution.EACH, QiDistribution.EVEN, QiDistribution.BY_BALANCE)) {
            var definition = multiChannel(distribution);
            var registry = registered(definition);
            var before = fundedOrgan();
            var host = new MemoryHost(before);
            var binding = TechniqueBinder.bind(before, definition).getBinding();
            assertTrue(TechniqueAvailability.affordable(before, definition, binding, registry.addonCostPayers()));
            var request = request(before, "organ_fee");
            var casting = new TechniqueCasting(registry);
            var result = casting.cast(host, request);
            assertEquals(result.getReason(), CastPhase.WARMUP, result.getPhase());
            assertEquals(CHANNELS, binding.getSlots(0).getModuleIdsCount());
            double expected = COST_QI;
            if (distribution == QiDistribution.EACH) {
                expected = COST_QI * CHANNELS;
            }
            var after = host.current();
            assertEquals(expected, before.organQiTotal() - after.organQiTotal(), 0);
            assertEquals(before.meridianStates(), after.meridianStates());
            assertTrue(casting.cast(host, request).getReplayed());
            assertEquals(1, host.activitySubmissions);
            assertEquals(expected, host.store.environmentQi().totalQi(), 0);
        }
    }

    @Test
    public void refusesEmptyBrokenOrMissingOrganAndClosedChannels() {
        // 经脉有气也不能替空丹垫款；经脉未开或付款器官失能均不能用库存绕过。
        var definition = multiChannel(QiDistribution.EACH);
        var registry = registered(definition);
        var funded = fundedOrgan();
        var bound = TechniqueBinder.bind(funded, definition).getBinding();
        var empty = body(Shape.PAIRED);
        assertRejected(registry, empty, bound, TechniqueFailure.INSUFFICIENT_QI);
        var injured = BodyProtobuf.toMessage(funded).toBuilder();
        injured.getOrganStatesBuilder(0).setHp(0);
        assertRejected(registry, BodyProtobuf.fromMessage(injured.build()), bound, TechniqueFailure.MODULE_UNAVAILABLE);
        var closed = BodyProtobuf.toMessage(funded).toBuilder();
        for (var channel : closed.getMeridianStatesBuilderList()) {
            channel.getOpeningBuilder().setStage(MeridianOpeningStage.UNOPENED)
                    .setProgress(0);
        }
        assertRejected(registry, BodyProtobuf.fromMessage(closed.build()), bound, TechniqueFailure.MERIDIAN_NOT_OPENED);
        var absent = new TechniqueAddonCostPayers();
        absent.registerQiPaymentOrgan(TEMPLATE, "absent");
        assertRejected(registry(definition, absent), funded, bound, TechniqueFailure.MISSING_MODULE);
    }

    @Test
    public void otherTemplateStillUsesLumenAndRegistryFreezes() {
        // 一个模板的登记不影响另一个模板；注册表快照及合并后都保留唯一登记。
        var definition = definition();
        var registry = registered(definition);
        var before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        var result = new TechniqueCasting(registry).cast(host, request(before, "ordinary_fee"));
        assertEquals(result.getReason(), CastPhase.WARMUP, result.getPhase());
        var after = host.current();
        assertEquals(COST_QI, before.lumenQiTotal() - after.lumenQiTotal(), 0);
        assertEquals(before.organQiTotal(), after.organQiTotal(), 0);
        var catalogs = new TechniqueCatalogs();
        catalogs.register("sample:merged", registry);
        var merged = catalogs.build().addonCostPayers();
        assertEquals(CONTROL_ORGAN, merged.qiPaymentOrgan(body(Shape.PAIRED)).orElseThrow());
        assertThrows(IllegalStateException.class, () -> merged.registerQiPaymentOrgan("sample:other", CONTROL_ORGAN));
        var payers = new TechniqueAddonCostPayers();
        payers.registerQiPaymentOrgan(TEMPLATE, CONTROL_ORGAN);
        assertThrows(IllegalArgumentException.class, () -> payers.registerQiPaymentOrgan(TEMPLATE, "another"));
    }

    private static TechniqueDefinition multiChannel(QiDistribution distribution) {
        // 仅保留经脉与真元费用，使器官失能和能否付款可独立验证。
        var base = definition();
        var slot = base.getSlots(0).toBuilder().setMaximumCount(CHANNELS).build();
        var cost = base.getCost();
        var fee = cost.getQi(0).toBuilder().setDistribution(distribution).build();
        var qiOnly = cost.toBuilder().clearWork().setQi(0, fee).build();
        return base.toBuilder()
                .clearSlots()
                .addSlots(slot)
                .setCost(qiOnly)
                .build();
    }

    private static TechniqueRegistry registered(TechniqueDefinition definition) {
        // 费用登记与正式内容共享同一套冻结和加载入口。
        var payers = new TechniqueAddonCostPayers();
        payers.registerQiPaymentOrgan(TEMPLATE, CONTROL_ORGAN);
        return registry(definition, payers);
    }

    private static TechniqueRegistry registry(TechniqueDefinition definition, TechniqueAddonCostPayers payers) {
        // 测试目录只装入这一式。
        var source = new TechniqueCatalogSource("sample:payment", addons(), catalog(definition));
        return TechniqueRegistry.loadSources(List.of(source), payers);
    }

    private static void assertRejected(TechniqueRegistry registry, BodySnapshot body, TechniqueBinding binding,
                                       TechniqueFailure expected) {
        // 即使预检拿到了先前健康时的绑定，实际身体仍必须能付款。
        var definition = registry.findLatest(TECHNIQUE_ID).orElseThrow();
        assertFalse(TechniqueAvailability.affordable(body, definition, binding, registry.addonCostPayers()));
        var host = new MemoryHost(body);
        var reply = new TechniqueCasting(registry).cast(host, request(body, "reject_fee"));
        assertEquals(reply.getReason(), expected, reply.getFailure());
        assertEquals(body, host.current());
        assertEquals(0, host.activitySubmissions);
    }
}
