package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

/** 服务端主线程使用的功法服务；状态和世界副作用仍归实体宿主。 */
public final class TechniqueService {
    public static final String LEARN_SOURCE = "meridian_technique:command";
    private static final String LEARN_REQUEST_PREFIX = "technique_learn_";
    private static final String EFFECT_REQUEST_PREFIX = "technique_effect_";
    private static final long BEFORE_START_MILLIS = Long.MIN_VALUE;

    private final TechniqueRegistry registry;
    private final TechniqueHostDirectory hosts;
    private final TechniqueCasting casting;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final List<Consumer<KnownChanged>> knownChangedListeners = new ArrayList<>();

    public TechniqueService(TechniqueRegistry registry, TechniqueHostDirectory hosts) {
        // 服务只持有不可变目录和宿主发现器，身体状态始终从宿主读取。
        this.registry = Objects.requireNonNull(registry);
        this.hosts = Objects.requireNonNull(hosts);
        this.casting = new TechniqueCasting(registry);
    }

    public CastOutcome cast(UUID entityUuid, CastRequest request) {
        // 先确认实体有唯一宿主，再让状态机校验身体、目录、学会和冷却。
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return noHost(request);
        }
        CastOutcome outcome = casting.cast(host, request);
        if (success(outcome)) {
            register(entityUuid, host);
        }
        return outcome;
    }

    public TechniqueRegistry registry() {
        // 命令和宿主复用服务启动时固定的目录，不再次查询内容入口。
        return registry;
    }

    public List<KnownEntry> known(UUID entityUuid) {
        // 查询不写状态；没有宿主时返回空列表，调用方可把它解释为未接入实体。
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return List.of();
        }
        BodySnapshot body = host.inspect();
        TechniqueProgress progress = TechniqueState.read(body);
        return entries(progress);
    }

    public List<KnownEntry> learn(UUID entityUuid, TechniqueReference reference) {
        // 客户端只能提交精确引用；目录没有该版本时在写身体前直接拒绝。
        Objects.requireNonNull(reference);
        TechniqueDefinition definition = registry.find(reference)
                .orElseThrow(() -> TechniqueErrors.invalid("technique", "目录中没有该功法版本"));
        return learn(entityUuid, definition);
    }

    public List<KnownEntry> learn(UUID entityUuid, TechniqueDefinition definition) {
        // 学会通过宿主活动原子写入身体的可信进度。
        Objects.requireNonNull(entityUuid);
        Objects.requireNonNull(definition);
        TechniqueReference reference = TechniqueRegistry.reference(definition);
        registry.find(reference)
                .orElseThrow(() -> TechniqueErrors.invalid("technique", "目录中没有该功法版本"));
        TechniqueHost host = requireHost(entityUuid);
        BodySnapshot body = host.inspect();
        TechniqueProgress progress = TechniqueState.read(body);
        if (isKnown(progress, reference)) {
            return entries(progress);
        }
        KnownTechnique known = knownRecord(reference, body.settledAt().elapsedMillis());
        BodyActivity activity = TechniqueActivities.learn(progress, known);
        String requestId = learnRequestId(reference);
        TechniqueHost.Submission submission = new TechniqueHost.Submission(requestId, body.revision(), activity);
        TechniqueHost.Commit commit = host.submit(submission);
        validateCommit(body, activity, commit);
        TechniqueProgress learned = progress.toBuilder().addKnown(known).build();
        List<KnownEntry> result = entries(learned);
        publishKnownChanged(entityUuid, result);
        return result;
    }

    public void addKnownChangedListener(Consumer<KnownChanged> listener) {
        // 监听器只在服务线程调用，复制列表避免回调修改当前遍历。
        knownChangedListeners.add(Objects.requireNonNull(listener));
    }

    public void removeKnownChangedListener(Consumer<KnownChanged> listener) {
        // Fabric 事件卸载时可移除附属监听器，避免保留旧服务器对象。
        knownChangedListeners.remove(listener);
    }

    public void tick() {
        // 每 tick 只推进已成功施放的实体，不按毫秒轮询世界。
        for (UUID entityUuid : List.copyOf(sessions.keySet())) {
            Session session = sessions.get(entityUuid);
            if (session != null) {
                advance(entityUuid, session);
            }
        }
    }

    public Optional<CastOutcome> interruptByDamage(UUID entityUuid) {
        // 伤害来源必须由可信宿主事件调用，服务只负责取消起手窗口内的施放。
        return transition(Objects.requireNonNull(entityUuid), Transition.DAMAGE);
    }

    public Optional<CastOutcome> cancelOnUnload(UUID entityUuid) {
        // 实体卸载前清除动作和当前施放，费用与冷却由已保存进度保留。
        return transition(Objects.requireNonNull(entityUuid), Transition.UNLOAD);
    }

    public Optional<CastOutcome> cancelOnDisconnect(UUID entityUuid) {
        // 玩家下线沿用卸载语义；调用方应在撤销宿主访问前调用此入口。
        return transition(Objects.requireNonNull(entityUuid), Transition.DISCONNECT);
    }

    public void shutdown() {
        // 服务器停止前逐个取消仍在运行的施放。
        // 这样下一次装载不会把旧动作当作新世界事实。
        for (UUID entityUuid : List.copyOf(sessions.keySet())) {
            cancelOnUnload(entityUuid);
        }
        sessions.clear();
    }

    private void register(UUID entityUuid, TechniqueHost host) {
        // 起手成功后把效果游标放在起手前一毫秒，确保 at=0 的效果只发一次。
        if (sessions.containsKey(entityUuid)) {
            return;
        }
        BodySnapshot body = host.inspect();
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return;
        }
        ActiveCast active = progress.getActiveCast();
        long previousMillis = previousMillis(active.getStartedMillis());
        sessions.put(entityUuid, new Session(host, previousMillis));
    }

    private void advance(UUID entityUuid, Session session) {
        // 正常推进与收势共用实际身体时刻；已生成的效果保留到全部收到回执。
        try {
            if (session.pendingAdvance == null) {
                TechniqueCasting.AdvanceResult result = casting.advance(session.host, session.previousMillis);
                if (!success(result.outcome())) {
                    discardUnlessRetryable(entityUuid, result.outcome().getFrameworkFailure());
                    return;
                }
                session.pendingAdvance = result;
            }
            deliverPending(entityUuid, session);
        } catch (TechniqueErrors.HostRejected exception) {
            discardUnlessRetryable(entityUuid, exception.failure());
        } catch (IllegalArgumentException exception) {
            // 损坏协议或进度无法靠重复同一次请求修复，停止持有该会话。
            sessions.remove(entityUuid);
        }
    }

    private void deliverPending(UUID entityUuid, Session session) {
        // 收势可能已提交；效果拒绝时保存原结果和请求 ID，不能重新 inspect 后丢掉意图。
        TechniqueCasting.AdvanceResult result = session.pendingAdvance;
        dispatch(session, result.effects(), session.previousMillis);
        session.previousMillis = result.advancedMillis();
        session.pendingAdvance = null;
        if (result.recoveryCommit().isPresent() || result.outcome().getPhase() == CastPhase.IDLE) {
            sessions.remove(entityUuid);
        }
    }

    private void discardUnlessRetryable(UUID entityUuid, FailureCode failure) {
        // 只保留结时、并发或存储暂不可用的会话；失去身体和永久拒绝都停止重试。
        switch (failure) {
            case REVISION_CONFLICT, TIME_REQUIRES_SETTLEMENT, PENDING_TIME_EVENT,
                    ENVIRONMENT_UNAVAILABLE, ENVIRONMENT_REVISION_CONFLICT, INTERNAL_ERROR -> { }
            default -> sessions.remove(entityUuid);
        }
    }

    private void dispatch(Session session, List<EffectIntent> effects, long previousMillis) {
        // 同一游标和序号生成同一请求 ID；部分成功重试依靠宿主幂等回执去重。
        int index = 0;
        for (EffectIntent effect : effects) {
            String requestId = effectRequestId(effect, previousMillis, index);
            session.host.submitEffect(new TechniqueHost.EffectSubmission(requestId, effect));
            index++;
        }
    }

    private Optional<CastOutcome> transition(UUID entityUuid, Transition transition) {
        // 取消只使用本服务保存的宿主和事件游标，避免实体身份变化后误写另一具身体。
        Session session = sessions.get(entityUuid);
        if (session == null) {
            return Optional.empty();
        }
        try {
            if (session.pendingAdvance != null) {
                CastOutcome pendingOutcome = session.pendingAdvance.outcome();
                deliverPending(entityUuid, session);
                if (!sessions.containsKey(entityUuid)) {
                    return Optional.of(pendingOutcome);
                }
            }
            TechniqueCasting.AdvanceResult result = switch (transition) {
                case DAMAGE -> casting.interruptByDamage(session.host, session.previousMillis);
                case UNLOAD -> casting.cancelOnUnload(session.host, session.previousMillis);
                case DISCONNECT -> casting.cancelOnDisconnect(session.host, session.previousMillis);
            };
            if (!success(result.outcome())) {
                discardUnlessRetryable(entityUuid, result.outcome().getFrameworkFailure());
            } else if (result.recoveryCommit().isPresent() || result.outcome().getPhase() == CastPhase.IDLE) {
                session.pendingAdvance = result;
                deliverPending(entityUuid, session);
            }
            return Optional.of(result.outcome());
        } catch (TechniqueErrors.HostRejected exception) {
            discardUnlessRetryable(entityUuid, exception.failure());
            return Optional.empty();
        } catch (IllegalArgumentException exception) {
            sessions.remove(entityUuid);
            return Optional.empty();
        }
    }

    private TechniqueHost requireHost(UUID entityUuid) {
        // 学会是可信写入，不能把没有实体宿主解释为新建身体。
        return hosts.resolve(entityUuid).orElseThrow(() -> new NoHostException(entityUuid));
    }

    private void publishKnownChanged(UUID entityUuid, List<KnownEntry> entries) {
        // 事件失败不回滚已提交身体；监听器只负责通知界面或其他附属刷新。
        KnownChanged event = new KnownChanged(entityUuid, entries);
        for (Consumer<KnownChanged> listener : List.copyOf(knownChangedListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 通知附属故障不能重复扣费或撤销已学记录。
            }
        }
    }

    private List<KnownEntry> entries(TechniqueProgress progress) {
        // 目录缺包时保留 ID，名称退回稳定的公开标识，不能丢失可信学会记录。
        List<KnownEntry> result = new ArrayList<>();
        for (KnownTechnique known : progress.getKnownList()) {
            String name = registry.find(known.getTechnique())
                    .map(this::nameOf)
                    .orElse(known.getTechnique().getId());
            result.add(new KnownEntry(known.getTechnique(), name));
        }
        return List.copyOf(result);
    }

    private String nameOf(TechniqueDefinition definition) {
        // 当前契约没有独立名称字段，优先使用内容描述，缺省时使用精确功法 ID。
        String description = definition.getPresentation().getDescription();
        if (!description.isBlank()) {
            return description;
        }
        return definition.getId();
    }

    private static KnownTechnique knownRecord(TechniqueReference reference, long learnedMillis) {
        // 新学功法从零熟练度开始，来源和学会时刻随身体进度一同保存。
        Quantity proficiency = Quantity.newBuilder()
                .setValue(0)
                .setUnit(Unit.RATIO)
                .build();
        return KnownTechnique.newBuilder()
                .setTechnique(reference)
                .setProficiency(proficiency)
                .setSource(LEARN_SOURCE)
                .setLearnedMillis(learnedMillis)
                .build();
    }

    private static boolean isKnown(TechniqueProgress progress, TechniqueReference reference) {
        // 精确版本已存在时学习命令幂等返回，不产生第二条记录或通知。
        return progress.getKnownList().stream()
                .anyMatch(known -> known.getTechnique().equals(reference));
    }

    private static String learnRequestId(TechniqueReference reference) {
        // UUID 名称哈希把任意内容 ID 压到有界且可重试的 Framework request_id。
        UUID requestUuid = UUID.nameUUIDFromBytes(reference.toByteArray());
        return LEARN_REQUEST_PREFIX + requestUuid;
    }

    private static String effectRequestId(EffectIntent effect, long previousMillis, int index) {
        // 同一效果在同一事件边界使用固定键，失败重试不会产生第二次世界副作用。
        return EFFECT_REQUEST_PREFIX + effect.getCastId() + "_" + previousMillis + "_" + index;
    }

    private static long previousMillis(long startedMillis) {
        // 身体时钟从零开始时使用 Long.MIN_VALUE 表示起手前的开放区间。
        if (startedMillis == 0) {
            return BEFORE_START_MILLIS;
        }
        return startedMillis - 1;
    }

    private static boolean success(CastOutcome outcome) {
        // 未设置失败枚举才代表状态机完成了可信提交。
        return outcome.getFailure() == TECHNIQUE_FAILURE_UNSPECIFIED;
    }

    private static void validateCommit(BodySnapshot body, BodyActivity activity, TechniqueHost.Commit commit) {
        // 学会活动必须只递增一次并返回原始计划，错误回执不能发布事件。
        if (commit.revision() != body.revision() + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("宿主返回的学会活动回执与提交计划不一致");
        }
    }

    private static CastOutcome noHost(CastRequest request) {
        // 没有任何实体宿主时返回结构化 NO_HOST，玩家只看到身体无法运功的感受。
        return CastOutcome.newBuilder()
                .setCastId(request.getCastId())
                .setFailure(NO_HOST)
                .setReason(TechniqueErrors.playerReason(NO_HOST))
                .build();
    }

    public record KnownEntry(TechniqueReference technique, String name) {
        public KnownEntry {
            // 事件和查询结果都使用不可变的精确引用与名称。
            Objects.requireNonNull(technique);
            Objects.requireNonNull(name);
        }
    }

    public record KnownChanged(UUID entityUuid, List<KnownEntry> known) {
        public KnownChanged {
            // 事件载荷固定为一份快照，监听器不能改写服务状态。
            Objects.requireNonNull(entityUuid);
            known = List.copyOf(known);
        }
    }

    public static final class NoHostException extends RuntimeException {
        public NoHostException(UUID entityUuid) {
            // 学会命令没有宿主时拒绝，不创建缺少权威宿主的身体。
            super("实体没有功法宿主：" + entityUuid);
        }
    }

    private static final class Session {
        private final TechniqueHost host;
        private long previousMillis;
        private TechniqueCasting.AdvanceResult pendingAdvance;

        private Session(TechniqueHost host, long previousMillis) {
            // 游标只属于一次服务实例；身体和持久进度仍由宿主持有。
            this.host = host;
            this.previousMillis = previousMillis;
        }
    }

    private enum Transition {
        DAMAGE,
        UNLOAD,
        DISCONNECT
    }
}
