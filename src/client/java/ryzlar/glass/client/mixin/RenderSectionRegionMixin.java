package ryzlar.glass.client.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.CloakHider;

/**
 * Vanilla's chunk builder sees a cloak's hidden part as the cloak makes it up (see {@link CloakHider}): the blocks, their
 * fluids, no block entities, and the light.
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin implements BlockAndTintGetter
{
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void glass$hiddenBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir)
    {
        if(CloakHider.hidesAnything())
        {
            BlockState instead = CloakHider.state(pos.getX(), pos.getY(), pos.getZ());
            if(instead != null)
            {
                cir.setReturnValue(instead);
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void glass$hiddenFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir)
    {
        if(CloakHider.hidesAnything())
        {
            BlockState instead = CloakHider.state(pos.getX(), pos.getY(), pos.getZ());
            if(instead != null)
            {
                cir.setReturnValue(instead.getFluidState());
            }
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void glass$hiddenBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir)
    {
        if(CloakHider.hidesAnything() && CloakHider.state(pos.getX(), pos.getY(), pos.getZ()) != null)
        {
            cir.setReturnValue(null);
        }
    }

    /**
     * The region has no light of its own (it reads the level's light engine): where a cloak hides, its light instead.
     * Implementing the interface here lets the game's remapper name this method as the one it overrides.
     */
    @Override
    public int getBrightness(LightLayer layer, BlockPos pos)
    {
        if(CloakHider.hidesAnything())
        {
            int light = CloakHider.light(layer, pos.getX(), pos.getY(), pos.getZ());
            if(light >= 0)
            {
                return light;
            }
        }
        return getLightEngine().getLayerListener(layer).getLightValue(pos);
    }
}
