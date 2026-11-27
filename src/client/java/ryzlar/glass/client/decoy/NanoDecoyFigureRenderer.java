package ryzlar.glass.client.decoy;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import ryzlar.glass.decoy.NanoDecoyFigure;

/**
 * Draws a Nano Decoy's figure: its owner's skin made of nano cubes, each two skin pixels across with a little gap round
 * it and a cyan glow, hovering above the decoy's floating ring, projected out of it in a cone of light.
 * <ul>
 * <li>Building up, the cubes fly out of the lens in a spiral to their places, feet first, and flash as they land.</li>
 * <li>Standing, a scan line runs up it lighting the cubes it passes, a few cubes drift off it and fade, and now and
 * then a band of it drops out for a moment, like a hologram.</li>
 * <li>Hit, it splits into a cyan and a magenta ghost, slices of it jump sideways and a few cubes fall off.</li>
 * <li>Going, the cubes fly back into the lens from the head down, and the lens flashes.</li>
 * </ul>
 * The light (beam, ring, glow, ghosts) is drawn adding light, so it glows.
 */
public class NanoDecoyFigureRenderer extends EntityRenderer<NanoDecoyFigure, NanoDecoyFigureRenderer.State>
{
    /** Pixels of the model to blocks (a player is 1.8 blocks tall, 32 skin pixels). */
    private static final float PX = 1.8F / 32F;
    /** How high it hovers (blocks above the ground), and how far it bobs. */
    private static final float HOVER = 0.55F, BOB = 0.035F;
    /** The decoy's floating ring, where the projection comes from (model pixels, from the hovering feet). */
    private static final float LENS = (NanoDecoyRenderer.RING_HEIGHT - HOVER) / PX;
    /** How long a cube flies (ticks), how long a hit glitches and its dropped cubes fall, and the scan line's round. */
    private static final float FLY = 9F, GLITCH = 7F, DROP = 16F, SCAN = 34F;
    /** A cube's size within its cell (the rest is the gap), and how far its glow reaches (pixels). */
    private static final float CUBE = 0.86F, GLOW = 0.22F;
    private static final int DROPPED = 6, MOTES = 14;

    /** One cube: its body part, its box (model pixels, facing +z, feet at 0) and its faces with their skin pixels. */
    private record Voxel(int part, float[] box, List<Face> faces, float height, float seed) {}

    /** A face of a cube: which side (0 +z, 1 -z, 2 -x, 3 +x, 4 +y, 5 -y) and its skin pixels (u0, v0, u1, v1). */
    private record Face(int side, float u0, float v0, float u1, float v1) {}

    /** The body parts: box (x0, y0, z0, size x, y, z), skin texture corner, overlay corner, overlay growth, and pivot. */
    private record Part(float[] box, int u, int v, int ou, int ov, float grow, float[] pivot) {}

    private static final List<Voxel> WIDE = build(false);
    private static final List<Voxel> SLIM = build(true);

    public static class State extends EntityRenderState
    {
        ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin().texture();
        boolean slim;
        float age;
        float yaw;
        float sinceHit = 1000F;
        int seed;
    }

    public NanoDecoyFigureRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public State createRenderState()
    {
        return new State();
    }

    @Override
    public void extractRenderState(NanoDecoyFigure entity, State state, float partialTick)
    {
        super.extractRenderState(entity, state, partialTick);
        PlayerSkin skin = skinOf(entity.ownerId());
        state.skin = skin.texture();
        state.slim = skin.model() == PlayerSkin.Model.SLIM;
        state.age = entity.age(partialTick);
        state.yaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        state.sinceHit = entity.level().getGameTime() - entity.clientHit + partialTick;
        state.seed = entity.getId();
    }

    private static PlayerSkin skinOf(UUID owner)
    {
        if(owner == null)
        {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        PlayerInfo info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(owner);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(owner);
    }

    @Override
    public void render(State state, PoseStack poseStack, MultiBufferSource buffers, int light)
    {
        List<Voxel> voxels = state.slim ? SLIM : WIDE;
        Part[] parts = state.slim ? SLIM_PARTS : WIDE_PARTS;
        float age = state.age;
        float collapse = age - NanoDecoyFigure.BUILD_TICKS - NanoDecoyFigure.LIFE_TICKS;
        boolean glitching = state.sinceHit < GLITCH;
        int tick = (int)age;
        //The projection: on as it builds, off as it goes.
        float on = Math.clamp(age / 6F, 0F, 1F) * Math.clamp(1F - (collapse - NanoDecoyFigure.COLLAPSE_TICKS + 8F) / 8F, 0F, 1F);
        //Now and then a band of it drops out for a moment.
        int beat = tick / 3;
        boolean dropout = !glitching && hash(beat * 31L + state.seed) < 0.06F && collapse < 0F;
        float dropLow = hash(beat * 17L + 5L) * 28F, dropHigh = dropLow + 3F + hash(beat * 7L + 1L) * 4F;
        float scan = fract(age / SCAN) * 40F - 4F;
        float breathe = Mth.sin(age * 0.067F) * 0.05F + 0.05F;

        //It hovers above the decoy's ring, bobbing a little (its name with it).
        float hover = HOVER + BOB * Mth.sin(age * 0.12F);
        poseStack.pushPose();
        poseStack.translate(0F, hover, 0F);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        poseStack.scale(PX, PX, PX);
        PoseStack.Pose root = poseStack.last();
        //Two passes, one buffer each (a buffer is closed as soon as another is asked for): the skin cubes, then the light.
        for(int pass = 0; pass < 2; pass++)
        {
            boolean light0 = pass == 1;
            VertexConsumer skin = light0 ? null : buffers.getBuffer(RenderType.entityTranslucent(state.skin));
            VertexConsumer glow = light0 ? buffers.getBuffer(RenderType.lightning()) : null;
            if(light0)
            {
                drawBeam(glow, root, age, on, scan);
            }
            for(int i = 0; i < voxels.size(); i++)
            {
                Voxel voxel = voxels.get(i);
                float seed = fract(voxel.seed + state.seed * 0.618F);
                float[] c = centre(voxel);
                //Building up: out of the lens in a spiral, feet first. Going: back into it, head first.
                float flyIn = (age - (voxel.height * 0.85F + seed * 0.15F) * (NanoDecoyFigure.BUILD_TICKS - FLY)) / FLY;
                float flyOut = (collapse - ((1F - voxel.height) * 0.85F + seed * 0.15F) * (NanoDecoyFigure.COLLAPSE_TICKS - FLY)) / FLY;
                if(flyIn <= 0F || flyOut >= 1F)
                {
                    continue;
                }
                if(flyIn < 1F || flyOut > 0F)
                {
                    if(light0)
                    {
                        float t = flyIn < 1F ? ease(flyIn) : 1F - ease(flyOut);
                        float[] p = spiral(c, t, seed);
                        float half = (voxel.box[3] - voxel.box[0]) / 2F * (0.35F + 0.65F * t);
                        glowBox(glow, root, p[0], p[1], p[2], half, half, half, 0.18F, 0.75F, 1F, 0.11F);
                    }
                    continue;
                }
                //In place: the skin cube, its glow, brighter where the scan line passes and just after it landed.
                if(dropout && c[1] > dropLow && c[1] < dropHigh)
                {
                    continue;
                }
                float landed = age - (voxel.height * 0.85F + seed * 0.15F) * (NanoDecoyFigure.BUILD_TICKS - FLY) - FLY;
                float flash = Math.clamp(1F - landed / 6F, 0F, 1F);
                float scanned = Math.clamp(1F - Math.abs(c[1] - scan) / 2.5F, 0F, 1F);
                float dx = 0F;
                if(glitching && hash((long)(c[1] / 3F) * 13L + tick * 7L + state.seed) < 0.4F)
                {
                    dx = (hash((long)(c[1] / 3F) * 5L + tick * 3L) - 0.5F) * 4F;
                }
                poseStack.pushPose();
                limb(poseStack, voxel.part, parts, breathe);
                PoseStack.Pose pose = poseStack.last();
                float hx = (voxel.box[3] - voxel.box[0]) / 2F, hy = (voxel.box[4] - voxel.box[1]) / 2F, hz = (voxel.box[5] - voxel.box[2]) / 2F;
                if(!light0)
                {
                    float tint = Math.max(flash, scanned * 0.6F);
                    skinBox(skin, pose, voxel, c[0] + dx, c[1], c[2], CUBE, Mth.lerp(tint, 0.66F, 0.45F), Mth.lerp(tint, 0.97F, 1F), 1F,
                            0.82F + 0.08F * Mth.sin(age * 0.9F + c[1] * 0.4F));
                }
                else
                {
                    float a = 0.07F + 0.3F * scanned + 0.25F * flash;
                    glowBox(glow, pose, c[0] + dx, c[1], c[2], hx * CUBE + GLOW, hy * CUBE + GLOW, hz * CUBE + GLOW, 0.2F * a, 0.8F * a, a, 1F);
                    if(glitching)
                    {
                        //The ghosts: a cyan one and a magenta one to either side.
                        float split = 1.6F * (1F - state.sinceHit / GLITCH);
                        glowBox(glow, pose, c[0] + dx + split, c[1], c[2], hx, hy, hz, 0.05F, 0.35F, 0.4F, 1F);
                        glowBox(glow, pose, c[0] + dx - split, c[1], c[2], hx, hy, hz, 0.4F, 0.05F, 0.3F, 1F);
                    }
                }
                poseStack.popPose();
            }
        }

        VertexConsumer glow = buffers.getBuffer(RenderType.lightning());
        drawMotes(glow, root, voxels, state, age, on, collapse);
        //A hit knocks a few cubes off: they fall and fade (the figure keeps its own).
        if(state.sinceHit < DROP)
        {
            float t = state.sinceHit;
            for(int k = 0; k < DROPPED; k++)
            {
                Voxel voxel = voxels.get(Math.floorMod((int)(state.seed * 31L + k * 977L + (long)(age - t) * 13L), voxels.size()));
                float[] c = centre(voxel);
                float fade = 1F - t / DROP;
                float side = (fract(voxel.seed * 5.3F + k) - 0.5F) * t * 0.9F;
                float half = (voxel.box[3] - voxel.box[0]) / 2F * fade;
                glowBox(glow, root, c[0] + side, c[1] - 0.11F * t * t, c[2] + side * 0.5F, half, half, half, 0.3F * fade, 0.9F * fade, fade, 1F);
            }
        }
        //Going, the lens flashes as the last cubes fly in.
        float last = collapse - NanoDecoyFigure.COLLAPSE_TICKS + 6F;
        if(last > 0F && last < 6F)
        {
            float f = Mth.sin(last / 6F * Mth.PI);
            glowBox(glow, root, 0F, LENS, 0F, 2.5F * f + 0.5F, 2.5F * f + 0.5F, 2.5F * f + 0.5F, 0.6F * f, f, f, 1F);
        }
        poseStack.popPose();
        super.render(state, poseStack, buffers, light);
        poseStack.popPose();
    }

    /** The cone of light from the lens to the figure, the glowing ring round its feet, and the scan line round it. */
    private static void drawBeam(VertexConsumer glow, PoseStack.Pose pose, float age, float on, float scan)
    {
        if(on <= 0F)
        {
            return;
        }
        float pulse = 0.85F + 0.15F * Mth.sin(age * 0.35F);
        float a = 0.22F * on * pulse;
        //The cone: from the lens out to a box round the figure, fading upwards.
        float wx = 6.5F, wz = 4F, top = 33F;
        float[][] rim = { { -wx, -wz }, { wx, -wz }, { wx, wz }, { -wx, wz } };
        for(int i = 0; i < 4; i++)
        {
            float[] p = rim[i], q = rim[(i + 1) % 4];
            //Lower half bright, upper half fading out, each side seen from both ways.
            float midY = (LENS + top) / 2F;
            float px = p[0] * 0.5F, pz = p[1] * 0.5F, qx = q[0] * 0.5F, qz = q[1] * 0.5F;
            twoSided(glow, pose, 0F, LENS, 0F, a, 0F, LENS, 0F, a, qx, midY, qz, a * 0.6F, px, midY, pz, a * 0.6F);
            twoSided(glow, pose, px, midY, pz, a * 0.6F, qx, midY, qz, a * 0.6F, q[0], top, q[1], 0F, p[0], top, p[1], 0F);
        }
        //The scan line: a thin bright band running up round the figure.
        if(scan > 0F && scan < 33F)
        {
            float s = 0.5F * on;
            float x = 5.5F, z = 3.2F, h = 0.45F;
            float[][] box = { { -x, -z }, { x, -z }, { x, z }, { -x, z } };
            for(int i = 0; i < 4; i++)
            {
                float[] p = box[i], q = box[(i + 1) % 4];
                twoSided(glow, pose, p[0], scan - h, p[1], 0F, q[0], scan - h, q[1], 0F, q[0], scan, q[1], s, p[0], scan, p[1], s);
                twoSided(glow, pose, p[0], scan, p[1], s, q[0], scan, q[1], s, q[0], scan + h, q[1], 0F, p[0], scan + h, p[1], 0F);
            }
        }
    }

    /** Cubes drifting up off the figure and fading, each from its own cube, on and off. */
    private static void drawMotes(VertexConsumer glow, PoseStack.Pose pose, List<Voxel> voxels, State state, float age, float on, float collapse)
    {
        if(on <= 0F || age < NanoDecoyFigure.BUILD_TICKS || collapse > 0F)
        {
            return;
        }
        for(int k = 0; k < MOTES; k++)
        {
            float round = 26F + k % 5 * 4F;
            float t = fract((age + k * 9.7F) / round);
            int cycle = (int)((age + k * 9.7F) / round);
            Voxel voxel = voxels.get(Math.floorMod((int)(hash(cycle * 101L + k * 7L + state.seed) * voxels.size()), voxels.size()));
            float[] c = centre(voxel);
            float drift = t * 7F;
            float out = (hash(cycle * 3L + k) - 0.5F) * 2F * t * 3F;
            float a = Mth.sin(t * Mth.PI) * 0.8F * on;
            float half = 0.45F * (1F - t * 0.6F);
            glowBox(glow, pose, c[0] + out, c[1] + drift, c[2] + out * 0.5F, half, half, half, 0.3F * a, 0.9F * a, a, 1F);
        }
    }

    /** Arms sway a little, breathing (round the shoulders); the rest stands still. */
    private static void limb(PoseStack poseStack, int part, Part[] parts, float breathe)
    {
        if(part != 2 && part != 3)
        {
            return;
        }
        float[] pivot = parts[part].pivot;
        poseStack.translate(pivot[0], pivot[1], pivot[2]);
        poseStack.mulPose(Axis.ZP.rotation(part == 2 ? -breathe : breathe));
        poseStack.translate(-pivot[0], -pivot[1], -pivot[2]);
    }

    /** Where a flying cube is: on a spiral from the lens (t 0) to its place (t 1). */
    private static float[] spiral(float[] to, float t, float seed)
    {
        float turn = (seed * 2F + (1F - t) * 2.2F) * Mth.PI;
        float r = (1F - t) * (3F + seed * 4F) * Mth.sin(t * Mth.PI) + (1F - t) * 2F;
        return new float[] {
                Mth.lerp(t, 0F, to[0]) + Mth.cos(turn) * r,
                Mth.lerp(t, LENS, to[1]),
                Mth.lerp(t, 0F, to[2]) + Mth.sin(turn) * r };
    }

    private static float[] centre(Voxel voxel)
    {
        float[] b = voxel.box;
        return new float[] { (b[0] + b[3]) / 2F, (b[1] + b[4]) / 2F, (b[2] + b[5]) / 2F };
    }

    private static float ease(float t)
    {
        t = Math.clamp(t, 0F, 1F);
        return 1F - (1F - t) * (1F - t) * (1F - t);
    }

    /** A skin cube round (x, y, z), scaled within its cell, its faces showing its own piece of the skin. */
    private static void skinBox(VertexConsumer out, PoseStack.Pose pose, Voxel voxel, float x, float y, float z, float size,
                                float r, float g, float b, float a)
    {
        float[] box = voxel.box;
        float hx = (box[3] - box[0]) / 2F * size, hy = (box[4] - box[1]) / 2F * size, hz = (box[5] - box[2]) / 2F * size;
        float x0 = x - hx, x1 = x + hx, y0 = y - hy, y1 = y + hy, z0 = z - hz, z1 = z + hz;
        for(Face f : voxel.faces)
        {
            float u0 = f.u0 / 64F, v0 = f.v0 / 64F, u1 = f.u1 / 64F, v1 = f.v1 / 64F;
            switch(f.side)
            {
                case 0 -> skinQuad(out, pose, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u0, v1, u1, v0, r, g, b, a);
                case 1 -> skinQuad(out, pose, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, u0, v1, u1, v0, r, g, b, a);
                case 2 -> skinQuad(out, pose, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, v1, u1, v0, r, g, b, a);
                case 3 -> skinQuad(out, pose, 1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, u0, v1, u1, v0, r, g, b, a);
                case 4 -> skinQuad(out, pose, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, u0, v1, u1, v0, r, g, b, a);
                default -> skinQuad(out, pose, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, u0, v1, u1, v0, r, g, b, a);
            }
        }
    }

    /** A quad from its corners in order bottom-left, bottom-right, top-right, top-left as seen from outside. */
    private static void skinQuad(VertexConsumer out, PoseStack.Pose pose, float nx, float ny, float nz,
                                 float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                                 float dx, float dy, float dz, float uL, float vB, float uR, float vT, float r, float g, float b, float a)
    {
        skinVertex(out, pose, ax, ay, az, uL, vB, nx, ny, nz, r, g, b, a);
        skinVertex(out, pose, bx, by, bz, uR, vB, nx, ny, nz, r, g, b, a);
        skinVertex(out, pose, cx, cy, cz, uR, vT, nx, ny, nz, r, g, b, a);
        skinVertex(out, pose, dx, dy, dz, uL, vT, nx, ny, nz, r, g, b, a);
    }

    private static void skinVertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                                   float nx, float ny, float nz, float r, float g, float b, float a)
    {
        out.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
    }

    /** A box of light (added to what is behind it), its six sides each seen from both ways. */
    private static void glowBox(VertexConsumer glow, PoseStack.Pose pose, float x, float y, float z, float hx, float hy, float hz,
                                float r, float g, float b, float a)
    {
        float x0 = x - hx, x1 = x + hx, y0 = y - hy, y1 = y + hy, z0 = z - hz, z1 = z + hz;
        float cr = r * a, cg = g * a, cb = b * a;
        lightQuad(glow, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, cr, cg, cb);
        lightQuad(glow, pose, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, cr, cg, cb);
        lightQuad(glow, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, cr, cg, cb);
        lightQuad(glow, pose, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, cr, cg, cb);
        lightQuad(glow, pose, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, cr, cg, cb);
        lightQuad(glow, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, cr, cg, cb);
    }

    private static void lightQuad(VertexConsumer glow, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz,
                                  float cx, float cy, float cz, float dx, float dy, float dz, float r, float g, float b)
    {
        twoSided(glow, pose, ax, ay, az, 1F, bx, by, bz, 1F, cx, cy, cz, 1F, dx, dy, dz, 1F, r, g, b);
    }

    /** A cyan quad of light with an alpha per corner (how bright), seen from both ways. */
    private static void twoSided(VertexConsumer glow, PoseStack.Pose pose, float ax, float ay, float az, float aa,
                                 float bx, float by, float bz, float ba, float cx, float cy, float cz, float ca,
                                 float dx, float dy, float dz, float da)
    {
        twoSided(glow, pose, ax, ay, az, aa, bx, by, bz, ba, cx, cy, cz, ca, dx, dy, dz, da, 0.3F, 0.9F, 1F);
    }

    private static void twoSided(VertexConsumer glow, PoseStack.Pose pose, float ax, float ay, float az, float aa,
                                 float bx, float by, float bz, float ba, float cx, float cy, float cz, float ca,
                                 float dx, float dy, float dz, float da, float r, float g, float b)
    {
        light(glow, pose, ax, ay, az, r, g, b, aa);
        light(glow, pose, bx, by, bz, r, g, b, ba);
        light(glow, pose, cx, cy, cz, r, g, b, ca);
        light(glow, pose, dx, dy, dz, r, g, b, da);
        light(glow, pose, dx, dy, dz, r, g, b, da);
        light(glow, pose, cx, cy, cz, r, g, b, ca);
        light(glow, pose, bx, by, bz, r, g, b, ba);
        light(glow, pose, ax, ay, az, r, g, b, aa);
    }

    /** Additive light: the colour times the alpha is what it adds. */
    private static void light(VertexConsumer glow, PoseStack.Pose pose, float x, float y, float z, float r, float g, float b, float a)
    {
        glow.addVertex(pose, x, y, z).setColor(r, g, b, Math.clamp(a, 0F, 1F));
    }

    private static float fract(float x)
    {
        return x - Mth.floor(x);
    }

    private static float hash(long n)
    {
        n = (n ^ 0x5DEECE66DL) * 0x9E3779B97F4A7C15L;
        n ^= n >>> 29;
        n *= 0xBF58476D1CE4E5B9L;
        n ^= n >>> 32;
        return (n & 0xFFFFFF) / (float)0x1000000;
    }

    // ---- The cubes, made once from the player model's parts and its skin layout ----

    private static final Part[] WIDE_PARTS = parts(false);
    private static final Part[] SLIM_PARTS = parts(true);

    /** Head, body, right arm, left arm, right leg, left leg (the right side is -x: the figure faces +z). */
    private static Part[] parts(boolean slim)
    {
        float arm = slim ? 3F : 4F;
        return new Part[] {
                new Part(new float[] { -4, 24, -4, 8, 8, 8 }, 0, 0, 32, 0, 0.5F, new float[] { 0, 24, 0 }),
                new Part(new float[] { -4, 12, -2, 8, 12, 4 }, 16, 16, 16, 32, 0.25F, new float[] { 0, 24, 0 }),
                new Part(new float[] { -4 - arm, 12, -2, arm, 12, 4 }, 40, 16, 40, 32, 0.25F, new float[] { -4, 23, 0 }),
                new Part(new float[] { 4, 12, -2, arm, 12, 4 }, 32, 48, 48, 48, 0.25F, new float[] { 4, 23, 0 }),
                new Part(new float[] { -4, 0, -2, 4, 12, 4 }, 0, 16, 0, 32, 0.25F, new float[] { -2, 12, 0 }),
                new Part(new float[] { 0, 0, -2, 4, 12, 4 }, 16, 48, 0, 48, 0.25F, new float[] { 2, 12, 0 }),
        };
    }

    private static List<Voxel> build(boolean slim)
    {
        List<Voxel> voxels = new ArrayList<>();
        Part[] parts = parts(slim);
        for(int p = 0; p < parts.length; p++)
        {
            Part part = parts[p];
            cubes(voxels, p, part, part.u, part.v, 0F);
            cubes(voxels, p, part, part.ou, part.ov, part.grow);
        }
        return voxels;
    }

    /** Cuts a part into cubes two skin pixels across, keeping the ones on its outside and the faces that show. */
    private static void cubes(List<Voxel> voxels, int partIndex, Part part, int u, int v, float grow)
    {
        float[] b = part.box;
        float w = b[3], h = b[4], d = b[5];
        float[] xs = cuts(w), ys = cuts(h), zs = cuts(d);
        for(int ix = 0; ix < xs.length - 1; ix++)
        {
            for(int iy = 0; iy < ys.length - 1; iy++)
            {
                for(int iz = 0; iz < zs.length - 1; iz++)
                {
                    float lx0 = xs[ix], lx1 = xs[ix + 1], ly0 = ys[iy], ly1 = ys[iy + 1], lz0 = zs[iz], lz1 = zs[iz + 1];
                    List<Face> faces = new ArrayList<>();
                    //Skin layout of a box (corner u, v; sizes w, h, d): top and bottom above, then right, front,
                    //left and back side by side; the front shows -x on the left, the top shows the front at its bottom.
                    if(lz1 == d)
                    {
                        faces.add(new Face(0, u + d + lx0, v + d + (h - ly1), u + d + lx1, v + d + (h - ly0)));
                    }
                    if(lz0 == 0)
                    {
                        faces.add(new Face(1, u + d + w + d + (w - lx1), v + d + (h - ly1), u + d + w + d + (w - lx0), v + d + (h - ly0)));
                    }
                    if(lx0 == 0)
                    {
                        faces.add(new Face(2, u + lz0, v + d + (h - ly1), u + lz1, v + d + (h - ly0)));
                    }
                    if(lx1 == w)
                    {
                        faces.add(new Face(3, u + d + w + (d - lz1), v + d + (h - ly1), u + d + w + (d - lz0), v + d + (h - ly0)));
                    }
                    if(ly1 == h)
                    {
                        faces.add(new Face(4, u + d + lx0, v + lz0, u + d + lx1, v + lz1));
                    }
                    if(ly0 == 0)
                    {
                        faces.add(new Face(5, u + d + w + lx0, v + (d - lz1), u + d + w + lx1, v + (d - lz0)));
                    }
                    if(faces.isEmpty())
                    {
                        continue;
                    }
                    float gx = (w + 2 * grow) / w, gy = (h + 2 * grow) / h, gz = (d + 2 * grow) / d;
                    float[] box = {
                            b[0] - grow + lx0 * gx, b[1] - grow + ly0 * gy, b[2] - grow + lz0 * gz,
                            b[0] - grow + lx1 * gx, b[1] - grow + ly1 * gy, b[2] - grow + lz1 * gz };
                    float height = ((box[1] + box[4]) / 2F) / 32F;
                    float seed = fract((box[0] * 12.9898F + box[1] * 78.233F + box[2] * 37.719F + grow * 11F) * 0.1371F);
                    voxels.add(new Voxel(partIndex, box, faces, height, seed));
                }
            }
        }
    }

    /** Where to cut a length into pieces of two (the last one shorter for an odd length). */
    private static float[] cuts(float length)
    {
        int n = (int)Math.ceil(length / 2F);
        float[] out = new float[n + 1];
        for(int i = 0; i <= n; i++)
        {
            out[i] = Math.min(i * 2F, length);
        }
        return out;
    }
}
