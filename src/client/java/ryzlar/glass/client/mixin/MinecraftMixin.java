package ryzlar.glass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.holo.SimulationView;
import ryzlar.glass.client.render.SpyViewRenderer;

/**
 * While a terminal view renders, everything that draws to "the screen" draws into that view's image instead. And
 * right-clicking a scanned chest in a holo cube simulation looks into it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin
{
    /**
     * Right-click, before Minecraft decides what it means. Fabric's use-block and use-item events missed the commonest
     * case: an empty hand aimed at a scanned chest with only air behind it in the real world fires neither.
     */
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void glass$useScannedContainer(CallbackInfo ci)
    {
        if(SimulationView.useAimed())
        {
            ci.cancel();
        }
    }

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void glass$spyViewTarget(CallbackInfoReturnable<RenderTarget> cir)
    {
        RenderTarget target = SpyViewRenderer.INSTANCE.targetOverride();
        if(target != null)
        {
            cir.setReturnValue(target);
        }
    }
}
