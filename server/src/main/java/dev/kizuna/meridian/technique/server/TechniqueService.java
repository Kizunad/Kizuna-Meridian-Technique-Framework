package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityEffort;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.SetAddonProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 服务端主线程使用的功法服务；状态和世界副作用仍归实体宿主。 */
public final class TechniqueService {
    public static final String LEARN_SOURCE = "meridian_technique:command";
    private static final String LEARN_REQUEST_PREFIX = "technique_learn_";
    private static final String EFFECT_REQUEST_PREFIX = "technique_effect_";
    private static final long BEFORE_START_MILLIS = Long.MIN_VALUE;
    private static final long NOT_STAGGERED_MILLIS = Long.MIN_VALUE;
    private static final long MAX_STAGGER_MILLIS = 60_000;

    private final TechniqueRegistry registry;
    private final TechniqueHostDirectory hosts;
    private final TechniqueCasting casting;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final List<Consumer<KnownChanged>> knownChangedListeners = new ArrayList<>();
    private final Map<UUID, Long> staggerEnds = new LinkedHashMap<>();
    private final List<Consumer<StaggerChanged>> staggerListeners = new ArrayList<>();
    private final List<Consumer<CastObserved>> castListeners = new ArrayList<>();
    private final List<Consumer<SoundDue>> soundListeners = new ArrayList<>();

    public TechniqueService(TechniqueRegistry registry, TechniqueHostDirectory hosts) {
        // 服务只持有不可变目录和宿主发现器，身体状态始终从宿主读取。
        this.registry = Objects.requireNonNull(registry);
        this.hosts = Objects.requireNonNull(hosts);
        this.casting = new TechniqueCasting(registry);
    }

    public CastOutcome cast(UUID entityUuid, CastRequest request) {
        // 每条返回路径都通知施放观察者，再把同一回执交给调用方。
        CastOutcome outcome = resolveCast(entityUuid, request);
        publishCast(new CastObserved(entityUuid, outcome));
        return outcome;
    }

    private CastOutcome resolveCast(UUID entityUuid, CastRequest request) {
        // 先确认实体有唯一宿主，再让状态机校验身体、目录、学会和冷却。
        Session current = sessions.get(entityUuid);
        if (current != null && (current.processing || current.terminating)) {
            return pendingFailure(request);
        }
        TechniqueHost claimedHost = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (claimedHost == null) {
            return noHost(request);
        }
        Session previous = sessions.get(entityUuid);
        TechniqueHost host;
        if (previous != null) {
            // 沿用会话回执及实时快照，避免推进前式后读取认领探测时的旧版本。
            host = previous.host;
        } else {
            host = claimedHost;
        }
        if (previous != null && !previous.castId.equals(request.getCastId())) {
            advance(entityUuid, previous);
            if (previous.pendingAdvance != null || (previous.terminating && sessions.containsKey(entityUuid))) {
                return pendingFailure(request);
            }
        }
        CastOutcome outcome = casting.cast(host, request, staggeredUntil(entityUuid));
        if (success(outcome)) {
            register(entityUuid, host);
        }
        return outcome;
    }

    public TechniqueRegistry registry() {
        // 命令和宿主复用服务启动时固定的目录，不再次查询内容入口。
        return registry;
    }

    public boolean exertLeap(UUID entityUuid, TechniqueDefinition definition, LeapProfile leap, String requestId) {
        // 只关心能否起跳的调用方沿用布尔结果，做功明细与拒绝原因见 submitLeap。
        LeapExertion exertion = submitLeap(entityUuid, definition, leap, requestId);
        return exertion.accepted();
    }

    public LeapExertion submitLeap(UUID entityUuid, TechniqueDefinition definition, LeapProfile leap,
                                   String requestId) {
        // 状态效果每次起跳重新解析宿主，身体版本与做功由原子活动入口复核。
        TechniqueHost host = hosts.resolve(entityUuid).orElse(null);
        if (host == null) {
            return LeapExertion.rejected(LeapExertion.NO_HOST);
        }
        return TechniqueLeapWork.submit(host, definition, leap, requestId);
    }

    public void presentLeap(UUID entityUuid, EffectIntent effect) {
        // 动画仍经过宿主，底层库不依赖玩家或类人的具体动画实现。
        String requestId = effect.getCastId() + "_" + effect.getEffectId();
        requireHost(entityUuid).submitEffect(new TechniqueHost.EffectSubmission(requestId, effect));
    }

    public List<KnownEntry> known(UUID entityUuid) {
        // 查询不写状态；没有宿主时返回空列表，调用方可把它解释为未接入实体。
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return List.of();
        }
        BodySnapshot body = host.inspect().body();
        TechniqueProgress progress = TechniqueState.read(body);
        return entries(progress);
    }

    public Optional<Observation> observe(UUID entityUuid) {
        // 读别人的身体与当前施放供 AI 预判，只读不写；没有宿主或宿主拒绝查询时为空。
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return Optional.empty();
        }
        BodySnapshot body;
        try {
            body = host.inspect().body();
        } catch (TechniqueErrors.HostRejected rejected) {
            return Optional.empty();
        }
        var cast = TechniqueForecasts.forecast(registry, TechniqueState.read(body), body.settledAt().elapsedMillis());
        return Optional.of(new Observation(body, cast));
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
        return learnReferences(entityUuid, List.of(reference));
    }

    public List<KnownEntry> learn(UUID entityUuid, String id) {
        // 卷、书使用完整身份；整书一次提交全部卷，单式仍取最高版本。
        Optional<TechniqueVolume> volume = registry.findVolume(id);
        if (volume.isPresent()) {
            return learnVolumes(entityUuid, List.of(volume.get()));
        }
        if (registry.books().stream().anyMatch(book -> book.getId().equals(id))) {
            var volumes = registry.volumes().stream()
                    .filter(candidate -> candidate.getBookId().equals(id))
                    .toList();
            return learnVolumes(entityUuid, volumes);
        }
        TechniqueDefinition definition = registry.findLatest(id)
                .orElseThrow(() -> TechniqueErrors.invalid("id", "目录中没有这本书、卷或式"));
        return learn(entityUuid, definition);
    }

    private List<KnownEntry> learnVolumes(UUID entityUuid, List<TechniqueVolume> volumes) {
        // 同次学习覆盖其中的前置卷；其余前置仍须已学，任一缺失都不提交部分知识。
        TechniqueHost host = requireHost(entityUuid);
        BodySnapshot body = host.inspect().body();
        TechniqueProgress progress = TechniqueState.read(body);
        var included = volumes.stream()
                .map(TechniqueVolume::getId)
                .collect(Collectors.toSet());
        var prerequisites = volumes.stream()
                .flatMap(volume -> volume.getPrerequisiteVolumeIdsList().stream())
                .filter(id -> !included.contains(id))
                .distinct()
                .toList();
        for (String id : prerequisites) {
            TechniqueVolume prerequisite = registry.findVolume(id).orElseThrow();
            if (!prerequisite.getTechniquesList().stream().allMatch(reference -> isKnown(progress, reference))) {
                throw new PrerequisiteVolumeException(id);
            }
        }
        var references = volumes.stream()
                .flatMap(volume -> volume.getTechniquesList().stream())
                .toList();
        return commitLearn(entityUuid, host, body, references);
    }

    private List<KnownEntry> learnReferences(UUID entityUuid, List<TechniqueReference> references) {
        // 单式与整卷都通过同一种进度活动提交，不保存独立的卷学习状态。
        TechniqueHost host = requireHost(entityUuid);
        BodySnapshot body = host.inspect().body();
        return commitLearn(entityUuid, host, body, references);
    }

    private List<KnownEntry> commitLearn(UUID entityUuid, TechniqueHost host, BodySnapshot body,
                                        List<TechniqueReference> references) {
        // 只添加缺少的精确版本，一次活动和一次通知覆盖整组新学单式。
        TechniqueProgress progress = TechniqueState.read(body);
        List<KnownTechnique> additions = references.stream()
                .filter(reference -> !isKnown(progress, reference))
                .map(reference -> knownRecord(reference, body.settledAt().elapsedMillis()))
                .toList();
        if (additions.isEmpty()) {
            return entries(progress);
        }
        BodyActivity activity = TechniqueActivities.learn(progress, additions);
        String requestId = learnRequestId(references);
        TechniqueHost.Submission submission = new TechniqueHost.Submission(requestId, body.revision(), activity);
        TechniqueHost.Commit commit = host.submit(submission);
        validateCommit(body, activity, commit);
        TechniqueProgress learned = progress.toBuilder().addAllKnown(additions).build();
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
        // 每 tick 只推进已成功施放的实体，不按毫秒轮询世界；僵直按各自身体时钟到期。
        for (UUID entityUuid : List.copyOf(sessions.keySet())) {
            Session session = sessions.get(entityUuid);
            if (session != null) {
                advance(entityUuid, session);
            }
        }
        for (UUID entityUuid : List.copyOf(staggerEnds.keySet())) {
            if (staggerRemainingMillis(entityUuid) == 0) {
                endStagger(entityUuid);
            }
        }
    }

    public Optional<CastOutcome> stagger(UUID entityUuid, Stagger stagger) {
        // 伤害附属按命中剂量换算僵直后调用；玩家与类人走同一入口，起手中的可打断式被打断。
        Objects.requireNonNull(entityUuid);
        Objects.requireNonNull(stagger);
        if (stagger.durationMillis() == 0 || !recordStagger(entityUuid, stagger)) {
            return Optional.empty();
        }
        return transition(entityUuid, Transition.STAGGER);
    }

    private boolean recordStagger(UUID entityUuid, Stagger stagger) {
        // 到期时刻使用实体自己的身体时钟；多次命中取较晚到期，不叠加时长。
        TechniqueHost host = hosts.resolve(entityUuid).orElse(null);
        if (host == null) {
            return false;
        }
        long nowMillis;
        try {
            nowMillis = host.inspect().body().settledAt().elapsedMillis();
        } catch (TechniqueErrors.HostRejected exception) {
            return false;
        }
        long untilMillis = nowMillis + stagger.durationMillis();
        Long previous = staggerEnds.get(entityUuid);
        if (previous == null || untilMillis > previous) {
            staggerEnds.put(entityUuid, untilMillis);
            publishStagger(new StaggerChanged(entityUuid, stagger.durationMillis(), stagger.movementMultiplier()));
        }
        return true;
    }

    public long staggerRemainingMillis(UUID entityUuid) {
        // 未受击、宿主失效或身体时钟已越过到期时刻都视为没有僵直。
        Long untilMillis = staggerEnds.get(Objects.requireNonNull(entityUuid));
        if (untilMillis == null) {
            return 0;
        }
        TechniqueHost host = hosts.resolve(entityUuid).orElse(null);
        if (host == null) {
            return 0;
        }
        try {
            long nowMillis = host.inspect().body().settledAt().elapsedMillis();
            return Math.max(0, untilMillis - nowMillis);
        } catch (TechniqueErrors.HostRejected exception) {
            return 0;
        }
    }

    private long staggeredUntil(UUID entityUuid) {
        // 尚未清理的过期记录交给状态机按当前身体时钟比较。
        return staggerEnds.getOrDefault(entityUuid, NOT_STAGGERED_MILLIS);
    }

    private void endStagger(UUID entityUuid) {
        // 结束事件让表现层恢复移动速度并清除界面显示。
        if (staggerEnds.remove(entityUuid) != null) {
            publishStagger(new StaggerChanged(entityUuid, 0, 1));
        }
    }

    public void addStaggerListener(Consumer<StaggerChanged> listener) {
        // 表现层订阅僵直开始与结束；监听器只在服务线程调用。
        staggerListeners.add(Objects.requireNonNull(listener));
    }

    public void removeStaggerListener(Consumer<StaggerChanged> listener) {
        // 服务器停止时移除表现层监听器，避免保留旧服务器对象。
        staggerListeners.remove(listener);
    }

    public void addCastListener(Consumer<CastObserved> listener) {
        // 调试用途，不进正式玩法：观察每次施放请求的回执，监听器只在服务线程调用。
        castListeners.add(Objects.requireNonNull(listener));
    }

    public void removeCastListener(Consumer<CastObserved> listener) {
        // 服务器停止时移除调试监听器，避免保留旧服务器对象。
        castListeners.remove(listener);
    }

    public void addSoundListener(Consumer<SoundDue> listener) {
        // 表现层订阅施放者声音；监听器只在服务线程调用。
        soundListeners.add(Objects.requireNonNull(listener));
    }

    public void removeSoundListener(Consumer<SoundDue> listener) {
        // 服务器停止时移除表现层监听器，避免保留旧服务器对象。
        soundListeners.remove(listener);
    }

    private void publishSound(SoundDue event) {
        // 播放失败不能打断同一批里其余效果的交付。
        for (Consumer<SoundDue> listener : List.copyOf(soundListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 声音只是表现，施放进度仍以本服务与宿主回执为准。
            }
        }
    }

    private void publishCast(CastObserved event) {
        // 观察者故障不能改变已经返回给施放方的回执。
        for (Consumer<CastObserved> listener : List.copyOf(castListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 调试记录失败只影响观察输出，施放状态仍以本服务为准。
            }
        }
    }

    private void publishStagger(StaggerChanged event) {
        // 表现失败不能撤销已经生效的僵直判定。
        for (Consumer<StaggerChanged> listener : List.copyOf(staggerListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 移动倍率与界面同步只是表现，服务端判定仍以本服务记录为准。
            }
        }
    }

    public Optional<CastOutcome> cancelOnUnload(UUID entityUuid) {
        // 实体卸载前清除动作和当前施放，费用与冷却由已保存进度保留；僵直随实体离场结束。
        endStagger(Objects.requireNonNull(entityUuid));
        return transition(entityUuid, Transition.UNLOAD);
    }

    public Optional<CastOutcome> cancelOnDisconnect(UUID entityUuid) {
        // 玩家下线沿用卸载语义；调用方应在撤销宿主访问前调用此入口。
        endStagger(Objects.requireNonNull(entityUuid));
        return transition(entityUuid, Transition.DISCONNECT);
    }

    public void recoverOnLoad(UUID entityUuid, TechniqueRecoveryHost host) {
        // 恢复只停止表现并解锁；死体动作已由 Framework 清空。
        // 再通过可信进度入口清除残留施放。
        Objects.requireNonNull(entityUuid);
        BodySnapshot body = host.snapshot();
        TechniqueProgress progress = TechniqueState.read(body);
        if (progress.hasActiveCast()) {
            clearRecoveredEffects(host, progress.getActiveCast(), body.settledAt().elapsedMillis());
            BodyCommand command = recoveryCommand(body, progress);
            String requestId = "technique_recover_" + progress.getActiveCast().getRequest().getCastId();
            long revision = host.submitRecovery(requestId, command);
            if (revision != body.revision() + 1) {
                throw new IllegalStateException("宿主返回的恢复提交版本不一致");
            }
        }
        sessions.remove(entityUuid);
    }

    private static void clearRecoveredEffects(TechniqueRecoveryHost host, ActiveCast active, long nowMillis) {
        // 先清理宿主约束，再清除进度；任何拒绝仍保留可重试的原始施放身份。
        for (EffectIntent effect : TechniqueScheduler.stopEffects(active, nowMillis, nowMillis)) {
            String requestId = effectRequestId(effect);
            host.submitEffect(new TechniqueHost.EffectSubmission(requestId, effect));
        }
    }

    private static BodyCommand recoveryCommand(BodySnapshot body, TechniqueProgress progress) {
        // 存活身体原子解除动作；死体保留费用、冷却、知识和其他附属进度。
        var command = BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision());
        if (body.vitality().dead()) {
            TechniqueProgress stopped = TechniqueActivities.endedProgress(progress,
                    body.settledAt().elapsedMillis());
            SetAddonProgress update = SetAddonProgress.newBuilder()
                    .setId(TechniqueState.KEY)
                    .setProgress(TechniqueState.pack(stopped))
                    .build();
            command.setSetAddonProgress(update);
        } else {
            command.setExecuteActivity(TechniqueActivities.stopAt(progress, body.settledAt().elapsedMillis()));
        }
        return command.build();
    }

    public void shutdown() {
        // 服务器停止前逐个取消仍在运行的施放。
        // 这样下一次装载不会把旧动作当作新世界事实。
        for (UUID entityUuid : List.copyOf(sessions.keySet())) {
            cancelOnUnload(entityUuid);
        }
        staggerEnds.clear();
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("功法清理尚未完成，宿主不能提前撤销访问");
        }
    }

    private void register(UUID entityUuid, TechniqueHost host) {
        // 起手成功后把效果游标放在起手前一毫秒，确保 at=0 的效果只发一次。
        BodySnapshot body = host.inspect().body();
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return;
        }
        ActiveCast active = progress.getActiveCast();
        String castId = active.getRequest().getCastId();
        Session previous = sessions.get(entityUuid);
        if (previous != null && previous.castId.equals(castId)) {
            return;
        }
        long previousMillis = previousMillis(active.getStartedMillis());
        List<EffectIntent> cleanup = TechniqueScheduler.stopEffects(active,
                active.getRecoveryEndMillis(), active.getRecoveryEndMillis());
        Session session = new Session(host, previousMillis, castId, cleanup);
        sessions.put(entityUuid, session);
        if (TechniqueMovement.multiplier(active) < 1 || active.getPredecessorCleanupCount() > 0) {
            advance(entityUuid, session);
        }
    }

    private void advance(UUID entityUuid, Session session) {
        // 回调只能排队迁移，同一实体不能嵌套交付尚未确认的批次。
        if (session.processing) {
            return;
        }
        process(entityUuid, session, () -> {
            advanceOnce(entityUuid, session);
            return Optional.empty();
        });
    }

    private void advanceOnce(UUID entityUuid, Session session) {
        // 正常推进与收势共用实际身体时刻；已生成的效果保留到全部收到回执。
        try {
            if (session.terminating) {
                terminate(entityUuid, session);
                return;
            }
            if (session.pendingAdvance == null) {
                TechniqueCasting.AdvanceResult result = casting.advance(session.host, session.previousMillis);
                if (!success(result.outcome())) {
                    handleFailure(entityUuid, session, result.outcome().getFrameworkFailure());
                    return;
                }
                session.pendingAdvance = result;
            }
            deliverPending(entityUuid, session);
        } catch (TechniqueErrors.HostRejected exception) {
            handleFailure(entityUuid, session, exception.failure());
        } catch (IllegalArgumentException exception) {
            // 即使业务进度损坏，已经开始的表现与移动锁仍有清理责任。
            beginTermination(entityUuid, session);
        }
    }

    private void deliverPending(UUID entityUuid, Session session) {
        // 收势可能已提交；效果拒绝时保存原结果和请求 ID，不能重新 inspect 后丢掉意图。
        TechniqueCasting.AdvanceResult result = session.pendingAdvance;
        dispatch(entityUuid, session, result.effects());
        session.previousMillis = result.advancedMillis();
        session.pendingAdvance = null;
        if (result.recoveryCommit().isPresent() || result.outcome().getPhase() == CastPhase.IDLE) {
            sessions.remove(entityUuid);
        }
    }

    private void handleFailure(UUID entityUuid, Session session, FailureCode failure) {
        // 暂时拒绝重试原批次；永久业务拒绝转为取消，不再重放剩余业务效果。
        if (!retryable(failure)) {
            beginTermination(entityUuid, session);
        }
    }

    private static boolean retryable(FailureCode failure) {
        // 只有状态尚可能自行恢复的错误才继续尝试原业务效果。
        switch (failure) {
            case REVISION_CONFLICT, TIME_REQUIRES_SETTLEMENT, PENDING_TIME_EVENT,
                    ENVIRONMENT_UNAVAILABLE, ENVIRONMENT_REVISION_CONFLICT, INTERNAL_ERROR -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void beginTermination(UUID entityUuid, Session session) {
        // 清理声明独立于业务游标，覆盖部分成功和身体已先收势的两种情况。
        session.terminating = true;
        session.pendingAdvance = null;
        terminate(entityUuid, session);
    }

    private void terminate(UUID entityUuid, Session session) {
        // 不论拒绝码是否可重试，都保留取消及外部清理，直到收到真实成功回执。
        stopBody(session);
        for (EffectIntent effect : List.copyOf(session.cleanup.values())) {
            try {
                String requestId = effectRequestId(effect);
                session.host.submitEffect(new TechniqueHost.EffectSubmission(requestId, effect));
                session.cleanup.remove(requestId);
            } catch (TechniqueErrors.HostRejected | IllegalArgumentException exception) {
                // 每项独立重试，表现停止被拒不能阻止移动解锁。
            }
        }
        if (session.bodyStopped && session.cleanup.isEmpty()) {
            sessions.remove(entityUuid, session);
        }
    }

    private void stopBody(Session session) {
        // 身体取消与外部效果清理各自保存完成状态，互相不阻塞。
        if (session.bodyStopped) {
            return;
        }
        try {
            TechniqueCasting.AdvanceResult stopped = casting.cancelOnUnload(session.host, session.previousMillis);
            if (success(stopped.outcome())) {
                session.bodyStopped = true;
                session.terminationOutcome = stopped.outcome();
            }
        } catch (TechniqueErrors.HostRejected | IllegalArgumentException exception) {
            // 下一 tick 或生命周期取消仍使用原施放身份。
        }
    }

    private void dispatch(UUID entityUuid, Session session, List<EffectIntent> effects) {
        // 请求身份只依赖施放和效果，不受 tick 分组或部分成功重试影响。
        // 施放者声音不提交宿主，交给声音监听者；宿主拒绝后整批重试时，同一请求 ID 只响一次。
        for (EffectIntent effect : effects) {
            String requestId = effectRequestId(effect);
            if (!effect.hasSound()) {
                session.host.submitEffect(new TechniqueHost.EffectSubmission(requestId, effect));
            } else if (session.sounded.add(requestId)) {
                publishSound(new SoundDue(entityUuid, effect.getSound()));
            }
        }
    }

    private Optional<CastOutcome> transition(UUID entityUuid, Transition transition) {
        // 取消只使用本服务保存的宿主和事件游标，避免实体身份变化后误写另一具身体。
        Session session = sessions.get(entityUuid);
        if (session == null) {
            return Optional.empty();
        }
        if (session.processing) {
            session.defer(transition);
            return Optional.empty();
        }
        if (session.deferred != null) {
            session.defer(transition);
        }
        return process(entityUuid, session, () -> transitionOnce(entityUuid, session, transition));
    }

    private Optional<CastOutcome> process(UUID entityUuid, Session session,
                                         Supplier<Optional<CastOutcome>> operation) {
        // 当前交付结束后最多消费一个排队迁移；回调再次排队留到下一 tick。
        // 这样避免递归或忙循环。
        session.processing = true;
        try {
            Transition queued = session.deferred;
            session.deferred = null;
            if (queued != null) {
                return transitionOnce(entityUuid, session, queued);
            }
            Optional<CastOutcome> result = operation.get();
            Transition deferred = session.deferred;
            session.deferred = null;
            if (deferred != null && sessions.get(entityUuid) == session) {
                transitionOnce(entityUuid, session, deferred);
            }
            return result;
        } finally {
            session.processing = false;
        }
    }

    private Optional<CastOutcome> transitionOnce(UUID entityUuid, Session session, Transition transition) {
        // 卸载不再补发尚未确认的业务效果，只完成取消和独立清理。
        if (transition != Transition.STAGGER || session.terminating) {
            beginTermination(entityUuid, session);
            return Optional.ofNullable(session.terminationOutcome);
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
                case STAGGER -> casting.interruptByStagger(session.host, session.previousMillis);
                case UNLOAD -> casting.cancelOnUnload(session.host, session.previousMillis);
                case DISCONNECT -> casting.cancelOnDisconnect(session.host, session.previousMillis);
            };
            if (!success(result.outcome())) {
                handleFailure(entityUuid, session, result.outcome().getFrameworkFailure());
            } else if (result.recoveryCommit().isPresent() || result.outcome().getPhase() == CastPhase.IDLE) {
                session.pendingAdvance = result;
                deliverPending(entityUuid, session);
            }
            return Optional.of(result.outcome());
        } catch (TechniqueErrors.HostRejected exception) {
            handleFailure(entityUuid, session, exception.failure());
            return Optional.empty();
        } catch (IllegalArgumentException exception) {
            beginTermination(entityUuid, session);
            return Optional.empty();
        }
    }

    public Optional<TechniqueHost> statusHost(UUID entityUuid) {
        // 状态宿主复用同一认领与活动协议，无身体实体返回空，不创建或代付身体。
        return hosts.resolve(Objects.requireNonNull(entityUuid));
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

    private static String learnRequestId(List<TechniqueReference> references) {
        // UUID 名称哈希把任意内容 ID 压到有界且可重试的 Framework request_id。
        TechniqueVolume selection = TechniqueVolume.newBuilder().addAllTechniques(references).build();
        UUID requestUuid = UUID.nameUUIDFromBytes(selection.toByteArray());
        return LEARN_REQUEST_PREFIX + requestUuid;
    }

    private static String effectRequestId(EffectIntent effect) {
        // 同一施放中的各效果独立去重，停止表现和解锁也各有固定身份。
        return EFFECT_REQUEST_PREFIX + effect.getCastId() + "_" + effect.getEffectId();
    }

    public static final class PrerequisiteVolumeException extends IllegalArgumentException {
        public PrerequisiteVolumeException(String volumeId) {
            // 命令直接展示可操作的前置卷身份，调用方无需解析内部校验路径。
            super("请先学会前置卷：" + volumeId);
        }
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
        return outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
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
                .setFailure(TechniqueFailure.NO_HOST)
                .setReason(TechniqueErrors.playerReason(TechniqueFailure.NO_HOST))
                .build();
    }

    private static CastOutcome pendingFailure(CastRequest request) {
        // 清理或同步回调期间不允许新施放覆盖原会话及其清理身份。
        return CastOutcome.newBuilder()
                .setCastId(request.getCastId())
                .setFailure(TechniqueFailure.FRAMEWORK_REJECTED)
                .setFrameworkFailure(FailureCode.INTERNAL_ERROR)
                .setReason("气息尚未接续，请稍候再试")
                .build();
    }

    public record Stagger(long durationMillis, double movementMultiplier) {
        public Stagger {
            // 时长为身体时钟毫秒；倍率只减速不加速，越界说明伤害附属的配置未经校验。
            if (durationMillis < 0 || durationMillis > MAX_STAGGER_MILLIS) {
                throw new IllegalArgumentException("僵直时长必须在 [0, 60000] 毫秒内");
            }
            if (Double.isNaN(movementMultiplier) || movementMultiplier <= 0 || movementMultiplier > 1) {
                throw new IllegalArgumentException("僵直移动倍率必须在 (0, 1] 内");
            }
        }
    }

    /** 僵直开始或结束；剩余 0 表示结束，表现层据此恢复移动速度。 */
    public record StaggerChanged(UUID entityUuid, long remainingMillis, double movementMultiplier) {
        public StaggerChanged {
            // 事件只描述一次判定结果，监听器不能借此改写服务状态。
            Objects.requireNonNull(entityUuid);
        }
    }

    /** 一次施放请求的回执；调试用途，不进正式玩法。 */
    public record CastObserved(UUID entityUuid, CastOutcome outcome) {
        public CastObserved {
            // 事件只转述同一份回执，监听器不能借此改写施放状态。
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(outcome);
        }
    }

    /** 一次施放者声音到期：目录里锚点为 CASTER 的声音，由表现层在该实体处播放。 */
    public record SoundDue(UUID entityUuid, SoundCue cue) {
        public SoundDue {
            // 事件只转述目录声明，监听器不能借此改写施放状态。
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(cue);
        }
    }

    /** 一次跃步做功：接受时为已提交的组织做功，拒绝时为原因。 */
    public record LeapExertion(List<ActivityEffort> efforts, String rejection) {
        public static final String NO_HOST = "NO_HOST";
        public static final String ACTIVE_CAST = "ACTIVE_CAST";
        public static final String NO_BINDING = "NO_BINDING";

        public LeapExertion {
            // 做功明细固定为一份快照；原因为空表示宿主已接受提交。
            efforts = List.copyOf(efforts);
            Objects.requireNonNull(rejection);
        }

        static LeapExertion paid(List<ActivityEffort> efforts) {
            // 成功只记录交给宿主的同一份做功计划。
            return new LeapExertion(efforts, "");
        }

        public static LeapExertion rejected(String rejection) {
            // 拒绝不带做功，调用方按普通跳跃处理。
            return new LeapExertion(List.of(), rejection);
        }

        public boolean accepted() {
            // 原因为空即已提交，不另存一份可能矛盾的标记。
            return rejection.isEmpty();
        }
    }

    /** 一次只读观察：对方当前身体快照与施放预报。 */
    public record Observation(BodySnapshot body, Optional<TechniqueForecasts.CastForecast> cast) {
        public Observation {
            // 身体必有，施放预报可空。
            Objects.requireNonNull(body);
            Objects.requireNonNull(cast);
        }
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
        private final String castId;
        private long previousMillis;
        private TechniqueCasting.AdvanceResult pendingAdvance;
        private final Map<String, EffectIntent> cleanup = new LinkedHashMap<>();
        /** 已交给声音监听者的请求 ID；整批重试时跳过，避免同一声音重复播放。 */
        private final Set<String> sounded = new HashSet<>();
        private boolean terminating;
        private boolean bodyStopped;
        private boolean processing;
        private Transition deferred;
        private CastOutcome terminationOutcome;

        private Session(TechniqueHost host, long previousMillis, String castId, List<EffectIntent> cleanup) {
            // 游标只属于一次服务实例；身体和持久进度仍由宿主持有。
            this.host = host;
            this.castId = castId;
            this.previousMillis = previousMillis;
            for (EffectIntent effect : cleanup) {
                this.cleanup.put(effectRequestId(effect), effect);
            }
        }

        private void defer(Transition transition) {
            // 卸载与断线优先于僵直，重复取消合并为一次迁移。
            if (deferred == null || transition != Transition.STAGGER) {
                deferred = transition;
            }
        }
    }

    private enum Transition {
        STAGGER,
        UNLOAD,
        DISCONNECT
    }
}
