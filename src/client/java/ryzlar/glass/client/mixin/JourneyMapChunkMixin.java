package ryzlar.glass.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.CloakMapHider;

/**
 * JourneyMap maps the world on threads of its own ("JM-task-..."), reading each block from the client's chunks. Asked
 * from one of those, a block a cloak hides is the made-up ground instead (see {@link CloakMapHider}), so the cloaked
 * build does not show on its maps. JourneyMap itself is closed source: nothing of it is touched, only what Minecraft
 * answers it. Only with JourneyMap installed (see GlassMixinPlugin).
 */
@Mixin(LevelChunk.class)
public abstract class JourneyMapChunkMixin
{
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void glass$cloaked(BlockPos pos, CallbackInfoReturnable<BlockState> cir)
    {
        if(CloakMapHider.hidesAnything() && CloakMapHider.journeyMapThread())
        {
            LevelChunk chunk = (LevelChunk)(Object)this;
            BlockState instead = CloakMapHider.state(chunk.getPos().getMinBlockX() + (pos.getX() & 15), pos.getY(),
                    chunk.getPos().getMinBlockZ() + (pos.getZ() & 15));
            if(instead != null)
            {
                cir.setReturnValue(instead);
            }
        }
    }
}
