package dev.kizuna.meridian.technique.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.kizuna.meridian.technique.fabric.client.TechniqueEffectTooltips;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 背包效果栏逐条绘制文字时，鼠标指向功法组织状态就先登记倍率说明；原版紧凑提示随后不再覆盖。 */
@Mixin(EffectsInInventory.class)
abstract class TechniqueEffectTooltipMixin {
    @WrapOperation(method = "extractEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/EffectsInInventory;extractText("
                    + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/network/chat/Component;"
                    + "Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/Font;IIIIII)V"))
    private void meridian$statusTooltip(EffectsInInventory self, GuiGraphicsExtractor graphics, Component name,
            Component duration, Font font, int x0, int y0, int width, int yStep, int mouseX, int mouseY,
            Operation<Void> original, @Local MobEffectInstance effect) {
        // 原版只在名字被截断时出提示；同一帧先登记者生效，所以先放功法说明再交回原版绘制。
        var hover = new TechniqueEffectTooltips.Hover(x0, y0, width, yStep);
        TechniqueEffectTooltips.offer(graphics, font, effect, hover.at(mouseX, mouseY));
        original.call(self, graphics, name, duration, font, x0, y0, width, yStep, mouseX, mouseY);
    }
}
