package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** 不依赖原版 Goal 或 Brain 的纯 Java 功法选择器。 */
public final class TechniqueSelector {
    private static final double DISTANCE_BONUS = 0.25;
    private static final double FLEEING_COMBAT_BONUS = 0.5;
    private static final double SECONDARY_CONTEXT_FACTOR = 0.5;

    private TechniqueSelector() {
        // 选择过程只读取调用方提供的快照，不保存随机数或身体状态。
    }

    public enum TargetState {
        ENGAGED,
        FLEEING
    }

    public record Situation(long nowMillis, double selfHpRatio, double selfQiRatio,
                            double targetDistanceMeters, TargetState targetState) {
        public Situation {
            // AI 输入来自宿主，先拒绝无界数值，避免权重计算产生 NaN。
            if (nowMillis < 0 || !ratio(selfHpRatio) || !ratio(selfQiRatio)
                    || !Double.isFinite(targetDistanceMeters) || targetDistanceMeters < 0
                    || targetState == null) {
                throw new IllegalArgumentException("AI 情境参数非法");
            }
        }
    }

    public record Availability(Map<TechniqueReference, BindingOutcome> bindings,
                               Set<TechniqueReference> affordable,
                               Map<String, Long> cooldownEnds) {
        public Availability {
            // 绑定、费用和冷却是同一权威快照的筛选结果，构造后不再允许外部修改。
            bindings = Map.copyOf(bindings);
            affordable = Set.copyOf(affordable);
            cooldownEnds = Map.copyOf(cooldownEnds);
            for (long cooldownEnd : cooldownEnds.values()) {
                if (cooldownEnd < 0) {
                    throw new IllegalArgumentException("AI 冷却时刻不能为负");
                }
            }
        }
    }

    public record Context(TechniqueRegistry registry, Situation situation, Availability availability) {
        public Context {
            // 目录与可用性结果必须来自同一轮 AI 查询，避免使用过期绑定。
            Objects.requireNonNull(registry);
            Objects.requireNonNull(situation);
            Objects.requireNonNull(availability);
        }
    }

    public static Optional<TechniqueReference> choose(Context context,
                                                       Collection<KnownTechnique> known,
                                                       Random random) {
        // 过滤完成后再按稳定顺序计算权重，确保相同种子不会受 Map 或目录加载顺序
        // 影响。
        Objects.requireNonNull(context);
        Objects.requireNonNull(known);
        Objects.requireNonNull(random);
        List<KnownTechnique> ordered = uniqueKnown(known);
        var candidates = new ArrayList<Weighted>();
        for (KnownTechnique entry : ordered) {
            addCandidate(context, entry, candidates);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return weightedChoice(candidates, random);
    }

    public static Optional<TechniqueDefinition> chooseDefinition(Context context,
                                                                  Collection<KnownTechnique> known,
                                                                  Random random) {
        // 需要完整定义的宿主可以复用同一选择结果，不重新抽样或计算权重。
        return choose(context, known, random).flatMap(context.registry()::find);
    }

    private static List<KnownTechnique> uniqueKnown(Collection<KnownTechnique> known) {
        // 同一精确版本重复出现时保留第一条可信记录，再按身份排序。
        Map<TechniqueReference, KnownTechnique> unique = new HashMap<>();
        for (KnownTechnique entry : known) {
            if (entry.hasTechnique()) {
                unique.putIfAbsent(entry.getTechnique(), entry);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing((KnownTechnique item) -> item.getTechnique().getId())
                        .thenComparingInt(item -> item.getTechnique().getVersion()))
                .toList();
    }

    private static void addCandidate(Context context, KnownTechnique known,
                                     List<Weighted> candidates) {
        TechniqueReference reference = known.getTechnique();
        TechniqueDefinition definition = context.registry().find(reference).orElse(null);
        if (definition == null || !available(context, known, definition)) {
            return;
        }
        double weight = weight(context.situation(), definition, known);
        if (Double.isFinite(weight) && weight > 0) {
            candidates.add(new Weighted(reference, weight));
        }
    }

    private static boolean available(Context context, KnownTechnique known,
                                     TechniqueDefinition definition) {
        // 绑定、费用、冷却和距离全部满足才允许进入加权池。
        TechniqueReference reference = known.getTechnique();
        BindingOutcome binding = context.availability().bindings().get(reference);
        if (binding == null || !binding.hasBinding()
                || !context.availability().affordable().contains(reference)) {
            return false;
        }
        long cooldownEnd = context.availability().cooldownEnds()
                .getOrDefault(definition.getId(), 0L);
        if (context.situation().nowMillis() < cooldownEnd || !definition.hasAiHints()) {
            return false;
        }
        AiHints hints = definition.getAiHints();
        double distance = context.situation().targetDistanceMeters();
        return distance >= hints.getMinimumDistance().getValue()
                && distance <= hints.getMaximumDistance().getValue();
    }

    private static double weight(Situation situation, TechniqueDefinition definition,
                                KnownTechnique known) {
        // 缺省熟练度保留基础系数一，熟练度只把 AI 权重加成到一至二。
        // 费用、曲线和施放时长仍由目录决定。
        AiHints hints = definition.getAiHints();
        double categoryWeight = hints.getCategoryWeight().getValue();
        double proficiency = 0;
        if (known.getProficiency().hasValue()) {
            proficiency = known.getProficiency().getValue();
        }
        if (!Double.isFinite(proficiency) || proficiency < 0 || proficiency > 1) {
            return 0;
        }
        double proficiencyBonus = 1 + proficiency;
        return categoryWeight * proficiencyBonus * situationWeight(situation, definition, hints);
    }

    private static double situationWeight(Situation situation, TechniqueDefinition definition,
                                          AiHints hints) {
        // 阈值表达当前压力，距离越近越适合当前目标；逃跑目标提高攻击和移动倾向。
        double hpPressure = Math.max(0, hints.getMaximumHpRatio().getValue()
                - situation.selfHpRatio());
        double qiReadiness = Math.max(0, situation.selfQiRatio()
                - hints.getMinimumQiRatio().getValue());
        double distanceSpan = hints.getMaximumDistance().getValue()
                - hints.getMinimumDistance().getValue();
        double distanceFit = 1;
        if (distanceSpan > 0) {
            distanceFit += DISTANCE_BONUS * (hints.getMaximumDistance().getValue()
                    - situation.targetDistanceMeters()) / distanceSpan;
        }
        double contextFit = switch (definition.getCategory()) {
            case RECOVERY -> 1 + hpPressure;
            case CULTIVATION -> 1 + qiReadiness;
            case DEFENSE -> 1 + hpPressure * SECONDARY_CONTEXT_FACTOR;
            case BUFF -> 1 + qiReadiness * SECONDARY_CONTEXT_FACTOR;
            case ATTACK, MOVEMENT -> fleeingBonus(situation.targetState());
            default -> 1;
        };
        return Math.max(0, distanceFit * contextFit);
    }

    private static double fleeingBonus(TargetState targetState) {
        // 目标逃跑时攻击和移动功法更值得优先考虑，其他情境保持基础权重。
        if (targetState == TargetState.FLEEING) {
            return 1 + FLEEING_COMBAT_BONUS;
        }
        return 1;
    }

    private static Optional<TechniqueReference> weightedChoice(List<Weighted> candidates,
                                                                Random random) {
        // 使用一次带种子随机数，最后一个候选吸收浮点舍入余数。
        double total = candidates.stream().mapToDouble(Weighted::weight).sum();
        if (!Double.isFinite(total) || total <= 0) {
            return Optional.empty();
        }
        double target = random.nextDouble() * total;
        double accumulated = 0;
        for (Weighted candidate : candidates) {
            accumulated += candidate.weight();
            if (target < accumulated) {
                return Optional.of(candidate.reference());
            }
        }
        return Optional.of(candidates.get(candidates.size() - 1).reference());
    }

    private static boolean ratio(double value) {
        // 比例只接受闭区间 [0, 1]，不让非法宿主输入污染所有候选权重。
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private record Weighted(TechniqueReference reference, double weight) { }
}
