package dev.kizuna.meridian.technique.fabric.client;

import net.minecraft.util.Identifier;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 卷轴背景资源的路径、缺图回退和进程内告警去重。 */
final class TechniqueScrollBackgrounds {
    static final Identifier FALLBACK = new Identifier(
            "meridian_technique", "textures/gui/scroll/generic.png");
    private static final Set<Identifier> WARNED = ConcurrentHashMap.newKeySet();

    private TechniqueScrollBackgrounds() {
        // 资源身份来自卷 ID，缺图不改变目录或学习行为。
    }

    static Identifier resolve(Identifier background, Predicate<Identifier> available,
                              Consumer<Identifier> warning) {
        // 有专属背景时直接使用，缺失时记录原始资源并返回库内通用纸面。
        if (available.test(background)) {
            return background;
        }
        warnOnce(background, warning);
        return FALLBACK;
    }

    private static void warnOnce(Identifier background, Consumer<Identifier> warning) {
        // 同一个缺失资源在客户端进程内只产生一条警告。
        if (WARNED.add(background)) {
            warning.accept(background);
        }
    }
}
