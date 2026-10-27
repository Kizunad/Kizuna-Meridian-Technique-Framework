package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;

/** 尚未对外认领或已经死亡的身体恢复边界；只能由可信宿主在身体事件线程调用。 */
public interface TechniqueRecoveryHost {
    /** 返回当前权威快照；宿主在恢复前完成必要的时间结算。 */
    BodySnapshot snapshot();

    /**
     * 按命令类型调用 Framework handleActivity 或 handleAddonProgress，保留 expected_revision。
     * 返回实际提交版本；失败抛 TechniqueErrors.HostRejected，不发布部分结果。
     */
    long submitRecovery(String requestId, BodyCommand command);
}
