package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.client.TechniqueScrollModels;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ModelManager.class)
public abstract class TechniqueScrollModelsMixin {
    @Inject(method = "loadBlockModels", at = @At("RETURN"), cancellable = true)
    private static void meridian$scrollModels(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<Identifier, UnbakedModel>>> callback) {
        // 原版读完全部模型文件后，再合并卷轴的 generated 模型。
        callback.setReturnValue(callback.getReturnValue()
                .thenApply(models -> TechniqueScrollModels.withModels(models, resources)));
    }
}
