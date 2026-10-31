package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.client.TechniqueScrollModels;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ClientItemInfoLoader.class)
public abstract class TechniqueScrollItemsMixin {
    @Inject(method = "scheduleLoad", at = @At("RETURN"), cancellable = true)
    private static void meridian$scrollItems(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<ClientItemInfoLoader.LoadedClientInfos>> callback) {
        // 原版读完全部 items/ 定义后，再给没有定义的卷轴补上。
        callback.setReturnValue(callback.getReturnValue().thenApply(TechniqueScrollModels::withItems));
    }
}
