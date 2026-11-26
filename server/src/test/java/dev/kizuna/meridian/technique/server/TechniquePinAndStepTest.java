package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import org.junit.Test;

import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.Hit;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.chain;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.strike;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static org.junit.Assert.assertThrows;

/** 钉住目标的一击与定距短移在目录边界的声明规则。 */
public class TechniquePinAndStepTest {
    private static final double DRIFT = 0.6;
    private static final double STEP = 1;
    private static final double TOO_FAR = 5;
    private static final double SPEED = 12;
    private static final int LAST_INDEX = 2;

    @Test
    public void pinCannotDamageOrCloseTheCast() {
        // 钉住的一击只记人和位置：可以作中间一击，不能兼作末击，也不能自己再查钉。
        WeaponStrike pin = strike(Hit.INTERMEDIATE).toBuilder()
                .setPinTarget(true)
                .build();
        TechniqueCombatEffects.validateStrike(pin);
        WeaponStrike closing = strike(Hit.LAST).toBuilder()
                .setPinTarget(true)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateStrike(closing));
        WeaponStrike both = pin.toBuilder()
                .setPinnedDrift(quantity(DRIFT, Unit.METER))
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateStrike(both));
        WeaponStrike wrongUnit = strike(Hit.LAST).toBuilder()
                .setPinnedDrift(quantity(DRIFT, Unit.DEGREE))
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateStrike(wrongUnit));
    }

    @Test
    public void pinnedStrikeNeedsAnEarlierPin() {
        // 查钉的一击之前必须有钉住的一击，否则整式在目录处拒绝。
        TechniqueDefinition plain = chain();
        TechniqueEffect last = plain.getEffects(LAST_INDEX);
        WeaponStrike pinned = last.getWeaponStrike().toBuilder()
                .setPinnedDrift(quantity(DRIFT, Unit.METER))
                .build();
        TechniqueEffect pinnedEffect = last.toBuilder()
                .setWeaponStrike(pinned)
                .build();
        TechniqueDefinition unpinned = plain.toBuilder()
                .setEffects(LAST_INDEX, pinnedEffect)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateSequence(unpinned));
        TechniqueEffect first = plain.getEffects(0);
        WeaponStrike pin = first.getWeaponStrike().toBuilder()
                .setPinTarget(true)
                .build();
        TechniqueEffect pinEffect = first.toBuilder()
                .setWeaponStrike(pin)
                .build();
        TechniqueDefinition complete = unpinned.toBuilder()
                .setEffects(0, pinEffect)
                .build();
        TechniqueCombatEffects.validateSequence(complete);
    }

    @Test
    public void distanceStepIsHorizontalAndBounded() {
        // 定距短移可以选边，不能向上；受阻取消打击要先声明定距；距离有上限。
        Impulse side = Impulse.newBuilder()
                .setDirection(ImpulseDirection.SIDE)
                .setSpeed(quantity(SPEED, Unit.METERS_PER_SECOND))
                .setMaximumDistance(quantity(STEP, Unit.METER))
                .setBlockedCancelsStrikes(true)
                .build();
        TechniqueDefinitions.validateImpulse(side);
        Impulse upward = side.toBuilder()
                .setDirection(ImpulseDirection.UP)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueDefinitions.validateImpulse(upward));
        Impulse unbounded = side.toBuilder()
                .clearMaximumDistance()
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueDefinitions.validateImpulse(unbounded));
        Impulse tooFar = side.toBuilder()
                .setMaximumDistance(quantity(TOO_FAR, Unit.METER))
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueDefinitions.validateImpulse(tooFar));
    }
}
