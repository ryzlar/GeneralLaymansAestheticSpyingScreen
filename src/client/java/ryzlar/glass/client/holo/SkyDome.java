package ryzlar.glass.client.holo;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ryzlar.glass.Glass;
import ryzlar.glass.holo.CubeScan;

import java.util.ArrayList;
import java.util.List;

/**
 * The scanned sky inside a simulation, in a dimension without a sky of its own (the Nether, the End). There the
 * scanned sky cannot be made by turning the real sky to the scanned time (see {@link SimulatedSky}): there is none. So
 * it is drawn here, on the inside of the bubble: the sky's colour, the sunrise glow, the sun or the moon and the stars,
 * as Minecraft's own overworld sky would show them at the scanned time and weather.
 * <p>
 * Every colour and the sun, moon and stars are worked out from the direction the eye looks in, not from where on the
 * bubble they are: walking about inside, the sky stays put, far away, as a real one does.
 */
final class SkyDome
{
    private static final ResourceLocation SUN = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    /** The sky's colour where no biome is known (the plains'), and the overworld's fog colour, at noon. */
    private static final float[] SKY = { 0x78 / 255F, 0xA7 / 255F, 1F };
    private static final float[] FOG = { 0xC0 / 255F, 0xD8 / 255F, 1F };
    private static final int RINGS = 24;
    private static final int SEGMENTS = 48;
    /** The dome: plain coloured quads, see-through only while it fades in, hiding what lies beyond the bubble. */
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Glass.id("pipeline/glass_sky_dome"))
            .withCull(false)
            .build();
    private static final RenderType DOME = RenderType.create("glass_sky_dome", 1536, false, false, PIPELINE,
            RenderType.CompositeState.builder().createCompositeState(false));
    /** The stars, as Minecraft makes them: directions in the sky's own frame, and each one's size (at a distance of 1). */
    private static final List<float[]> STARS = makeStars();

    private SkyDome() {}

    /** True where the scanned sky has to be drawn here: the scan has one, and this dimension has none. */
    static boolean needed(ClientLevel level, CubeScan scan)
    {
        return scan.hasSky() && !level.dimensionType().hasSkyLight();
    }

    /**
     * Draws the dome. {@code origin}: the world point the pose stands at. {@code centre} and {@code radius}: the dome
     * (just outside the bubble's wall). {@code fade}: how far it has come in (0..1), as you walk in and as it grows.
     */
    static void draw(PoseStack.Pose pose, MultiBufferSource buffers, Vec3 origin, Vec3 centre, float radius, Vec3 eye, CubeScan scan, float fade)
    {
        if(fade <= 0.01F || radius <= 0.5F || eye.distanceTo(centre) >= radius)
        {
            return;
        }
        float time = SimulatedSky.overworldTimeOfDay(scan.skyTime());
        float angle = time * Mth.TWO_PI;
        float cos = Mth.cos(angle);
        //How bright the day is, and the sky's and the horizon's colours (as ClientLevel.getSkyColor and the overworld fog).
        float day = Mth.clamp(cos * 2F + 0.5F, 0F, 1F);
        float[] sky = weather(new float[] { SKY[0] * day, SKY[1] * day, SKY[2] * day }, scan);
        float[] fog = weather(new float[] { FOG[0] * (day * 0.94F + 0.06F), FOG[1] * (day * 0.94F + 0.06F), FOG[2] * (day * 0.91F + 0.09F) }, scan);
        //The sunrise or sunset glow, on the side the sun is on (as DimensionSpecialEffects.OverworldEffects works it out).
        float[] glow = null;
        if(cos >= -0.4F && cos <= 0.4F)
        {
            float g = cos / 0.4F * 0.5F + 0.5F;
            float a = 1F - (1F - Mth.sin(g * Mth.PI)) * 0.99F;
            glow = new float[] { g * 0.3F + 0.7F, g * g * 0.7F + 0.2F, 0.2F, a * a * (1F - scan.rain())};
        }
        float sunSide = -Mth.sin(angle) >= 0F ? 1F : -1F;
        int alpha = Mth.clamp(Math.round(fade * 255F), 0, 255);

        //The dome itself, drawn at once: the sun, moon and stars go in front of it.
        VertexConsumer dome = buffers.getBuffer(DOME);
        for(int r = 0; r < RINGS; r++)
        {
            double lat0 = Math.PI * r / RINGS - Math.PI / 2D, lat1 = Math.PI * (r + 1) / RINGS - Math.PI / 2D;
            for(int s = 0; s < SEGMENTS; s++)
            {
                double lon0 = Math.PI * 2D * s / SEGMENTS, lon1 = Math.PI * 2D * (s + 1) / SEGMENTS;
                domeVertex(dome, pose, origin, centre, radius, eye, lat0, lon0, sky, fog, glow, sunSide, alpha);
                domeVertex(dome, pose, origin, centre, radius, eye, lat0, lon1, sky, fog, glow, sunSide, alpha);
                domeVertex(dome, pose, origin, centre, radius, eye, lat1, lon1, sky, fog, glow, sunSide, alpha);
                domeVertex(dome, pose, origin, centre, radius, eye, lat1, lon0, sky, fog, glow, sunSide, alpha);
            }
        }
        flush(buffers, DOME);

        //The stars, at night (as Level.getStarBrightness), turning with the sky.
        float stars = 1F - (cos * 2F + 0.25F);
        stars = Mth.clamp(stars, 0F, 1F);
        stars = stars * stars * 0.5F * (1F - scan.rain()) * fade;
        if(stars > 0.01F)
        {
            VertexConsumer starQuads = buffers.getBuffer(DOME);
            int starAlpha = Mth.clamp(Math.round(stars * 255F), 0, 255);
            for(float[] star : STARS)
            {
                Vec3 dir = skyToWorld(star[0], star[1], star[2], angle);
                celestial(starQuads, pose, origin, centre, radius, eye, dir, star[3], 0F, 0F, 0F, 0F, starAlpha, false, 0.15F);
            }
            flush(buffers, DOME);
        }

        //The sun and the moon (the moon in its phase), dimmed by rain.
        int light = Mth.clamp(Math.round((1F - scan.rain()) * fade * 255F), 0, 255);
        if(light > 0)
        {
            Vec3 sun = skyToWorld(0F, 1F, 0F, angle);
            celestial(buffers.getBuffer(RenderType.celestial(SUN)), pose, origin, centre, radius, eye, sun, 0.3F, 0F, 0F, 1F, 1F, light, true, 0.1F);
            int phase = (int)Math.floorMod(scan.skyTime() / 24000L, 8L);
            float u0 = (phase % 4) / 4F, v0 = (phase / 4 % 2) / 2F;
            celestial(buffers.getBuffer(RenderType.celestial(MOON)), pose, origin, centre, radius, eye, sun.scale(-1D), 0.2F,
                    u0 + 0.25F, v0 + 0.5F, u0, v0, light, true, 0.1F);
            flush(buffers, RenderType.celestial(SUN));
            flush(buffers, RenderType.celestial(MOON));
        }
    }

    /** Rain and thunder grey the sky, as ClientLevel.getSkyColor does. */
    private static float[] weather(float[] c, CubeScan scan)
    {
        float luma = (c[0] * 0.3F + c[1] * 0.59F + c[2] * 0.11F) * 0.6F;
        float rain = scan.rain() * 0.75F;
        for(int i = 0; i < 3; i++)
        {
            c[i] = c[i] * (1F - rain) + luma * rain;
        }
        float dark = luma * 0.2F;
        float thunder = scan.thunder() * 0.75F;
        for(int i = 0; i < 3; i++)
        {
            c[i] = c[i] * (1F - thunder) + dark * thunder;
        }
        return c;
    }

    private static void domeVertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 origin, Vec3 centre, float radius, Vec3 eye, double lat, double lon,
                                   float[] sky, float[] fog, float[] glow, float sunSide, int alpha)
    {
        double x = centre.x + Math.cos(lat) * Math.cos(lon) * radius;
        double y = centre.y + Math.sin(lat) * radius;
        double z = centre.z + Math.cos(lat) * Math.sin(lon) * radius;
        Vec3 dir = new Vec3(x - eye.x, y - eye.y, z - eye.z).normalize();
        //Up from the horizon: the sky's colour; at the horizon: the fog's; below it, darker.
        float up = (float)dir.y;
        float t = smooth(-0.05F, 0.4F, up);
        float r = fog[0] + (sky[0] - fog[0]) * t, g = fog[1] + (sky[1] - fog[1]) * t, b = fog[2] + (sky[2] - fog[2]) * t;
        if(up < 0F)
        {
            float below = 1F - 0.5F * smooth(0F, 0.4F, -up);
            r *= below;
            g *= below;
            b *= below;
        }
        if(glow != null)
        {
            float towards = (float)Math.max(0D, dir.x * sunSide);
            float w = glow[3] * towards * towards * (1F - smooth(0F, 0.45F, Math.abs(up)));
            r += (glow[0] - r) * w;
            g += (glow[1] - g) * w;
            b += (glow[2] - b) * w;
        }
        consumer.addVertex(pose, (float)(x - origin.x), (float)(y - origin.y), (float)(z - origin.z))
                .setColor(Mth.clamp((int)(r * 255F), 0, 255), Mth.clamp((int)(g * 255F), 0, 255), Mth.clamp((int)(b * 255F), 0, 255), alpha);
    }

    /**
     * A sun, moon or star: a square facing the eye in direction {@code dir}, just inside the dome where that direction
     * meets it, as big as it looks in the real sky ({@code size}: half its width at a distance of 1).
     */
    private static void celestial(VertexConsumer consumer, PoseStack.Pose pose, Vec3 origin, Vec3 centre, float radius, Vec3 eye, Vec3 dir, float size,
                                  float u0, float v0, float u1, float v1, int alpha, boolean textured, float inset)
    {
        double reach = raySphere(eye, dir, centre, radius) - inset;
        if(reach <= 0.2D)
        {
            return;
        }
        Vec3 middle = eye.add(dir.scale(reach)).subtract(origin);
        //Two directions across it.
        Vec3 side = Math.abs(dir.y) < 0.99D ? dir.cross(new Vec3(0D, 1D, 0D)).normalize() : new Vec3(1D, 0D, 0D);
        Vec3 up = side.cross(dir).normalize();
        double half = size * reach;
        Vec3 a = side.scale(half), b = up.scale(half);
        Vec3[] corners = { middle.subtract(a).subtract(b), middle.add(a).subtract(b), middle.add(a).add(b), middle.subtract(a).add(b) };
        float[][] uv = { { u0, v0 }, { u1, v0 }, { u1, v1 }, { u0, v1 } };
        for(int i = 0; i < 4; i++)
        {
            VertexConsumer v = consumer.addVertex(pose, (float)corners[i].x, (float)corners[i].y, (float)corners[i].z);
            if(textured)
            {
                v.setUv(uv[i][0], uv[i][1]).setColor(255, 255, 255, alpha);
            }
            else
            {
                v.setColor(255, 255, 255, alpha);
            }
        }
    }

    /** How far along {@code dir} from inside the ball the eye meets its surface. */
    private static double raySphere(Vec3 eye, Vec3 dir, Vec3 centre, float radius)
    {
        Vec3 off = eye.subtract(centre);
        double b = off.dot(dir);
        double c = off.lengthSqr() - radius * radius;
        double disc = b * b - c;
        return disc < 0D ? -1D : -b + Math.sqrt(disc);
    }

    /** A direction in the sky's frame turned to where it is at this time, as SkyRenderer turns the sky (Y -90, then X). */
    private static Vec3 skyToWorld(float x, float y, float z, float angle)
    {
        float cos = Mth.cos(angle), sin = Mth.sin(angle);
        float y1 = y * cos - z * sin, z1 = y * sin + z * cos;
        return new Vec3(-z1, y1, x).normalize();
    }

    private static float smooth(float from, float to, float x)
    {
        float t = Mth.clamp((x - from) / (to - from), 0F, 1F);
        return t * t * (3F - 2F * t);
    }

    private static void flush(MultiBufferSource buffers, RenderType type)
    {
        if(buffers instanceof MultiBufferSource.BufferSource source)
        {
            source.endBatch(type);
        }
    }

    /** The same stars as SkyRenderer.buildStars makes (seed 10842). */
    private static List<float[]> makeStars()
    {
        List<float[]> stars = new ArrayList<>();
        RandomSource random = RandomSource.create(10842L);
        for(int i = 0; i < 1500; i++)
        {
            float x = random.nextFloat() * 2F - 1F, y = random.nextFloat() * 2F - 1F, z = random.nextFloat() * 2F - 1F;
            float size = 0.15F + random.nextFloat() * 0.1F;
            float length = Mth.lengthSquared(x, y, z);
            if(length > 0.010000001F && length < 1F)
            {
                float inv = 1F / Mth.sqrt(length);
                stars.add(new float[] { x * inv, y * inv, z * inv, size / 100F });
            }
        }
        return stars;
    }
}
