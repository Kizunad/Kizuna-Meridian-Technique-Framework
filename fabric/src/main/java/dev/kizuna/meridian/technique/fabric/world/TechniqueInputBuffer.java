package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpHint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueCastTimelines;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * 输入缓冲：玩家按早了一点（上一式还没到能接的时候，或冷却只差一点），服务端替他把这一次按键记一小会儿，
 * 每 tick 再试，能出手的那一 tick 就放。只记最后一次按键；窗口长度在配置里，0 是关掉。
 *
 * <p>什么时候能出手仍由功法服务按身体时钟判定，这里不采信客户端的任何时间，只是替玩家「再按一次」。</p>
 */
public final class TechniqueInputBuffer {
    /** 一次按键的结果：功法服务的回执，以及这次按键是不是被记下来等着了。 */
    public record Press(CastOutcome outcome, boolean buffered) { }

    /** deadlineTick 按玩家所在世界的时间算：世界冻结、暂停时身体时钟也不走，记着的按键跟着等。 */
    private record Pending(CastRequest request, long deadlineTick, CastOutcome last) { }

    private static final long MILLIS_PER_TICK = 50;
    private static final boolean ACTION_BAR = true;
    /** 等一等会好的拒绝原因：正在放别的、冷却没到、承接窗口还没开。 */
    private static final Set<TechniqueFailure> WAITABLE = Set.of(TechniqueFailure.ALREADY_CASTING,
            TechniqueFailure.COOLING_DOWN, TechniqueFailure.FOLLOW_UP_REQUIRED, TechniqueFailure.FOLLOW_UP_UNAVAILABLE);
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static TechniqueFeelServerSettings settings =
            new TechniqueFeelServerSettings(TechniqueFeelServerSettings.DEFAULT_INPUT_BUFFER_MILLIS);

    private TechniqueInputBuffer() {
        // 记录只在服务端主线程读写。
    }

    /** 开服时重读配置、清空上一个世界留下的记录。 */
    public static void start() {
        settings = TechniqueFeelServerSettings.load();
        PENDING.clear();
    }

    /** 玩家的一次按键。放出来了照常；差一点就能放的记下来等，回执里的失败原因先不用报给玩家。 */
    public static Press press(ServerPlayer player, CastRequest request) {
        UUID id = player.getUUID();
        PENDING.remove(id);
        CastOutcome outcome = TechniqueFabric.service().cast(id, request);
        if (outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            return new Press(outcome, false);
        }
        boolean buffered = waitMillis(player, request, outcome.getFailure()).isPresent();
        if (buffered) {
            // 多给一 tick：窗口正好在最后一毫秒打开时，那一 tick 的重试还赶得上。
            long ticks = Math.ceilDiv(settings.inputBufferMillis(), MILLIS_PER_TICK) + 1;
            PENDING.put(id, new Pending(request, player.level().getGameTime() + ticks, outcome));
            TechniqueCastStatusSync.send(player, "", TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED);
        } else {
            TechniqueCastStatusSync.send(player, request.getTechnique().getId(), outcome.getFailure());
        }
        return new Press(outcome, buffered);
    }

    /** 每 tick 替记着的按键再试一次。 */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (var entry : List.copyOf(PENDING.entrySet())) {
            // 先销账再试：试成了，起手那条状态里就不该再写着「预备」。
            PENDING.remove(entry.getKey());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.isAlive()) {
                retry(player, entry.getValue());
            }
        }
    }

    private static void retry(ServerPlayer player, Pending pending) {
        UUID id = player.getUUID();
        if (TechniqueFabric.service().staggerRemainingMillis(id) > 0) {
            // 被打出僵直：这次按键作废，不在僵直结束后突然放出来。
            TechniqueCastStatusSync.send(player, pending.request().getTechnique().getId(), TechniqueFailure.STAGGERED);
            return;
        }
        // 每次重试是一次新的施放请求，换一个施放编号。
        CastRequest request = pending.request().toBuilder().setCastId(UUID.randomUUID().toString()).build();
        CastOutcome outcome = TechniqueFabric.service().cast(id, request);
        if (outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            // 放出来了：起手那一条状态由施放监听发。
            return;
        }
        if (WAITABLE.contains(outcome.getFailure()) && player.level().getGameTime() < pending.deadlineTick()) {
            PENDING.put(id, pending);
            return;
        }
        // 等到头也没放出来，或者换成了别的拒绝原因：这时才把原因报给玩家。
        player.sendSystemMessage(Component.literal(outcome.getReason()), ACTION_BAR);
        TechniqueCastStatusSync.send(player, pending.request().getTechnique().getId(), outcome.getFailure());
    }

    /** 玩家断线、死亡、退出架势时清掉。 */
    public static void clear(UUID player) {
        PENDING.remove(player);
    }

    public static void clear(UUID player, String castId) {
        // 松开只清理同一次按键，迟到包不能清掉另一式的输入。
        Pending pending = PENDING.get(player);
        if (pending != null && pending.request().getCastId().equals(castId)) {
            PENDING.remove(player);
        }
    }

    /** 这名玩家记着的那一式，没有时为空串。 */
    public static String buffered(UUID player) {
        Pending pending = PENDING.get(player);
        return pending == null ? "" : pending.request().getTechnique().getId();
    }

    private static OptionalLong waitMillis(ServerPlayer player, CastRequest request, TechniqueFailure failure) {
        // 还要等多久才能出手；等也没用（没学会、真元不够、没拿兵器）或者要等的比缓冲窗口长，就是空。
        // 要等的是：这一式自己的冷却，和上一式到能接它的时刻（接不了的式等上一式进收招段），取晚的。
        long window = settings.inputBufferMillis();
        var definition = TechniqueFabric.service().registry().find(request.getTechnique());
        if (definition.isPresent() && definition.get().getPhases().getActiveDuration()
                == ActiveDuration.ACTIVE_DURATION_UNTIL_RELEASE) {
            return OptionalLong.empty();
        }
        var observation = TechniqueFabric.service().observe(player.getUUID()).orElse(null);
        if (window <= 0 || observation == null || !WAITABLE.contains(failure)) {
            return OptionalLong.empty();
        }
        var body = observation.body();
        long now = body.settledAt().elapsedMillis();
        TechniqueProgress progress = TechniqueState.read(body);
        String technique = request.getTechnique().getId();
        long cooldownEnd = Math.max(progress.getCooldownsOrDefault(technique, 0),
                progress.getCurveEndsOrDefault(technique, 0));
        long wait = Math.max(0, cooldownEnd - now);
        CastStatus status = TechniqueCastTimelines.status(TechniqueFabric.service().registry(), body, now)
                .build();
        if (status.getEndTimesUnknown()) {
            return OptionalLong.empty();
        }
        Optional<FollowUpHint> opening = status.getFollowUpsList().stream()
                .filter(hint -> hint.getTechniqueId().equals(technique)).findFirst();
        boolean needsWindow = failure == TechniqueFailure.FOLLOW_UP_REQUIRED
                || failure == TechniqueFailure.FOLLOW_UP_UNAVAILABLE;
        if (opening.isPresent()) {
            if (status.getElapsedMillis() > opening.get().getCloseMillis()) {
                return OptionalLong.empty();
            }
            wait = Math.max(wait, opening.get().getOpenMillis() - status.getElapsedMillis());
        } else if (needsWindow) {
            // 只能承接的式，眼下这一式后面接不了它（或者根本没在施放）：等不来。
            return OptionalLong.empty();
        } else if (!status.getTechniqueId().isEmpty()) {
            wait = Math.max(wait, Math.min(status.getFreeMillis(), status.getRecoveryEndMillis())
                    - status.getElapsedMillis());
        }
        return wait <= settings.inputBufferMillis() ? OptionalLong.of(wait) : OptionalLong.empty();
    }
}
