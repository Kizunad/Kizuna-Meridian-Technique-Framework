package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.*;
import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 施放预报：阶段、起手剩余、可否打断、剪辑起点与未来打击。 */
public class TechniqueForecastTest {
    private static final long STARTED = 1000;
    private static final long PARTWAY = 40;

    @Test
    public void warmupForecastListsFutureStrikesAndIsInterruptible() {
        // 起手中途：剩余起手、两次打击的距命中毫秒都从当前时刻算，可被僵直打断。
        TechniqueRegistry registry = combatRegistry();
        long now = STARTED + PARTWAY;
        var forecast = TechniqueForecasts.forecast(registry, casting(first(), 0), now).orElseThrow();
        assertEquals(CastPhase.WARMUP, forecast.phase());
        assertEquals(WARMUP - PARTWAY, forecast.millisUntilWarmupEnd());
        assertTrue(forecast.interruptible());
        assertEquals(List.of(WARMUP - PARTWAY, SECOND_STRIKE - PARTWAY), hits(forecast));
    }

    @Test
    public void effectivePhaseIsNotInterruptibleAndDropsLandedStrikes() {
        // 起手结束后不可打断，已过时刻的打击不再列出。
        TechniqueRegistry registry = combatRegistry();
        long now = STARTED + WARMUP;
        var forecast = TechniqueForecasts.forecast(registry, casting(first(), 0), now).orElseThrow();
        assertEquals(CastPhase.EFFECTIVE, forecast.phase());
        assertEquals(0, forecast.millisUntilWarmupEnd());
        assertFalse(forecast.interruptible());
        assertEquals(List.of(SECOND_STRIKE - WARMUP), hits(forecast));
    }

    @Test
    public void clipStartShiftsStrikesAndCatalogFlagGatesInterrupt() {
        // 承接剪辑把打击提前剪辑毫秒；施放记录不允许打断时起手中也不可打断。
        TechniqueRegistry registry = combatRegistry();
        TechniqueDefinition definition = next();
        CastPhases uninterruptible = definition.getPhases().toBuilder()
                .setInterruptibleByDamage(false /* interruptibleByDamage */)
                .build();
        TechniqueDefinition recorded = definition.toBuilder().setPhases(uninterruptible).build();
        var forecast = TechniqueForecasts.forecast(registry, casting(recorded, CLIP_OFFSET), STARTED).orElseThrow();
        assertFalse(forecast.interruptible());
        assertEquals(List.of(WARMUP - CLIP_OFFSET, SECOND_STRIKE - CLIP_OFFSET), hits(forecast));
    }

    @Test
    public void noCastOrFinishedCastHasNoForecast() {
        // 没有施放、或已过收势结束，都没有预报。
        TechniqueRegistry registry = combatRegistry();
        long finished = STARTED + WARMUP + ACTIVE + RECOVERY;
        assertTrue(TechniqueForecasts.forecast(registry, TechniqueState.empty(), STARTED).isEmpty());
        assertTrue(TechniqueForecasts.forecast(registry, casting(first(), 0), finished).isEmpty());
    }

    @Test
    public void firstStrikeIsEarliestStrikeFromWarmupStart() {
        // 首击取最早的打击效果；没有打击的式没有首击。
        assertEquals(WARMUP, TechniqueForecasts.firstStrike(first()).orElseThrow().millis());
        assertTrue(TechniqueForecasts.firstStrike(definition()).isEmpty());
    }

    private static List<Long> hits(TechniqueForecasts.CastForecast forecast) {
        // 只比较距命中毫秒。
        return forecast.strikes().stream().map(TechniqueForecasts.StrikeWindow::millisUntilHit).toList();
    }

    private static TechniqueProgress casting(TechniqueDefinition definition, long clipStart) {
        // 按调度器的边界写一条进行中的施放：三段连续，剪辑缩短起手，打断标志取自给定定义。
        long warmupEnd = STARTED + WARMUP - clipStart;
        CastRequest request = CastRequest.newBuilder()
                .setTechnique(TechniqueRegistry.reference(definition))
                .setCastId("forecast")
                .build();
        ActiveCast active = ActiveCast.newBuilder()
                .setRequest(request)
                .setStartedMillis(STARTED)
                .setWarmupEndMillis(warmupEnd)
                .setActiveEndMillis(warmupEnd + ACTIVE)
                .setRecoveryEndMillis(warmupEnd + ACTIVE + RECOVERY)
                .setClipStartMillis(clipStart)
                .setInterruptibleByDamage(definition.getPhases().getInterruptibleByDamage())
                .build();
        return TechniqueState.empty().toBuilder().setActiveCast(active).build();
    }
}
