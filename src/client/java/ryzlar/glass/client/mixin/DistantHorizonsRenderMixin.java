package ryzlar.glass.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzlar.glass.client.render.SpyViewRenderer;

/**
 * Distant Horizons draws its far terrain (and blends it into the near terrain) inside the world drawing, so it would
 * also do so in every view GLASS draws: a camera screen, a cloak's walls rising. That is its whole far-terrain pass again
 * per view, with its textures sized and lined up for the player's window, so it costs frames and leaves scrambled
 * colours in the view (and over its clouds). Views only need the normal render distance, so these passes are skipped
 * while a view draws. Only with Distant Horizons installed (see GlassMixinPlugin); a version without these methods just
 * leaves them alone ({@code require = 0}).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DistantHorizonsRenderMixin
{
    @Inject(method = { "renderLods", "renderDeferredLodsForShaders", "renderFadeOpaque", "renderFadeTransparent" },
            at = @At("HEAD"), cancellable = true, require = 0)
    private void glass$notInViews(CallbackInfo ci)
    {
        if(SpyViewRenderer.INSTANCE.isRendering())
        {
            ci.cancel();
        }
    }
}
