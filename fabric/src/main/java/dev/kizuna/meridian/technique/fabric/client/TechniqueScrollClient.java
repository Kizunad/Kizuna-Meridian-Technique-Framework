package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollNetworking;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

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
        // 26.2 的收包回调已在客户端主线程上，界面回调直接执行；卷轴模型改由客户端混入在资源加载时补齐。
        // 26.2 的界面切换移到 Gui 上。
        TechniqueLeapClient.initialize();
        TechniqueStaggerClient.initialize();
        LEARNED.register(scroll -> Minecraft.getInstance().gui.setScreen(new TechniqueScrollScreen(scroll)));
        ClientPlayNetworking.registerGlobalReceiver(TechniqueScrollNetworking.LEARNED,
                (payload, context) -> notifyLearned(payload.volume()));
    }

    public static Identifier background(TechniqueScrollCatalog.Scroll scroll) {
        // 阅读背景沿用卷路径，不依赖物品模型或物品的 _scroll 后缀。
        Identifier volume = Identifier.parse(scroll.volume().getId());
        return volume.withPath("textures/gui/scroll/" + volume.getPath() + ".png");
    }

    private static void notifyLearned(Identifier volume) {
        // 只转发本地同一份清单已登记的卷，晚到目录没有卷轴展示通知。
        Identifier itemId = volume.withSuffix("_scroll");
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
