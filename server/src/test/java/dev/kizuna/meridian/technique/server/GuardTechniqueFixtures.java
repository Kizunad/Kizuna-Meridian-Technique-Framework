package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import static dev.kizuna.meridian.technique.server.Fixtures.*;

/** 持续守式样本只供服务和游戏回归使用。 */
final class GuardTechniqueFixtures {
    static final String ID = "sample:guard";
    static final int INNATE_COUNT = 1;
    static final double ANGLE = 120;
    static final double DAMAGE_RATIO = 0.6;
    static final double MOVEMENT = 0.7;
    static final long LONG_HOLD = 90_000_000;
    static final long RELEASE_AT = 3_000;

    private GuardTechniqueFixtures() {
        // 正式内容的数值和身份由内容附属提供。
    }

    static TechniqueDefinition guard() {
        // 不带部位、费用和世界伤害；起手末只开启守和持续表现。
        TechniqueDefinition base = definition();
        CastPhases phases = base.getPhases().toBuilder()
                .clearActiveMillis()
                .setActiveDuration(ActiveDuration.ACTIVE_DURATION_UNTIL_RELEASE)
                .build();
        Guard guard = Guard.newBuilder()
                .setAngle(quantity(ANGLE, Unit.DEGREE))
                .setDamageMultiplier(quantity(DAMAGE_RATIO, Unit.RATIO))
                .addDamageTags("sample:blockable")
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setGuard(guard)
                .build();
        return base.toBuilder()
                .setId(ID)
                .setCategory(Category.DEFENSE)
                .setInnate(true /* innate */)
                .setPhases(phases)
                .setAiHints(hints())
                .setMovementSpeedMultiplier(MOVEMENT)
                .clearSlots()
                .setCost(TechniqueCost.getDefaultInstance())
                .clearEffects()
                .addEffects(effect)
                .addEffects(presentation())
                .build();
    }

    private static AiHints hints() {
        // 正权重和可达距离确保选式测试确实依靠角色排除守式。
        SupportAiHints support = SupportAiHints.newBuilder().setRole(AiRole.AI_ROLE_GUARD).build();
        return AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(1, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO))
                .setSupport(support)
                .build();
    }

    private static TechniqueEffect presentation() {
        // 生效段表现必须在收势开始停止，动作锁则保持至收势结束。
        PresentationEffect presentation = PresentationEffect.newBuilder()
                .setId("sample:guard_pose")
                .setDuration(PresentationDuration.UNTIL_ACTIVE_END)
                .build();
        return TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setPresentation(presentation)
                .build();
    }

    static MemoryHost host() {
        // 身体从无学习记录开始，天生式不依赖初始化塞知识。
        BodySnapshot body = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
        return new MemoryHost(body);
    }

    static CastRequest press(MemoryHost host, String castId) {
        // 请求身份来自宿主当前身体，守式引用使用独立测试命名空间。
        return request(host.current(), castId).toBuilder()
                .setTechnique(TechniqueRegistry.reference(guard()))
                .build();
    }

    static EndActiveRequest release(CastRequest press) {
        // 松开只带身体和施放身份，没有客户端时钟。
        return EndActiveRequest.newBuilder()
                .setBodyId(press.getBodyId())
                .setCastId(press.getCastId())
                .build();
    }
}
