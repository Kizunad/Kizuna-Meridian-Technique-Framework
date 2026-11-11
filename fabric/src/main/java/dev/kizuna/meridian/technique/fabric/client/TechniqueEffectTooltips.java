package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.server.TechniqueScrollText;
import dev.kizuna.meridian.technique.server.TechniqueStatusText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

/** 组织状态在背包效果栏的悬停说明：名字、各部位倍率与曲线、已过与剩余时间。 */
public final class TechniqueEffectTooltips {
    /** 一条效果背景的屏幕范围；鼠标在内才出说明。 */
    public record Hover(int x, int y, int width, int height) {
        public Pointer at(int mouseX, int mouseY) {
            // 鼠标位置和条目范围一起交给提示，范围判断与原版紧凑提示相同。
            return new Pointer(this, mouseX, mouseY);
        }
    }

    /** 本帧鼠标与它指向的条目。 */
    public record Pointer(Hover hover, int mouseX, int mouseY) {
        boolean inside() {
            // 与原版一致：左上角含边，右下边界也算在内。
            return mouseX >= hover.x() && mouseX <= hover.x() + hover.width()
                    && mouseY >= hover.y() && mouseY <= hover.y() + hover.height();
        }
    }

    private TechniqueEffectTooltips() {
        // 只读目录和原版效果实例，不发包、不读身体。
    }

    public static void offer(GuiGraphicsExtractor graphics, Font font, MobEffectInstance effect, Pointer pointer) {
        // 只认目录里带组织修正的状态；其他效果保持原版提示。
        if (!pointer.inside()) {
            return;
        }
        Optional<TechniqueStatusBenefits.Registered> registered = TechniqueStatusBenefits.tissue(effect);
        if (registered.isEmpty()) {
            return;
        }
        var benefit = registered.get().benefit();
        var lines = new ArrayList<Component>();
        lines.add(effect.getEffect().value().getDisplayName());
        String prefix = TechniqueScrollText.key("effect", benefit.getId()) + ".";
        for (String line : TechniqueStatusText.lines(benefit, effect.getDuration(), slot -> label(prefix + slot))) {
            lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        graphics.setTooltipForNextFrame(font, List.copyOf(lines), Optional.empty(), pointer.mouseX(), pointer.mouseY());
    }

    private static String label(String key) {
        // 部位名由内容包语言给出；没写就不加部位前缀，不露出语言键。
        Language language = Language.getInstance();
        return language.has(key) ? language.getOrDefault(key) : "";
    }
}
