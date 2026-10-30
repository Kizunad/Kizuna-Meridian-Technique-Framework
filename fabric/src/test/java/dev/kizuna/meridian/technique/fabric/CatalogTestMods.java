package dev.kizuna.meridian.technique.fabric;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 仅替换发现器使用的 Fabric 元数据，不启动 Minecraft。 */
final class CatalogTestMods {
    private CatalogTestMods() {
        // 测试容器不提供未使用的加载器功能。
    }

    static ModContainer mod(String id, Object configured, Path root) {
        // 模组拥有独立来源 ID，资源解析遵循 ModContainer.findPath 的存在性约定。
        CustomValue custom = null;
        if (configured != null) {
            custom = value(configured);
        }
        CustomValue declared = custom;
        ModMetadata metadata = proxy(ModMetadata.class, (instance, method, args) -> switch (method.getName()) {
            case "getId" -> id;
            case "getCustomValue" -> declared;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(ModContainer.class, (instance, method, args) -> switch (method.getName()) {
            case "getMetadata" -> metadata;
            case "findPath" -> Optional.of(root.resolve((String) args[0])).filter(Files::exists);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static CustomValue value(Object raw) {
        // 使用 API 类型模拟从 JSON 读出的值，不依赖 Fabric 的内部解析器。
        CustomValue.CvType type = type(raw);
        return proxy(CustomValue.class, (instance, method, args) -> switch (method.getName()) {
            case "getType" -> type;
            case "getAsString" -> (String) raw;
            case "getAsArray" -> array((List<?>) raw);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static CustomValue.CvType type(Object raw) {
        // 非法类型仍忠实返回原始类型，交给生产代码诊断。
        if (raw instanceof String) {
            return CustomValue.CvType.STRING;
        }
        if (raw instanceof List<?>) {
            return CustomValue.CvType.ARRAY;
        }
        if (raw instanceof Boolean) {
            return CustomValue.CvType.BOOLEAN;
        }
        return CustomValue.CvType.NUMBER;
    }

    private static CustomValue.CvArray array(List<?> raw) {
        // 数组保留原顺序及每个成员的独立类型。
        List<CustomValue> values = raw.stream().map(CatalogTestMods::value).toList();
        return proxy(CustomValue.CvArray.class, (instance, method, args) -> switch (method.getName()) {
            case "iterator" -> values.iterator();
            case "size" -> values.size();
            case "get" -> values.get((Integer) args[0]);
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> api, InvocationHandler handler) {
        // 测试只依赖公开接口，用单一代理避免复制整个加载器实现。
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, handler));
    }
}
