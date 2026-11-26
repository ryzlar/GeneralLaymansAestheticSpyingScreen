package ryzlar.glass.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.CloakMapHider;

/**
 * JourneyMap finds the top of each column with the chunk's heightmap. Where a cloak hides something, the top is that of
 * the made-up ground instead (its build's top would show as a step in the map's shading). Asked from JourneyMap's own
 * threads only, with JourneyMap installed (see {@link JourneyMapChunkMixin}).
 */
@Mixin(ChunkAccess.class)
public abstract class JourneyMapHeightMixin
{
    @Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("RETURN"), cancellable = true)
    private void glass$cloaked(Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> cir)
    {
        if(!CloakMapHider.hidesAnything() || !CloakMapHider.journeyMapThread())
        {
            return;
        }
        ChunkAccess chunk = (ChunkAccess)(Object)this;
        int bx = chunk.getPos().getMinBlockX() + (x & 15), bz = chunk.getPos().getMinBlockZ() + (z & 15);
        if(!CloakMapHider.covers(bx, bz))
        {
            return;
        }
        //Down from the real top to the first block the map is to see (the chunk answers this thread as the map sees it).
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int y = cir.getReturnValueI(); y >= chunk.getMinY(); y--)
        {
            if(!chunk.getBlockState(pos.set(bx, y, bz)).isAir())
            {
                cir.setReturnValue(y);
                return;
            }
        }
    }
}
