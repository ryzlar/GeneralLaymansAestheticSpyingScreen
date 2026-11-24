package ryzlar.glass.client.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzlar.glass.client.render.CloakHider;

/** Entities in a cloak's hidden part are not drawn (see {@link CloakHider}): the villager in the cloaked house is gone too. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin
{
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void glass$hiddenEntity(E entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir)
    {
        if(CloakHider.hidesAnything() && CloakHider.hidesEntity(entity.getX(), entity.getY(), entity.getZ()))
        {
            cir.setReturnValue(false);
        }
    }
}
