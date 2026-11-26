package ryzlar.glass.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import ryzlar.glass.Glass;
import ryzlar.glass.blockentity.GlassProjectorBlockEntity;
import ryzlar.glass.client.GlassClientState;

/**
 * Walking through a cloak field's wall (without a shader pack).
 * <p>
 * Seen from outside, someone walking in is cut off at the wall: the part of them already in the field is gone, the rest
 * is there (they used to vanish whole once their feet were in). Players and mobs are drawn as always, then the picture
 * from just before they were drawn is put back wherever what is seen lies inside the field's hidden box
 * ({@link GlassRenderTypes#WALL_CUT_PIPELINE}, which works that out from the depth). Only while someone is crossing.
 * <p>
 * Wherever someone crosses a field's wall, the wall shows a flickering patch of nano pixels around them, seen from inside
 * and outside, in first and third person, fading once they are through.
 */
public final class CloakWall
{
    /** Entities: shown as always, hidden (whole, in a field's hidden box), or crossing a field's wall (cut off at it). */
    public static final int DEFAULT = 0, HIDE = 1, CROSSING = 2;
    /** How far the nano patch reaches out from the body, in blocks; nano pixels per block; ticks it fades over. */
    private static final double PATCH_MARGIN = 0.55D;
    private static final int CELLS = 8;
    private static final float FADE_TICKS = 8F;
    private static final ResourceLocation COLOUR_ID = Glass.id("wall_cut/colour");
    private static final ResourceLocation DEPTH_ID = Glass.id("wall_cut/depth");

    private static TextureTarget saved;
    private static final WallTexture COLOUR = new WallTexture();
    private static final WallTexture DEPTH = new WallTexture();
    private static final Matrix4f SCREEN_TO_WORLD = new Matrix4f();
    private static RenderType cutType;

    /** This frame: someone crosses the wall of a field that hides its build from the eye (the picture was saved). */
    private static boolean cutting;
    private static boolean saving;
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();

    /** A nano patch on one face of a field: where the body meets it (u, v on the face), last seen when. */
    private static final class Patch
    {
        AABB field;
        Direction face;
        double u0, u1, v0, v1;
        float lastTime;
        int seed;
    }

    private static final Map<Long, Patch> PATCHES = new HashMap<>();

    private CloakWall() {}

    public static void init()
    {
        WorldRenderEvents.START.register(context -> {
            if(!SpyViewRenderer.INSTANCE.isRendering())
            {
                cutting = false;
            }
        });
        WorldRenderEvents.BEFORE_ENTITIES.register(CloakWall::beforeEntities);
    }

    /** How an entity is drawn (asked while the game picks what to draw). */
    public static int entityRule(Entity entity)
    {
        if(SpyViewRenderer.INSTANCE.isRendering() || !ViewBackend.current().drawsViews())
        {
            return DEFAULT;
        }
        AABB body = entity.getBoundingBox();
        for(AABB wall : CloakHider.fieldWalls())
        {
            if(contains(wall, body))
            {
                return HIDE;
            }
            if(wall.intersects(body))
            {
                cutting = true;
                return CROSSING;
            }
        }
        return DEFAULT;
    }

    private static boolean contains(AABB outer, AABB inner)
    {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }

    /** Saves the picture before players and mobs are drawn, if someone is cut off at a wall this frame. */
    private static void beforeEntities(WorldRenderContext context)
    {
        saving = false;
        if(!cutting || SpyViewRenderer.INSTANCE.isRendering())
        {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if(main.getColorTexture() == null || main.getDepthTexture() == null)
        {
            return;
        }
        if(saved == null)
        {
            saved = new TextureTarget("GLASS wall cut", main.width, main.height, true);
        }
        else if(saved.width != main.width || saved.height != main.height)
        {
            saved.resize(main.width, main.height);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), saved.getColorTexture(),
                0, 0, 0, 0, 0, main.width, main.height);
        PROJECTION.set(context.projectionMatrix());
        VIEW.set(context.positionMatrix());
        saving = true;
    }

    /** After players and mobs are drawn (see LevelRendererCloakWallMixin): the cut at the walls, then the nano patches. */
    public static void afterEntities()
    {
        if(SpyViewRenderer.INSTANCE.isRendering())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if(level == null)
        {
            return;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if(saving)
        {
            saving = false;
            cut(mc, camera);
        }
        patches(mc, level, camera);
    }

    private static void cut(Minecraft mc, Vec3 camera)
    {
        RenderTarget main = mc.getMainRenderTarget();
        saved.copyDepthFrom(main);
        COLOUR.set(saved.getColorTexture(), saved.getColorTextureView());
        DEPTH.set(saved.getDepthTexture(), saved.getDepthTextureView());
        if(cutType == null)
        {
            mc.getTextureManager().register(COLOUR_ID, COLOUR);
            mc.getTextureManager().register(DEPTH_ID, DEPTH);
            cutType = GlassRenderTypes.wallCut(COLOUR_ID, DEPTH_ID, () -> SCREEN_TO_WORLD);
        }
        SCREEN_TO_WORLD.set(PROJECTION).mul(VIEW).invert();
        BufferBuilder builder = null;
        for(AABB wall : CloakHider.fieldWalls())
        {
            if(wall.contains(camera))
            {
                continue;
            }
            if(builder == null)
            {
                builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            }
            AABB box = wall.move(-camera.x, -camera.y, -camera.z);
            AABB faces = box.inflate(0.05D);
            for(Direction face : Direction.values())
            {
                if(facesCamera(faces, face))
                {
                    cutFace(builder, faces, face, box);
                }
            }
        }
        if(builder != null)
        {
            MeshData mesh = builder.build();
            if(mesh != null)
            {
                cutType.draw(mesh);
            }
        }
    }

    /** True if a face of a box (relative to the camera) faces the camera. */
    private static boolean facesCamera(AABB box, Direction face)
    {
        return switch(face)
        {
            case WEST -> box.minX > 0D;
            case EAST -> box.maxX < 0D;
            case DOWN -> box.minY > 0D;
            case UP -> box.maxY < 0D;
            case NORTH -> box.minZ > 0D;
            case SOUTH -> box.maxZ < 0D;
        };
    }

    private static void cutFace(BufferBuilder b, AABB f, Direction face, AABB box)
    {
        double[][] corners = corners(f, face);
        int y0 = clampShort(box.minY * 32D), y1 = clampShort(box.maxY * 32D);
        int z0 = clampShort(box.minZ * 32D), z1 = clampShort(box.maxZ * 32D);
        for(double[] c : corners)
        {
            b.addVertex((float)c[0], (float)c[1], (float)c[2]).setColor(0xFFFFFFFF).setUv((float)box.minX, (float)box.maxX)
                    .setUv1(y0, y1).setUv2(z0, z1).setNormal(0F, 1F, 0F);
        }
    }

    private static int clampShort(double v)
    {
        return (int)Math.max(-32767D, Math.min(32767D, Math.round(v)));
    }

    /** The four corners of a face of a box. */
    private static double[][] corners(AABB f, Direction face)
    {
        return switch(face)
        {
            case WEST -> new double[][] { { f.minX, f.minY, f.minZ }, { f.minX, f.maxY, f.minZ }, { f.minX, f.maxY, f.maxZ }, { f.minX, f.minY, f.maxZ } };
            case EAST -> new double[][] { { f.maxX, f.minY, f.minZ }, { f.maxX, f.minY, f.maxZ }, { f.maxX, f.maxY, f.maxZ }, { f.maxX, f.maxY, f.minZ } };
            case DOWN -> new double[][] { { f.minX, f.minY, f.minZ }, { f.minX, f.minY, f.maxZ }, { f.maxX, f.minY, f.maxZ }, { f.maxX, f.minY, f.minZ } };
            case UP -> new double[][] { { f.minX, f.maxY, f.minZ }, { f.maxX, f.maxY, f.minZ }, { f.maxX, f.maxY, f.maxZ }, { f.minX, f.maxY, f.maxZ } };
            case NORTH -> new double[][] { { f.minX, f.minY, f.minZ }, { f.maxX, f.minY, f.minZ }, { f.maxX, f.maxY, f.minZ }, { f.minX, f.maxY, f.minZ } };
            case SOUTH -> new double[][] { { f.minX, f.minY, f.maxZ }, { f.minX, f.maxY, f.maxZ }, { f.maxX, f.maxY, f.maxZ }, { f.maxX, f.minY, f.maxZ } };
        };
    }

    // ---- Nano patches ----

    private static void patches(Minecraft mc, ClientLevel level, Vec3 camera)
    {
        if(!ViewBackend.current().drawsViews())
        {
            PATCHES.clear();
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = level.getGameTime() + partial;
        //Who crosses the wall of a field that is on, and where.
        for(GlassProjectorBlockEntity master : GlassClientState.INSTANCE.loadedProjectors)
        {
            AABB field = master.cloakFieldBox();
            if(field == null || master.isRemoved() || master.getLevel() != level || !master.active || !master.cloak
                    || level.getGameTime() - master.onStart < 42L)
            {
                continue;
            }
            for(Entity entity : level.getEntities((Entity)null, field.inflate(1D), e -> !e.isSpectator()))
            {
                Vec3 at = entity.getPosition(partial);
                AABB body = entity.getBoundingBox().move(at.subtract(entity.position()));
                if(!field.intersects(body) || contains(field, body))
                {
                    continue;
                }
                for(Direction face : Direction.values())
                {
                    if(face == Direction.DOWN || !straddles(body, field, face))
                    {
                        continue;
                    }
                    long key = (long)entity.getId() * 8L + face.ordinal() + ((long)master.getBlockPos().hashCode() << 32);
                    Patch patch = PATCHES.computeIfAbsent(key, k -> new Patch());
                    patch.field = field;
                    patch.face = face;
                    patch.seed = entity.getId() * 31 + face.ordinal();
                    double[] uv = faceRect(body, face);
                    patch.u0 = uv[0];
                    patch.u1 = uv[1];
                    patch.v0 = uv[2];
                    patch.v1 = uv[3];
                    patch.lastTime = time;
                }
            }
        }
        if(PATCHES.isEmpty())
        {
            return;
        }
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        Iterator<Patch> it = PATCHES.values().iterator();
        while(it.hasNext())
        {
            Patch patch = it.next();
            float fade = 1F - Math.max(0F, time - patch.lastTime - 1F) / FADE_TICKS;
            if(fade <= 0F)
            {
                it.remove();
                continue;
            }
            any |= drawPatch(builder, patch, fade, (int)time, camera);
        }
        MeshData mesh = builder.build();
        if(any && mesh != null)
        {
            GlassRenderTypes.planes(false).draw(mesh);
        }
        else if(mesh != null)
        {
            mesh.close();
        }
    }

    /** True if a body sticks through this face of the field (across its plane, and within the face). */
    private static boolean straddles(AABB body, AABB field, Direction face)
    {
        return switch(face)
        {
            case WEST -> body.minX < field.minX && body.maxX > field.minX;
            case EAST -> body.minX < field.maxX && body.maxX > field.maxX;
            case NORTH -> body.minZ < field.minZ && body.maxZ > field.minZ;
            case SOUTH -> body.minZ < field.maxZ && body.maxZ > field.maxZ;
            case UP -> body.minY < field.maxY && body.maxY > field.maxY;
            case DOWN -> false;
        };
    }

    /** Where the body meets a face: (u0, u1, v0, v1) in the face's own two axes. */
    private static double[] faceRect(AABB body, Direction face)
    {
        return switch(face.getAxis())
        {
            case X -> new double[] { body.minZ, body.maxZ, body.minY, body.maxY };
            case Z -> new double[] { body.minX, body.maxX, body.minY, body.maxY };
            case Y -> new double[] { body.minX, body.maxX, body.minZ, body.maxZ };
        };
    }

    /** A patch of flickering nano pixels on the face round where the body meets it. */
    private static boolean drawPatch(BufferBuilder b, Patch p, float fade, int tick, Vec3 camera)
    {
        AABB f = p.field;
        double plane = switch(p.face)
        {
            case WEST -> f.minX;
            case EAST -> f.maxX;
            case NORTH -> f.minZ;
            case SOUTH -> f.maxZ;
            default -> f.maxY;
        };
        double faceU0, faceU1, faceV0, faceV1;
        switch(p.face.getAxis())
        {
            case X -> { faceU0 = f.minZ; faceU1 = f.maxZ; faceV0 = f.minY; faceV1 = f.maxY; }
            case Z -> { faceU0 = f.minX; faceU1 = f.maxX; faceV0 = f.minY; faceV1 = f.maxY; }
            default -> { faceU0 = f.minX; faceU1 = f.maxX; faceV0 = f.minZ; faceV1 = f.maxZ; }
        }
        double cell = 1D / CELLS;
        int cu0 = (int)Math.floor(Math.max(faceU0, p.u0 - PATCH_MARGIN) * CELLS);
        int cu1 = (int)Math.ceil(Math.min(faceU1, p.u1 + PATCH_MARGIN) * CELLS);
        int cv0 = (int)Math.floor(Math.max(faceV0, p.v0 - PATCH_MARGIN) * CELLS);
        int cv1 = (int)Math.ceil(Math.min(faceV1, p.v1 + PATCH_MARGIN) * CELLS);
        boolean any = false;
        //The pixels sit a hair off the face, on the side the camera is on.
        double side = switch(p.face)
        {
            case WEST -> camera.x < plane ? -1D : 1D;
            case EAST -> camera.x > plane ? 1D : -1D;
            case NORTH -> camera.z < plane ? -1D : 1D;
            case SOUTH -> camera.z > plane ? 1D : -1D;
            default -> camera.y > plane ? 1D : -1D;
        };
        double off = plane + side * 0.002D;
        for(int cu = cu0; cu < cu1; cu++)
        {
            for(int cv = cv0; cv < cv1; cv++)
            {
                double u = (cu + 0.5D) * cell, v = (cv + 0.5D) * cell;
                double du = u < p.u0 ? p.u0 - u : (u > p.u1 ? u - p.u1 : 0D);
                double dv = v < p.v0 ? p.v0 - v : (v > p.v1 ? v - p.v1 : 0D);
                double reach = 1D - Math.sqrt(du * du + dv * dv) / PATCH_MARGIN;
                if(reach <= 0D)
                {
                    continue;
                }
                float flicker = hash(cu, cv, tick / 2 + p.seed);
                if(flicker > 0.25F + 0.6F * reach)
                {
                    continue;
                }
                float shade = hash(cv, cu, tick + p.seed * 7);
                //Right in front of the eye (walking through in first person) the pixels fade, so the glitch shows round
                //you instead of filling the screen.
                double cx = p.face.getAxis() == Direction.Axis.X ? plane : u;
                double cy = p.face.getAxis() == Direction.Axis.Y ? plane : v;
                double cz = p.face.getAxis() == Direction.Axis.Z ? plane : p.face.getAxis() == Direction.Axis.X ? u : v;
                double near = Math.clamp((Math.sqrt(camera.distanceToSqr(cx, cy, cz)) - 0.3D) / 1.4D, 0D, 1D);
                if(near <= 0.05D)
                {
                    continue;
                }
                int alpha = (int)(255F * fade * (float)near * (float)Math.min(1D, 0.35D + reach * 0.7D) * (0.55F + 0.45F * shade));
                int colour = shade > 0.85F ? 0xFFFFFF : (shade > 0.4F ? 0x46E6FF : 0x1EA0DC);
                quad(b, p.face, off, cu * cell, (cu + 1) * cell, cv * cell, (cv + 1) * cell, camera, colour, alpha);
                any = true;
            }
        }
        return any;
    }

    private static void quad(BufferBuilder b, Direction face, double plane, double u0, double u1, double v0, double v1,
                             Vec3 cam, int rgb, int alpha)
    {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, bl = rgb & 255;
        double[][] pts = switch(face.getAxis())
        {
            case X -> new double[][] { { plane, v0, u0 }, { plane, v1, u0 }, { plane, v1, u1 }, { plane, v0, u1 } };
            case Z -> new double[][] { { u0, v0, plane }, { u1, v0, plane }, { u1, v1, plane }, { u0, v1, plane } };
            case Y -> new double[][] { { u0, plane, v0 }, { u1, plane, v0 }, { u1, plane, v1 }, { u0, plane, v1 } };
        };
        for(double[] pt : pts)
        {
            b.addVertex((float)(pt[0] - cam.x), (float)(pt[1] - cam.y), (float)(pt[2] - cam.z)).setColor(r, g, bl, alpha);
        }
    }

    private static float hash(int a, int b, int c)
    {
        int h = a * 374761393 + b * 668265263 + c * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float)0x1000000;
    }

    public static void clear()
    {
        PATCHES.clear();
        cutting = false;
        saving = false;
    }

    /** Hands a texture of the saved picture to the texture manager without owning it. */
    private static final class WallTexture extends AbstractTexture
    {
        void set(GpuTexture texture, GpuTextureView view)
        {
            this.texture = texture;
            this.textureView = view;
        }

        @Override
        public void close()
        {
            this.texture = null;
            this.textureView = null;
        }
    }
}
