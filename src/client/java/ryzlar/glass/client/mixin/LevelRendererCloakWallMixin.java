package ryzlar.glass.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzlar.glass.client.render.CloakWall;

/**
 * Right after the world drawing's main pass has drawn its players, mobs and block entities (the second plain
 * {@code endBatch()} of its main pass, {@code method_62214}, which keeps that name in a player's game): someone walking
 * through a cloak field's wall is cut off at it, and the wall shows nano pixels round them (see {@link CloakWall}).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCloakWallMixin
{
    @Inject(method = "method_62214", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 1, shift = At.Shift.AFTER))
    private void glass$cloakWalls(CallbackInfo ci)
    {
        CloakWall.afterEntities();
    }
}
