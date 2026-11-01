package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.movementDefinition;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class TechniqueUseInputTest {
    @Test
    public void lockCarriesTheCatalogUseAllowanceAndUnlockDoesNot() {
        // 目录缺省拦右键，声明放行的式在起手快照与 LOCK 里都带 true；UNLOCK 只是清理，不带放行。
        for (boolean allowed : List.of(false, true)) {
            TechniqueDefinition definition = movementDefinition().toBuilder()
                    .setAllowUseWhileCasting(allowed)
                    .build();
            MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
            TechniqueService service = service(host, registry(definition));
            assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "use")).getPhase());
            assertEquals(allowed, TechniqueState.read(host.current()).getActiveCast().getAllowUseWhileCasting());
            host.nowMillis = WARMUP + ACTIVE + RECOVERY;
            service.tick();
            List<MovementIntent> movements = host.effects.stream()
                    .filter(EffectIntent::hasMovement)
                    .map(EffectIntent::getMovement)
                    .toList();
            assertEquals(List.of(MovementAction.LOCK, MovementAction.UNLOCK),
                    movements.stream().map(MovementIntent::getAction).toList());
            assertEquals(allowed, movements.get(0).getAllowUseWhileCasting());
            assertFalse(movements.get(1).getAllowUseWhileCasting());
        }
    }
}
