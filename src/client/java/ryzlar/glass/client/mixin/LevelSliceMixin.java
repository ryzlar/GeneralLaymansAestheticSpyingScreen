package ryzlar.glass.client.mixin;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.CloakHider;

/**
 * Sodium's chunk builder (and Iris's, which uses it) sees a cloak's hidden part as the cloak makes it up (see
 * {@link CloakHider}): the blocks, no block entities, and the light. Only with a Sodium version this was made for (see
 * GlassMixinPlugin).
 * <p>
 * Sodium's own {@code (x, y, z)} methods are hooked: the {@code BlockPos} ones (and the fluid one) go through them, and
 * their names are the same in the development setup and in a player's game. {@code getBrightness} overrides Minecraft's,
 * so it is named by its intermediary name in a player's game ({@code method_8314}). Minecraft's classes are named the same
 * way there, so each method is given as it is named in both: one of them matches.
 */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin
{
    @Inject(method = { "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", "getBlockState(III)Lnet/minecraft/class_2680;" }, at = @At("HEAD"), cancellable = true)
    private void glass$hiddenBlock(int x, int y, int z, CallbackInfoReturnable<BlockState> cir)
    {
        if(CloakHider.hidesAnything())
        {
            BlockState instead = CloakHider.state(x, y, z);
            if(instead != null)
            {
                cir.setReturnValue(instead);
            }
        }
    }

    @Inject(method = { "getBlockEntity(III)Lnet/minecraft/world/level/block/entity/BlockEntity;", "getBlockEntity(III)Lnet/minecraft/class_2586;" }, at = @At("HEAD"), cancellable = true)
    private void glass$hiddenBlockEntity(int x, int y, int z, CallbackInfoReturnable<BlockEntity> cir)
    {
        if(CloakHider.hidesAnything() && CloakHider.state(x, y, z) != null)
        {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = { "getBrightness", "method_8314" }, at = @At("HEAD"), cancellable = true)
    private void glass$hiddenLight(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir)
    {
        if(CloakHider.hidesAnything())
        {
            int light = CloakHider.light(layer, pos.getX(), pos.getY(), pos.getZ());
            if(light >= 0)
            {
                cir.setReturnValue(light);
            }
        }
    }
}
