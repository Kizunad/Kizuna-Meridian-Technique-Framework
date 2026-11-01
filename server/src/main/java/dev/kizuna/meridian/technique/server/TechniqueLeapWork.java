package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityEffort;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;

/** 每次跃步只提交组织做功，不再收费、学功法或占用施放动作。 */
final class TechniqueLeapWork {
    private TechniqueLeapWork() {
        // 身体仍由宿主的同一事务入口持有。
    }

    static TechniqueService.LeapExertion submit(TechniqueHost host, TechniqueDefinition definition, LeapProfile leap,
                                                String requestId) {
        // 药水命令得到的能力不要求学会功法或开脉，但腿肌有效能力依旧严格检查。
        BodySnapshot body = host.inspect().body();
        if (TechniqueState.read(body).hasActiveCast()) {
            return TechniqueService.LeapExertion.rejected(TechniqueService.LeapExertion.ACTIVE_CAST);
        }
        var work = leap.getWork();
        var slots = definition.getSlotsList().stream().filter(slot -> slot.getId().equals(work.getSlotId())).toList();
        var movement = definition.toBuilder()
                .clearSlots()
                .addAllSlots(slots)
                .setMinimumRealm(Realm.valueOf(body.realm().name()))
                .build();
        var binding = TechniqueBinder.bind(body, movement);
        if (!binding.hasBinding()) {
            return TechniqueService.LeapExertion.rejected(TechniqueService.LeapExertion.NO_BINDING);
        }
        var activity = BodyActivity.newBuilder();
        for (String module : binding.getBinding().getSlots(0).getModuleIdsList()) {
            var load = new TissueCapabilities.Exertion(new Anatomy.TissueId(module), work.getForce().getValue(),
                    work.getPower().getValue(), work.getDuration().getValue());
            // 同一 revision 先验证力量与耐力；失败抛异常，候选不写回身体。
            TissueCapabilities.exert(body, load);
            var effort = ActivityEffort.newBuilder()
                    .setTissueId(module)
                    .setWatts(work.getPower().getValue())
                    .setSeconds(work.getDuration().getValue())
                    .setTraining(work.getTraining())
                    .build();
            activity.addEfforts(effort);
        }
        BodyActivity plan = activity.build();
        // Framework 预检整个活动，真正提交仍校验版本并原子保存。
        BodyActivityExecution.apply(body, plan, TechniqueHost.CALLER_ID);
        host.submit(new TechniqueHost.Submission(requestId, body.revision(), plan));
        return TechniqueService.LeapExertion.paid(plan.getEffortsList());
    }
}
