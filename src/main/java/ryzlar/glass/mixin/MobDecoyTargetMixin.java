package ryzlar.glass.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ryzlar.glass.decoy.NanoDecoyFigure;

/**
 * A mob going for a Nano Decoy that still lures keeps going for it: its own target search (the nearest player) does
 * not pull it away. Only someone who hits it can, and the decoy letting it go.
 */
@Mixin(Mob.class)
public abstract class MobDecoyTargetMixin
{
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void glass$stayOnDecoy(LivingEntity target, CallbackInfo ci)
    {
        if(!ryzlar.glass.decoy.NanoDecoyFeature.ENABLED)
        {
            return;
        }
        Mob self = (Mob)(Object)this;
        if(self.getTarget() instanceof NanoDecoyFigure decoy && decoy.isAlive() && decoy.luring() && target != decoy
                && target != null && target != self.getLastHurtByMob())
        {
            ci.cancel();
        }
    }
}
