package net.coderbot.iris.mixin.sky;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;

import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * Allows pipelines to disable the sun, moon, or both.
 */
@Mixin(RenderGlobal.class)
public class MixinLevelRenderer_SunMoonToggle {
	/**
	 * This is a convenient way to disable rendering the sun / moon, since this clears the sun's vertices from
	 * the buffer, then when BufferRenderer is passed the empty buffer it will notice that it's empty and
	 * won't dispatch an unnecessary draw call. Nice!
	 */
	@Unique
	private void iris$emptyBuilder() {
		BufferBuilder builder = Tessellator.getInstance().getBuffer();

		builder.discard();
		builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
		builder.end();
	}

	@Inject(method = "renderSky(FI)V",
		at = @At(value = "INVOKE", target = "com/mojang/blaze3d/vertex/BufferUploader.end (Lcom/mojang/blaze3d/vertex/BufferBuilder;)V"),
		slice = @Slice(
			from = @At(value = "FIELD", target = "net/minecraft/client/renderer/LevelRenderer.SUN_LOCATION : Lnet/minecraft/resources/ResourceLocation;"),
			to = @At(value = "FIELD", target = "net/minecraft/client/renderer/LevelRenderer.MOON_LOCATION : Lnet/minecraft/resources/ResourceLocation;")),
		allow = 1)
	private void iris$beforeDrawSun(float partialTicks, int pass, CallbackInfo ci) {
		if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldRenderSun).orElse(true)) {
			iris$emptyBuilder();
		}
	}

	@Inject(method = "renderSky(FI)V",
		at = @At(value = "INVOKE", target = "com/mojang/blaze3d/vertex/BufferUploader.end (Lcom/mojang/blaze3d/vertex/BufferBuilder;)V"),
		slice = @Slice(
			from = @At(value = "FIELD", target = "net/minecraft/client/renderer/LevelRenderer.MOON_LOCATION : Lnet/minecraft/resources/ResourceLocation;"),
			to = @At(value = "INVOKE", target = "net/minecraft/client/multiplayer/ClientLevel.getStarBrightness (F)F")),
		allow = 1)
	private void iris$beforeDrawMoon(float partialTicks, int pass, CallbackInfo ci) {
		if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldRenderMoon).orElse(true)) {
			iris$emptyBuilder();
		}
	}
}
