package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;

/** 目录诊断和运行拒绝均提供可定位的中文说明。 */
public final class TechniqueErrors {
    private TechniqueErrors() {
        // 错误只描述拒绝事实，不持有身体或执行补偿。
    }

    static IllegalArgumentException invalid(String path, String reason) {
        // 字段路径保留英文标识，方便内容作者直接定位定义。
        return new IllegalArgumentException(path + "：" + reason);
    }

    static Rejected rejected(TechniqueFailure code, String reason) {
        // 运行拒绝单独携带线协议原因码，避免调用方解析中文文本。
        return new Rejected(code, reason);
    }

    static final class Rejected extends RuntimeException {
        final TechniqueFailure code;

        Rejected(TechniqueFailure code, String reason) {
            // 校验拒绝不产生任何候选状态的持久写入。
            super(reason);
            this.code = code;
        }
    }

    public static final class HostRejected extends RuntimeException {
        private final FailureCode failure;

        public HostRejected(FailureCode failure) {
            // 宿主保留 Framework 的失败码，不能把提交失败当成成功施放。
            super("宿主拒绝提交身体活动：" + failure);
            this.failure = failure;
        }

        public FailureCode failure() {
            // 原始失败码用于判断版本冲突与后续重试。
            return failure;
        }
    }
}
