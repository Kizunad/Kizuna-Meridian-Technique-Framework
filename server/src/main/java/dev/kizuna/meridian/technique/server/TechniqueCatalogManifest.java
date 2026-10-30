package dev.kizuna.meridian.technique.server;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 模组内功法目录清单的有界文本与二进制解析。 */
public final class TechniqueCatalogManifest {
    public static final int FORMAT_VERSION = TechniqueValues.SCHEMA_VERSION;
    public static final int MAX_BYTES = TechniqueProtocol.MAX_PAYLOAD_BYTES;
    public static final String TEXT_SUFFIX = ".txtpb";
    public static final String BINARY_SUFFIX = ".binpb";

    private TechniqueCatalogManifest() {
        // 解析不登记内容，语义及跨目录引用由统一注册表校验。
    }

    public static TechniqueCatalog read(String source, Path file) {
        // 读取长度再次设界，文件在元信息检查后发生变化也不会无限读入。
        try {
            requireLength(source, Files.size(file));
            byte[] bytes;
            try (var stream = Files.newInputStream(file)) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
            requireLength(source, bytes.length);
            String name = file.toString();
            if (name.endsWith(BINARY_SUFFIX)) {
                return parseBinary(source, bytes);
            }
            if (name.endsWith(TEXT_SUFFIX)) {
                return parseText(source, new String(bytes, StandardCharsets.UTF_8));
            }
            throw TechniqueErrors.invalid(source, "清单后缀必须为 .txtpb 或 .binpb");
        } catch (IOException exception) {
            throw new IllegalArgumentException(source + "：清单读取失败", exception);
        }
    }

    public static TechniqueCatalog parseText(String source, String text) {
        // 默认文本解析器拒绝未知字段，错误保留行列号。
        requireLength(source, text.getBytes(StandardCharsets.UTF_8).length);
        var builder = TechniqueCatalog.newBuilder();
        try {
            TextFormat.getParser().merge(text, builder);
            TechniqueCatalog catalog = builder.build();
            validateFormat(catalog);
            return catalog;
        } catch (TextFormat.ParseException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(source + "：" + exception.getMessage(), exception);
        }
    }

    public static TechniqueCatalog parseBinary(String source, byte[] bytes) {
        // 二进制与文本执行相同版本检查，递归未知字段检查沿用协议边界。
        requireLength(source, bytes.length);
        try {
            TechniqueCatalog catalog = TechniqueProtocol.decodeCatalog(bytes);
            validateFormat(catalog);
            return catalog;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(source + "：" + exception.getMessage(), exception);
        }
    }

    private static void validateFormat(TechniqueCatalog catalog) {
        // 现有 schema_version 已表达目录格式版本，不另套重复的外层消息。
        TechniqueValues.knownFields(catalog);
        if (catalog.getSchemaVersion() != FORMAT_VERSION) {
            throw TechniqueErrors.invalid("schema_version", "必须为 " + FORMAT_VERSION);
        }
    }

    private static void requireLength(String source, long bytes) {
        // 清单文本和二进制均使用现有协议的 1 MiB 上限。
        if (bytes > MAX_BYTES) {
            throw TechniqueErrors.invalid(source, "清单大小超过 " + MAX_BYTES + " 字节");
        }
    }
}
