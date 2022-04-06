package net.coderbot.iris.mixin.fantastic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3d;

import net.coderbot.iris.Iris;
import net.coderbot.iris.fantastic.ParticleRenderingPhase;
import net.coderbot.iris.fantastic.PhasedParticleEngine;
import net.coderbot.iris.layer.GbufferProgram;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Uses the PhasedParticleManager changes to render opaque particles much earlier than other particles.
 *
 * See the comments in {@link MixinParticleEngine} for more details.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	@Final
	private RenderBuffers renderBuffers;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void iris$resetParticleManagerPhase(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		((PhasedParticleEngine) minecraft.particleEngine).setParticleRenderingPhase(ParticleRenderingPhase.EVERYTHING);
	}
	
	//FORGE
	@Shadow
	private Frustum capturedFrustum;
	
	@Shadow
	private Frustum cullingFrustum;
	
	@Shadow
	@Final
	private Vector3d frustumPos;

	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=entities"))
	private void iris$renderOpaqueParticles(PoseStack poseStack, float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, CallbackInfo ci) {
		minecraft.getProfiler().popPush("opaque_particles");

		//  Forge shitty zone start
		Frustum frustum;
		boolean flag = this.capturedFrustum != null;
		
        if (flag) {
        	frustum = this.capturedFrustum;
        	frustum.prepare(this.frustumPos.x, this.frustumPos.y, this.frustumPos.z);
        } else {
        	frustum = this.cullingFrustum;
        }
        //  Forge shitty zone end
		
		MultiBufferSource.BufferSource bufferSource = renderBuffers.bufferSource();

		((PhasedParticleEngine) minecraft.particleEngine).setParticleRenderingPhase(ParticleRenderingPhase.OPAQUE);

		GbufferPrograms.push(GbufferProgram.TEXTURED_LIT);
		minecraft.particleEngine.render(poseStack, bufferSource, lightTexture, camera, f, frustum);

		((PhasedParticleEngine) minecraft.particleEngine).setParticleRenderingPhase(ParticleRenderingPhase.TRANSLUCENT);
		if (Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldRenderParticlesBeforeDeferred).orElse(false)) {
			// Render translucent particles here as well if the pack requests so
			minecraft.particleEngine.render(poseStack, bufferSource, lightTexture, camera, f, frustum);
		}
		GbufferPrograms.pop(GbufferProgram.TEXTURED_LIT);
	}

	//TODO
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V", remap = false))
	private void iris$renderTranslucentAfterDeferred(ParticleEngine instance, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LightTexture lightTexture, Camera camera, float f, Frustum frustum) {
		// We don't want to render translucent particles again if we already rendered them earlier!
		if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::shouldRenderParticlesBeforeDeferred).orElse(false)) {
			instance.render(poseStack, bufferSource, lightTexture, camera, f, frustum);
		}
	}
}
