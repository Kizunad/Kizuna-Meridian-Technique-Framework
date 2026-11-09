package dev.kizuna.meridian.technique.server;

/** 处理 Framework 未直接执行的世界效果。 */
@FunctionalInterface
public interface TechniqueEffectHandler {
    EffectHandling handle(TechniqueEffectEvent event);
}
