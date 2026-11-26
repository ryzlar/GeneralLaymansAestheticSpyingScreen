package ryzlar.glass.client.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.CloakHider;
import ryzlar.glass.client.render.CloakWall;

/** Entities in a cloak's hidden part are not drawn (see {@link CloakHider}): the villager in the cloaked house is gone too. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin
{
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void glass$hiddenEntity(E entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir)
    {
        if(!CloakHider.hidesAnything())
        {
            return;
        }
        //Crossing a cloak field's wall: drawn, and cut off at the wall afterwards (see CloakWall); wholly in it: hidden.
        int rule = CloakWall.entityRule(entity);
        if(rule == CloakWall.HIDE)
        {
            cir.setReturnValue(false);
        }
        else if(rule == CloakWall.DEFAULT && CloakHider.hidesEntity(entity.getX(), entity.getY(), entity.getZ()))
        {
            cir.setReturnValue(false);
        }
    }
}
