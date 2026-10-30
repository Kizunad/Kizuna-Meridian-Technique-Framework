package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class TechniqueMovementTest {
    private static final double SLOW_MULTIPLIER = 0.4;
    private static final double ACCELERATION = 1.1;
    private static final double EPSILON = 0.000001;

    @Test
    public void absentMultiplierDefaultsToOneAcrossDefinitionSavedCastAndIntent() {
        // 缺省、显式 1 和合法减速都保持一致，不把缺字段当作禁止移动。
        assertEquals(1, TechniqueMovement.multiplier(TechniqueDefinition.getDefaultInstance()), EPSILON);
        assertEquals(1, TechniqueMovement.multiplier(ActiveCast.getDefaultInstance()), EPSILON);
        assertEquals(1, TechniqueMovement.multiplier(MovementIntent.getDefaultInstance()), EPSILON);
        TechniqueMovement.validateMultiplier(1);
        TechniqueMovement.validateMultiplier(SLOW_MULTIPLIER);
    }

    @Test
    public void registryRejectsZeroNegativeNonfiniteAndAcceleration() {
        // 走正式目录装配校验，防止只测试辅助方法却漏接注册边界。
        var catalog = Fixtures.catalog(Fixtures.definition());
        var original = catalog.getDefinitions(0);
        for (double invalid : List.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, ACCELERATION)) {
            var definition = original.toBuilder().setMovementSpeedMultiplier(invalid).build();
            var malformed = catalog.toBuilder().setDefinitions(0, definition).build();
            assertThrows(IllegalArgumentException.class,
                    () -> TechniqueRegistry.load(Fixtures.addons(), List.of(malformed)));
        }
    }

    @Test
    public void movementIntentValidatesLimitAndCleanRemoval() {
        // UNLOCK 不能夹带新倍率；LOCK 的显式零也必须被拒绝。
        var limit = MovementIntent.newBuilder()
                .setAction(MovementAction.LOCK)
                .setMovementSpeedMultiplier(SLOW_MULTIPLIER)
                .build();
        TechniqueMovement.validateIntent(limit);
        assertEquals(SLOW_MULTIPLIER, TechniqueMovement.multiplier(limit), EPSILON);
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueMovement.validateIntent(limit.toBuilder().setMovementSpeedMultiplier(0).build()));
        var invalidRemoval = limit.toBuilder().setAction(MovementAction.UNLOCK).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueMovement.validateIntent(invalidRemoval));
        TechniqueMovement.validateIntent(invalidRemoval.toBuilder().clearMovementSpeedMultiplier().build());
    }
}
