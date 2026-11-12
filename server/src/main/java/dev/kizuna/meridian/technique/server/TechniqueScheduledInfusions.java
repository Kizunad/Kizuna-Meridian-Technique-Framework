package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveInfusion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;

import java.util.Optional;

/** 附行到时由本库写进身体进度，覆盖旧附行；施放在此前终止就不写，宿主不必认识这个算子。 */
final class TechniqueScheduledInfusions {
    private static final String REQUEST_PREFIX = "technique_infusion_";

    /** 本次施放里附行生效的身体时刻和它的定义。 */
    private record Due(long scheduledMillis, TechniqueEffect effect) {
    }

    private TechniqueScheduledInfusions() {
        // 生效与否只看身体进度里记下的施放身份，不另建账本。
    }

    static long boundary(TechniqueDefinition definition, ActiveCast active, long previousMillis, long nowMillis) {
        // 附行时刻落在本次推进区间里时停在那一刻，让同刻的施放声和后续效果按原顺序处理。
        if (previousMillis > nowMillis) {
            throw new IllegalArgumentException("附行时刻倒退");
        }
        return scheduled(definition, active)
                .map(Due::scheduledMillis)
                .filter(dueMillis -> dueMillis > previousMillis && dueMillis <= nowMillis)
                .orElse(nowMillis);
    }

    static BodySnapshot applyDue(TechniqueHost host, TechniqueDefinition definition, BodySnapshot body,
                                 long boundaryMillis) {
        // 已写过本次施放的附行就跳过；回执丢失后重新 inspect 也能据此认出已经提交。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            return body;
        }
        TechniqueProgress progress = TechniqueState.read(body);
        ActiveCast active = progress.getActiveCast();
        String castId = active.getRequest().getCastId();
        Optional<Due> scheduled = scheduled(definition, active);
        if (scheduled.isEmpty() || progress.getActiveInfusion().getCastId().equals(castId)) {
            return body;
        }
        long startedMillis = scheduled.get().scheduledMillis();
        if (startedMillis > boundaryMillis) {
            return body;
        }
        ActiveInfusion infusion = TechniqueInfusions.begin(castId, active.getRequest().getTechnique().getId(),
                startedMillis, scheduled.get().effect().getElementalInfusion());
        TechniqueProgress updated = progress.toBuilder().setActiveInfusion(infusion).build();
        BodyActivity activity = BodyActivity.newBuilder()
                .putProgress(TechniqueState.KEY, TechniqueState.pack(updated))
                .build();
        // Framework 复核进度载荷；失败抛异常，本次施放的附行留到下一次推进再写。
        BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
        long revision = body.revision();
        TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(REQUEST_PREFIX + castId,
                revision, activity));
        if (commit.revision() != revision + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("宿主返回的附行回执与计划不一致");
        }
        return host.inspect().body();
    }

    private static Optional<Due> scheduled(TechniqueDefinition definition, ActiveCast active) {
        // 时刻与其他效果一样按起播点裁掉预备段、再按起手动作速度缩放；被裁掉的附行不生效。
        long clipStartMillis = active.getClipStartMillis();
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (effect.hasElementalInfusion() && effect.getAtMillis() >= clipStartMillis) {
                long offset = TechniqueTempo.scale(effect.getAtMillis() - clipStartMillis,
                        TechniqueTempo.multiplier(active));
                return Optional.of(new Due(active.getStartedMillis() + offset, effect));
            }
        }
        return Optional.empty();
    }
}
