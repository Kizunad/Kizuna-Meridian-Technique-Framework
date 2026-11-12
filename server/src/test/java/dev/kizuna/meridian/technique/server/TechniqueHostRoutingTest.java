package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.HIT_STAGGER;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 实体到宿主的路由缓存、轻量时钟，以及一个 tick 内对同一身体的查询次数。 */
public class TechniqueHostRoutingTest {
    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long MID_WARMUP = WARMUP / 2;
    private static final long FINISHED_MILLIS = WARMUP + ACTIVE + RECOVERY;

    @Test
    public void routedEntityAsksOnlyItsOwnHost() {
        // 第一次解析逐个探测；归属确认后，旁边不认领的宿主不再被问。
        Endpoint bystander = new Endpoint(learnedHost());
        bystander.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(bystander, owner));

        assertTrue(directory.resolve(ENTITY).isPresent());
        assertEquals(1, bystander.exchanges);
        assertEquals(1, owner.exchanges);

        assertTrue(directory.resolve(ENTITY).isPresent());
        assertEquals(1, bystander.exchanges);
        assertEquals(2, owner.exchanges);
    }

    @Test
    public void routeFollowsEntityToNewHostWhenOldHostStopsClaiming() {
        // 原宿主回 UNBOUND 就作废路由并重新探测；不能拿着旧路由把实体当成没有身体。
        Endpoint first = new Endpoint(learnedHost());
        Endpoint second = new Endpoint(learnedHost());
        second.claims = false;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(first, second));
        directory.resolve(ENTITY).orElseThrow().inspect();

        first.claims = false;
        second.claims = true;
        second.host.nowMillis = MID_WARMUP;
        long clock = directory.resolve(ENTITY).orElseThrow().inspect().body().settledAt().elapsedMillis();
        assertEquals(MID_WARMUP, clock);

        int firstExchanges = first.exchanges;
        directory.resolve(ENTITY).orElseThrow();
        assertEquals(firstExchanges, first.exchanges);
    }

    @Test
    public void unclaimedEntityIsProbedAgainNextTime() {
        // 「没有宿主」不缓存：实体稍后被认领，下一次解析就能找到。
        Endpoint owner = new Endpoint(learnedHost());
        owner.claims = false;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(owner));
        assertTrue(directory.resolve(ENTITY).isEmpty());
        owner.claims = true;
        assertTrue(directory.resolve(ENTITY).isPresent());
    }

    @Test
    public void unloadForgetsRoute() {
        // 实体卸载后路由作废，下次解析重新探测全部宿主。
        Endpoint bystander = new Endpoint(learnedHost());
        bystander.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(bystander, owner));
        TechniqueService service = new TechniqueService(registry(definition()), directory);
        assertFalse(service.known(ENTITY).isEmpty());
        assertEquals(1, bystander.exchanges);

        service.cancelOnUnload(ENTITY);
        assertFalse(service.known(ENTITY).isEmpty());
        assertEquals(2, bystander.exchanges);
    }

    @Test
    public void staggerExpiryReadsClockWithoutInspectingBody() {
        // 宿主提供轻量读数时，僵直到期只比较时钟；到期事件和剩余时间与查询身体时一致。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        List<TechniqueService.StaggerChanged> events = new ArrayList<>();
        service.addStaggerListener(events::add);
        service.stagger(ENTITY, HIT_STAGGER);
        int inspects = owner.inspects;

        assertEquals(HIT_STAGGER.durationMillis(), service.staggerRemainingMillis(ENTITY));
        service.tick();
        assertEquals(1, events.size());

        owner.host.nowMillis = HIT_STAGGER.durationMillis();
        service.tick();
        assertEquals(2, events.size());
        assertEquals(0, events.get(1).remainingMillis());
        assertEquals(0, service.staggerRemainingMillis(ENTITY));
        assertEquals(inspects, owner.inspects);
    }

    @Test
    public void staggerExpiryFallsBackToInspectWithoutStamp() {
        // 宿主不提供轻量读数时照旧查询身体，结果相同。
        Endpoint owner = new Endpoint(learnedHost());
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        service.stagger(ENTITY, HIT_STAGGER);
        int inspects = owner.inspects;
        assertEquals(HIT_STAGGER.durationMillis(), service.staggerRemainingMillis(ENTITY));
        assertTrue(owner.inspects > inspects);
        owner.host.nowMillis = HIT_STAGGER.durationMillis();
        assertEquals(0, service.staggerRemainingMillis(ENTITY));
    }

    @Test
    public void stampIsOnlyReadThroughKnownRoute() {
        // 归属没确认过的实体不读轻量读数，避免绕过认领探测。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(owner));
        assertTrue(directory.bodyStamp(ENTITY).isEmpty());
        directory.resolve(ENTITY).orElseThrow();
        assertEquals(0, directory.bodyStamp(ENTITY).orElseThrow().clockMillis());
    }

    @Test
    public void staggerIsNotRecordedWhenInspectFails() {
        // 宿主还报得出读数、但 inspect 已经失败：僵直不能落下，和没有轻量读数时一样。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        assertFalse(service.known(ENTITY).isEmpty());
        List<TechniqueService.StaggerChanged> events = new ArrayList<>();
        service.addStaggerListener(events::add);

        owner.inspectFailure = FailureCode.BODY_DEAD;
        assertTrue(service.stagger(ENTITY, HIT_STAGGER).isEmpty());
        assertTrue(events.isEmpty());
        assertEquals(0, service.staggerRemainingMillis(ENTITY));
    }

    @Test
    public void stampIsWithheldWhileInspectFails() {
        // 路由上最近一次 inspect 失败后不再给轻量读数，已有的僵直按宿主失效处理；inspect 恢复后读数也恢复。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(owner));
        TechniqueService service = new TechniqueService(registry(definition()), directory);
        service.stagger(ENTITY, HIT_STAGGER);
        assertEquals(HIT_STAGGER.durationMillis(), service.staggerRemainingMillis(ENTITY));

        owner.inspectFailure = FailureCode.BODY_DEAD;
        assertTrue(directory.resolve(ENTITY).isPresent());
        assertTrue(directory.bodyStamp(ENTITY).isEmpty());
        assertEquals(0, service.staggerRemainingMillis(ENTITY));

        owner.inspectFailure = FailureCode.FAILURE_CODE_UNSPECIFIED;
        directory.resolve(ENTITY).orElseThrow().inspect();
        assertTrue(directory.bodyStamp(ENTITY).isPresent());
    }

    @Test
    public void stampExpiresUntilRouteIsConfirmedAgain() {
        // 路由确认只管 CONFIRM_TICKS 个 tick：过期后不给读数，下一次 inspect 把其余宿主也问一遍，读数恢复。
        Endpoint bystander = new Endpoint(learnedHost());
        bystander.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(bystander, owner));
        TechniqueHost host = directory.resolve(ENTITY).orElseThrow();
        host.inspect();
        pass(directory, TechniqueHostDirectory.CONFIRM_TICKS - 1);
        assertTrue(directory.bodyStamp(ENTITY).isPresent());
        host.inspect();
        assertEquals(1, bystander.exchanges);

        pass(directory, 1);
        assertTrue(directory.bodyStamp(ENTITY).isEmpty());
        host.inspect();
        assertEquals(2, bystander.exchanges);
        assertTrue(directory.bodyStamp(ENTITY).isPresent());
    }

    @Test
    public void secondClaimIsNoticedWhenRouteIsConfirmedAgain() {
        // 路由建好以后另一个宿主也认领同一实体：确认过期后的下一次解析报多重认领，之后不给读数，再解析仍报错。
        Endpoint late = new Endpoint(learnedHost());
        late.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(late, owner));
        directory.resolve(ENTITY).orElseThrow();
        assertTrue(directory.bodyStamp(ENTITY).isPresent());
        late.claims = true;
        pass(directory, TechniqueHostDirectory.CONFIRM_TICKS);
        assertThrows(TechniqueHostDirectory.MultipleHostsException.class, () -> directory.resolve(ENTITY));
        assertTrue(directory.bodyStamp(ENTITY).isEmpty());
        assertThrows(TechniqueHostDirectory.MultipleHostsException.class, () -> directory.resolve(ENTITY));
    }

    @Test
    public void secondClaimSeenByRunningSessionDropsRouteWithoutThrowing() {
        // 已有会话的查询撞上确认、另一个宿主也认领：不抛，照常回身体，只作废路由；
        // 之后这个会话只问自己的宿主，下一次解析才报多重认领。
        Endpoint late = new Endpoint(learnedHost());
        late.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(late, owner));
        TechniqueHost host = directory.resolve(ENTITY).orElseThrow();
        host.inspect();
        late.claims = true;
        pass(directory, TechniqueHostDirectory.CONFIRM_TICKS);

        assertEquals(owner.host.current().id(), host.inspect().body().id());
        assertTrue(directory.bodyStamp(ENTITY).isEmpty());
        int asked = late.exchanges;
        host.inspect();
        assertEquals(asked, late.exchanges);
        assertThrows(TechniqueHostDirectory.MultipleHostsException.class, () -> directory.resolve(ENTITY));
    }

    @Test
    public void castKeepsTickingWhenAnotherHostStartsClaiming() {
        // 施放中另一个宿主也认领同一实体：服务的 tick 不抛，施放不因此结束。
        Endpoint late = new Endpoint(learnedHost());
        late.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(late, owner)));
        List<TechniqueCastEnded> ended = new ArrayList<>();
        service.addCastEndListener(ended::add);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "claimed")).getPhase());
        late.claims = true;
        int asked = late.exchanges;
        for (int tick = 0; tick <= TechniqueHostDirectory.CONFIRM_TICKS; tick++) {
            service.tick();
        }
        assertTrue(late.exchanges > asked);
        assertTrue(ended.isEmpty());
    }

    @Test
    public void secondClaimIsNoticedAtOnceWhenInspectFails() {
        // 认领宿主 inspect 失败时不等到期，当场把其余宿主问一遍。
        Endpoint late = new Endpoint(learnedHost());
        late.claims = false;
        Endpoint owner = new Endpoint(learnedHost());
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(late, owner));
        directory.resolve(ENTITY).orElseThrow();
        late.claims = true;
        owner.inspectFailure = FailureCode.INTERNAL_ERROR;
        assertThrows(TechniqueHostDirectory.MultipleHostsException.class, () -> directory.resolve(ENTITY));
    }

    @Test
    public void quietCastNoticesFailingHostWithinConfirmPeriod() {
        // 施放中宿主的 inspect 开始失败、版本和时钟都没动：最晚 CONFIRM_TICKS 个 tick 后重新查询，施放按失败结束，
        // 不会一直跳过到下一个边界。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        List<TechniqueCastEnded> ended = new ArrayList<>();
        service.addCastEndListener(ended::add);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "failing")).getPhase());
        owner.host.nowMillis = MID_WARMUP;
        service.tick();

        // 路由在起式那一 tick 确认过，上面已经走了 1 个 tick：再走 CONFIRM_TICKS - 2 个都还是安静 tick，不查询宿主。
        owner.inspectFailure = FailureCode.ACCESS_DENIED;
        int inspects = owner.inspects;
        for (int tick = 0; tick < TechniqueHostDirectory.CONFIRM_TICKS - 2; tick++) {
            service.tick();
        }
        assertEquals(inspects, owner.inspects);
        service.tick();
        assertTrue(owner.inspects > inspects);
        assertTrue(ended.isEmpty());
        owner.inspectFailure = FailureCode.FAILURE_CODE_UNSPECIFIED;
        service.tick();
        assertEquals(1, ended.size());
        assertEquals(TechniqueCastEndReason.FAILED, ended.get(0).reason());
    }

    private static void pass(TechniqueHostDirectory directory, int ticks) {
        // 让目录的 tick 计数往前走这么多。
        for (int tick = 0; tick < ticks; tick++) {
            directory.tick();
        }
    }

    @Test
    public void quietTickInspectsCastingBodyOnce() {
        // 施放中、这一 tick 没有到期的边界：整具身体只查询一次。
        Endpoint owner = new Endpoint(learnedHost());
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "quiet")).getPhase());
        owner.host.nowMillis = MID_WARMUP;
        int inspects = owner.inspects;
        service.tick();
        assertEquals(1, owner.inspects - inspects);
        assertTrue(TechniqueState.read(owner.host.current()).hasActiveCast());
    }

    @Test
    public void quietTicksSkipInspectUntilNextBoundary() {
        // 宿主能报版本和时钟时，没到下一个边界的 tick 不查询身体；到了边界照常推进，一路走到收势。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "skip")).getPhase());
        owner.host.nowMillis = MID_WARMUP;
        service.tick();
        int inspects = owner.inspects;
        owner.host.nowMillis = MID_WARMUP + 1;
        service.tick();
        owner.host.nowMillis = WARMUP - 1;
        service.tick();
        assertEquals(inspects, owner.inspects);
        assertTrue(TechniqueState.read(owner.host.current()).hasActiveCast());

        owner.host.nowMillis = FINISHED_MILLIS;
        service.tick();
        assertTrue(owner.inspects > inspects);
        var progress = TechniqueState.read(owner.host.current());
        assertFalse(progress.hasActiveCast());
        assertEquals(CastPhase.FINISHED, progress.getLastCast().getEnding());
    }

    @Test
    public void effectOnlyBoundaryIsDeliveredNotSkipped() {
        // 只有世界效果、没有做功的边界也算到期：到点的那个 tick 不能跳过，效果要交到宿主。
        TechniqueDefinition definition = impulseDefinition();
        Endpoint owner = new Endpoint(new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition))));
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition),
                new TechniqueHostDirectory(List.of(owner)));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "impulse")).getPhase());
        owner.host.nowMillis = MID_WARMUP;
        service.tick();
        assertEquals(0, impulses(owner));
        owner.host.nowMillis = WARMUP;
        service.tick();
        assertEquals(1, impulses(owner));
    }

    private static long impulses(Endpoint owner) {
        // 宿主收到的冲量效果条数。
        return owner.host.effects.stream().filter(EffectIntent::hasImpulse).count();
    }

    private static TechniqueDefinition impulseDefinition() {
        // 冲量只在起手结束的那一刻触发，这个边界上没有做功也没有收势。
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setImpulse(impulse)
                .build();
        return definition().toBuilder().addEffects(effect).build();
    }

    @Test
    public void quietTickIsNotSkippedAfterBodyChanged() {
        // 身体在两次 tick 之间被别人改过（这里是死亡），版本对不上就必须重新查询，施放随之结束。
        Endpoint owner = new Endpoint(learnedHost());
        owner.offersStamp = true;
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        List<TechniqueCastEnded> ended = new ArrayList<>();
        service.addCastEndListener(ended::add);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "changed")).getPhase());
        owner.host.nowMillis = MID_WARMUP;
        service.tick();
        assertTrue(ended.isEmpty());

        owner.host.die();
        service.tick();
        assertEquals(1, ended.size());
        assertEquals(TechniqueCastEndReason.DIED, ended.get(0).reason());
    }

    @Test
    public void lateTickStillCrossesEveryBoundaryOnFreshBody() {
        // 迟到的 tick 一次跨过出手和收势；每段提交后都按新身体继续，最后正常收势。
        Endpoint owner = new Endpoint(learnedHost());
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(owner)));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(owner.host.current(), "late")).getPhase());
        owner.host.nowMillis = FINISHED_MILLIS;
        service.tick();
        var progress = TechniqueState.read(owner.host.current());
        assertFalse(progress.hasActiveCast());
        assertEquals(CastPhase.FINISHED, progress.getLastCast().getEnding());
    }

    private static MemoryHost learnedHost() {
        // 每项测试独立一具已学会测试功法的身体。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition())));
    }

    /** 记录交换次数的测试入口：可以切换是否认领，可以选择是否提供轻量读数，可以让 inspect 回指定的失败码。 */
    private static final class Endpoint implements TechniqueHostEntrypoint {
        private final MemoryHost host;
        private boolean claims = true;
        private boolean offersStamp;
        private FailureCode inspectFailure = FailureCode.FAILURE_CODE_UNSPECIFIED;
        private int exchanges;
        private int inspects;

        private Endpoint(MemoryHost host) {
            // 身体和回执仍由真实 Framework 处理器保存。
            this.host = host;
        }

        @Override
        public byte[] exchange(byte[] payload) {
            // 不认领时回 UNBOUND；其余三种操作照常经过字节协议。
            exchanges++;
            TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
            var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
            if (!claims || !ENTITY.toString().equals(request.getEntityUuid())) {
                return TechniqueProtocol.encodeHostResponse(response.setUnbound(true /* unbound */).build());
            }
            try {
                route(request, response);
            } catch (TechniqueErrors.HostRejected exception) {
                RequestFailure failure = RequestFailure.newBuilder().setCode(exception.failure()).build();
                response.setFailure(failure);
            }
            return TechniqueProtocol.encodeHostResponse(response.build());
        }

        @Override
        public Optional<TechniqueBodyStamp> bodyStamp(UUID entityUuid) {
            // 版本是已提交的那一版；时钟与 inspect 结清后的数相同：宿主时刻与已结清游标取较晚者。
            if (!offersStamp || !claims || !ENTITY.equals(entityUuid)) {
                return Optional.empty();
            }
            var body = host.current();
            return Optional.of(new TechniqueBodyStamp(body.revision(),
                    Math.max(host.nowMillis, body.settledAt().elapsedMillis())));
        }

        private void route(TechniqueHostRequest request, TechniqueHostResponse.Builder response) {
            // 三种操作都返回同一宿主的真实身体或幂等回执。
            switch (request.getOperationCase()) {
                case INSPECT -> {
                    inspects++;
                    if (inspectFailure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
                        throw new TechniqueErrors.HostRejected(inspectFailure);
                    }
                    TechniqueHost.Inspection inspection = host.inspect();
                    response.setBody(BodyProtobuf.toMessage(inspection.body()));
                    response.setHeldItems(inspection.heldItems());
                    response.setInspectFacts(inspection.facts());
                }
                case ACTIVITY -> {
                    TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(request.getRequestId(),
                            request.getExpectedRevision(), request.getActivity()));
                    TechniqueActivityCommit activity = TechniqueActivityCommit.newBuilder()
                            .setRevision(commit.revision())
                            .setActivity(commit.activity())
                            .build();
                    response.setActivity(activity);
                }
                case EFFECT -> {
                    host.submitEffect(new TechniqueHost.EffectSubmission(request.getRequestId(), request.getEffect()));
                    response.setEffectAccepted(true /* effectAccepted */);
                }
                default -> throw new IllegalArgumentException("缺少宿主操作");
            }
        }
    }
}
