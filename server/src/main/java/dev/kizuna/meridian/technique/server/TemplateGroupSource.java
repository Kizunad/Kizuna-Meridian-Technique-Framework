package dev.kizuna.meridian.technique.server;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TemplateReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TemplateGroupManifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** 单个附属声明的组及成员追加项，保留来源以定位跨附属冲突。 */
public record TemplateGroupSource(String source, TemplateGroupManifest manifest) {
    private static final String INDEX = "META-INF/kizuna_meridian/technique_template_groups.list";

    public TemplateGroupSource {
        // 文本、二进制和直接构造的消息使用同一格式约束。
        TechniqueValues.knownFields(manifest);
        if (manifest.getSchemaVersion() != TechniqueCatalogManifest.FORMAT_VERSION) {
            throw TechniqueErrors.invalid(source, "模板组清单版本不支持");
        }
    }

    public static TemplateGroupSource read(String source, InputStream input) throws IOException {
        // 来源后缀决定编码，文本和二进制沿用功法目录的字节上限。
        byte[] bytes = bounded(source, input);
        try {
            TemplateGroupManifest manifest;
            if (source.endsWith(TechniqueCatalogManifest.BINARY_SUFFIX)) {
                manifest = TechniqueProtocol.parse(bytes, TemplateGroupManifest.parser());
            } else if (source.endsWith(TechniqueCatalogManifest.TEXT_SUFFIX)) {
                var builder = TemplateGroupManifest.newBuilder();
                TextFormat.getParser().merge(new String(bytes, StandardCharsets.UTF_8), builder);
                manifest = builder.build();
            } else {
                throw new IllegalArgumentException("模板组清单必须为 .txtpb 或 .binpb");
            }
            return new TemplateGroupSource(source, manifest);
        } catch (TextFormat.ParseException | IllegalArgumentException error) {
            throw new IllegalArgumentException(source + "：" + error.getMessage(), error);
        }
    }

    public static List<TemplateGroupSource> classpath(ClassLoader loader) {
        // 每份索引只解析同一公共 JAR 里的清单，保留来源以诊断加载错误。
        var sources = new ArrayList<TemplateGroupSource>();
        try {
            for (var index : Collections.list(loader.getResources(INDEX))) {
                String paths;
                try (var in = index.openStream()) {
                    paths = new String(bounded(index.toExternalForm(), in), StandardCharsets.UTF_8);
                }
                for (String entry : paths.lines().filter(line -> !line.isBlank()).toList()) {
                    sources.add(indexedSource(index, entry));
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("读取模板组索引失败：" + error.getMessage(), error);
        }
        return List.copyOf(sources);
    }

    private static TemplateGroupSource indexedSource(URL index, String path) {
        // 索引项从该 JAR 的根目录解析，报错同时包含包位置和资源路径。
        if (!path.matches("data/[a-z0-9_.-]+/[a-z0-9_/.-]+") || path.contains("..")) {
            throw TechniqueErrors.invalid(index.toString(), "非法模板组资源路径：" + path);
        }
        String location = index.toExternalForm();
        String root = location.substring(0, location.length() - INDEX.length());
        String source = root + path;
        try (var input = URI.create(source).toURL().openStream()) {
            return read(source, input);
        } catch (IOException error) {
            throw new IllegalArgumentException(source + "：读取模板组清单失败", error);
        }
    }

    Stream<TemplateReference> templates() {
        // 引用的实际模板决定从哪些内容包建立校验上下文。
        var declared = manifest.getGroupsList().stream()
                .flatMap(group -> Stream.concat(Stream.of(group.getBase()), group.getMembersList().stream()));
        var added = manifest.getAdditionsList().stream().flatMap(group -> group.getMembersList().stream());
        return Stream.concat(declared, added);
    }

    private static byte[] bounded(String source, InputStream input) throws IOException {
        // 多读一个字节以区分上限整长和超长清单。
        int limit = TechniqueCatalogManifest.MAX_BYTES;
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw TechniqueErrors.invalid(source, "模板组资源超出字节上限");
        }
        return bytes;
    }
}
