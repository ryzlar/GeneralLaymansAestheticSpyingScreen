package ryzlar.glass.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.config.Config;
import java.awt.Color;
import java.lang.ref.WeakReference;
import net.minecraft.world.phys.Vec3;

/**
 * Distant Horizons switches Minecraft's clouds off and draws its own (big boxes high up, in three layers), in the same
 * pass as its far terrain. That pass is left out of GLASS's views (see DistantHorizonsRenderMixin), so a view drew no
 * clouds at all: while a cloak field's walls rose, the sky behind them was empty.
 * <p>
 * Instead, a view draws a copy of Distant Horizons' clouds itself: the very boxes it made, where it put them this frame
 * (they drift), in their colour, with the same shading and faded into its fog the same way (its fog settings as it
 * reports them each frame, worked out per corner). Each layer repeats every so many blocks; only the repeats near
 * enough to be seen are drawn. Only touched when Distant Horizons is installed (its classes are not there otherwise).
 */
public final class DistantHorizonsClouds
{
    /** How far out the copy is drawn at most (blocks each way); otherwise as far as Distant Horizons draws. */
    private static final double MAX_REACH = 8192D;
    /** Distant Horizons' layout: repeats -5..5 each way per layer. */
    private static final int CENTRE = 5;
    /** Distant Horizons' shading per side. */
    private static final float TOP = 1F, BOTTOM = 0.7F, NORTH_SOUTH = 0.9F, EAST_WEST = 0.8F;
    /** Fog settings older than this (Distant Horizons' fog switched off since) are not used. */
    private static final long FOG_STALE_NANOS = 1_000_000_000L;

    /** Distant Horizons' cloud boxes, [layer][x repeat + 5][z repeat + 5], set by DistantHorizonsCloudsMixin. */
    private static WeakReference<IDhApiRenderableBoxGroup[][][]> groups = new WeakReference<>(null);
    private static boolean listening;
    /** Distant Horizons' fog this frame (see {@link #listen}), or null. */
    private static volatile DhApiFogRenderParam fog;
    private static volatile long fogTime;

    private DistantHorizonsClouds() {}

    public static void setGroups(IDhApiRenderableBoxGroup[][][] made)
    {
        groups = new WeakReference<>(made);
        listen();
    }

    /** Keeps Distant Horizons' fog settings, which it reports before drawing its fog each frame. */
    private static void listen()
    {
        if(listening)
        {
            return;
        }
        listening = true;
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent()
        {
            @Override
            public void beforeRender(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam> event)
            {
                fog = event.value.getFogRenderParam().copy();
                fogTime = System.nanoTime();
            }
        });
    }

    /** Draws the clouds, with the view's model view and projection already set. */
    public static void draw(Vec3 camera)
    {
        IDhApiRenderableBoxGroup[][][] layers = groups.get();
        if(layers == null)
        {
            return;
        }
        Fog haze = Fog.current();
        double reach = Math.min(MAX_REACH, Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * 16D);
        BufferBuilder builder = null;
        for(IDhApiRenderableBoxGroup[][] layer : layers)
        {
            if(layer.length <= CENTRE + 1 || layer[CENTRE].length <= CENTRE)
            {
                continue;
            }
            //The middle repeats are never culled by Distant Horizons, so they are where it put the layer this frame (or
            //they are off: clouds switched off, or this layer).
            IDhApiRenderableBoxGroup middle = layer[CENTRE][CENTRE];
            IDhApiRenderableBoxGroup next = layer[CENTRE + 1][CENTRE];
            if(middle == null || next == null || !middle.isActive() || middle.isEmpty())
            {
                continue;
            }
            DhApiVec3d origin = middle.getOriginBlockPos();
            //One repeat across: its texture's width times 128 blocks.
            double width = Math.round((next.getOriginBlockPos().x - origin.x) / 128D) * 128D;
            if(width <= 0D)
            {
                continue;
            }
            Color colour = middle.get(0).color;
            for(int ix = -CENTRE; ix <= CENTRE; ix++)
            {
                double ox = origin.x + ix * width;
                if(ox > camera.x + reach || ox + width < camera.x - reach)
                {
                    continue;
                }
                for(int iz = -CENTRE; iz <= CENTRE; iz++)
                {
                    double oz = origin.z + iz * width;
                    if(oz > camera.z + reach || oz + width < camera.z - reach)
                    {
                        continue;
                    }
                    for(DhApiRenderableBox box : middle)
                    {
                        float x0 = (float)(ox + box.minPos.x - camera.x), x1 = (float)(ox + box.maxPos.x - camera.x);
                        float z0 = (float)(oz + box.minPos.z - camera.z), z1 = (float)(oz + box.maxPos.z - camera.z);
                        if(x0 > reach || x1 < -reach || z0 > reach || z1 < -reach)
                        {
                            continue;
                        }
                        float y0 = (float)(origin.y + box.minPos.y - camera.y), y1 = (float)(origin.y + box.maxPos.y - camera.y);
                        if(builder == null)
                        {
                            builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                        }
                        box(builder, haze, colour, x0, y0, z0, x1, y1, z1);
                    }
                }
            }
        }
        if(builder != null)
        {
            MeshData mesh = builder.build();
            if(mesh != null)
            {
                GlassRenderTypes.DH_CLOUDS.draw(mesh);
            }
        }
    }

    private static void box(BufferBuilder b, Fog fog, Color c, float x0, float y0, float z0, float x1, float y1, float z1)
    {
        face(b, fog, c, TOP, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(b, fog, c, BOTTOM, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(b, fog, c, NORTH_SOUTH, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        face(b, fog, c, NORTH_SOUTH, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(b, fog, c, EAST_WEST, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(b, fog, c, EAST_WEST, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void face(BufferBuilder b, Fog fog, Color c, float shade,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz)
    {
        corner(b, fog, c, shade, ax, ay, az);
        corner(b, fog, c, shade, bx, by, bz);
        corner(b, fog, c, shade, cx, cy, cz);
        corner(b, fog, c, shade, dx, dy, dz);
    }

    private static void corner(BufferBuilder b, Fog fog, Color c, float shade, float x, float y, float z)
    {
        float r = c.getRed() * shade, g = c.getGreen() * shade, bl = c.getBlue() * shade;
        float t = fog == null ? 0F : fog.thickness(x, y, z);
        if(t > 0F)
        {
            r += (fog.r - r) * t;
            g += (fog.g - g) * t;
            bl += (fog.b - bl) * t;
        }
        b.addVertex(x, y, z).setColor(Math.round(r), Math.round(g), Math.round(bl), c.getAlpha());
    }

    /** Distant Horizons' far fog, as its fog shader works it out (its height fog is left out). */
    private record Fog(float r, float g, float b, float scale, boolean spherical, EDhApiFogFalloff falloff,
                       float start, float length, float min, float range, float density)
    {
        static Fog current()
        {
            DhApiFogRenderParam p = fog;
            if(p == null || System.nanoTime() - fogTime > FOG_STALE_NANOS)
            {
                return null;
            }
            Color colour = p.getFogColor();
            int radius = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
            float length = p.getFarFogEndPercent() - p.getFarFogStartPercent();
            return new Fog(colour.getRed(), colour.getGreen(), colour.getBlue(), 1F / (radius * 16F),
                    p.getHeightFogMixingMode() == EDhApiHeightFogMixMode.SPHERICAL, p.getFarFogFalloff(),
                    p.getFarFogStartPercent(), length == 0F ? 1e-4F : length, p.getFarFogMinThickness(),
                    p.getFarFogMaxThickness() - p.getFarFogMinThickness(), p.getFarFogDensity());
        }

        float thickness(float x, float y, float z)
        {
            float distance = (float)Math.sqrt(spherical ? x * x + y * y + z * z : x * x + z * z) * scale;
            float t;
            switch(falloff)
            {
                case LINEAR -> t = min + range * Math.clamp((distance - start) / length, 0F, 1F);
                case EXPONENTIAL ->
                {
                    float e = Math.max((distance - start) / length, 0F) * density;
                    t = min + range - range / (float)Math.exp(e);
                }
                default ->
                {
                    float e = Math.max((distance - start) / length, 0F) * density;
                    t = min + range - range / (float)Math.exp(e * e);
                }
            }
            return Math.clamp(t, 0F, 1F);
        }
    }
}
