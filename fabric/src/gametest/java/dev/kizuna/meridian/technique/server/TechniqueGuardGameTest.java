package dev.kizuna.meridian.technique.server;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.fabric.world.TechniqueCastStatusSync;
import dev.kizuna.meridian.technique.fabric.world.TechniqueInputBuffer;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.GuardTechniqueFixtures.*;
import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/** 玩家经正式连接、输入缓冲和状态同步入口使用测试守式。 */
public final class TechniqueGuardGameTest {
    private static final int NAME_LENGTH = 8;
    private static final int SETTLE_TICKS = 2;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void innateGuardHoldsAndPublishesRealRecovery(GameTestHelper context) {
        // 空知识起守，未知边界同步，收势全程占用，最后解锁。
        PlayerGuard player = new PlayerGuard(context);
        TechniqueService service = TechniqueFabric.service();
        CastRequest press = player.request(ID);
        context.assertTrue(TechniqueInputBuffer.press(player.player, press).outcome().getFailure()
                == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED, "天生守式失败");
        context.assertTrue(player.progress().getKnownCount() == 0, "天生式写入学习记录");
        context.assertTrue(player.status().getEndTimesUnknown(), "未知结束没有同步");
        context.assertTrue(service.activeGuard(player.id()).isEmpty(), "起手提前守住");
        service.learn(player.id(), TECHNIQUE_ID);
        var ordinary = TechniqueInputBuffer.press(player.player, player.request(TECHNIQUE_ID));
        context.assertTrue(!ordinary.buffered() && ordinary.outcome().getFailure()
                == TechniqueFailure.ALREADY_CASTING, "未知收势前排队了普通式");
        player.host.nowMillis = LONG_HOLD;
        service.tick();
        context.assertTrue(service.activeGuard(player.id()).isPresent(), "超过一天守势提前消失");
        service.endActive(player.id(), release(press));
        CastStatus status = player.status();
        context.assertTrue(!status.getEndTimesUnknown() && status.getActiveEndMillis() == LONG_HOLD
                && status.getRecoveryEndMillis() == LONG_HOLD + RECOVERY, "松开没有同步真实收势");
        player.host.nowMillis = LONG_HOLD + RECOVERY - 1;
        context.assertTrue(service.cast(player.id(), player.request(TECHNIQUE_ID)).getFailure()
                == TechniqueFailure.ALREADY_CASTING, "最后一毫秒收势被顶掉");
        player.host.nowMillis++;
        service.tick();
        context.assertTrue(!player.progress().hasActiveCast() && player.host.current().actions().isEmpty(), "未清动作");
        context.assertTrue(player.status().getTechniqueId().isEmpty(), "结束未同步");
        player.close();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void busyGuardPressIsNeverBuffered(GameTestHelper context) {
        // 普通式快结束时按守立即松开，之后不能突然补放守。
        PlayerGuard player = new PlayerGuard(context);
        TechniqueService service = TechniqueFabric.service();
        service.learn(player.id(), TECHNIQUE_ID);
        TechniqueInputBuffer.press(player.player, player.request(TECHNIQUE_ID));
        player.host.nowMillis = WARMUP;
        CastRequest guardPress = player.request(ID);
        var refused = TechniqueInputBuffer.press(player.player, guardPress);
        context.assertTrue(refused.outcome().getFailure() == TechniqueFailure.ALREADY_CASTING
                && !refused.buffered() && TechniqueInputBuffer.buffered(player.id()).isEmpty(), "守式进入了输入队列");
        TechniqueInputBuffer.clear(player.id(), guardPress.getCastId());
        service.endActive(player.id(), release(guardPress));
        player.host.nowMillis = WARMUP + ACTIVE + RECOVERY;
        context.runAfterDelay(SETTLE_TICKS, () -> {
            context.assertTrue(!player.progress().hasActiveCast(), "忙时的守式后来补放了");
            player.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shortPressAndHardControlNeverLeaveGuard(GameTestHelper context) {
        // 短按的同步边界立即确定；普通受击与硬控分别走既定清理规则。
        PlayerGuard player = new PlayerGuard(context);
        TechniqueService service = TechniqueFabric.service();
        CastRequest press = player.request(ID);
        TechniqueInputBuffer.press(player.player, press);
        service.endActive(player.id(), release(press));
        context.assertTrue(!player.status().getEndTimesUnknown(), "短按没有立即同步收势边界");
        player.host.nowMillis = WARMUP;
        service.tick();
        context.assertTrue(service.activeGuard(player.id()).isEmpty(), "短按产生守窗");
        var hard = new TechniqueService.Stagger(STAGGER_MILLIS, 0, TechniqueService.StaggerKind.HOLD);
        service.stagger(player.id(), hard);
        context.assertTrue(!player.progress().hasActiveCast() && player.host.current().actions().isEmpty(),
                "硬控没有立即清理守式");
        context.assertTrue(player.host.effects.stream().anyMatch(effect -> effect.hasMovement()
                && effect.getMovement().getAction() == MovementAction.UNLOCK), "硬控遗留输入锁");
        player.close();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void staleReleaseDoesNotClearAnotherBufferedPress(GameTestHelper context) {
        // 定向清理只删除同身份的普通按键；持续式本身不入缓冲。
        PlayerGuard player = new PlayerGuard(context);
        TechniqueService service = TechniqueFabric.service();
        service.learn(player.id(), TECHNIQUE_ID);
        service.learn(player.id(), TechniqueGameTestHost.HELD_TECHNIQUE_ID);
        TechniqueInputBuffer.press(player.player, player.request(TECHNIQUE_ID));
        player.host.nowMillis = WARMUP;
        CastRequest pending = player.request(TechniqueGameTestHost.HELD_TECHNIQUE_ID);
        context.assertTrue(TechniqueInputBuffer.press(player.player, pending).buffered(), "普通式未按旧规则缓冲");
        TechniqueInputBuffer.clear(player.id(), "old_press");
        context.assertTrue(!TechniqueInputBuffer.buffered(player.id()).isEmpty(), "旧松开误清新按键");
        TechniqueInputBuffer.clear(player.id(), pending.getCastId());
        context.assertTrue(TechniqueInputBuffer.buffered(player.id()).isEmpty(), "同身份没有清掉按键");
        player.close();
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void guardIsExcludedFromOrdinaryAiAndDisconnectCleansIt(GameTestHelper context) {
        // 正式服务目录中的守式不能进入普通选式，断线立即撤防解锁。
        PlayerGuard player = new PlayerGuard(context);
        TechniqueService service = TechniqueFabric.service();
        TechniqueDefinition guard = service.registry().findLatest(ID).orElseThrow();
        TechniqueReference reference = TechniqueRegistry.reference(guard);
        BindingOutcome binding = TechniqueBinder.bind(player.host.current(), guard);
        var availability = new TechniqueSelector.Availability(Map.of(reference, binding), Set.of(reference), Map.of());
        var situation = new TechniqueSelector.Situation(0, 1, 1, 1, TechniqueSelector.TargetState.ENGAGED);
        var selection = new TechniqueSelector.Context(service.registry(), situation, availability);
        context.assertTrue(TechniqueSelector.choose(selection, Fixtures.progress(guard).getKnownList(),
                new Random(0)).isEmpty() && !TechniqueSelector.isOrdinarySupport(guard), "守式进了普通选式");
        TechniqueInputBuffer.press(player.player, player.request(ID));
        player.host.nowMillis = WARMUP;
        service.tick();
        context.assertTrue(service.activeGuard(player.id()).isPresent(), "没有进入守段");
        service.stagger(player.id(), HIT_STAGGER);
        context.assertTrue(service.activeGuard(player.id()).isPresent(), "普通受击中断守段");
        player.close();
        context.assertTrue(!player.progress().hasActiveCast() && player.host.current().actions().isEmpty(), "断线残留守式");
        context.succeed();
    }

    private static final class PlayerGuard {
        final ServerPlayer player;
        final RecordingConnection connection;
        final MemoryHost host;
        private int presses;

        PlayerGuard(GameTestHelper context) {
            // 玩家连接和消息注册走正式入口；身体只有测试宿主一份状态。
            String name = "guard_" + UUID.randomUUID().toString().substring(0, NAME_LENGTH);
            player = TestPlayers.detached(context.getLevel(), name);
            connection = new RecordingConnection(List.of(TechniqueCastStatusSync.CHANNEL.type().id()));
            host = GuardTechniqueFixtures.host();
            TechniqueGameTestHost.bind(id(), host);
            TestPlayers.logIn(player, connection);
        }

        UUID id() {
            // 施放与世界宿主共用真实实体身份。
            return player.getUUID();
        }

        CastRequest request(String technique) {
            // 每次按键拥有独立施放身份，松开必须携带相同身份。
            var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
            return Fixtures.request(host.current(), "guard_" + id() + "_" + presses++).toBuilder()
                    .setTechnique(TechniqueRegistry.reference(definition))
                    .build();
        }

        TechniqueProgress progress() {
            // 直接查看 Framework 权威进度，避免以客户端副本证明运行状态。
            return TechniqueState.read(host.current());
        }

        CastStatus status() {
            // 状态从真实发出的网络包解码，检验同步监听已经接线。
            return connection.packets().stream()
                    .filter(ClientboundCustomPayloadPacket.class::isInstance)
                    .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                    .filter(payload -> payload.type().equals(TechniqueCastStatusSync.CHANNEL.type()))
                    .map(payload -> decode(((ProtobufChannel.Payload) payload).bytes()))
                    .toList().getLast();
        }

        private static CastStatus decode(byte[] bytes) {
            // 测试失败保留协议解析原因。
            try {
                return CastStatus.parseFrom(bytes);
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(invalid);
            }
        }

        void close() {
            // 先让正式断线回调清理，再撤销测试宿主。
            connection.disconnectFromClient();
            TechniqueGameTestHost.unbind(id());
        }
    }
}
