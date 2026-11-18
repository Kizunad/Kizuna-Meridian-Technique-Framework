package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.server.TechniqueScrollText;
import dev.kizuna.meridian.technique.server.TechniqueStatusText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

/** 组织状态的附加说明：各部位倍率与曲线、已过与剩余时间；由界面宿主接到效果名和时间下面。 */
public final class TechniqueEffectTooltips {
    private TechniqueEffectTooltips() {
        // 只读目录和原版效果实例，不发包、不读身体。
    }

    public static List<Component> lines(MobEffectInstance effect) {
        // 只认目录里带组织修正的状态；效果名和通用时长由界面宿主提供。
        Optional<TechniqueStatusBenefits.Registered> registered = TechniqueStatusBenefits.tissue(effect);
        if (registered.isEmpty()) {
            return List.of();
        }
        var benefit = registered.get().benefit();
        var lines = new ArrayList<Component>();
        String prefix = TechniqueScrollText.key("effect", benefit.getId()) + ".";
        for (String line : TechniqueStatusText.lines(benefit, effect.getDuration(), slot -> label(prefix + slot))) {
            lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(lines);
    }

    private static String label(String key) {
        // 部位名由内容包语言给出；没写就不加部位前缀，不露出语言键。
        Language language = Language.getInstance();
        if (language.has(key)) {
            return language.getOrDefault(key);
        }
        return "";
    }
}
