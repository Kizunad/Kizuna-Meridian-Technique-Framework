package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AddonCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;

import java.util.Objects;

/** 纯计算附属费用，返回的进度由功法活动与其他资源一起提交。 */
public interface TechniqueAddonCostPayer {
    String key();

    Payment pay(BodySnapshot body, AddonCost cost);

    default double qiMultiplier(BodySnapshot body, TechniqueDefinition technique) {
        // 付款方可按可信宿主状态调整起手真元，未参与时使用目录原价。
        return 1;
    }

    record Payment(Any progress, String failureTranslationKey, String reason) {
        public Payment {
            // 成功返回候选进度，拒绝必须携带玩家可翻译的原因键。
            if (progress == null && (failureTranslationKey == null || failureTranslationKey.isBlank())) {
                throw new IllegalArgumentException("附属费用拒绝必须提供翻译键");
            }
            if (failureTranslationKey != null && failureTranslationKey.isBlank()) {
                throw new IllegalArgumentException("附属费用翻译键不能是空值");
            }
            Objects.requireNonNull(reason, "reason");
        }

        public static Payment accepted(Any progress) {
            // 接受结果只保存尚未提交的附属进度。
            return new Payment(Objects.requireNonNull(progress), null, "");
        }

        public static Payment rejected(String translationKey, String reason) {
            // 拒绝结果不包含可提交进度。
            return new Payment(null, Objects.requireNonNull(translationKey), Objects.requireNonNull(reason));
        }

        public boolean accepted() {
            // 只有存在候选进度时费用支付才成功。
            return progress != null;
        }
    }
}
