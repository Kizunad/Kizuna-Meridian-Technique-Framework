package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;

import java.util.Optional;

/**
 * 可信且已绑定实体的宿主边界。调用须与身体事件串行；每次操作重新验证身份与权限。
 * 宿主持有身体、时间和回执，库只在一次调用中使用权威快照。
 */
public interface TechniqueHost {
    String CALLER_ID = "meridian_technique:caster";

    /** 先按旧动作结算至可信当前身体时刻，再返回权威不可变快照；不接受客户端时间。 */
    BodySnapshot inspect();

    /**
     * 返回该身体、本库固定可信调用身份下已提交的活动；未提交返回 empty。
     * 使用 Framework 与身体共同保存的回执，不可只用连接缓存；查询也必须检查授权。
     */
    Optional<Commit> findActivity(String requestId);

    /**
     * 按 expectedRevision 调 handleActivity，原子保存身体、回执与释放账户。
     * 拒绝抛 TechniqueErrors.HostRejected；失败不留局部写入。结果不确定时保留原请求以供重试。
     */
    Commit submit(Submission submission);

    /** 把冲量或表现交给实体宿主；本库不直接改世界。 */
    EffectCommit submitEffect(EffectSubmission submission);

    record Submission(String requestId, long expectedRevision, BodyActivity activity) { }

    record Commit(long revision, BodyActivity activity) { }

    record EffectSubmission(String requestId, EffectIntent effect) { }

    record EffectCommit(String requestId) { }
}
