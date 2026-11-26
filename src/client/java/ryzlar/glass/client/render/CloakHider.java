package ryzlar.glass.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ryzlar.glass.GlassRegistry;
import ryzlar.glass.blockentity.GlassBaseBlockEntity;
import ryzlar.glass.blockentity.GlassProjectorBlockEntity;
import ryzlar.glass.client.GlassClientState;
import ryzlar.glass.client.TerminalPlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A cloak that is on hides its blocks from the world drawing itself: the chunk builder (vanilla's or Sodium's, see
 * RenderSectionRegionMixin and LevelSliceMixin) sees the hidden part as {@link CloakFill} makes it up (the ground carried
 * on from around it, air above it, and the light there), and the entities in it are not drawn. What lies behind it is
 * then simply drawn by the game, which is far cheaper than drawing the world again from the eye for every side of the
 * cloak, and works with shader packs (Iris draws it like any other terrain, shadows included).
 * <p>
 * A cloak field always hides its blocks this way. A cloak of glass blocks only with a shader pack: otherwise it keeps its
 * views, which show what lies behind the glass as seen through it.
 * <p>
 * Only for an eye outside it (inside, the base is there as always). Switching on and off without a shader pack, the
 * cloak is drawn with its views as before (the walls rising, the nano wave), and hands over to this once that is done:
 * the views are kept a few ticks longer, until the chunks have been rebuilt without the hidden blocks. With a shader pack
 * there are no views, so the hiding itself is the animation: a field's build goes from the ground up as its walls would
 * rise (and comes back from the top down), a glass cloak's from the projector out as its nano wave would run (and comes
 * back from the outside in). At the front its blocks turn into nano pixels for a moment ({@link GlassRegistry#NANO_VEIL},
 * real terrain, so any shader pack draws it), which stream into the projector (and back out of it).
 */
public final class CloakHider
{
    /** How long a cloak's views go on after its blocks are hidden (the chunks around it are rebuilt meanwhile), in ticks. */
    private static final int HANDOVER_TICKS = 10;
    /** How long a cloak switching off asks for its views again before its blocks come back, in ticks. */
    private static final int WAKE_TICKS = 3;
    /** The eye counts as inside a hidden part this far into it (and as outside again only this far out of it), in blocks. */
    private static final double INSIDE_MARGIN = 0.3D;
    /** A field takes this long to switch on (its walls rising), in ticks. */
    private static final long FIELD_ON_TICKS = 42L;
    /** The hiding animation moves on in steps of this many ticks (each step has the chunks around it rebuilt). */
    private static final float STEP_TICKS = 2F;
    /** At most this many nano pixels per cloak per tick stream into (or out of) the projector. */
    private static final int MAX_PIXELS = 40;
    /** How long a block shows as nano pixels at the front as it goes or comes back, in ticks. */
    private static final float FRONT_TICKS = 6F;
    private static final BlockState NANO = GlassRegistry.NANO_VEIL.defaultBlockState();
    private static final float STEADY = Float.MAX_VALUE;
    /** {@link Hidden#kind}: something stands here in the real world. */
    static final byte BUILT = 1;
    /** {@link Hidden#kind}: a field's wall or roof. */
    static final byte WALL = 2;

    /**
     * One hidden part, as the chunk builder sees it: per block in the box from {@code min}, the block in its place and
     * its sky and block light (null and -1: the real ones). {@code hidden}: the part itself (entities in it are not
     * drawn); {@code looked}: what the made-up ground was worked out from (a block changing there rebuilds it).
     * {@code at}: per block, how many ticks into the cloak's switching on it goes (null: all at once); it is hidden once
     * that is no more than {@code shown}. {@code kind}: per block, {@link #BUILT} where something stands in the real
     * world (it shows as nano pixels at the front, and streams into the projector), {@link #WALL} on a field's walls and
     * roof (nano pixels at the front even where there is only air: the walls rising), else 0. {@code growing}: switching
     * on (the hidden part grows), not off.
     */
    public record Hidden(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, BlockState[] states, byte[] sky,
                         byte[] block, AABB hidden, AABB looked, @Nullable float[] at, @Nullable byte[] kind, float shown,
                         boolean growing)
    {
        int index(int x, int y, int z)
        {
            int dx = x - minX, dy = y - minY, dz = z - minZ;
            if(dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ)
            {
                return -1;
            }
            return (dy * sizeZ + dz) * sizeX + dx;
        }

        boolean isHidden(int i)
        {
            return at == null || at[i] <= shown;
        }

        /** True if the block here is at the front of the animation: it shows as nano pixels. */
        boolean isNano(int i)
        {
            if(at == null || kind == null || shown == STEADY || kind[i] == 0 || kind[i] == BUILT && states[i] == null)
            {
                return false;
            }
            float behind = growing ? shown - at[i] : at[i] - shown;
            return behind >= 0F && behind < FRONT_TICKS;
        }

        Hidden showing(@Nullable float[] at, @Nullable byte[] kind, float shown, boolean growing)
        {
            return new Hidden(minX, minY, minZ, sizeX, sizeY, sizeZ, states, sky, block, hidden, looked, at, kind, shown, growing);
        }

        void markDirty(Minecraft mc)
        {
            //One more all round: the faces and shading of the blocks next to it depend on it too.
            mc.levelRenderer.setBlocksDirty(minX - 1, minY - 1, minZ - 1, minX + sizeX, minY + sizeY, minZ + sizeZ);
        }
    }

    /** What the chunk builders read, from their own threads: replaced as a whole, never changed. */
    private static volatile Hidden[] active = new Hidden[0];
    /** The hidden boxes of cloak fields that hide their whole build from the eye right now (see {@link CloakWall}). */
    private static volatile AABB[] fieldWalls = new AABB[0];

    /** A cloak that hides (or is about to): its hidden parts, and since when. */
    private static final class Cloak
    {
        final GlassProjectorBlockEntity master;
        final TerminalPlacement placement;
        /** Its hidden parts, all hidden, with the tick each block goes at when it switches on. */
        List<Hidden> parts = List.of();
        List<AABB> boxes = List.of();
        boolean stale;
        long since;
        /** How far into switching on it is shown, in ticks ({@link #STEADY}: on). */
        float shown = STEADY;
        /** The parts as published: {@link #parts} at {@link #shown} (kept, so a step is published once). */
        List<Hidden> published = List.of();
        float publishedShown = Float.NaN;
        /** Switching off with views: the game time its blocks come back (its views are asked for again until then). */
        long releaseAt = Long.MIN_VALUE;
        /** Switching off with a shader pack: the game time its blocks started coming back, and from how far. */
        long fadeStart = Long.MIN_VALUE;
        float fadeFrom;

        Cloak(GlassProjectorBlockEntity master, TerminalPlacement placement)
        {
            this.master = master;
            this.placement = placement;
        }

        boolean switchingOff()
        {
            return releaseAt != Long.MIN_VALUE || fadeStart != Long.MIN_VALUE;
        }
    }

    private static final Map<String, Cloak> CLOAKS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private CloakHider() {}

    // ---- Read by the chunk builders ----

    /** The block the chunk builder is to see here instead of the real one, or null. */
    @Nullable
    public static BlockState state(int x, int y, int z)
    {
        for(Hidden hidden : active)
        {
            int i = hidden.index(x, y, z);
            if(i >= 0 && hidden.isNano(i))
            {
                return NANO;
            }
            if(i >= 0 && hidden.states[i] != null && hidden.isHidden(i))
            {
                return hidden.states[i];
            }
        }
        return null;
    }

    /** The light the chunk builder is to see here instead of the real one, or -1. */
    public static int light(LightLayer layer, int x, int y, int z)
    {
        for(Hidden hidden : active)
        {
            int i = hidden.index(x, y, z);
            if(i >= 0 && layer == LightLayer.BLOCK && hidden.isNano(i))
            {
                return 15; //nano pixels glow
            }
            if(i >= 0 && hidden.isHidden(i))
            {
                int light = layer == LightLayer.SKY ? hidden.sky[i] : hidden.block[i];
                if(light >= 0)
                {
                    return light;
                }
            }
        }
        return -1;
    }

    /** True if anything is hidden at all (a quick test before looking up blocks). */
    public static boolean hidesAnything()
    {
        return active.length > 0;
    }

    /** True if an entity here is in a hidden part (it is not drawn). */
    public static boolean hidesEntity(double x, double y, double z)
    {
        for(Hidden hidden : active)
        {
            AABB box = hidden.hidden;
            if(x >= box.minX && x < box.maxX && y >= box.minY && y < box.maxY && z >= box.minZ && z < box.maxZ)
            {
                int i = hidden.index((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
                if(i < 0 || hidden.isHidden(i))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** The hidden boxes of cloak fields that hide their whole build from the eye right now (fully on, not switching). */
    public static AABB[] fieldWalls()
    {
        return fieldWalls;
    }

    // ---- Asked by the glass renderer ----

    /**
     * True if this screen's cloak hides its blocks and has handed over: its glass and views need not be drawn (and its
     * views are let go, which is the whole saving).
     */
    public static boolean isHiding(String key, long gameTime)
    {
        Cloak cloak = CLOAKS.get(key);
        return cloak != null && !cloak.switchingOff() && cloak.shown == STEADY && !cloak.parts.isEmpty()
                && gameTime - cloak.since >= HANDOVER_TICKS;
    }

    // ---- Kept up to date every tick ----

    /** A block changed on the client: a cloak whose made-up ground was worked out from it works it out again. */
    public static void blockChanged(BlockPos pos)
    {
        CloakMapHider.blockChanged(pos);
        if(CLOAKS.isEmpty())
        {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(pos);
        for(Cloak cloak : CLOAKS.values())
        {
            for(Hidden part : cloak.parts)
            {
                if(part.looked.contains(centre))
                {
                    cloak.stale = true;
                }
            }
        }
    }

    public static void tick(Minecraft mc)
    {
        ClientLevel level = mc.level;
        if(level == null)
        {
            clear();
            return;
        }
        long now = level.getGameTime();
        boolean views = ViewBackend.current().drawsViews();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Map<String, Cloak> wanted = new HashMap<>();
        for(GlassProjectorBlockEntity master : GlassClientState.INSTANCE.loadedProjectors)
        {
            String key = master.screenKey();
            if(master.isRemoved() || master.getLevel() != level || !master.active || !master.cloak || key.isEmpty() || !master.channel.equals(key))
            {
                continue;
            }
            //A cloak of glass blocks keeps its views when it can: they show what lies behind it as seen through the glass
            //(a wall in front of a hill shows the hill), where hiding the blocks would make the part behind it air. Only
            //with a shader pack (no views) does it hide its blocks. A field's box is hidden from every side anyway.
            if(master.cloakFieldBox() == null && views)
            {
                continue;
            }
            //Switching on without a shader pack, the views draw the cloak (the walls rising, the nano wave): its blocks
            //are hidden after that. With one, the hiding itself is the animation.
            long on = now - master.onStart;
            long onTicks = onTicks(master);
            boolean switchingOn = on >= 0 && on < onTicks;
            if(switchingOn && views)
            {
                continue;
            }
            TerminalPlacement placement = GlassClientState.INSTANCE.getTerminalPlacement(key);
            if(placement == null || !placement.cloak)
            {
                continue;
            }
            List<TerminalPlacement> sides = sides(master, placement);
            List<AABB> boxes = new ArrayList<>();
            for(TerminalPlacement side : sides)
            {
                boxes.add(side.hiddenPart());
            }
            Cloak cloak = CLOAKS.get(key);
            boolean wasInside = cloak != null && cloak.parts.isEmpty();
            if(isInside(eye, boxes, wasInside))
            {
                //Inside it the base is there as always.
                if(cloak != null && !cloak.parts.isEmpty())
                {
                    release(mc, cloak);
                }
                if(cloak == null || cloak.master != master)
                {
                    cloak = new Cloak(master, placement);
                }
                cloak.since = now;
                cloak.releaseAt = Long.MIN_VALUE;
                cloak.fadeStart = Long.MIN_VALUE;
                wanted.put(key, cloak);
                continue;
            }
            if(cloak == null || cloak.master != master || cloak.parts.isEmpty() || cloak.stale || !cloak.boxes.equals(boxes)
                    || cloak.releaseAt != Long.MIN_VALUE)
            {
                Cloak fresh = cloak != null && cloak.master == master ? cloak : new Cloak(master, placement);
                boolean wasHiding = !fresh.parts.isEmpty() && !fresh.switchingOff();
                if(!fresh.parts.isEmpty())
                {
                    release(mc, fresh);
                }
                List<Hidden> parts = new ArrayList<>();
                for(TerminalPlacement side : sides)
                {
                    CloakFill.Area area = side.hiderArea();
                    if(area != null)
                    {
                        Hidden part = CloakFill.hide(level, area);
                        parts.add(part.showing(goTimes(part, master, onTicks), kinds(part, master, level), STEADY, true));
                    }
                }
                fresh.parts = parts;
                fresh.boxes = boxes;
                fresh.stale = false;
                fresh.releaseAt = Long.MIN_VALUE;
                fresh.publishedShown = Float.NaN;
                if(!wasHiding)
                {
                    fresh.since = now;
                }
                cloak = fresh;
            }
            //Switched on again while its blocks were coming back: it goes on from there.
            cloak.fadeStart = Long.MIN_VALUE;
            cloak.shown = switchingOn ? (float)on : STEADY;
            wanted.put(key, cloak);
        }

        //Cloaks no longer on. Without a shader pack their views are asked for again a few ticks before their blocks come
        //back, so the switch-off animation has an image (the glass is drawn again once its chunk is rebuilt). With one,
        //their blocks come back the way they went, backwards.
        Iterator<Map.Entry<String, Cloak>> it = CLOAKS.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<String, Cloak> e = it.next();
            Cloak cloak = e.getValue();
            if(wanted.get(e.getKey()) == cloak || wanted.containsKey(e.getKey()) || cloak.parts.isEmpty())
            {
                continue;
            }
            if(views)
            {
                if(cloak.releaseAt == Long.MIN_VALUE)
                {
                    cloak.releaseAt = now + WAKE_TICKS;
                }
                if(now < cloak.releaseAt)
                {
                    wakeViews(cloak, now);
                    wanted.put(e.getKey(), cloak);
                    continue;
                }
            }
            else
            {
                if(cloak.fadeStart == Long.MIN_VALUE)
                {
                    cloak.fadeStart = now;
                    cloak.fadeFrom = Math.min(cloak.shown, onTicks(cloak.master));
                }
                cloak.shown = cloak.fadeFrom - (now - cloak.fadeStart);
                if(cloak.shown >= 0F)
                {
                    wanted.put(e.getKey(), cloak);
                    continue;
                }
            }
            release(mc, cloak);
        }
        CLOAKS.clear();
        CLOAKS.putAll(wanted);
        publish(mc, level);
        CloakMapHider.tick(mc, level);
    }

    /** How long a cloak takes to switch on: the field's walls rising, or the nano wave reaching the far glass, in ticks. */
    private static long onTicks(GlassProjectorBlockEntity master)
    {
        if(master.cloakFieldBox() != null)
        {
            return FIELD_ON_TICKS;
        }
        TerminalPlacement placement = GlassClientState.INSTANCE.getTerminalPlacement(master.screenKey());
        AABB box = placement == null ? null : placement.screenBox();
        if(box == null)
        {
            return 40L;
        }
        Vec3 from = Vec3.atCenterOf(master.getBlockPos());
        double far = 0D;
        for(double x : new double[] { box.minX, box.maxX })
        {
            for(double y : new double[] { box.minY, box.maxY })
            {
                for(double z : new double[] { box.minZ, box.maxZ })
                {
                    far = Math.max(far, from.distanceTo(new Vec3(x, y, z)));
                }
            }
        }
        return (long)Math.ceil((far + 1.4D) / GlassBaseBlockEntity.WAVE_SPEED) + 2L;
    }

    /**
     * When each block of a hidden part goes as the cloak switches on, in ticks: a field's from the ground up as its walls
     * rise, a glass cloak's from the projector out as its nano wave runs.
     */
    private static float[] goTimes(Hidden part, GlassProjectorBlockEntity master, long onTicks)
    {
        float[] at = new float[part.states().length];
        AABB field = master.cloakFieldBox();
        Vec3 from = Vec3.atCenterOf(master.getBlockPos());
        int i = 0;
        for(int y = part.minY(); y < part.minY() + part.sizeY(); y++)
        {
            for(int z = part.minZ(); z < part.minZ() + part.sizeZ(); z++)
            {
                for(int x = part.minX(); x < part.minX() + part.sizeX(); x++, i++)
                {
                    if(field != null)
                    {
                        double up = (y + 0.5D - field.minY) / Math.max(1D, field.maxY - field.minY);
                        at[i] = (float)(Math.clamp(up, 0D, 1D) * (onTicks - 2));
                    }
                    else
                    {
                        at[i] = (float)(Math.sqrt(from.distanceToSqr(x + 0.5D, y + 0.5D, z + 0.5D)) / GlassBaseBlockEntity.WAVE_SPEED);
                    }
                }
            }
        }
        return at;
    }

    /** The cloak's sides that hide something: a field's whole box (one side's view of it), or each side the glass faces. */
    static List<TerminalPlacement> sides(GlassProjectorBlockEntity master, TerminalPlacement placement)
    {
        if(master.cloakFieldBox() != null)
        {
            return List.of(placement.forFace(Direction.SOUTH));
        }
        List<TerminalPlacement> sides = new ArrayList<>();
        for(Direction side : placement.screenSides())
        {
            if(side.getAxis().isHorizontal())
            {
                sides.add(placement.forFace(side));
            }
        }
        return sides;
    }

    private static boolean isInside(Vec3 eye, List<AABB> boxes, boolean wasInside)
    {
        double margin = wasInside ? -INSIDE_MARGIN : INSIDE_MARGIN;
        for(AABB box : boxes)
        {
            if(box.deflate(margin).contains(eye))
            {
                return true;
            }
        }
        return false;
    }

    /** Asks for the views of a cloak about to show its blocks again (its switch-off is drawn with them). */
    private static void wakeViews(Cloak cloak, long now)
    {
        List<Direction> sides = new ArrayList<>();
        if(cloak.master.cloakFieldBox() != null)
        {
            for(Direction side : Direction.Plane.HORIZONTAL)
            {
                sides.add(side);
            }
            sides.add(Direction.UP);
        }
        else
        {
            sides.addAll(cloak.placement.screenSides());
        }
        for(Direction side : sides)
        {
            SpyViewRenderer.INSTANCE.wake(cloak.placement.forFace(side), now + 60L);
        }
    }

    private static void release(Minecraft mc, Cloak cloak)
    {
        for(Hidden part : cloak.parts)
        {
            part.markDirty(mc);
        }
        cloak.parts = List.of();
        cloak.published = List.of();
        cloak.publishedShown = Float.NaN;
    }

    /**
     * Hands the chunk builders the hidden parts now in use, and has the chunks that changed rebuilt. A cloak switching on
     * or off with a shader pack moves on in steps of {@link #STEP_TICKS}, with nano pixels where its blocks go or come.
     */
    private static void publish(Minecraft mc, ClientLevel level)
    {
        List<Hidden> now = new ArrayList<>();
        for(Cloak cloak : CLOAKS.values())
        {
            float shown = cloak.shown == STEADY ? STEADY : (float)Math.floor(cloak.shown / STEP_TICKS) * STEP_TICKS;
            if(cloak.published.size() != cloak.parts.size() || Float.compare(shown, cloak.publishedShown) != 0)
            {
                float before = cloak.publishedShown;
                List<Hidden> published = new ArrayList<>();
                boolean growing = cloak.fadeStart == Long.MIN_VALUE;
                for(Hidden part : cloak.parts)
                {
                    published.add(part.showing(part.at(), part.kind(), shown, growing));
                    if(!Float.isNaN(before))
                    {
                        stream(level, part, cloak.master, before, shown, growing);
                    }
                }
                cloak.published = published;
                cloak.publishedShown = shown;
            }
            now.addAll(cloak.published);
        }
        List<AABB> walls = new ArrayList<>();
        for(Cloak cloak : CLOAKS.values())
        {
            if(cloak.master.cloakFieldBox() != null && cloak.shown == STEADY)
            {
                for(Hidden part : cloak.parts)
                {
                    walls.add(part.hidden());
                }
            }
        }
        fieldWalls = walls.toArray(new AABB[0]);
        Hidden[] before = active;
        if(sameParts(before, now))
        {
            return;
        }
        active = now.toArray(new Hidden[0]);
        for(Hidden part : now)
        {
            boolean old = false;
            for(Hidden was : before)
            {
                old |= was == part;
            }
            if(!old)
            {
                part.markDirty(mc);
            }
        }
    }

    /**
     * The blocks of a hidden part that went (or came back) between two steps of its animation send nano pixels streaming
     * into the projector (or the projector sends them out to them).
     */
    private static void stream(ClientLevel level, Hidden part, GlassProjectorBlockEntity master, float from, float to, boolean growing)
    {
        float[] at = part.at();
        byte[] kind = part.kind();
        if(at == null || kind == null || from == STEADY || to == STEADY || from == to)
        {
            return; //switched on or off at once, or no step taken
        }
        float low = Math.min(from, to), high = Math.max(from, to);
        List<BlockPos> changed = new ArrayList<>();
        int i = 0;
        for(int y = part.minY(); y < part.minY() + part.sizeY(); y++)
        {
            for(int z = part.minZ(); z < part.minZ() + part.sizeZ(); z++)
            {
                for(int x = part.minX(); x < part.minX() + part.sizeX(); x++, i++)
                {
                    if(at[i] > low && at[i] <= high && kind[i] == BUILT && part.states()[i] != null)
                    {
                        changed.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        if(changed.isEmpty())
        {
            return;
        }
        Vec3 projector = Vec3.atCenterOf(master.getBlockPos());
        int pixels = Math.min(MAX_PIXELS, changed.size() * 2);
        for(int n = 0; n < pixels; n++)
        {
            BlockPos block = changed.get(RANDOM.nextInt(changed.size()));
            Vec3 there = new Vec3(block.getX() + RANDOM.nextDouble(), block.getY() + RANDOM.nextDouble(), block.getZ() + RANDOM.nextDouble());
            Vec3 near = projector.add((RANDOM.nextDouble() - 0.5D) * 0.4D, (RANDOM.nextDouble() - 0.5D) * 0.4D, (RANDOM.nextDouble() - 0.5D) * 0.4D);
            //A nano particle flies from (where it is told + its offset) to where it is told.
            Vec3 goal = growing ? near : there;
            Vec3 offset = (growing ? there : near).subtract(goal);
            level.addAlwaysVisibleParticle(GlassRegistry.NANO_PARTICLE, true, goal.x, goal.y, goal.z, offset.x, offset.y, offset.z);
        }
    }

    /**
     * Per block of a hidden part, what shows as nano pixels at the front of its animation: what stands there in the real
     * world, and a field's walls and roof (its outermost blocks all round and its top layer), so they rise like the walls
     * of the field do without a shader pack, and close over it.
     */
    private static byte[] kinds(Hidden part, GlassProjectorBlockEntity master, ClientLevel level)
    {
        byte[] kind = new byte[part.states().length];
        AABB field = master.cloakFieldBox();
        int x0 = 0, x1 = -1, y0 = 0, y1 = -1, z0 = 0, z1 = -1;
        if(field != null)
        {
            x0 = (int)Math.floor(field.minX + 1.0E-4D);
            x1 = (int)Math.ceil(field.maxX - 1.0E-4D) - 1;
            y0 = (int)Math.floor(field.minY + 1.0E-4D);
            y1 = (int)Math.ceil(field.maxY - 1.0E-4D) - 1;
            z0 = (int)Math.floor(field.minZ + 1.0E-4D);
            z1 = (int)Math.ceil(field.maxZ - 1.0E-4D) - 1;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for(int y = part.minY(); y < part.minY() + part.sizeY(); y++)
        {
            for(int z = part.minZ(); z < part.minZ() + part.sizeZ(); z++)
            {
                for(int x = part.minX(); x < part.minX() + part.sizeX(); x++, i++)
                {
                    boolean inField = x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
                    if(inField && (x == x0 || x == x1 || z == z0 || z == z1 || y == y1))
                    {
                        kind[i] = WALL;
                    }
                    else if(!level.getBlockState(pos.set(x, y, z)).isAir())
                    {
                        kind[i] = BUILT;
                    }
                }
            }
        }
        return kind;
    }

    private static boolean sameParts(Hidden[] before, List<Hidden> now)
    {
        if(before.length != now.size())
        {
            return false;
        }
        for(int i = 0; i < before.length; i++)
        {
            if(before[i] != now.get(i))
            {
                return false;
            }
        }
        return true;
    }

    public static void clear()
    {
        CLOAKS.clear();
        active = new Hidden[0];
        fieldWalls = new AABB[0];
        CloakWall.clear();
        CloakMapHider.clear();
    }
}
