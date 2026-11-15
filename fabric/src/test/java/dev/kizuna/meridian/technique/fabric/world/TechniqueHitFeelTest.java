package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitTier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 打中的轻重按掉血占血量上限的比例分档。 */
public final class TechniqueHitFeelTest {
    @Test
    public void tierFollowsShareOfMaxHealth() {
        // 不到 5% 是擦到，5% 起算轻，15% 起算重；两条线上的值归上面一档。
        assertEquals(HitTier.GRAZE, TechniqueHitFeel.tier(0.049));
        assertEquals(HitTier.LIGHT, TechniqueHitFeel.tier(0.05));
        assertEquals(HitTier.LIGHT, TechniqueHitFeel.tier(0.149));
        assertEquals(HitTier.HEAVY, TechniqueHitFeel.tier(0.15));
        assertEquals(HitTier.HEAVY, TechniqueHitFeel.tier(3));
    }
}
