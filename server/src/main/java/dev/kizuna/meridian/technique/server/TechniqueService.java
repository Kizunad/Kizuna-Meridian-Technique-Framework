package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityEffort;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.SetAddonProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EndActiveRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ElementalInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
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
import java.util.function.BiPredicate;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 服务端主线程使用的功法服务；状态和世界副作用仍归实体宿主。 */
public final class TechniqueService {
    public static final String LEARN_SOURCE = "meridian_technique:command";
    private static final String LEARN_REQUEST_PREFIX = "technique_learn_";
    private static final String EFFECT_REQUEST_PREFIX = "technique_effect_";
    private static final String END_INFUSION_PREFIX = "technique_end_infusion_";
    private static final long BEFORE_START_MILLIS = Long.MIN_VALUE;
    private static final long NOT_STAGGERED_MILLIS = Long.MIN_VALUE;
    private static final long MAX_STAGGER_MILLIS = 60_000;

    private final TechniqueRegistry registry;
    private final TechniqueHostDirectory hosts;
    private final TechniqueCasting casting;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final List<Consumer<KnownChanged>> knownChangedListeners = new ArrayList<>();
    private final Map<StaggerKey, Long> staggerEnds = new LinkedHashMap<>();
    private final List<Consumer<StaggerChanged>> staggerListeners = new ArrayList<>();
    private final List<Consumer<CastObserved>> castListeners = new ArrayList<>();
    private final List<Consumer<CastStateChanged>> castStateListeners = new ArrayList<>();
    private final List<Consumer<TechniqueCastEnded>> castEndListeners = new ArrayList<>();
    private final List<Consumer<InfusionBegan>> infusionListeners = new ArrayList<>();
    private final List<Consumer<SoundDue>> soundListeners = new ArrayList<>();
    private final List<Consumer<StrikeReport>> strikeListeners = new ArrayList<>();
    private TechniqueEffectHandler effectHandler;
    private final List<BiPredicate<UUID, CastRequest>> castGuards = new ArrayList<>();
    private final List<BiFunction<UUID, CastRequest, Optional<String>>> castChecks = new ArrayList<>();

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

    public CastOutcome endActive(UUID entityUuid, EndActiveRequest request) {
        // 收势请求与起式分开校验，不重做费用或宿主起式准入。
        Objects.requireNonNull(entityUuid);
        try {
            TechniqueProtocol.validateEndActive(request);
        } catch (IllegalArgumentException rejected) {
            return releaseFailure(request, TechniqueFailure.INVALID_REQUEST);
        }
        try {
            return receiveEndActive(entityUuid, request);
        } catch (TechniqueErrors.HostRejected rejected) {
            return hostFailureOutcome(request.getCastId(), rejected.failure());
        } catch (TechniqueErrors.Rejected rejected) {
            return releaseFailure(request, rejected.code);
        } catch (IllegalArgumentException rejected) {
            return releaseFailure(request, TechniqueFailure.INVALID_STATE);
        }
    }

    public Optional<GuardState> activeGuard(UUID entityUuid) {
        // 已接收收势或正在终止时立即撤防，不等活动重试；其余只读权威进度。
        Session session = sessions.get(Objects.requireNonNull(entityUuid));
        if (session != null && (session.terminating || session.pendingRelease != null
                || session.deferred != null && session.deferred != Transition.STAGGER)) {
            return Optional.empty();
        }
        TechniqueHost host = hosts.resolve(entityUuid).orElse(null);
        if (host == null) {
            return Optional.empty();
        }
        try {
            BodySnapshot body = host.inspect().body();
            ActiveCast active = TechniqueState.read(body).getActiveCast();
            var vitality = body.vitality();
            if (vitality.dead() || vitality.remainingSeconds() == 0 || !active.hasSustained()
                    || TechniqueScheduler.phase(active, body.settledAt().elapsedMillis()) != CastPhase.EFFECTIVE
                    || hardControlled(entityUuid)) {
                return Optional.empty();
            }
            CastRequest request = active.getRequest();
            return Optional.of(new GuardState(body.id().value(), request.getCastId(),
                    request.getTechnique(), active.getSustained().getGuard()));
        } catch (TechniqueErrors.HostRejected rejected) {
            return Optional.empty();
        }
    }

    private CastOutcome receiveEndActive(UUID entityUuid, EndActiveRequest request) {
        // 首次接受时刻随当前会话保存，重复请求不延长收势。
        Session session = sessions.get(entityUuid);
        TechniqueHost host = hosts.resolve(entityUuid).orElse(null);
        if (host == null) {
            return releaseFailure(request, TechniqueFailure.NO_HOST);
        }
        BodySnapshot body = host.inspect().body();
        var vitality = body.vitality();
        if (!body.id().value().equals(request.getBodyId()) || vitality.dead() || vitality.remainingSeconds() == 0) {
            return releaseFailure(request, TechniqueFailure.BODY_UNAVAILABLE);
        }
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return releaseIdle(request);
        }
        ActiveCast active = progress.getActiveCast();
        validateReleaseSession(session, active, request);
        boolean replayed = session.pendingRelease != null || active.getSustained().hasEndRequestedMillis();
        if (!replayed && !session.terminating) {
            session.pendingRelease = new PendingRelease(request, body.settledAt().elapsedMillis());
        }
        if (!session.processing) {
            advance(entityUuid, session);
        }
        var receipt = CastOutcome.newBuilder()
                .setCastId(request.getCastId())
                .setReplayed(replayed);
        return receivedRelease(session, receipt);
    }

    private static void validateReleaseSession(Session session, ActiveCast active, EndActiveRequest request) {
        // 身体已经匹配后再验证施放身份，旧请求不能作用于后来的一式。
        String castId = request.getCastId();
        if (!active.getRequest().getCastId().equals(castId)) {
            throw TechniqueErrors.rejected(TechniqueFailure.CAST_ID_CONFLICT);
        }
        if (!active.hasSustained()) {
            throw TechniqueErrors.rejected(TechniqueFailure.INVALID_REQUEST);
        }
        if (session == null || !session.castId.equals(castId)) {
            throw TechniqueErrors.rejected(TechniqueFailure.INVALID_STATE);
        }
    }

    private static CastOutcome receivedRelease(Session session, CastOutcome.Builder receipt) {
        // 成功提交后返回新阶段；排队时只报告最后确认的身体事实。
        if (session.bodyStopped && session.terminationOutcome != null) {
            return receipt.setPhase(session.terminationOutcome.getPhase())
                    .setRevision(session.terminationOutcome.getRevision())
                    .build();
        }
        BodySnapshot body = session.inspected;
        ActiveCast active = TechniqueState.read(body).getActiveCast();
        CastPhase phase = CastPhase.FINISHED;
        if (active.hasSustained()) {
            phase = TechniqueScheduler.phase(active, body.settledAt().elapsedMillis());
        }
        return receipt.setPhase(phase)
                .setRevision(body.revision())
                .build();
    }

    private static CastOutcome releaseFailure(EndActiveRequest request, TechniqueFailure failure) {
        // 收势沿用施放的结构化失败，不要求下游识别异常文本。
        return CastOutcome.newBuilder()
                .setCastId(request.getCastId())
                .setFailure(failure)
                .setReason(TechniqueErrors.playerReason(failure))
                .build();
    }

    private void applyRelease(UUID entityUuid, Session session, BodySnapshot body) {
        // 请求只在交付安全点写入；宿主瞬时拒绝时保留同一请求与首次接收时刻。
        PendingRelease pending = session.pendingRelease;
        EndActiveRequest request = pending.request();
        long requestedMillis = pending.requestedMillis();
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            session.pendingRelease = null;
            return;
        }
        ActiveCast active = progress.getActiveCast();
        if (!body.id().value().equals(request.getBodyId())
                || !active.getRequest().getCastId().equals(request.getCastId())) {
            throw TechniqueErrors.invalid("end_active", "会话身体或施放身份已变化");
        }
        if (!active.getSustained().hasEndRequestedMillis()) {
            BodyActivity activity = TechniqueSustained.release(body, requestedMillis);
            String requestId = "technique_release_" + session.castId;
            Optional<TechniqueHost.Commit> receipt = session.host.findActivity(requestId);
            if (receipt.isEmpty()) {
                TechniqueHost.Commit commit = session.host.submit(
                        new TechniqueHost.Submission(requestId, body.revision(), activity));
                validateCommit(body, activity, commit);
            }
        }
        BodySnapshot releasedBody = session.host.inspect().body();
        session.inspected = releasedBody;
        session.pendingRelease = null;
        ActiveCast released = TechniqueState.read(releasedBody).getActiveCast();
        if (!released.getSustained().hasEndRequestedMillis()) {
            throw TechniqueErrors.invalid("end_active", "收势回执没有对应的已保存收势事实");
        }
        publishState(entityUuid, session, released, requestedMillis);
    }

    private void publishState(UUID entityUuid, Session session, ActiveCast active, long millis) {
        // 只发布真实阶段或首次确定的结束边界；同阶段 tick 不重播表现。
        if (!active.hasSustained()) {
            return;
        }
        CastPhase phase = TechniqueScheduler.phase(active, millis);
        if (phase == CastPhase.FINISHED) {
            return;
        }
        ActiveCast previous = session.publishedActive;
        if (previous != null && phase == session.publishedPhase
                && previous.getSustained().equals(active.getSustained())) {
            return;
        }
        session.publishedActive = active;
        session.publishedPhase = phase;
        var event = new CastStateChanged(entityUuid, active, phase);
        for (Consumer<CastStateChanged> listener : List.copyOf(castStateListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 通知故障不回滚已经提交的施放事实。
            }
        }
    }

    private static CastOutcome releaseIdle(EndActiveRequest request) {
        // 同身体已无施放时，迟到的松开只得到幂等空闲回执。
        return CastOutcome.newBuilder()
                .setCastId(request.getCastId())
                .setPhase(CastPhase.IDLE)
                .setReplayed(true /* replayed */)
                .build();
    }

    private boolean hardControlled(UUID entityUuid) {
        // 普通受击不撤防，三种硬控各自按身体时钟判断。
        return staggerRemainingMillis(entityUuid, StaggerKind.CHARM) > 0
                || staggerRemainingMillis(entityUuid, StaggerKind.HOLD) > 0
                || staggerRemainingMillis(entityUuid, StaggerKind.DOWN) > 0;
    }

    public void addCastStateListener(Consumer<CastStateChanged> listener) {
        // 宿主订阅持续施放的阶段及结束边界变化。
        castStateListeners.add(Objects.requireNonNull(listener));
    }

    public void removeCastStateListener(Consumer<CastStateChanged> listener) {
        // 停服时移除宿主监听，避免保留旧世界对象。
        castStateListeners.remove(listener);
    }

    private CastOutcome resolveCast(UUID entityUuid, CastRequest request) {
        // 先经可信宿主准入，再确认唯一身体并校验目录、学会和冷却。
        for (var guard : castGuards) {
            if (!guard.test(entityUuid, request)) {
                return hostFailureOutcome(request.getCastId(), FailureCode.ACCESS_DENIED);
            }
        }
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
        if (previous == null || !previous.castId.equals(request.getCastId())) {
            for (var check : castChecks) {
                Optional<String> rejection = check.apply(entityUuid, request);
                if (rejection.isPresent()) {
                    return effectRejected(request.getCastId(), rejection.orElseThrow());
                }
            }
        }
        CastOutcome outcome = casting.cast(host, request, staggeredUntil(entityUuid));
        if (success(outcome)) {
            Session registered = register(entityUuid, host);
            if (registered != null && registered.terminationOutcome != null
                    && !success(registered.terminationOutcome)) {
                return registered.terminationOutcome;
            }
        }
        return outcome;
    }

    public TechniqueRegistry registry() {
        // 命令和宿主复用服务启动时固定的目录，不再次查询内容入口。
        return registry;
    }

    public void addCastGuard(BiPredicate<UUID, CastRequest> guard) {
        // 可信宿主在服务启动时登记准入规则，所有起式入口共用且不影响已付款施放的推进。
        castGuards.add(Objects.requireNonNull(guard));
    }

    public void addCastCheck(BiFunction<UUID, CastRequest, Optional<String>> check) {
        // 内容宿主在扣费前检查瞬时世界条件，拒绝原因原样返回；已起手的同身份重试不重验。
        castChecks.add(Objects.requireNonNull(check));
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
        return entries(TechniqueKnowledge.references(registry, body));
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
        var included = volumes.stream()
                .map(TechniqueVolume::getId)
                .collect(Collectors.toSet());
        var prerequisites = volumes.stream()
                .flatMap(volume -> volume.getPrerequisiteVolumeIdsList().stream())
                .filter(id -> !included.contains(id))
                .distinct()
                .toList();
        var references = volumes.stream()
                .flatMap(volume -> volume.getTechniquesList().stream())
                .toList();
        return commitLearn(entityUuid, host, body, references, prerequisites);
    }

    private List<KnownEntry> learnReferences(UUID entityUuid, List<TechniqueReference> references) {
        // 单式与整卷都通过同一种进度活动提交，不保存独立的卷学习状态。
        TechniqueHost host = requireHost(entityUuid);
        BodySnapshot body = host.inspect().body();
        return commitLearn(entityUuid, host, body, references, List.of());
    }

    private List<KnownEntry> commitLearn(UUID entityUuid, TechniqueHost host, BodySnapshot body,
                                        List<TechniqueReference> references, List<String> prerequisites) {
        // 只添加缺少的精确版本，一次活动和一次通知覆盖整组新学单式。
        TechniqueProgress progress = TechniqueState.read(body);
        for (var reference : references) {
            if (!registry.applies(reference, body.template())) {
                throw new BodyTemplateMismatchException();
            }
        }
        var knowledge = TechniqueKnowledge.references(registry, body, progress);
        for (String id : prerequisites) {
            TechniqueVolume prerequisite = registry.findVolume(id).orElseThrow();
            if (!knowledge.containsAll(prerequisite.getTechniquesList())) {
                throw new PrerequisiteVolumeException(id);
            }
        }
        List<KnownTechnique> additions = references.stream()
                .filter(reference -> !knowledge.contains(reference))
                .map(reference -> knownRecord(reference, body.settledAt().elapsedMillis()))
                .toList();
        if (additions.isEmpty()) {
            return entries(knowledge);
        }
        BodyActivity activity = TechniqueActivities.learn(progress, additions);
        String requestId = learnRequestId(references);
        TechniqueHost.Submission submission = new TechniqueHost.Submission(requestId, body.revision(), activity);
        TechniqueHost.Commit commit = host.submit(submission);
        validateCommit(body, activity, commit);
        TechniqueProgress learned = progress.toBuilder().addAllKnown(additions).build();
        List<KnownEntry> result = entries(TechniqueKnowledge.references(registry, body, learned));
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
        hosts.tick();
        for (UUID entityUuid : List.copyOf(sessions.keySet())) {
            Session session = sessions.get(entityUuid);
            if (session != null) {
                advance(entityUuid, session);
            }
        }
        for (StaggerKey key : List.copyOf(staggerEnds.keySet())) {
            if (staggerRemainingMillis(key.entityUuid(), key.kind()) == 0) {
                endStagger(key);
            }
        }
    }

    public Optional<CastOutcome> stagger(UUID entityUuid, Stagger stagger) {
        // Damage 写受击僵直，Brewing 写魅惑定身，袖手拳写被擒和倒地；都拒绝新式并打断可打断的起手。
        Objects.requireNonNull(entityUuid);
        Objects.requireNonNull(stagger);
        if (stagger.durationMillis() == 0 || !recordStagger(entityUuid, stagger)) {
            return Optional.empty();
        }
        if (stagger.kind() == StaggerKind.HIT) {
            return transition(entityUuid, Transition.STAGGER);
        }
        return transition(entityUuid, Transition.HARD_CONTROL);
    }

    private boolean recordStagger(UUID entityUuid, Stagger stagger) {
        // 各种僵直独立取较晚到期；受击不能覆盖魅惑定身的期限或强度。
        TechniqueHost host = hosts.resolve(entityUuid).orElse(null);
        if (host == null) {
            return false;
        }
        // 解析时已经向宿主 inspect 过一次，这里读的就是那份回执：inspect 失败时不落僵直。
        long nowMillis;
        try {
            nowMillis = host.inspect().body().settledAt().elapsedMillis();
        } catch (TechniqueErrors.HostRejected exception) {
            return false;
        }
        long untilMillis = nowMillis + stagger.durationMillis();
        var key = new StaggerKey(entityUuid, stagger.kind());
        Long previous = staggerEnds.get(key);
        if (previous == null || untilMillis > previous) {
            staggerEnds.put(key, untilMillis);
            publishStagger(new StaggerChanged(entityUuid, stagger.durationMillis(),
                    stagger.movementMultiplier(), stagger.kind()));
        }
        return true;
    }

    public void releaseStagger(UUID entityUuid, StaggerKind kind) {
        // 放手等提前解除只撤这一种期限并发布剩余 0，其余种类照旧按身体时钟到期。
        endStagger(new StaggerKey(Objects.requireNonNull(entityUuid), Objects.requireNonNull(kind)));
    }

    public long staggerRemainingMillis(UUID entityUuid) {
        // 对外总期限用于功法门禁和显示，保留原有查询契约。
        return remainingMillis(entityUuid, staggeredUntil(entityUuid));
    }

    public long staggerRemainingMillis(UUID entityUuid, StaggerKind kind) {
        // 世界宿主可按种类查询期限，不从总期限反推是否定身。
        var key = new StaggerKey(Objects.requireNonNull(entityUuid), Objects.requireNonNull(kind));
        return remainingMillis(entityUuid, staggerEnds.getOrDefault(key, NOT_STAGGERED_MILLIS));
    }

    public long infusionRemainingMillis(UUID entityUuid) {
        // 附行还剩多久，供 AI 与显示使用；宿主不认领或拒绝查询都按没有附行。
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return 0;
        }
        try {
            return TechniqueInfusions.remainingMillis(host.inspect().body());
        } catch (TechniqueErrors.HostRejected exception) {
            return 0;
        }
    }

    private long remainingMillis(UUID entityUuid, long untilMillis) {
        // 宿主失效或身体时钟已越过到期时刻都视为没有僵直。
        if (untilMillis == NOT_STAGGERED_MILLIS) {
            return 0;
        }
        // 认领宿主能直接报身体时钟时只比较时钟，不为了一个到期时刻查询整具身体。
        Optional<TechniqueBodyStamp> stamp = hosts.bodyStamp(entityUuid);
        if (stamp.isPresent()) {
            return Math.max(0, untilMillis - stamp.get().clockMillis());
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
        // 任一种僵直仍有效就拒绝起式，各自的过期记录由 tick 单独清理。
        Objects.requireNonNull(entityUuid);
        long untilMillis = NOT_STAGGERED_MILLIS;
        for (StaggerKind kind : StaggerKind.values()) {
            untilMillis = Math.max(untilMillis,
                    staggerEnds.getOrDefault(new StaggerKey(entityUuid, kind), NOT_STAGGERED_MILLIS));
        }
        return untilMillis;
    }

    private void endStagger(UUID entityUuid) {
        // 死亡、卸载与断线同时释放全部种类的期限，逐种通知其输入和属性宿主。
        for (StaggerKind kind : StaggerKind.values()) {
            endStagger(new StaggerKey(entityUuid, kind));
        }
    }

    private void endStagger(StaggerKey key) {
        // 单种到期只撤销自己的表现，不解除另一种仍有效的僵直。
        if (staggerEnds.remove(key) != null) {
            publishStagger(new StaggerChanged(key.entityUuid(), 0, 1, key.kind()));
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

    public void addInfusionListener(Consumer<InfusionBegan> listener) {
        // 表现层订阅「附行写进身体了」，据此挂图标；监听器只在服务线程调用。
        infusionListeners.add(Objects.requireNonNull(listener));
    }

    public void removeInfusionListener(Consumer<InfusionBegan> listener) {
        // 服务器停止时移除表现层监听器，避免保留旧服务器对象。
        infusionListeners.remove(listener);
    }

    /** 身体上还没到期的附行和它的剩余时间；宿主不认领、拒绝查询、没有附行都是空。 */
    public Optional<InfusionBegan> activeInfusion(UUID entityUuid) {
        // 供表现层在实体装载后对一次图标；平时不要每 tick 调。
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return Optional.empty();
        }
        try {
            BodySnapshot body = host.inspect().body();
            return TechniqueInfusions.active(body).map(active -> new InfusionBegan(entityUuid, active.getInfusion(),
                    TechniqueInfusions.remainingMillis(body)));
        } catch (TechniqueErrors.HostRejected exception) {
            return Optional.empty();
        }
    }

    /**
     * 提前结束身体上的附行：它的图标被牛奶、指令清掉时由表现层调用，不留下看不见的附行。
     *
     * @param statusId 被清掉的图标；身体上的附行不是这一个（已经换了一式）就不动
     * @return 这次调用把附行清掉了
     */
    public boolean endInfusion(UUID entityUuid, String statusId) {
        // 没有附行、已到期、死体（另有清除路径）、宿主拒绝都返回 false；请求号带施放身份，重复提交无害。
        Objects.requireNonNull(statusId);
        TechniqueHost host = hosts.resolve(Objects.requireNonNull(entityUuid)).orElse(null);
        if (host == null) {
            return false;
        }
        try {
            BodySnapshot body = host.inspect().body();
            Optional<ActiveInfusion> active = TechniqueInfusions.active(body);
            if (active.isEmpty() || !active.get().getInfusion().getStatus().getId().equals(statusId)) {
                return false;
            }
            TechniqueProgress cleared = TechniqueState.read(body).toBuilder().clearActiveInfusion().build();
            BodyActivity activity = BodyActivity.newBuilder()
                    .putProgress(TechniqueState.KEY, TechniqueState.pack(cleared))
                    .build();
            BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
            host.submit(new TechniqueHost.Submission(END_INFUSION_PREFIX + active.get().getCastId(),
                    body.revision(), activity));
            return true;
        } catch (TechniqueErrors.HostRejected exception) {
            return false;
        }
    }

    public void addCastEndListener(Consumer<TechniqueCastEnded> listener) {
        // 附属模组按实体和 cast_id 释放世界接管，监听器只在服务线程调用。
        castEndListeners.add(Objects.requireNonNull(listener));
    }

    public void removeCastEndListener(Consumer<TechniqueCastEnded> listener) {
        // 服务器停止时移除附属监听器，避免保留旧世界对象。
        castEndListeners.remove(listener);
    }

    public void addSoundListener(Consumer<SoundDue> listener) {
        // 表现层订阅施放者声音；监听器只在服务线程调用。
        soundListeners.add(Objects.requireNonNull(listener));
    }

    public void removeSoundListener(Consumer<SoundDue> listener) {
        // 服务器停止时移除表现层监听器，避免保留旧服务器对象。
        soundListeners.remove(listener);
    }

    public void addStrikeListener(Consumer<StrikeReport> listener) {
        // 附属按施放统计实战打中与接住；监听器只在服务线程调用。
        strikeListeners.add(Objects.requireNonNull(listener));
    }

    public void removeStrikeListener(Consumer<StrikeReport> listener) {
        // 服务器停止时移除附属监听器，避免保留旧世界对象。
        strikeListeners.remove(listener);
    }

    public void reportStrike(StrikeReport report) {
        // 打击执行器和接招提供方结算后各报一次；引用未登记功法的报告不发布。
        Objects.requireNonNull(report);
        if (registry.find(report.technique()).isEmpty()) {
            throw new IllegalArgumentException("打中报告引用了未登记的功法");
        }
        for (Consumer<StrikeReport> listener : List.copyOf(strikeListeners)) {
            try {
                listener.accept(report);
            } catch (RuntimeException ignored) {
                // 统计失败只影响附属的计数，打击已经由宿主结算。
            }
        }
    }

    public void setEffectHandler(TechniqueEffectHandler handler) {
        // Fabric 事件负责多附属分发；服务本身只保留一个世界效果桥接层。
        effectHandler = Objects.requireNonNull(handler);
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

    private void publishInfusion(InfusionBegan event) {
        // 图标挂不上不能撤销已经写进身体的附行。
        for (Consumer<InfusionBegan> listener : List.copyOf(infusionListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 图标只是显示，附伤仍以身体上的附行为准。
            }
        }
    }

    private void publishCastEnded(TechniqueCastEnded event) {
        // 附属清理失败不能改变已经结束的身体状态；监听器只观察最终事实。
        for (Consumer<TechniqueCastEnded> listener : List.copyOf(castEndListeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 世界接管由附属自行补偿，Framework 不重放已完成的身体提交。
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
        Optional<CastOutcome> outcome = transition(entityUuid, Transition.UNLOAD);
        hosts.forget(entityUuid);
        return outcome;
    }

    public Optional<CastOutcome> cancelOnDisconnect(UUID entityUuid) {
        // 玩家下线沿用卸载语义；调用方应在撤销宿主访问前调用此入口。
        endStagger(Objects.requireNonNull(entityUuid));
        Optional<CastOutcome> outcome = transition(entityUuid, Transition.DISCONNECT);
        hosts.forget(entityUuid);
        return outcome;
    }

    public Optional<CastOutcome> cancelOnDeath(UUID entityUuid) {
        // 原版实体死亡也要释放附属世界占用，不依赖后续身体 tick 或卸载。
        endStagger(Objects.requireNonNull(entityUuid));
        return transition(entityUuid, Transition.DEATH);
    }

    public void recoverOnLoad(UUID entityUuid, TechniqueRecoveryHost host) {
        // 恢复只停止表现并解锁；死体动作已由 Framework 清空。
        // 再通过可信进度入口清除残留施放；死体没有施放时也要清掉附行。
        Objects.requireNonNull(entityUuid);
        BodySnapshot body = host.snapshot();
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast() && body.vitality().dead() && progress.hasActiveInfusion()) {
            clearDeadInfusion(host, body, progress);
        }
        if (progress.hasActiveCast()) {
            ActiveCast active = progress.getActiveCast();
            clearRecoveredEffects(host, active, body.settledAt().elapsedMillis());
            BodyCommand command = recoveryCommand(body, progress);
            String requestId = "technique_recover_" + active.getRequest().getCastId();
            long revision = host.submitRecovery(requestId, command);
            if (revision != body.revision() + 1) {
                throw new IllegalStateException("宿主返回的恢复提交版本不一致");
            }
            CastOutcome outcome = CastOutcome.newBuilder()
                    .setCastId(active.getRequest().getCastId())
                    .setPhase(CastPhase.CANCELLED)
                    .setRevision(revision)
                    .setReason("施放在身体装载时清理")
                    .build();
            Session session = sessions.get(entityUuid);
            if (session != null && session.castId.equals(active.getRequest().getCastId())) {
                session.terminationOutcome = outcome;
                session.endReason = TechniqueCastEndReason.UNLOADED;
                if (body.vitality().dead()) {
                    session.endReason = TechniqueCastEndReason.DIED;
                }
                finishSession(entityUuid, session);
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
        // 存活身体原子解除动作；死体保留费用、冷却、知识和其他附属进度，只清掉施放和附行。
        if (body.vitality().dead()) {
            TechniqueProgress stopped = TechniqueActivities.endedProgress(progress,
                    body.settledAt().elapsedMillis());
            return progressCommand(body, stopped.toBuilder().clearActiveInfusion().build());
        }
        return BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setExecuteActivity(TechniqueActivities.stopAt(progress, body.settledAt().elapsedMillis()))
                .build();
    }

    private static void clearDeadInfusion(TechniqueRecoveryHost host, BodySnapshot body,
                                          TechniqueProgress progress) {
        // 死体时钟停住，附行不会自己到期；复活后不能带着死前的附行。
        TechniqueProgress cleared = progress.toBuilder().clearActiveInfusion().build();
        String requestId = "technique_recover_infusion_" + progress.getActiveInfusion().getCastId();
        long revision = host.submitRecovery(requestId, progressCommand(body, cleared));
        if (revision != body.revision() + 1) {
            throw new IllegalStateException("宿主返回的附行清除版本不一致");
        }
    }

    private static BodyCommand progressCommand(BodySnapshot body, TechniqueProgress progress) {
        // 死体不能执行活动，只能经可信入口整份改写本库进度。
        SetAddonProgress update = SetAddonProgress.newBuilder()
                .setId(TechniqueState.KEY)
                .setProgress(TechniqueState.pack(progress))
                .build();
        return BodyCommand.newBuilder()
                .setBodyId(body.id().value())
                .setExpectedRevision(body.revision())
                .setSetAddonProgress(update)
                .build();
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

    private Session register(UUID entityUuid, TechniqueHost host) {
        // 起手成功后把效果游标放在起手前一毫秒，确保 at=0 的效果只发一次。
        BodySnapshot body = host.inspect().body();
        TechniqueProgress progress = TechniqueState.read(body);
        if (!progress.hasActiveCast()) {
            return null;
        }
        ActiveCast active = progress.getActiveCast();
        String castId = active.getRequest().getCastId();
        Session previous = sessions.get(entityUuid);
        if (previous != null && previous.castId.equals(castId)) {
            return previous;
        }
        if (previous != null) {
            // 后式已原子替换身体中的施放；前式的世界清理由后式携带，附属占用立即释放。
            previous.endReason = TechniqueCastEndReason.INTERRUPTED;
            previous.terminationOutcome = cancellationOutcome(previous.castId);
            finishSession(entityUuid, previous);
        }
        long previousMillis = previousMillis(active.getStartedMillis());
        long cleanupMillis = active.getRecoveryEndMillis();
        if (active.hasSustained()) {
            cleanupMillis = active.getWarmupEndMillis();
        }
        List<EffectIntent> cleanup = TechniqueScheduler.stopEffects(active, cleanupMillis, cleanupMillis);
        Session session = new Session(host, previousMillis, castId, cleanup);
        sessions.put(entityUuid, session);
        if (active.hasSustained()) {
            session.inspected = body;
            process(entityUuid, session, () -> {
                publishState(entityUuid, session, active, body.settledAt().elapsedMillis());
                advanceOnce(entityUuid, session);
                return Optional.empty();
            });
        }
        if (TechniqueMovement.multiplier(active) < 1 || active.getPredecessorCleanupCount() > 0
                || active.getLockTarget()) {
            advance(entityUuid, session);
        }
        session.initialResponseDelivered = true;
        return session;
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
        // 一个 tick 里这具身体只查询一次，查到的快照逐段往下传；
        // 某一段向宿主写过东西（做功、修正、收势、任何效果）后这份快照作废，下一段重新查询。
        if (quietTick(entityUuid, session)) {
            return;
        }
        try {
            BodySnapshot body = session.host.inspect().body();
            session.inspected = body;
            if (body.vitality().dead()) {
                // Framework 已清空死亡身体的动作，附属进度由恢复入口清理。
                session.bodyStopped = true;
                beginTermination(entityUuid, session, TechniqueCastEndReason.DIED,
                        cancellationOutcome(session.castId));
                return;
            }
            if (session.terminating) {
                terminate(entityUuid, session);
                return;
            }
            if (session.pendingRelease != null && session.pendingAdvance == null) {
                applyRelease(entityUuid, session, body);
                body = session.host.inspect().body();
                session.inspected = body;
            }
            // 迟到 tick 逐边界完成做功和效果；异常回执保留当前批次，下次仍从同一边界重试。
            for (int boundary = 0; boundary <= TechniqueValues.MAX_TIMELINE_EVENTS + 1; boundary++) {
                Optional<BodySnapshot> next = advanceBoundary(entityUuid, session, body);
                if (next.isEmpty()) {
                    return;
                }
                body = next.get();
            }
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome outcome = hostFailureOutcome(session.castId, exception.failure());
            handleFailure(entityUuid, session, outcome);
        } catch (IllegalArgumentException exception) {
            // 即使业务进度损坏，已经开始的表现与移动锁仍有清理责任。
            beginTermination(entityUuid, session, TechniqueCastEndReason.FAILED,
                    invalidStateOutcome(session.castId));
        }
    }

    private boolean quietTick(UUID entityUuid, Session session) {
        // 这一 tick 可以整个跳过、连身体都不查询，要同时满足两条：
        // 一、宿主报的已提交版本还是本会话上次查询到的那一版，即身体从那以后没被任何人改过；
        // 二、按宿主报的时钟用那份身体试算，没有任何到期的曲线、做功、效果或收势。
        // 版本对不上、宿主报不出读数、有没交付完的批次或正在清理时，一律照常查询。
        if (session.inspected == null || session.terminating || session.pendingAdvance != null
                || session.pendingRelease != null) {
            return false;
        }
        Optional<TechniqueBodyStamp> stamp = hosts.bodyStamp(entityUuid);
        if (stamp.isEmpty() || stamp.get().revision() != session.inspected.revision()) {
            return false;
        }
        return casting.quietAt(session.inspected, session.previousMillis, stamp.get().clockMillis());
    }

    /** 推进一个边界。后面还有到期的边界时返回下一段要用的身体，否则为空。 */
    private Optional<BodySnapshot> advanceBoundary(UUID entityUuid, Session session, BodySnapshot body) {
        // 每段做功使用身体进度去重，世界效果继续使用原有稳定效果身份。
        // 上一 tick 留下的批次是按当时的身体算的，交付后一律重新查询。
        boolean wrote = true;
        if (session.pendingAdvance == null) {
            TechniqueCasting.AdvanceResult result = casting.advance(session.host, body, session.previousMillis);
            if (!success(result.outcome())) {
                handleFailure(entityUuid, session, result.outcome());
                return Optional.empty();
            }
            session.pendingAdvance = result;
            wrote = wroteToHost(result, body);
            result.infusionBegan().ifPresent(began -> publishInfusion(
                    new InfusionBegan(entityUuid, began.infusion(), began.remainingMillis())));
        }
        if (!deliverPending(entityUuid, session)) {
            return Optional.empty();
        }
        if (sessions.get(entityUuid) != session || session.terminating || session.deferred != null
                || session.pendingRelease != null) {
            return Optional.empty();
        }
        BodySnapshot current = body;
        if (wrote) {
            current = session.host.inspect().body();
            session.inspected = current;
        }
        if (session.previousMillis < current.settledAt().elapsedMillis()) {
            return Optional.of(current);
        }
        return Optional.empty();
    }

    private static boolean wroteToHost(TechniqueCasting.AdvanceResult result, BodySnapshot body) {
        // 这一段只要提交过活动（版本变了、收势）或要交付任何效果，手里的快照就可能已经过期。
        return result.recoveryCommit().isPresent() || !result.effects().isEmpty()
                || result.outcome().getRevision() != body.revision();
    }

    private boolean deliverPending(UUID entityUuid, Session session) {
        // 收势可能已提交；效果拒绝时保存原结果和请求 ID，不能重新 inspect 后丢掉意图。
        TechniqueCasting.AdvanceResult result = session.pendingAdvance;
        Optional<String> rejection = dispatch(entityUuid, session, result.effects());
        session.previousMillis = result.advancedMillis();
        session.pendingAdvance = null;
        if (rejection.isPresent()) {
            CastOutcome outcome = effectRejected(session.castId, rejection.orElseThrow());
            beginTermination(entityUuid, session, TechniqueCastEndReason.FAILED, outcome);
            return false;
        }
        if (result.recoveryCommit().isPresent() || result.outcome().getPhase() == CastPhase.IDLE) {
            session.bodyStopped = true;
            session.terminationOutcome = result.outcome();
            if (session.endReason == null) {
                session.endReason = TechniqueCastEndReason.COMPLETED;
            }
            finishSession(entityUuid, session);
        } else if (session.inspected != null) {
            ActiveCast active = TechniqueState.read(session.inspected).getActiveCast();
            publishState(entityUuid, session, active, result.advancedMillis());
        }
        return true;
    }

    private void handleFailure(UUID entityUuid, Session session, CastOutcome outcome) {
        // 暂时拒绝重试原批次；永久业务拒绝转为取消，不再重放剩余业务效果。
        FailureCode failure = outcome.getFrameworkFailure();
        if (!retryable(failure)) {
            beginTermination(entityUuid, session, TechniqueCastEndReason.FAILED, outcome);
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

    private void beginTermination(UUID entityUuid, Session session, TechniqueCastEndReason reason,
                                  CastOutcome outcome) {
        // 清理声明独立于业务游标，覆盖部分成功和身体已先收势的两种情况。
        session.terminating = true;
        session.pendingRelease = null;
        session.pendingAdvance = null;
        session.endReason = reason;
        session.terminationOutcome = outcome;
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
        if (session.bodyStopped) {
            notifyEnded(entityUuid, session);
            if (session.cleanup.isEmpty()) {
                finishSession(entityUuid, session);
            }
        }
    }

    private void stopBody(Session session) {
        // 身体取消与外部效果清理各自保存完成状态，互相不阻塞。
        if (session.bodyStopped) {
            return;
        }
        try {
            if (session.host.inspect().body().vitality().dead()) {
                // 死亡身体的动作已经清空；残留进度由可信恢复入口清理。
                session.bodyStopped = true;
                return;
            }
            TechniqueCasting.AdvanceResult stopped;
            if (session.endReason == TechniqueCastEndReason.STAGGERED) {
                stopped = casting.interruptSustained(session.host, session.previousMillis);
            } else {
                stopped = casting.cancelOnUnload(session.host, session.previousMillis);
            }
            if (success(stopped.outcome())) {
                session.bodyStopped = true;
                if (session.terminationOutcome == null) {
                    session.terminationOutcome = stopped.outcome();
                }
            }
        } catch (TechniqueErrors.HostRejected | IllegalArgumentException exception) {
            // 下一 tick 或生命周期取消仍使用原施放身份。
        }
    }

    private Optional<String> dispatch(UUID entityUuid, Session session, List<EffectIntent> effects) {
        // 请求身份只依赖施放和效果，不受 tick 分组或部分成功重试影响。
        // 施放者声音不提交宿主，交给声音监听者；宿主拒绝后整批重试时，同一请求 ID 只响一次。
        for (EffectIntent effect : effects) {
            String requestId = effectRequestId(effect);
            if (!effect.hasSound()) {
                if (session.completedCleanup.contains(requestId)) {
                    continue;
                }
                if (effect.hasBlockWork() || effect.hasUseBrew() || effect.hasOrganRefine()
                        || effect.hasGrapple() || effect.hasThrow()
                        || effect.hasLaunchProjectile() && effect.getLaunchProjectile().getProjectile().hasBrew()) {
                    HeldItems heldItems = session.host.inspect().heldItems();
                    EffectHandling handling = handleExtensionEffect(entityUuid, effect, heldItems);
                    if (!handling.accepted()) {
                        return Optional.of(handling.reason());
                    }
                    continue;
                }
                session.host.submitEffect(new TechniqueHost.EffectSubmission(requestId, effect));
                if (session.cleanup.remove(requestId) != null) {
                    session.completedCleanup.add(requestId);
                }
            } else if (session.sounded.add(requestId)) {
                publishSound(new SoundDue(entityUuid, effect.getSound()));
            }
        }
        return Optional.empty();
    }

    private EffectHandling handleExtensionEffect(UUID entityUuid, EffectIntent effect,
                                                  HeldItems heldItems) {
        // 附属入口缺失必须明确拒绝，不能把未实现的效果当作普通宿主效果吞掉。
        if (effectHandler == null) {
            return EffectHandling.rejected("没有注册该效果的附属处理器");
        }
        TechniqueEffectEvent event = new TechniqueEffectEvent(entityUuid, effect, heldItems);
        EffectHandling result = effectHandler.handle(event);
        if (result.status() == EffectHandling.Status.NOT_APPLICABLE) {
            return EffectHandling.rejected("没有接受该效果的附属处理器");
        }
        return result;
    }

    private static CastOutcome effectRejected(String castId, String reason) {
        // 附属效果拒绝保留原始文本，施放方能区分结构、距离和物品事实问题。
        return CastOutcome.newBuilder()
                .setCastId(castId)
                .setFailure(TechniqueFailure.EFFECT_REJECTED)
                .setReason(reason)
                .build();
    }

    private void finishSession(UUID entityUuid, Session session) {
        // 先移除会话再通知，避免监听器重入或生命周期重复信号造成二次清理。
        if (!sessions.remove(entityUuid, session)) {
            return;
        }
        notifyEnded(entityUuid, session);
    }

    private void notifyEnded(UUID entityUuid, Session session) {
        // 身体已经结束就释放附属占用，表现清理的重试不阻塞通知，也不能重复通知。
        if (session.endPublished) {
            return;
        }
        session.endPublished = true;
        TechniqueCastEndReason reason = session.endReason;
        if (reason == null) {
            reason = TechniqueCastEndReason.COMPLETED;
        }
        CastOutcome recordedOutcome = session.terminationOutcome;
        CastOutcome outcome;
        if (recordedOutcome == null) {
            outcome = CastOutcome.newBuilder()
                    .setCastId(session.castId)
                    .setPhase(CastPhase.CANCELLED)
                    .setReason("施放结束")
                    .build();
        } else if (recordedOutcome.getCastId().isEmpty()) {
            outcome = recordedOutcome.toBuilder().setCastId(session.castId).build();
        } else {
            outcome = recordedOutcome;
        }
        if (session.initialResponseDelivered && !success(outcome)) {
            publishCast(new CastObserved(entityUuid, outcome));
        }
        publishCastEnded(new TechniqueCastEnded(entityUuid, session.castId, reason, outcome));
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
        PendingRelease initialRelease = session.pendingRelease;
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
            if (sessions.get(entityUuid) == session && !session.terminating
                    && session.pendingRelease != null && session.pendingAdvance == null && session.deferred == null) {
                if (session.pendingRelease != initialRelease) {
                    advanceOnce(entityUuid, session);
                }
            }
            return result;
        } finally {
            session.processing = false;
        }
    }

    private Optional<CastOutcome> transitionOnce(UUID entityUuid, Session session, Transition transition) {
        // 卸载不再补发尚未确认的业务效果，只完成取消和独立清理。
        if (transition == Transition.HARD_CONTROL) {
            if (session.inspected == null
                    || !TechniqueState.read(session.inspected).getActiveCast().hasSustained()) {
                return interruptByStagger(entityUuid, session);
            }
        }
        if (transition != Transition.STAGGER || session.terminating) {
            if (!session.terminating) {
                CastOutcome outcome;
                if (transition == Transition.HARD_CONTROL) {
                    outcome = cancellationOutcome(session.castId).toBuilder()
                            .setPhase(CastPhase.INTERRUPTED)
                            .build();
                } else {
                    outcome = cancellationOutcome(session.castId);
                }
                beginTermination(entityUuid, session, endReason(transition), outcome);
            } else {
                terminate(entityUuid, session);
            }
            return Optional.ofNullable(session.terminationOutcome);
        }
        return interruptByStagger(entityUuid, session);
    }

    private Optional<CastOutcome> interruptByStagger(UUID entityUuid, Session session) {
        // 先完成未交付批次，再按当前起手窗口决定僵直是否终止施放。
        try {
            if (session.pendingAdvance != null) {
                CastOutcome pendingOutcome = session.pendingAdvance.outcome();
                if (!deliverPending(entityUuid, session)) {
                    return Optional.ofNullable(session.terminationOutcome);
                }
                if (!sessions.containsKey(entityUuid)) {
                    return Optional.of(pendingOutcome);
                }
            }
            TechniqueCasting.AdvanceResult result = casting.interruptByStagger(session.host, session.previousMillis);
            if (!success(result.outcome())) {
                handleFailure(entityUuid, session, result.outcome());
            } else if (result.recoveryCommit().isPresent() || result.outcome().getPhase() == CastPhase.IDLE) {
                session.pendingAdvance = result;
                session.endReason = TechniqueCastEndReason.COMPLETED;
                if (result.outcome().getPhase() == CastPhase.INTERRUPTED) {
                    session.endReason = TechniqueCastEndReason.STAGGERED;
                }
                if (!deliverPending(entityUuid, session)) {
                    return Optional.ofNullable(session.terminationOutcome);
                }
            }
            return Optional.of(result.outcome());
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome outcome = hostFailureOutcome(session.castId, exception.failure());
            handleFailure(entityUuid, session, outcome);
            return Optional.empty();
        } catch (IllegalArgumentException exception) {
            beginTermination(entityUuid, session, TechniqueCastEndReason.FAILED,
                    invalidStateOutcome(session.castId));
            return Optional.empty();
        }
    }

    private static TechniqueCastEndReason endReason(Transition transition) {
        // 生命周期迁移使用稳定原因，附属无需解释内部状态机分支。
        return switch (transition) {
            case STAGGER, HARD_CONTROL -> TechniqueCastEndReason.STAGGERED;
            case UNLOAD -> TechniqueCastEndReason.UNLOADED;
            case DISCONNECT -> TechniqueCastEndReason.DISCONNECTED;
            case DEATH -> TechniqueCastEndReason.DIED;
        };
    }

    private static CastOutcome hostFailureOutcome(String castId, FailureCode failure) {
        // 推进与生命周期共享宿主失败映射，保留框架码及同一份玩家提示。
        return CastOutcome.newBuilder()
                .setCastId(castId)
                .setFailure(TechniqueFailure.FRAMEWORK_REJECTED)
                .setFrameworkFailure(failure)
                .setReason(TechniqueErrors.frameworkPlayerReason(failure))
                .build();
    }

    private static CastOutcome cancellationOutcome(String castId) {
        // 生命周期清理没有新的业务失败码，回执仍携带施放身份和取消阶段。
        return CastOutcome.newBuilder()
                .setCastId(castId)
                .setPhase(CastPhase.CANCELLED)
                .setReason("施放已清理")
                .build();
    }

    private static CastOutcome invalidStateOutcome(String castId) {
        // 状态机异常走结构化失败，避免把异常文本当作玩家回执。
        return CastOutcome.newBuilder()
                .setCastId(castId)
                .setFailure(TechniqueFailure.INVALID_STATE)
                .setReason(TechniqueErrors.playerReason(TechniqueFailure.INVALID_STATE))
                .build();
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

    private List<KnownEntry> entries(List<TechniqueReference> references) {
        // 只投影当前身体适用的精确版本，保留原有学习记录。
        List<KnownEntry> result = new ArrayList<>();
        for (TechniqueReference reference : references) {
            String name = registry.find(reference)
                    .map(this::nameOf)
                    .orElse(reference.getId());
            result.add(new KnownEntry(reference, name));
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

    public static final class BodyTemplateMismatchException extends IllegalArgumentException {
        private BodyTemplateMismatchException() {
            // 读卷和命令共享模板准入拒绝的具体原因。
            super(TechniqueErrors.playerReason(TechniqueFailure.BODY_TEMPLATE_MISMATCH));
        }
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

    /** HIT 受击减速，CHARM 魅惑定身，HOLD 被擒定身，DOWN 倒地减速；各种类期限互不覆盖。 */
    public enum StaggerKind { HIT, CHARM, HOLD, DOWN }

    private record StaggerKey(UUID entityUuid, StaggerKind kind) { }

    public record Stagger(long durationMillis, double movementMultiplier, StaggerKind kind) {
        public Stagger(long durationMillis, double movementMultiplier) {
            // 原受击入口保持兼容；魅惑调用方必须明确指定类型。
            this(durationMillis, movementMultiplier, StaggerKind.HIT);
        }

        public Stagger {
            // 受击和倒地只减速，魅惑和被擒必须定身；各种期限都使用身体时钟毫秒。
            Objects.requireNonNull(kind);
            if (durationMillis < 0 || durationMillis > MAX_STAGGER_MILLIS) {
                throw new IllegalArgumentException("僵直时长必须在 [0, 60000] 毫秒内");
            }
            if (!Double.isFinite(movementMultiplier) || movementMultiplier < 0 || movementMultiplier > 1) {
                throw new IllegalArgumentException("僵直移动倍率必须在 [0, 1] 内");
            }
            boolean immobilizing = kind == StaggerKind.CHARM || kind == StaggerKind.HOLD;
            boolean standsStill = movementMultiplier == 0;
            if (immobilizing != standsStill) {
                throw new IllegalArgumentException("受击、倒地的移动倍率必须在 (0, 1] 内，魅惑、被擒必须为 0");
            }
        }
    }

    /** 附行刚写进身体，或装载后查到的还在生效的附行；表现层据此挂图标。 */
    public record InfusionBegan(UUID entityUuid, ElementalInfusion infusion, long remainingMillis) {
        public InfusionBegan {
            // 事件只描述身体上的事实，监听器不能借此改写服务状态。
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(infusion);
        }
    }

    /** 僵直开始或结束；剩余 0 表示结束，表现层据此恢复移动速度。 */
    public record StaggerChanged(UUID entityUuid, long remainingMillis,
                                 double movementMultiplier, StaggerKind kind) {
        public StaggerChanged {
            // 事件只描述一次判定结果，监听器不能借此改写服务状态。
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(kind);
        }
    }

    /** 一次打击的结算：打中、被盾挡下，或被对方的接招接住；空挥不报告。 */
    public record StrikeReport(String castId, TechniqueReference technique, UUID casterUuid,
                               UUID targetUuid, StrikeOutcome outcome) {
        public StrikeReport {
            // 报告只转述宿主的结算结果，施放身份、功法、双方和结果都必须完整。
            TechniqueValues.castId(castId);
            Objects.requireNonNull(technique);
            Objects.requireNonNull(casterUuid);
            Objects.requireNonNull(targetUuid);
            Objects.requireNonNull(outcome);
        }
    }

    /** HIT 造成了伤害，BLOCKED 被盾挡下，CAUGHT 被对方的接招接住。 */
    public enum StrikeOutcome { HIT, BLOCKED, CAUGHT }

    /** 一次施放请求的回执；调试用途，不进正式玩法。 */
    public record CastObserved(UUID entityUuid, CastOutcome outcome) {
        public CastObserved {
            // 事件只转述同一份回执，监听器不能借此改写施放状态。
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(outcome);
        }
    }

    public record CastStateChanged(UUID entityUuid, ActiveCast active, CastPhase phase) {
        public CastStateChanged {
            // 事件只携带不可变施放快照，不承担当下防御的权威存储。
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(active);
            Objects.requireNonNull(phase);
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

    private record PendingRelease(EndActiveRequest request, long requestedMillis) { }

    private static final class Session {
        private final TechniqueHost host;
        private final String castId;
        /** 本会话最近一次从宿主查询到的身体；只有宿主报的已提交版本仍等于它的版本时才拿来试算。 */
        private BodySnapshot inspected;
        private long previousMillis;
        private TechniqueCasting.AdvanceResult pendingAdvance;
        private PendingRelease pendingRelease;
        private ActiveCast publishedActive;
        private CastPhase publishedPhase;
        private final Map<String, EffectIntent> cleanup = new LinkedHashMap<>();
        private final Set<String> completedCleanup = new HashSet<>();
        /** 已交给声音监听者的请求 ID；整批重试时跳过，避免同一声音重复播放。 */
        private final Set<String> sounded = new HashSet<>();
        private boolean terminating;
        private boolean bodyStopped;
        private boolean processing;
        private Transition deferred;
        private CastOutcome terminationOutcome;
        private TechniqueCastEndReason endReason;
        private boolean endPublished;
        private boolean initialResponseDelivered;

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
            if (deferred == null || transition.ordinal() > deferred.ordinal()) {
                deferred = transition;
            }
        }
    }

    private enum Transition {
        STAGGER,
        HARD_CONTROL,
        UNLOAD,
        DISCONNECT,
        DEATH
    }
}
