package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrike;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 宿主完成几何、阵营和可伤害筛选后，按稳定近远顺序应用跨击预算。 */
public final class TechniqueStrikeTargets {
    private TechniqueStrikeTargets() {
        // 本库不持有世界目标，宿主按 cast_id 和 dedup_group 保存并原子更新命中集合。
    }

    public static List<String> select(WeaponStrike strike, Map<String, Set<String>> hitIdsByGroup,
                                      List<String> candidates) {
        // 同组每目标只选一次，跨组可重击。
        // 整次施放限制不同目标总数，不限制重复命中次数。
        TechniqueCombatEffects.validateStrike(strike);
        Targeting targeting = new Targeting(strike.getMaximumTargets(), strike.getCastTargetLimit(),
                strike.getDedupGroup());
        return select(targeting, hitIdsByGroup, candidates);
    }

    public static List<String> select(LegStrike strike, Map<String, Set<String>> hitIdsByGroup,
                                      List<String> candidates) {
        // 腿法沿用同一去重和整式目标预算，朝向与几何参数由宿主读取腿击声明。
        TechniqueCombatEffects.validateLegStrike(strike);
        Targeting targeting = new Targeting(strike.getMaximumTargets(), strike.getCastTargetLimit(),
                strike.getDedupGroup());
        return select(targeting, hitIdsByGroup, candidates);
    }

    private static List<String> select(Targeting targeting, Map<String, Set<String>> hitIdsByGroup,
                                       List<String> candidates) {
        int maximumTargets = targeting.maximumTargets();
        int castTargetLimit = targeting.castTargetLimit();
        Set<String> groupHitIds = hitIdsByGroup.getOrDefault(targeting.dedupGroup(), Set.of());
        var seen = new HashSet<>(groupHitIds);
        var castHitIds = new HashSet<String>();
        hitIdsByGroup.values().forEach(castHitIds::addAll);
        var selected = new ArrayList<String>();
        for (String candidate : candidates) {
            if (selected.size() >= maximumTargets) {
                break;
            }
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalArgumentException("候选目标身份缺失");
            }
            if (!seen.add(candidate)) {
                continue;
            }
            if (!castHitIds.contains(candidate) && castHitIds.size() >= castTargetLimit) {
                continue;
            }
            castHitIds.add(candidate);
            selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    private record Targeting(int maximumTargets, int castTargetLimit, String dedupGroup) { }
}
