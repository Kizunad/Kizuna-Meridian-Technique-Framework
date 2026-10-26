package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Message;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** 公共输入边界共用的单位、规模与标识约束。 */
final class TechniqueValues {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_MODULES = 16;
    static final int MAX_COSTS = 32;
    static final int MAX_EFFECTS = 32;
    static final long MAX_MILLIS = 86_400_000;
    static final long MAX_CURVE_MILLIS = 300_000;
    static final double MAX_QUANTITY = 1_000_000_000;
    static final double MAX_SECONDS = 86_400;
    static final double MAX_PEAK = 10;
    static final double MIN_MULTIPLIER = 0.05;
    static final double MAX_SPEED = 100;
    static final double MAX_DISTANCE = 1024;
    static final double MAX_WEIGHT = 100;
    static final int MAX_TEXT = 256;
    static final int MAX_DESCRIPTION = 4096;
    static final int MAX_CAST_ID = 64;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern LOCAL_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern CAST_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private TechniqueValues() {
        // 校验纯数据，不读取宿主配置或身体资源。
    }

    static void id(String value, String path) {
        // 内容身份与 Framework 使用同一种命名空间语法。
        if (value.length() > MAX_TEXT || !ID.matcher(value).matches()) {
            throw TechniqueErrors.invalid(path, "必须是有界的 namespace:path 标识");
        }
    }

    static void localId(String value, String path) {
        // 局部槽位和命名空间禁止空值，防止默认字符串隐式匹配。
        if (value.length() > MAX_TEXT || !LOCAL_ID.matcher(value).matches()) {
            throw TechniqueErrors.invalid(path, "必须是非空英文局部标识");
        }
    }

    static void castId(String value) {
        // 幂等键可安全嵌入动作实例与宿主请求 ID。
        if (value.length() > MAX_CAST_ID || !CAST_ID.matcher(value).matches()) {
            throw TechniqueErrors.invalid("cast_id", "必须是 1–64 位英文字母、数字、下划线或连字符");
        }
    }

    static double quantity(Quantity quantity, Unit unit, double minimum, double maximum, String path) {
        // value 的存在性与单位独立检查；0 只有在范围允许时才合法。
        double value = quantity.getValue();
        if (!quantity.hasValue() || quantity.getUnit() != unit) {
            throw TechniqueErrors.invalid(path, "缺少数值或单位错误，应为 " + unit);
        }
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw TechniqueErrors.invalid(path, "数值必须有限且位于 [" + minimum + ", " + maximum + "]");
        }
        return value;
    }

    static double positive(Quantity quantity, Unit unit, double maximum, String path) {
        // 正费用不能借零值绕过扣费，微小数值仍须通过 Framework 的可流转精度检查。
        double value = quantity(quantity, unit, 0, maximum, path);
        if (value == 0) {
            throw TechniqueErrors.invalid(path, "数值必须大于 0");
        }
        return value;
    }

    static void millis(long value, String path) {
        // 时间上限固定为一天，避免失控的定义与加法溢出。
        if (value < 0 || value > MAX_MILLIS) {
            throw TechniqueErrors.invalid(path, "毫秒必须位于 [0, " + MAX_MILLIS + "]");
        }
    }

    static void knownFields(Message message) {
        // 未知 oneof 分支也会落入 unknown fields，递归拒绝可防止静默忽略新算子。
        if (message.getSerializedSize() > TechniqueProtocol.MAX_PAYLOAD_BYTES) {
            throw TechniqueErrors.invalid(message.getDescriptorForType().getName(), "消息大小超限");
        }
        if (!message.getUnknownFields().asMap().isEmpty()) {
            throw TechniqueErrors.invalid(message.getDescriptorForType().getName(), "包含未知字段或算子");
        }
        for (Object value : message.getAllFields().values()) {
            if (value instanceof Message nested) {
                knownFields(nested);
            } else if (value instanceof String text) {
                validateText(text);
            } else if (value instanceof List<?> values) {
                for (Object item : values) {
                    if (item instanceof Message nested) {
                        knownFields(nested);
                    } else if (item instanceof String text) {
                        validateText(text);
                    }
                }
            }
        }
    }

    private static void validateText(String text) {
        // 本地 Java 构造也拒绝孤立代理项，不能让编码替换字符改变幂等身份。
        if (text.length() > MAX_DESCRIPTION || !StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            throw TechniqueErrors.invalid("text", "文本过长或包含非法 Unicode");
        }
    }
}
