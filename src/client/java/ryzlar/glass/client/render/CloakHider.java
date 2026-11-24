package ryzlar.glass.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ryzlar.glass.blockentity.GlassBaseBlockEntity;
import ryzlar.glass.blockentity.GlassProjectorBlockEntity;
import ryzlar.glass.client.GlassClientState;
import ryzlar.glass.client.TerminalPlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cloak that is on hides its blocks from the world drawing itself: the chunk builder (vanilla's or Sodium's, see
 * RenderSectionRegionMixin and LevelSliceMixin) sees the hidden part as {@link CloakFill} makes it up (the ground carried
 * on from around it, air above it, and the light there), and the entities in it are not drawn. What lies behind it is
 * then simply drawn by the game, which is far cheaper than drawing the world again from the eye for every side of the
 * cloak, and works with shader packs (Iris draws it like any other terrain, shadows included).
 * <p>
 * Only while the cloak is steady, and only for an eye outside it (inside, the base is there as always). Switching on and
 * off, the cloak is drawn with its views as before (the walls rising, the nano wave), and hands over to this once that
 * is done: the views are kept a few ticks longer, until the chunks have been rebuilt without the hidden blocks.
 */
public final class CloakHider
{
    /** How long a cloak's views go on after its blocks are hidden (the chunks around it are rebuilt meanwhile), in ticks. */
    private static final int HANDOVER_TICKS = 10;
    /** How long a cloak switching off asks for its views again before its blocks come back, in ticks. */
    private static final int WAKE_TICKS = 3;
    /** The eye counts as inside a hidden part this far into it (and as outside again only this far out of it), in blocks. */
    private static final double INSIDE_MARGIN = 0.3D;

    /**
     * One hidden part, as the chunk builder sees it: per block in the box from {@code min}, the block in its place and
     * its sky and block light (null and -1: the real ones). {@code hidden}: the part itself (entities in it are not
     * drawn); {@code looked}: what the made-up ground was worked out from (a block changing there rebuilds it).
     */
    public record Hidden(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, BlockState[] states, byte[] sky,
                         byte[] block, AABB hidden, AABB looked)
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

        void markDirty(Minecraft mc)
        {
            //One more all round: the faces and shading of the blocks next to it depend on it too.
            mc.levelRenderer.setBlocksDirty(minX - 1, minY - 1, minZ - 1, minX + sizeX, minY + sizeY, minZ + sizeZ);
        }
    }

    /** What the chunk builders read, from their own threads: replaced as a whole, never changed. */
    private static volatile Hidden[] active = new Hidden[0];

    /** A cloak that hides (or is about to): its hidden parts, and since when. */
    private static final class Cloak
    {
        final GlassProjectorBlockEntity master;
        final TerminalPlacement placement;
        List<Hidden> parts = List.of();
        List<AABB> boxes = List.of();
        boolean stale;
        long since;
        /** Switching off: the game time its blocks come back (its views are asked for again until then). */
        long releaseAt = Long.MIN_VALUE;

        Cloak(GlassProjectorBlockEntity master, TerminalPlacement placement)
        {
            this.master = master;
            this.placement = placement;
        }
    }

    private static final Map<String, Cloak> CLOAKS = new HashMap<>();

    private CloakHider() {}

    // ---- Read by the chunk builders ----

    /** The block the chunk builder is to see here instead of the real one, or null. */
    @Nullable
    public static BlockState state(int x, int y, int z)
    {
        for(Hidden hidden : active)
        {
            int i = hidden.index(x, y, z);
            if(i >= 0 && hidden.states[i] != null)
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
            if(i >= 0)
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
                return true;
            }
        }
        return false;
    }

    // ---- Asked by the glass renderer ----

    /**
     * True if this screen's cloak hides its blocks and has handed over: its glass and views need not be drawn (and its
     * views are let go, which is the whole saving).
     */
    public static boolean isHiding(String key, long gameTime)
    {
        Cloak cloak = CLOAKS.get(key);
        return cloak != null && cloak.releaseAt == Long.MIN_VALUE && !cloak.parts.isEmpty() && gameTime - cloak.since >= HANDOVER_TICKS;
    }

    // ---- Kept up to date every tick ----

    /** A block changed on the client: a cloak whose made-up ground was worked out from it works it out again. */
    public static void blockChanged(BlockPos pos)
    {
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
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Map<String, Cloak> wanted = new HashMap<>();
        for(GlassProjectorBlockEntity master : GlassClientState.INSTANCE.loadedProjectors)
        {
            String key = master.screenKey();
            if(master.isRemoved() || master.getLevel() != level || !master.active || !master.cloak || key.isEmpty() || !master.channel.equals(key))
            {
                continue;
            }
            //Switching on, the views draw the cloak (the walls rising, the nano wave): its blocks are hidden after that.
            //With a shader pack there are no views to draw it with, so at once.
            long on = now - master.onStart;
            if(on >= 0 && on < onTicks(master) && ViewBackend.current().drawsViews())
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
                wanted.put(key, cloak);
                continue;
            }
            if(cloak == null || cloak.master != master || cloak.parts.isEmpty() || cloak.stale || !cloak.boxes.equals(boxes)
                    || cloak.releaseAt != Long.MIN_VALUE)
            {
                Cloak fresh = cloak != null && cloak.master == master ? cloak : new Cloak(master, placement);
                boolean wasHiding = !fresh.parts.isEmpty() && fresh.releaseAt == Long.MIN_VALUE;
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
                        parts.add(CloakFill.hide(level, area));
                    }
                }
                fresh.parts = parts;
                fresh.boxes = boxes;
                fresh.stale = false;
                fresh.releaseAt = Long.MIN_VALUE;
                if(!wasHiding)
                {
                    fresh.since = now;
                }
                cloak = fresh;
            }
            wanted.put(key, cloak);
        }

        //Cloaks no longer on: switching off, their views are asked for again a few ticks before their blocks come back,
        //so the switch-off animation has an image (the glass is drawn again once its chunk is rebuilt).
        Iterator<Map.Entry<String, Cloak>> it = CLOAKS.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<String, Cloak> e = it.next();
            if(wanted.containsKey(e.getKey()) && wanted.get(e.getKey()) == e.getValue())
            {
                continue;
            }
            Cloak cloak = e.getValue();
            if(cloak.parts.isEmpty() || wanted.containsKey(e.getKey()))
            {
                continue;
            }
            if(cloak.releaseAt == Long.MIN_VALUE)
            {
                cloak.releaseAt = now + (ViewBackend.current().drawsViews() ? WAKE_TICKS : 0);
            }
            if(now < cloak.releaseAt)
            {
                wakeViews(cloak, now);
                wanted.put(e.getKey(), cloak);
                continue;
            }
            release(mc, cloak);
        }
        CLOAKS.clear();
        CLOAKS.putAll(wanted);
        publish(mc);
    }

    /** How long a cloak takes to switch on: the field's walls rising, or the nano wave reaching the far glass, in ticks. */
    private static long onTicks(GlassProjectorBlockEntity master)
    {
        if(master.cloakFieldBox() != null)
        {
            return 42L;
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

    /** The cloak's sides that hide something: a field's whole box (one side's view of it), or each side the glass faces. */
    private static List<TerminalPlacement> sides(GlassProjectorBlockEntity master, TerminalPlacement placement)
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
    }

    /** Hands the chunk builders the hidden parts now in use, and has the chunks that changed rebuilt. */
    private static void publish(Minecraft mc)
    {
        List<Hidden> now = new ArrayList<>();
        for(Cloak cloak : CLOAKS.values())
        {
            now.addAll(cloak.parts);
        }
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

    private static boolean sameParts(Hidden[] before, List<Hidden> now)
    {
        if(before.length != now.size())
        {
            return false;
        }
        for(int i = 0; i < before.length; i++)
        {
            if(!Objects.equals(before[i], now.get(i)) || before[i] != now.get(i))
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
    }
}
