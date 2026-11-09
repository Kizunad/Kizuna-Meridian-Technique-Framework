package dev.kizuna.meridian.technique.server;

import java.util.Objects;

/** 附属效果处理器的处理结果；拒绝时保留给调用方的原因。 */
public record EffectHandling(Status status, String reason) {
    public enum Status { ACCEPTED, REJECTED, NOT_APPLICABLE }

    public EffectHandling {
        // 未认领与业务拒绝分开，其他附属不能覆盖已认领处理器给出的拒绝原因。
        Objects.requireNonNull(status);
        Objects.requireNonNull(reason);
        if (status == Status.REJECTED && reason.isBlank()) {
            throw new IllegalArgumentException("拒绝的效果必须携带原因");
        }
        if (status != Status.REJECTED && !reason.isEmpty()) {
            throw new IllegalArgumentException("未拒绝的效果不能携带原因");
        }
    }

    public boolean accepted() {
        // 调用方只在明确接受时推进效果游标。
        return status == Status.ACCEPTED;
    }

    public static EffectHandling handled() {
        // 已执行效果可由处理器按同一身份幂等接受。
        return new EffectHandling(Status.ACCEPTED, "");
    }

    public static EffectHandling notApplicable() {
        // 未认领的处理器继续交给下一个附属。
        return new EffectHandling(Status.NOT_APPLICABLE, "");
    }

    public static EffectHandling rejected(String reason) {
        // 拒绝原因随结构化施放结果返回，不使用异常控制正常业务流程。
        return new EffectHandling(Status.REJECTED, reason);
    }
}
