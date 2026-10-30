package dev.kizuna.meridian.technique.fabric.client;

import com.google.gson.JsonObject;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 按卷身份补齐 generated 模型，资源重载后重新选择专用图标或通用卷轴。 */
public final class TechniqueScrollModels {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");
    private static final Identifier FALLBACK = new Identifier("meridian_technique", "item/generic_scroll");
    private static final Set<Identifier> WARNED = ConcurrentHashMap.newKeySet();

    private TechniqueScrollModels() {
        // 模型只依赖已冻结的物品身份和本次资源集合，不读取服务器身体。
    }

    public static void register() {
        // 准备阶段使用本次重载的资源管理器，避免读到上一套资源包。
        PreparableModelLoadingPlugin.register(
                (resources, executor) -> CompletableFuture.supplyAsync(() -> models(resources), executor),
                (models, context) -> context.resolveModel().register(resolver -> models.get(resolver.id())));
    }

    private static Map<Identifier, JsonUnbakedModel> models(ResourceManager resources) {
        // 内容包有专用图标和模型时保留其模型；缺图时总是使用可见的通用图标。
        var models = new HashMap<Identifier, JsonUnbakedModel>();
        for (Identifier item : TechniqueScrollItems.scrolls().keySet()) {
            String namespace = item.getNamespace();
            String itemPath = "item/" + item.getPath();
            Identifier texture = new Identifier(namespace, itemPath);
            Identifier image = new Identifier(namespace, "textures/" + itemPath + ".png");
            Identifier model = new Identifier(namespace, "models/" + itemPath + ".json");
            if (resources.getResource(image).isEmpty()) {
                models.put(texture, generated(FALLBACK));
                if (WARNED.add(item)) {
                    LOG.warn("卷轴 {} 缺少贴图 {}，使用功法库通用卷轴图标", item, image);
                }
            } else if (resources.getResource(model).isEmpty()) {
                models.put(texture, generated(texture));
            }
        }
        return Map.copyOf(models);
    }

    private static JsonUnbakedModel generated(Identifier texture) {
        // 原版生成器负责边缘和手持变换，JSON 由结构化对象构造。
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", texture.toString());
        model.add("textures", textures);
        return JsonUnbakedModel.deserialize(model.toString());
    }
}
