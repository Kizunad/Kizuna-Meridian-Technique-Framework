package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;

/** 目录诊断保留可定位说明，运行拒绝提供结构化码和玩家感受。 */
public final class TechniqueErrors {
    private TechniqueErrors() {
        // 错误只描述拒绝事实，不持有身体或执行补偿。
    }

    static IllegalArgumentException invalid(String path, String reason) {
        // 字段路径保留英文标识，方便内容作者直接定位定义。
        return new IllegalArgumentException(path + "：" + reason);
    }

    static Rejected rejected(TechniqueFailure code) {
        // 运行拒绝只携带结构化原因码，玩家文本统一由 playerReason 生成。
        return new Rejected(code);
    }

    static String playerReason(TechniqueFailure code) {
        // 玩家只看到身体感受，失败码仍由结构化字段提供给宿主和测试。
        return switch (code) {
            case UNKNOWN_TECHNIQUE, NOT_KNOWN -> "你还不会这门功法";
            case COOLING_DOWN -> "气息尚未平复";
            case ALREADY_CASTING -> "正运功中，无暇他顾";
            case BODY_UNAVAILABLE -> "你已无法运功";
            case REALM_TOO_LOW -> "修为尚浅，尚不能运功";
            case MISSING_MODULE, MODULE_UNAVAILABLE -> "身体尚未准备好";
            case MERIDIAN_NOT_OPENED -> "经脉未通";
            case INSUFFICIENT_CAPABILITY -> "你过于疲惫";
            case INSUFFICIENT_QI -> "真元不济";
            case CAST_ID_CONFLICT -> "这次施放已另有安排";
            case INVALID_STATE -> "心神未定，暂时无法运功";
            case FRAMEWORK_REJECTED -> "身体暂不听使唤";
            case INVALID_REQUEST -> "心意未定，无法运功";
            case NO_HOST -> "暂时无法运功";
            default -> "暂时无法运功";
        };
    }

    static String frameworkPlayerReason(FailureCode failure) {
        // Framework 的技术失败码只用于选择合适的感受描述，不直接展示枚举名。
        return switch (failure) {
            case BODY_DEAD -> playerReason(TechniqueFailure.BODY_UNAVAILABLE);
            case TIME_REQUIRES_SETTLEMENT -> "身体尚未平定";
            case REVISION_CONFLICT -> "气息忽乱，暂时无法运功";
            default -> playerReason(TechniqueFailure.FRAMEWORK_REJECTED);
        };
    }

    static final class Rejected extends RuntimeException {
        final TechniqueFailure code;

        Rejected(TechniqueFailure code) {
            // 校验拒绝不产生任何候选状态的持久写入。
            super(code.name());
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
