package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.UseBrew;

import java.util.HashSet;

/** 巫药与方块处理器的声明边界；这里只校验身份和时间，不执行世界行为。 */
final class TechniqueBrewEffects {
    private static final int MAX_BREW_KINDS = 16;
    private static final int MAX_BLOCK_TAGS = 64;
    private static final long MIN_INTERVAL_MILLIS = 1;
    private static final int MAX_BREW_QUALITY = 3;

    private TechniqueBrewEffects() {
        // 处理器声明由 Framework 校验，实际执行交给附属模组。
    }

    static void validateBlockWork(BlockWork work) {
        // 目标与周期必须有界，否则目录可生成无限量世界事件。
        TechniqueValues.id(work.getProcessorId(), "block_work.processor_id");
        if (work.getBlockTagsCount() == 0 || work.getBlockTagsCount() > MAX_BLOCK_TAGS
                || new HashSet<>(work.getBlockTagsList()).size() != work.getBlockTagsCount()) {
            throw TechniqueErrors.invalid("block_work.block_tags", "目标方块标签必须非空且不重复");
        }
        for (String tag : work.getBlockTagsList()) {
            TechniqueValues.id(tag, "block_work.block_tags");
        }
        TechniqueValues.positive(work.getMaximumDistance(), Unit.METER,
                TechniqueValues.MAX_DISTANCE, "block_work.maximum_distance");
        TechniqueValues.millis(work.getIntervalMillis(), "block_work.interval_millis");
        TechniqueValues.millis(work.getDurationMillis(), "block_work.duration_millis");
        if (work.getIntervalMillis() < MIN_INTERVAL_MILLIS
                || work.getDurationMillis() < work.getIntervalMillis()) {
            throw TechniqueErrors.invalid("block_work", "间隔必须大于零且不超过持续时间");
        }
    }

    static void validateUseBrew(UseBrew useBrew) {
        // 种类和用法必须显式声明，未知枚举不能扩展附属的消费权限。
        TechniqueValues.id(useBrew.getProcessorId(), "use_brew.processor_id");
        if (useBrew.getBrewKindIdsCount() == 0
                || useBrew.getBrewKindIdsCount() > MAX_BREW_KINDS
                || new HashSet<>(useBrew.getBrewKindIdsList()).size() != useBrew.getBrewKindIdsCount()) {
            throw TechniqueErrors.invalid("use_brew.brew_kind_ids", "药水种类必须非空且不重复");
        }
        for (String kindId : useBrew.getBrewKindIdsList()) {
            TechniqueValues.id(kindId, "use_brew.brew_kind_ids");
        }
        if (useBrew.getUseMethodsCount() == 0
                || new HashSet<>(useBrew.getUseMethodsList()).size() != useBrew.getUseMethodsCount()) {
            throw TechniqueErrors.invalid("use_brew.use_methods", "药水用法必须非空且不重复");
        }
        for (BrewUseMethod method : useBrew.getUseMethodsList()) {
            if (method == BrewUseMethod.BREW_USE_METHOD_UNSPECIFIED
                    || method == BrewUseMethod.UNRECOGNIZED) {
                throw TechniqueErrors.invalid("use_brew.use_methods", "药水用法不能为 unspecified");
            }
        }
    }

    static void validateHeldBrewFacts(HeldItem item) {
        // 巫药事实要么全无，要么完整提供；空手不能伪装为药水。
        if (item.getItemId().isEmpty()) {
            if (!item.getBrewKindId().isEmpty() || item.hasBrewQuality() || item.hasBrewUse()) {
                throw TechniqueErrors.invalid("held_item.brew", "空手不能携带巫药事实");
            }
            return;
        }
        boolean hasAnyFact = !item.getBrewKindId().isEmpty()
                || item.hasBrewQuality() || item.hasBrewUse();
        if (!hasAnyFact) {
            return;
        }
        TechniqueValues.id(item.getBrewKindId(), "held_item.brew_kind_id");
        if (!item.hasBrewQuality() || item.getBrewQuality() < 0 || item.getBrewQuality() > MAX_BREW_QUALITY) {
            throw TechniqueErrors.invalid("held_item.brew_quality", "品质必须位于 [0, 3]");
        }
        if (!item.hasBrewUse() || item.getBrewUse() == BrewUseMethod.BREW_USE_METHOD_UNSPECIFIED
                || item.getBrewUse() == BrewUseMethod.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("held_item.brew_use", "巫药用法必须显式提供");
        }
    }
}
