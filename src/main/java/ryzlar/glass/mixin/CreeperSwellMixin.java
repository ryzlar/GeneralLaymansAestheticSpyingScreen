package ryzlar.glass.mixin;

import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.decoy.NanoDecoyFigure;

/** A creeper going for a Nano Decoy reaches it and hisses at nothing: it does not swell up and blow up there. */
@Mixin(SwellGoal.class)
public abstract class CreeperSwellMixin
{
    @Shadow
    @Final
    private Creeper creeper;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void glass$notAtDecoys(CallbackInfoReturnable<Boolean> cir)
    {
        if(ryzlar.glass.decoy.NanoDecoyFeature.ENABLED && creeper.getTarget() instanceof NanoDecoyFigure)
        {
            cir.setReturnValue(false);
        }
    }
}
