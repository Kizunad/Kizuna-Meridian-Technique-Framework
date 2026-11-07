package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 完整伤害身份不受宿主原有命名空间影响。 */
public final class TechniqueWorldDamageTypeTest {
    @Test
    public void explicitIdsOverrideHostNamespaceForBothHands() {
        // 同一打击仅按真实手位选目录 ID，不能再拼旧书名或致伤后缀。
        Identifier fallback = Identifier.parse("sample:default");
        Identifier main = Identifier.parse("other:main");
        Identifier off = Identifier.parse("other:off");
        WeaponStrike strike = WeaponStrike.newBuilder()
                .setMainHandDamageTypeId(main.toString())
                .setOffHandDamageTypeId(off.toString())
                .build();
        assertEquals(main, TechniqueWorldEffects.damageType(strike, InteractionHand.MAIN_HAND, fallback));
        assertEquals(off, TechniqueWorldEffects.damageType(strike, InteractionHand.OFF_HAND, fallback));
        assertEquals(fallback, TechniqueWorldEffects.damageType(
                WeaponStrike.getDefaultInstance(), InteractionHand.MAIN_HAND, fallback));
    }
}
