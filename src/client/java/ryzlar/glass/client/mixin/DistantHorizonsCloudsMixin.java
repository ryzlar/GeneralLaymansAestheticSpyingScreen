package ryzlar.glass.client.mixin;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzlar.glass.client.render.DistantHorizonsClouds;

/**
 * Hands Distant Horizons' clouds (its cloud boxes, per layer and repeat) to {@link DistantHorizonsClouds}, which draws a
 * copy of them in GLASS's views. Only with Distant Horizons installed (see GlassMixinPlugin).
 */
@Mixin(value = CloudRenderHandler.class, remap = false)
public abstract class DistantHorizonsCloudsMixin
{
    @Shadow
    @Final
    private IDhApiRenderableBoxGroup[][][] boxGroupByOffset;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void glass$share(CallbackInfo ci)
    {
        DistantHorizonsClouds.setGroups(boxGroupByOffset);
    }
}
