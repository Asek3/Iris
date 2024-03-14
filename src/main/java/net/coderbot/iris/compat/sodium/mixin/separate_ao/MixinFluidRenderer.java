package net.coderbot.iris.compat.sodium.mixin.separate_ao;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuffers;
import me.jellysquid.mods.sodium.client.render.pipeline.FluidRenderer;
import me.jellysquid.mods.sodium.client.util.color.ColorABGR;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;

/**
 * Basically the same as {@link MixinBlockRenderer}, but for fluid rendering.
 */
@Mixin(FluidRenderer.class)
public class MixinFluidRenderer {
	@Unique
	private boolean useSeparateAo;

	@Inject(method = "render", remap = false, at = @At("HEAD"))
	private void iris$cacheSeparateAoSetting(IBlockAccess world, IBlockState fluidState, BlockPos pos, ChunkModelBuffers buffers, CallbackInfoReturnable<Boolean> cir) {
		this.useSeparateAo = BlockRenderingSettings.INSTANCE.shouldUseSeparateAo();
	}

	@Redirect(method = "calculateQuadColors", remap = false,
			at = @At(value = "INVOKE", target = "me/jellysquid/mods/sodium/client/util/color/ColorABGR.mul (IF)I", remap = false))
	private int iris$applySeparateAo(int color, float ao) {
		if (useSeparateAo) {
			color &= 0x00FFFFFF;
			color |= ((int) (ao * 255.0f)) << 24;
		} else {
			color = ColorABGR.mul(color, ao);
		}

		return color;
	}
}
