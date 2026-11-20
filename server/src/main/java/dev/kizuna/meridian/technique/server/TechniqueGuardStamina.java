package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityAction;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.ActionDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SustainedCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;

import java.util.List;

/** 宿主按身体构型算出费用和参与组织；结算游标、占用和破防期限随施放一起提交。 */
public final class TechniqueGuardStamina {
    private TechniqueGuardStamina() {
        // 只构造候选活动，提交仍由权威身体宿主完成。
    }

    public static BodyActivity hold(BodySnapshot body, TechniqueProgress progress, List<String> tissues) {
        // 同刻重查不重写游标；伤愈或失能后按当前可工作的肌肉更新占用。
        ActiveCast active = progress.getActiveCast();
        var sustained = active.getSustained();
        long now = body.settledAt().elapsedMillis();
        var plan = BodyActivity.newBuilder();
        String instance = occupancy(active);
        var previous = body.actions().stream().filter(action -> action.instanceId().equals(instance)).findFirst();
        if (previous.isEmpty() || !previous.get().definition().tissueIds().stream()
                .map(id -> id.value()).toList().equals(tissues)) {
            if (previous.isPresent()) {
                plan.addStopActions(instance);
            }
            var technique = active.getRequest().getTechnique();
            var definition = ActionDefinition.newBuilder()
                    .setId(technique.getId())
                    .setVersion(technique.getVersion())
                    .setDisplayName("守式占用")
                    .addAllTissueIds(tissues)
                    .build();
            var action = ActivityAction.newBuilder().setInstanceId(instance).setDefinition(definition).build();
            plan.addStartActions(action);
        }
        if (!sustained.hasStaminaPaidMillis() || sustained.getStaminaPaidMillis() != now) {
            var paid = sustained.toBuilder().setStaminaPaidMillis(now).build();
            var updated = active.toBuilder().setSustained(paid).build();
            var state = progress.toBuilder().setActiveCast(updated).build();
            plan.putProgress(TechniqueState.KEY, TechniqueState.pack(state));
        }
        return plan.build();
    }

    public static BodyActivity exhaust(BodySnapshot body, TechniqueProgress progress) {
        // 最后一笔疲劳和破防共用一个活动，命中提交也可复用此边界。
        return exhaust(body, progress, progress.getActiveCast().getSustained().toBuilder());
    }

    public enum Contact { FOREARM, WEAPON }

    public static BodyActivity exhaust(BodySnapshot body, TechniqueProgress progress, Contact contact) {
        // 碎裂的兵器按前臂承力；破防表现使用命中时保存的样式。
        var guard = progress.getActiveCast().getSustained().toBuilder()
                .setWeaponContactOnBreak(contact == Contact.WEAPON);
        return exhaust(body, progress, guard);
    }

    private static BodyActivity exhaust(BodySnapshot body, TechniqueProgress progress,
                                        SustainedCast.Builder guard) {
        // 结算时刻、破防原因与收势使用同一个身体游标。
        long now = body.settledAt().elapsedMillis();
        var ended = guard
                .setExhausted(true /* exhausted */)
                .setStaminaPaidMillis(now)
                .build();
        return TechniqueSustained.release(progress, now, ended);
    }

    static void stopOccupancy(ActiveCast active, BodyActivity.Builder plan) {
        // 只有已开始计费、尚未收势的实例仍持有肌肉占用。
        var guard = active.getSustained();
        if (guard.hasStaminaPaidMillis() && !guard.hasEndRequestedMillis()) {
            plan.addStopActions(occupancy(active));
        }
    }

    private static String occupancy(ActiveCast active) {
        // 复用施放动作的有界实例身份，旧施放的清理不会释放新守式。
        return active.getActionInstanceId() + ".guard";
    }
}
