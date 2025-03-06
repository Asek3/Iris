package net.coderbot.iris.mixin;

import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.ShadowRenderer;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.pipeline.newshader.CoreWorldRenderingPipeline;
import net.coderbot.iris.pipeline.newshader.ShaderKey;

import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ForgeHooksClient.ClientEvents.class)
public class MixinForgeTranslucentShader {

    @Inject(method = {"getEntityTranslucentUnlitShader"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void iris$overrideForgeTranslucentShader(CallbackInfoReturnable<ShaderInstance> cir) {
        if (ShadowRenderer.ACTIVE) {
            override(ShaderKey.SHADOW_ENTITIES_CUTOUT, cir);
        }
        else if (shouldOverrideShaders()) {
            override(ShaderKey.ENTITIES_TRANSLUCENT, cir);
        }
    }

    private static boolean shouldOverrideShaders() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

        if (pipeline instanceof CoreWorldRenderingPipeline) {
            return ((CoreWorldRenderingPipeline) pipeline).shouldOverrideShaders();
        } else {
            return false;
        }
    }

    private static void override(ShaderKey key, CallbackInfoReturnable<ShaderInstance> cir) {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

        if (pipeline instanceof CoreWorldRenderingPipeline) {
            ShaderInstance override = ((CoreWorldRenderingPipeline) pipeline).getShaderMap().getShader(key);

            if (override != null) {
                cir.setReturnValue(override);
            }
        }
    }
}