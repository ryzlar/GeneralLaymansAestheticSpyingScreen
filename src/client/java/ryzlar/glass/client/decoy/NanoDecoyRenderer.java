package ryzlar.glass.client.decoy;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import ryzlar.glass.Glass;
import ryzlar.glass.decoy.NanoDecoy;
import ryzlar.glass.decoy.NanoDecoyBall;

/**
 * Draws a thrown Nano Decoy from its Blockbench models (blockbench/nano_decoy*.bbmodel): the ball tumbling through the
 * air; landed, its bottom half sinks into the ground and its top half rises, spins and goes in a flash, while a
 * hexagonal base grows out of the ground and a smaller glowing hexagon ring comes up over it, turning and bobbing (the
 * figure hovers above that ring). Its time up, the ring and the base fall apart into particles in their own shape.
 * <p>
 * Java models turn only in 22.5 degree steps, so the hexagons are put together here from one piece turned 60 degrees
 * at a time: three bars make the base's plate, six sides its glowing edge, six sides the ring.
 */
public class NanoDecoyRenderer extends EntityRenderer<NanoDecoyBall, NanoDecoyRenderer.State>
{
    private static final float PX = 1F / 16F;
    /** The models' middle (8, 8, 8) above the ground: the ball's foot is at 4, the base plate's underside at 7.5. */
    private static final float BALL_UP = 4F * PX, BASE_UP = 0.5F * PX;
    /** The ball's middle above the model's middle (it spins round its own middle). */
    private static final float BALL_MIDDLE = 0.5F * PX;
    /** Where the ring floats: the height of its middle (blocks). Public: the figure hovers above it. */
    public static final float RING_HEIGHT = 6F * PX;
    /** How long the landing shockwave runs (ticks) and how far it reaches (blocks). */
    private static final float WAVE_TICKS = 12F, WAVE_REACH = 1.4F;

    private static final ItemStack WHOLE = part("nano_decoy_ball");
    private static final ItemStack TOP = part("nano_decoy_top");
    private static final ItemStack BOTTOM = part("nano_decoy_bottom");
    private static final ItemStack PLATE = part("nano_decoy_plate");
    private static final ItemStack RIM = part("nano_decoy_rim");
    private static final ItemStack RING = part("nano_decoy_ring");

    public static class State extends EntityRenderState
    {
        float heading;
        float roll;
        boolean landed;
        /** Ticks since it landed (with the partial tick), and how far it has gone (0 to 1). */
        float sinceLanded;
        float gone;
        final ItemStackRenderState whole = new ItemStackRenderState();
        final ItemStackRenderState top = new ItemStackRenderState();
        final ItemStackRenderState bottom = new ItemStackRenderState();
        final ItemStackRenderState plate = new ItemStackRenderState();
        final ItemStackRenderState rim = new ItemStackRenderState();
        final ItemStackRenderState ring = new ItemStackRenderState();
    }

    private final ItemModelResolver itemModels;

    public NanoDecoyRenderer(EntityRendererProvider.Context context)
    {
        super(context);
        itemModels = context.getItemModelResolver();
    }

    private static ItemStack part(String model)
    {
        ItemStack stack = new ItemStack(NanoDecoy.NANO_DECOY);
        stack.set(DataComponents.ITEM_MODEL, Glass.id(model));
        return stack;
    }

    @Override
    public State createRenderState()
    {
        return new State();
    }

    @Override
    public void extractRenderState(NanoDecoyBall entity, State state, float partialTick)
    {
        super.extractRenderState(entity, state, partialTick);
        state.heading = entity.heading();
        state.roll = Mth.lerp(partialTick, entity.rollO, entity.roll);
        long landedAt = entity.landedAt();
        state.landed = landedAt >= 0L;
        if(state.landed)
        {
            float time = entity.level().getGameTime() + partialTick - landedAt;
            state.sinceLanded = time;
            float closing = time - NanoDecoyBall.OPEN_TICKS - NanoDecoyBall.STAND_TICKS;
            //Its time up, the hexagons are gone at once: they fall apart into particles (see NanoDecoyBall).
            state.gone = closing >= 0F ? 1F : 0F;
            itemModels.updateForNonLiving(state.top, TOP, ItemDisplayContext.NONE, entity);
            itemModels.updateForNonLiving(state.bottom, BOTTOM, ItemDisplayContext.NONE, entity);
            itemModels.updateForNonLiving(state.plate, PLATE, ItemDisplayContext.NONE, entity);
            itemModels.updateForNonLiving(state.rim, RIM, ItemDisplayContext.NONE, entity);
            itemModels.updateForNonLiving(state.ring, RING, ItemDisplayContext.NONE, entity);
        }
        else
        {
            itemModels.updateForNonLiving(state.whole, WHOLE, ItemDisplayContext.NONE, entity);
        }
    }

    @Override
    public void render(State state, PoseStack poseStack, MultiBufferSource buffers, int light)
    {
        poseStack.pushPose();
        //Its models face south (+z): turn them the way it was thrown.
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading));
        if(!state.landed)
        {
            //The ball, tumbling round its own middle.
            poseStack.translate(0F, BALL_UP + BALL_MIDDLE, 0F);
            poseStack.mulPose(Axis.XP.rotation(state.roll));
            poseStack.translate(0F, -BALL_MIDDLE, 0F);
            state.whole.render(poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
        }
        else if(state.gone < 1F)
        {
            drawOpening(state, poseStack, buffers, light);
        }
        poseStack.popPose();
    }

    private static void drawOpening(State state, PoseStack poseStack, MultiBufferSource buffers, int light)
    {
        float t = state.sinceLanded;
        float size = 1F - state.gone;
        //The ball parts: its bottom half sinks into the ground, its top half rises, spins and shrinks away.
        float split = ease(t / 8F);
        if(split < 1F)
        {
            poseStack.pushPose();
            poseStack.scale(1F - split, 1F - split, 1F - split);
            poseStack.translate(0F, BALL_UP, 0F);
            state.bottom.render(poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();

            poseStack.pushPose();
            poseStack.translate(0F, BALL_UP + BALL_MIDDLE + split * 5F * PX, 0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(split * 270F));
            float s = 1F - split;
            poseStack.scale(s, s, s);
            poseStack.translate(0F, -BALL_MIDDLE, 0F);
            state.top.render(poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        //The base grows out of the ground: three bars make its plate, six sides its glowing edge.
        float base = ease((t - 2F) / 10F) * size;
        if(base > 0F)
        {
            poseStack.pushPose();
            poseStack.scale(base, 1F, base);
            poseStack.translate(0F, BASE_UP * Math.min(1F, base * 2F), 0F);
            for(int k = 0; k < 3; k++)
            {
                poseStack.pushPose();
                //Each bar a hair higher than the last, so their tops do not flicker against each other.
                poseStack.translate(0F, k * 0.002F, 0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(k * 60F));
                state.plate.render(poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();
            }
            for(int k = 0; k < 6; k++)
            {
                poseStack.pushPose();
                poseStack.mulPose(Axis.YP.rotationDegrees(k * 60F));
                state.rim.render(poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();
            }
            poseStack.popPose();
        }
        //The ring comes up over it, turning and bobbing.
        float ring = ease((t - 5F) / 10F) * size;
        if(ring > 0F)
        {
            poseStack.pushPose();
            poseStack.translate(0F, RING_HEIGHT * ring + 0.4F * PX * Mth.sin(t * 0.15F), 0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(t * 2.5F));
            poseStack.scale(ring, ring, ring);
            for(int k = 0; k < 6; k++)
            {
                poseStack.pushPose();
                poseStack.mulPose(Axis.YP.rotationDegrees(k * 60F));
                state.ring.render(poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
                poseStack.popPose();
            }
            poseStack.popPose();
        }
        drawLight(state, poseStack.last(), buffers.getBuffer(RenderType.lightning()), split, base, ring);
    }

    /** Light: the landing shockwave, the flash as the ball parts, the base's glow and the ring's halo. */
    private static void drawLight(State state, PoseStack.Pose pose, VertexConsumer glow, float split, float base, float ringUp)
    {
        float t = state.sinceLanded;
        if(t < WAVE_TICKS)
        {
            float k = t / WAVE_TICKS;
            float r = 0.15F + WAVE_REACH * (1F - (1F - k) * (1F - k));
            ring(glow, pose, r - 0.12F, r, 0.02F, 0.7F * (1F - k));
        }
        //The flash where the ball was, as its halves go.
        float flash = Mth.sin(Math.clamp(split, 0F, 1F) * Mth.PI);
        if(flash > 0.01F)
        {
            ring(glow, pose, 0F, 0.35F * flash + 0.05F, BALL_UP + BALL_MIDDLE, 0.8F * flash);
        }
        float pulse = 0.75F + 0.25F * Mth.sin(t * 0.3F);
        if(base > 0F)
        {
            ring(glow, pose, 0F, 0.5F * base, 0.04F, 0.22F * base * pulse);
        }
        if(ringUp > 0F)
        {
            float y = RING_HEIGHT * ringUp + 0.4F * PX * Mth.sin(t * 0.15F);
            ring(glow, pose, 0.1F * ringUp, 0.3F * ringUp, y, 0.45F * ringUp * pulse);
        }
    }

    private static float ease(float t)
    {
        t = Math.clamp(t, 0F, 1F);
        return t * t * (3F - 2F * t);
    }

    /** A flat ring of light (inner and outer radius, height, brightness), bright inside, fading out. */
    private static void ring(VertexConsumer glow, PoseStack.Pose pose, float inner, float outer, float y, float bright)
    {
        int segments = 32;
        for(int i = 0; i < segments; i++)
        {
            float a0 = i / (float)segments * Mth.TWO_PI, a1 = (i + 1) / (float)segments * Mth.TWO_PI;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            light(glow, pose, c0 * inner, y, s0 * inner, bright);
            light(glow, pose, c1 * inner, y, s1 * inner, bright);
            light(glow, pose, c1 * outer, y, s1 * outer, 0F);
            light(glow, pose, c0 * outer, y, s0 * outer, 0F);
            light(glow, pose, c0 * outer, y, s0 * outer, 0F);
            light(glow, pose, c1 * outer, y, s1 * outer, 0F);
            light(glow, pose, c1 * inner, y, s1 * inner, bright);
            light(glow, pose, c0 * inner, y, s0 * inner, bright);
        }
    }

    private static void light(VertexConsumer glow, PoseStack.Pose pose, float x, float y, float z, float a)
    {
        glow.addVertex(pose, x, y, z).setColor(0.3F, 0.9F, 1F, Math.clamp(a, 0F, 1F));
    }
}
