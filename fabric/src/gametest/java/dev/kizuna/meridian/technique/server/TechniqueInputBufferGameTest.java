package dev.kizuna.meridian.technique.server;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.fabric.world.TechniqueCastStatusSync;
import dev.kizuna.meridian.technique.fabric.world.TechniqueInputBuffer;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.*;
import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/**
 * 输入缓冲和收招段：按早了一点的键由服务端记着，到能出手的那一 tick 放出来；按太早、被打出僵直、等过头都不放。
 * 玩家走正式进服入口，身体是内存宿主，身体时钟由用例拨。
 */
public final class TechniqueInputBufferGameTest {
    /** 默认配置的缓冲窗口。 */
    private static final long BUFFER_MILLIS = 400;
    private static final int RETRY_TICKS = 2;
    /** 缓冲窗口换成 tick 再多等几 tick，记着的按键一定已经过期。 */
    private static final int EXPIRED_TICKS = 14;
    private static final long WIND_DOWN_LEAD_MILLIS = 100;
    private static final int NAME_ID_LENGTH = 8;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pressJustBeforeTheFollowUpWindowIsHeldAndFiresWhenItOpens(GameTestHelper context) {
        // 只能承接的后式在窗口打开前一个缓冲窗口按下：当场不放、记着，状态消息里写着「预备」；窗口一开就接上，预备清掉。
        Presser presser = new Presser(context);
        long started = presser.castFirst(context);
        presser.host.nowMillis = started + WINDOW_START - BUFFER_MILLIS;
        var press = TechniqueInputBuffer.press(presser.player, presser.request(TechniqueGameTestHost.COMBAT_NEXT_ID));
        context.assertTrue(press.buffered() && press.outcome().getFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                "窗口前一个缓冲窗口内的按键没有被记下：" + press);
        CastStatus held = presser.statuses().getLast();
        context.assertTrue(held.getTechniqueId().equals(TechniqueGameTestHost.COMBAT_ID)
                && held.getBufferedTechniqueId().equals(TechniqueGameTestHost.COMBAT_NEXT_ID)
                && held.getRejectedTechniqueId().isEmpty(), "记下按键时发给本人的状态不对：" + held);
        context.runAfterDelay(RETRY_TICKS, () -> {
            context.assertTrue(presser.active().getRequest().getTechnique().getId()
                    .equals(TechniqueGameTestHost.COMBAT_ID), "窗口没开就接上了");
            presser.host.nowMillis = started + WINDOW_START;
            context.runAfterDelay(RETRY_TICKS, () -> {
                ActiveCast active = presser.active();
                context.assertTrue(active.getRequest().getTechnique().getId()
                        .equals(TechniqueGameTestHost.COMBAT_NEXT_ID) && active.getClipStartMillis() == CLIP_OFFSET,
                        "窗口打开后记着的按键没有接上：" + active);
                CastStatus followed = presser.statuses().getLast();
                context.assertTrue(followed.getTechniqueId().equals(TechniqueGameTestHost.COMBAT_NEXT_ID)
                        && followed.getBufferedTechniqueId().isEmpty(), "接上以后状态里还写着预备：" + followed);
                presser.close();
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void pressEarlierThanTheBufferWindowIsRefusedAsBefore(GameTestHelper context) {
        // 比缓冲窗口再早一毫秒：照旧当场拒绝，状态消息通报这一式没放出来和原因，之后窗口开了也不会自己放。
        Presser presser = new Presser(context);
        long started = presser.castFirst(context);
        presser.host.nowMillis = started + WINDOW_START - BUFFER_MILLIS - 1;
        var press = TechniqueInputBuffer.press(presser.player, presser.request(TechniqueGameTestHost.COMBAT_NEXT_ID));
        CastStatus refused = presser.statuses().getLast();
        context.assertTrue(!press.buffered() && refused.getBufferedTechniqueId().isEmpty()
                && refused.getRejectedTechniqueId().equals(TechniqueGameTestHost.COMBAT_NEXT_ID)
                && refused.getRejectedFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                "按得太早的键应当当场拒绝：" + press + " " + refused);
        presser.host.nowMillis = started + WINDOW_START;
        context.runAfterDelay(RETRY_TICKS, () -> {
            context.assertTrue(presser.active().getRequest().getTechnique().getId()
                    .equals(TechniqueGameTestHost.COMBAT_ID), "被拒的按键后来自己放出来了");
            presser.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void staggerDropsTheHeldPress(GameTestHelper context) {
        // 记着按键的时候被打出僵直：这次按键作废并通报，僵直过后、窗口时刻到了也不放。
        Presser presser = new Presser(context);
        long started = presser.castFirst(context);
        presser.host.nowMillis = started + WINDOW_START - BUFFER_MILLIS;
        TechniqueInputBuffer.press(presser.player, presser.request(TechniqueGameTestHost.COMBAT_NEXT_ID));
        UUID id = presser.player.getUUID();
        context.assertTrue(TechniqueInputBuffer.buffered(id).equals(TechniqueGameTestHost.COMBAT_NEXT_ID),
                "按键没有被记下");
        TechniqueFabric.service().stagger(id, HIT_STAGGER);
        context.runAfterDelay(RETRY_TICKS, () -> {
            CastStatus dropped = presser.statuses().getLast();
            context.assertTrue(TechniqueInputBuffer.buffered(id).isEmpty()
                    && dropped.getRejectedTechniqueId().equals(TechniqueGameTestHost.COMBAT_NEXT_ID)
                    && dropped.getRejectedFailure() == TechniqueFailure.STAGGERED,
                    "僵直没有清掉记着的按键：" + dropped);
            presser.host.nowMillis = started + WINDOW_START;
            context.runAfterDelay(RETRY_TICKS, () -> {
                context.assertTrue(!presser.progress().hasActiveCast(), "僵直以后作废的按键又放出来了");
                presser.close();
                context.succeed();
            });
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void heldPressExpiresWhenTheWindowNeverOpens(GameTestHelper context) {
        // 身体时钟不走（等于窗口一直不开）：过了缓冲窗口就不再记着，这时才通报没放出来。
        Presser presser = new Presser(context);
        long started = presser.castFirst(context);
        presser.host.nowMillis = started + WINDOW_START - BUFFER_MILLIS;
        TechniqueInputBuffer.press(presser.player, presser.request(TechniqueGameTestHost.COMBAT_NEXT_ID));
        context.runAfterDelay(EXPIRED_TICKS, () -> {
            CastStatus expired = presser.statuses().getLast();
            context.assertTrue(TechniqueInputBuffer.buffered(presser.player.getUUID()).isEmpty()
                    && expired.getRejectedTechniqueId().equals(TechniqueGameTestHost.COMBAT_NEXT_ID)
                    && expired.getRejectedFailure() == TechniqueFailure.FOLLOW_UP_REQUIRED,
                    "等过头的按键没有作废并通报：" + expired);
            context.assertTrue(presser.active().getRequest().getTechnique().getId()
                    .equals(TechniqueGameTestHost.COMBAT_ID), "前式被动过了");
            presser.close();
            context.succeed();
        });
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void anotherTechniquePressedLateInTheCastStartsWhenItWindsDown(GameTestHelper context) {
        // 接不了的别的式：按在收招段开始前一点，记着；前式一进收招段（效果都出完了）就起手，前式记成正常放完。
        Presser presser = new Presser(context);
        TechniqueService service = TechniqueFabric.service();
        service.learn(presser.player.getUUID(),
                TechniqueRegistry.reference(service.registry().findLatest(TECHNIQUE_ID).orElseThrow()));
        long started = presser.castFirst(context);
        long free = TechniqueCastTimelines.status(service.registry(), presser.progress(), started).getFreeMillis();
        context.assertTrue(free > WIND_DOWN_LEAD_MILLIS && free < CAST_END, "前式的收招段不在施放中间：" + free);
        presser.host.nowMillis = started + free - WIND_DOWN_LEAD_MILLIS;
        var press = TechniqueInputBuffer.press(presser.player, presser.request(TECHNIQUE_ID));
        context.assertTrue(press.buffered() && press.outcome().getFailure() == TechniqueFailure.ALREADY_CASTING,
                "收招段前的按键没有被记下：" + press);
        context.runAfterDelay(RETRY_TICKS, () -> {
            context.assertTrue(presser.active().getRequest().getTechnique().getId()
                    .equals(TechniqueGameTestHost.COMBAT_ID), "效果还没出完就被顶掉了");
            presser.host.nowMillis = started + free;
            context.runAfterDelay(RETRY_TICKS, () -> {
                TechniqueProgress progress = presser.progress();
                context.assertTrue(progress.getActiveCast().getRequest().getTechnique().getId().equals(TECHNIQUE_ID)
                        && progress.getLastCast().getTechnique().getId().equals(TechniqueGameTestHost.COMBAT_ID)
                        && progress.getLastCast().getEnding() == CastPhase.FINISHED,
                        "进了收招段记着的按键没有起手：" + progress.getActiveCast().getRequest());
                presser.close();
                context.succeed();
            });
        });
    }

    /** 一名走正式进服入口的玩家，身体是按 UUID 认领的内存宿主，手里一把斧子，会战斗测试卷的两式。 */
    private static final class Presser {
        final ServerPlayer player;
        final RecordingConnection connection;
        final MemoryHost host;
        private int presses;

        Presser(GameTestHelper context) {
            String name = "press_" + UUID.randomUUID().toString().substring(0, NAME_ID_LENGTH);
            player = TestPlayers.detached(context.getLevel(), name);
            connection = new RecordingConnection(List.of(TechniqueCastStatusSync.CHANNEL.type().id()));
            BodySnapshot initial = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
            host = new MemoryHost(initial);
            host.heldItems = HeldTechniqueFixtures.hands(
                    HeldTechniqueFixtures.durableItem("minecraft:iron_axe", HeldTechniqueFixtures.AXES),
                    HeldItem.getDefaultInstance());
            TechniqueGameTestHost.bind(player.getUUID(), host);
            TestPlayers.logIn(player, connection);
            TechniqueFabric.service().learn(player.getUUID(), TechniqueGameTestHost.COMBAT_VOLUME_ID);
        }

        long castFirst(GameTestHelper context) {
            // 前式经同一个按键入口放出来，返回它起手的身体时刻。
            var press = TechniqueInputBuffer.press(player, request(TechniqueGameTestHost.COMBAT_ID));
            context.assertTrue(!press.buffered() && press.outcome().getPhase() == CastPhase.WARMUP,
                    "前式没有放出来：" + press);
            return active().getStartedMillis();
        }

        CastRequest request(String technique) {
            var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
            return Fixtures.request(host.current(), "press_" + player.getUUID() + "_" + presses++).toBuilder()
                    .setTechnique(TechniqueRegistry.reference(definition))
                    .build();
        }

        TechniqueProgress progress() {
            return TechniqueState.read(host.current());
        }

        ActiveCast active() {
            return progress().getActiveCast();
        }

        /** 发给本人的施放状态消息，按发出的先后。 */
        List<CastStatus> statuses() {
            return connection.packets().stream()
                    .filter(ClientboundCustomPayloadPacket.class::isInstance)
                    .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                    .filter(payload -> payload.type().equals(TechniqueCastStatusSync.CHANNEL.type()))
                    .map(payload -> status(((ProtobufChannel.Payload) payload).bytes()))
                    .toList();
        }

        private static CastStatus status(byte[] bytes) {
            try {
                return CastStatus.parseFrom(bytes);
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalStateException("施放状态消息解不开", invalid);
            }
        }

        void close() {
            // 真实断线入口清掉会话和记着的按键，再撤销宿主认领。
            connection.disconnectFromClient();
            TechniqueGameTestHost.unbind(player.getUUID());
        }
    }
}
