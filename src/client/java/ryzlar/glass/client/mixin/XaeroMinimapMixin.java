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
 * Xaero's Minimap finds what to show for each spot by going down from the top block until it finds one to show. Where a
 * cloak hides something it gets the made-up ground instead (see {@link CloakMapHider}), so the cloaked build does not
 * show on the map. Only with Xaero's Minimap installed (see GlassMixinPlugin); a version without this method just
 * leaves it alone ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "xaero.common.minimap.write.MinimapWriter", remap = false)
public abstract class XaeroMinimapMixin
{
    @WrapOperation(method = "findBlock", require = 0,
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

    /**
     * The minimap redraws itself bit by bit, aiming to have gone round once in about a second (more for a bigger map),
     * and only then shows the new picture. While a cloak's picture changes it aims for its quickest round instead, so
     * the cloak's wave shows as it happens.
     */
    @WrapOperation(method = "onRender", require = 0,
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int glass$hurry(int a, int b, Operation<Integer> original)
    {
        //Its target time in milliseconds, never under 100 (its quickest).
        int time = original.call(a, b);
        return CloakMapHider.hurry() ? Math.min(time, 100) : time;
    }
}
