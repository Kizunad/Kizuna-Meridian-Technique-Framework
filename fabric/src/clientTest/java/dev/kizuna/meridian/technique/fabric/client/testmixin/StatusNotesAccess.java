package dev.kizuna.meridian.technique.fabric.client.testmixin;

import dev.kizuna.inventoryui.client.status.StatusNotes;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 只在客户端验收中读取图标实际使用的说明，不给正式 API 增加测试入口。 */
@Mixin(value = StatusNotes.class, remap = false)
public interface StatusNotesAccess {
    @Invoker("of")
    static List<Component> notes(MobEffectInstance effect) {
        // 运行时由 Mixin 接到界面框架汇总方法，未接线时不能静默通过。
        throw new AssertionError("状态说明的测试入口没有接上");
    }
}
