package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollNetworking;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** 学习成功后的只读阅读钩子；展示端不决定学习结果或发送确认。 */
public final class TechniqueScrollClient implements ClientModInitializer {
    public static final Event<LearnedCallback> LEARNED = EventFactory.createArrayBacked(
            LearnedCallback.class, callbacks -> scroll -> {
                for (LearnedCallback callback : callbacks) {
                    callback.onLearned(scroll);
                }
            });

    @Override
    public void onInitializeClient() {
        // 收包线程只解码卷身份，界面回调始终在客户端主线程执行。
        TechniqueScrollModels.register();
        TechniqueLeapClient.initialize();
        TechniqueStaggerClient.initialize();
        LEARNED.register(scroll -> Minecraft.getInstance().setScreen(new TechniqueScrollScreen(scroll)));
        ClientPlayNetworking.registerGlobalReceiver(TechniqueScrollNetworking.LEARNED,
                (client, handler, buffer, sender) -> {
                    ResourceLocation volume = buffer.readResourceLocation();
                    client.execute(() -> notifyLearned(volume));
                });
    }

    public static ResourceLocation background(TechniqueScrollCatalog.Scroll scroll) {
        // 阅读背景沿用卷路径，不依赖物品模型或物品的 _scroll 后缀。
        ResourceLocation volume = new ResourceLocation(scroll.volume().getId());
        return new ResourceLocation(volume.getNamespace(), "textures/gui/scroll/" + volume.getPath() + ".png");
    }

    private static void notifyLearned(ResourceLocation volume) {
        // 只转发本地同一份清单已登记的卷，晚到目录没有卷轴展示通知。
        ResourceLocation itemId = new ResourceLocation(volume.getNamespace(), volume.getPath() + "_scroll");
        var item = TechniqueScrollItems.scrolls().get(itemId);
        if (item != null) {
            LEARNED.invoker().onLearned(item.scroll());
        }
    }

    @FunctionalInterface
    public interface LearnedCallback {
        /** 在服务端确认学习后显示卷名、式说明与背景；无需回复服务器。 */
        void onLearned(TechniqueScrollCatalog.Scroll scroll);
    }
}
