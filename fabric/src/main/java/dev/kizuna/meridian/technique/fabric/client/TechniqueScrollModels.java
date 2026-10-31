package dev.kizuna.meridian.technique.fabric.client;

import com.google.gson.JsonObject;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按卷身份补齐 generated 模型和物品定义，资源重载后重新选择专用图标或通用卷轴。
 *
 * <p>26.2 的 Fabric 模型接口不能按 ID 新增模型或物品定义，由客户端混入在原版读完资源后调用这里合并。
 */
public final class TechniqueScrollModels {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");
    private static final Identifier FALLBACK =
            Identifier.fromNamespaceAndPath("meridian_technique", "item/generic_scroll");
    private static final Set<Identifier> WARNED = ConcurrentHashMap.newKeySet();

    private TechniqueScrollModels() {
        // 模型只依赖已冻结的物品身份和本次资源集合，不读取服务器身体。
    }

    public static Map<Identifier, UnbakedModel> withModels(Map<Identifier, UnbakedModel> loaded,
                                                          ResourceManager resources) {
        // 内容包有专用图标和模型时保留其模型；缺图时总是使用可见的通用图标，覆盖内容包自带的模型。
        // 资源管理器是本次重载原版读模型用的同一个，不会读到上一套资源包。
        var models = new HashMap<>(loaded);
        for (Identifier item : TechniqueScrollItems.scrolls().keySet()) {
            Identifier texture = item.withPrefix("item/");
            Identifier image = texture.withPath(path -> "textures/" + path + ".png");
            Identifier model = texture.withPath(path -> "models/" + path + ".json");
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

    public static ClientItemInfoLoader.LoadedClientInfos withItems(ClientItemInfoLoader.LoadedClientInfos loaded) {
        // 26.2 的物品要有 items/ 定义才会用上模型；内容包没写的，补一份直接指向 item/<路径> 模型的定义。
        var items = new HashMap<>(loaded.contents());
        for (Identifier item : TechniqueScrollItems.scrolls().keySet()) {
            items.computeIfAbsent(item, id -> new ClientItem(
                    new CuboidItemModelWrapper.Unbaked(id.withPrefix("item/"), Optional.empty(), List.of()),
                    ClientItem.Properties.DEFAULT));
        }
        return new ClientItemInfoLoader.LoadedClientInfos(items);
    }

    private static UnbakedModel generated(Identifier texture) {
        // 原版生成器负责边缘和手持变换，JSON 由结构化对象构造。
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", texture.toString());
        model.add("textures", textures);
        return CuboidModel.fromStream(new StringReader(model.toString()));
    }
}
