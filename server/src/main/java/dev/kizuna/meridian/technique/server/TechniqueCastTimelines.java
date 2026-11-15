package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpHint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import java.util.Optional;

/** 把身体上正在进行的施放整理成给本人看的时间轴：三段的边界、各次打击、能接哪几式和各自的窗口。只读。 */
public final class TechniqueCastTimelines {
    private TechniqueCastTimelines() {
        // 只有一个纯函数入口。
    }

    public static CastStatus.Builder status(TechniqueRegistry registry, TechniqueProgress progress, long nowMillis) {
        // 没有施放时返回空状态；时刻都换成相对起手的毫秒，和调度器发效果用的是同一套边界。
        CastStatus.Builder status = CastStatus.newBuilder();
        if (!progress.hasActiveCast()) {
            return status;
        }
        ActiveCast active = progress.getActiveCast();
        long started = active.getStartedMillis();
        TechniqueReference technique = active.getRequest().getTechnique();
        status.setTechniqueId(technique.getId())
                .setCastId(active.getRequest().getCastId())
                .setElapsedMillis(nowMillis - started)
                .setWarmupEndMillis(active.getWarmupEndMillis() - started)
                .setActiveEndMillis(active.getActiveEndMillis() - started)
                .setRecoveryEndMillis(active.getRecoveryEndMillis() - started);
        Optional<TechniqueDefinition> definition = registry.find(technique);
        definition.ifPresent(found -> hits(found, active, status));
        // 收招段从哪一刻开始；目录里没有这一式时写成收势结束，等于不能提前。
        status.setFreeMillis(TechniqueFollowUps.windDownStart(active, definition)
                .orElse(active.getRecoveryEndMillis() - started));
        for (KnownTechnique known : progress.getKnownList()) {
            registry.find(known.getTechnique()).ifPresent(next -> followUp(next, active, status));
        }
        return status;
    }

    private static void hits(TechniqueDefinition definition, ActiveCast active, CastStatus.Builder status) {
        // 承接跳过的那段剪辑里的打击不会发，和调度器一致。
        long clipStart = active.getClipStartMillis();
        for (TechniqueEffect effect : definition.getEffectsList()) {
            boolean strike = effect.hasWeaponStrike() || effect.hasLegStrike() || effect.hasLimbStrike();
            if (strike && effect.getAtMillis() >= clipStart) {
                status.addHitMillis(effect.getAtMillis() - clipStart);
            }
        }
    }

    private static void followUp(TechniqueDefinition next, ActiveCast active, CastStatus.Builder status) {
        // 这一式声明了能接在当前这一式后面：窗口从收势过了声明的比例开到收势结束后的宽限为止。
        long started = active.getStartedMillis();
        for (FollowUp transition : next.getFollowUp().getTransitionsList()) {
            if (!transition.getPrevious().equals(active.getRequest().getTechnique())) {
                continue;
            }
            long open = TechniqueFollowUps.windowOpen(active.getActiveEndMillis(), active.getRecoveryEndMillis(),
                    transition);
            status.addFollowUps(FollowUpHint.newBuilder()
                    .setTechniqueId(next.getId())
                    .setOpenMillis(open - started)
                    .setCloseMillis(active.getRecoveryEndMillis() + transition.getAfterRecoveryMillis() - started));
        }
    }
}
