package ryzlar.glass.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.SpyViewRenderer;

/**
 * Distant Horizons turns Minecraft's fog off, as its far terrain carries on where the normal terrain ends. A GLASS view
 * draws no far terrain (see DistantHorizonsRenderMixin), so it keeps the fog: the view ends softly, as it does without
 * Distant Horizons.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon", remap = false)
public abstract class DistantHorizonsFogMixin
{
    @Inject(method = "cancelFog", at = @At("HEAD"), cancellable = true, require = 0)
    private static void glass$fogInViews(CallbackInfoReturnable<Boolean> cir)
    {
        if(SpyViewRenderer.INSTANCE.isRendering())
        {
            cir.setReturnValue(false);
        }
    }
}
