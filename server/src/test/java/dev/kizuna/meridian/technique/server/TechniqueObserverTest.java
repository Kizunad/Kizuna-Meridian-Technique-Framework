package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.COST_POWER;
import static dev.kizuna.meridian.technique.server.Fixtures.COST_SECONDS;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.Fixtures.work;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 调试观察入口：施放回执监听与跃步做功明细。 */
public class TechniqueObserverTest {
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String LEAP_REQUEST = "leap_observer";

    @Test
    public void castListenerSeesEveryReturnedOutcome() {
        // 成功起手、无宿主和冷却拒绝都各通知一次，载荷就是返回给施放方的同一份回执。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        List<TechniqueService.CastObserved> events = new ArrayList<>();
        service.addCastListener(events::add);
        CastOutcome started = service.cast(ENTITY, request(host.current(), "observed"));
        assertEquals(CastPhase.WARMUP, started.getPhase());
        CastOutcome stranger = service.cast(STRANGER, request(host.current(), "stranger"));
        assertEquals(TechniqueFailure.NO_HOST, stranger.getFailure());
        assertEquals(2, events.size());
        assertEquals(ENTITY, events.get(0).entityUuid());
        assertSame(started, events.get(0).outcome());
        assertEquals(STRANGER, events.get(1).entityUuid());
        assertSame(stranger, events.get(1).outcome());
    }

    @Test
    public void removedOrFailingCastListenerDoesNotChangeOutcome() {
        // 观察者抛错不影响回执；移除后不再收到通知。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        List<TechniqueService.CastObserved> events = new ArrayList<>();
        java.util.function.Consumer<TechniqueService.CastObserved> recorder = events::add;
        service.addCastListener(event -> {
            throw new IllegalStateException("observer failure");
        });
        service.addCastListener(recorder);
        service.removeCastListener(recorder);
        CastOutcome started = service.cast(ENTITY, request(host.current(), "unobserved"));
        assertEquals(CastPhase.WARMUP, started.getPhase());
        assertTrue(events.isEmpty());
    }

    @Test
    public void submitLeapReturnsPaidEffortsOrRejection() {
        // 接受时返回交给宿主的组织做功；有施放进行中或无宿主时给出原因且不扣做功。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        LeapProfile leap = LeapProfile.newBuilder()
                .setWork(work("actuator"))
                .build();
        TechniqueService.LeapExertion paid = service.submitLeap(ENTITY, definition(), leap, LEAP_REQUEST);
        assertTrue(paid.accepted());
        assertEquals(1, paid.efforts().size());
        assertEquals(COST_POWER, paid.efforts().get(0).getWatts(), 0);
        assertEquals(COST_SECONDS, paid.efforts().get(0).getSeconds(), 0);
        TechniqueService.LeapExertion stranger = service.submitLeap(STRANGER, definition(), leap, LEAP_REQUEST);
        assertFalse(stranger.accepted());
        assertEquals(TechniqueService.LeapExertion.NO_HOST, stranger.rejection());
        service.cast(ENTITY, request(host.current(), "busy"));
        long revision = host.current().revision();
        TechniqueService.LeapExertion busy = service.submitLeap(ENTITY, definition(), leap, LEAP_REQUEST + "_busy");
        assertEquals(TechniqueService.LeapExertion.ACTIVE_CAST, busy.rejection());
        assertTrue(busy.efforts().isEmpty());
        assertEquals(revision, host.current().revision());
    }

    private static MemoryHost learnedHost() {
        // 每项测试独立一具已学会测试功法的身体。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition())));
    }
}
