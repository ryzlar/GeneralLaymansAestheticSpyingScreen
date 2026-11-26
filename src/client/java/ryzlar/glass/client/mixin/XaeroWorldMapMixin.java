package ryzlar.glass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import ryzlar.glass.client.render.CloakMapHider;

/**
 * Xaero's World Map finds what to show for each spot by going down from the top block until it finds one to show, like
 * the minimap (see XaeroMinimapMixin). Where a cloak hides something it gets the made-up ground instead (see
 * {@link CloakMapHider}), so the cloaked build does not show on the map (and is not saved into it). Only with Xaero's
 * World Map installed (see GlassMixinPlugin); a version without this method just leaves it alone ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "xaero.map.MapWriter", remap = false)
public abstract class XaeroWorldMapMixin
{
    @WrapOperation(method = "loadPixel", require = 0,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState glass$cloaked(LevelChunk chunk, BlockPos pos, Operation<BlockState> original)
    {
        if(CloakMapHider.hidesAnything())
        {
            //It asks the chunk with the block's place within the chunk, not in the world.
            BlockState instead = CloakMapHider.state(chunk.getPos().getMinBlockX() + (pos.getX() & 15), pos.getY(),
                    chunk.getPos().getMinBlockZ() + (pos.getZ() & 15));
            if(instead != null)
            {
                return instead;
            }
        }
        return original.call(chunk, pos);
    }
}
