package ryzlar.glass.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ryzlar.glass.GlassRegistry;
import ryzlar.glass.blockentity.GlassProjectorBlockEntity;
import ryzlar.glass.client.GlassClientState;
import ryzlar.glass.client.TerminalPlacement;

/**
 * What map mods (Xaero's Minimap) are to see where a cloak hides something: the same made-up ground the world drawing
 * gets ({@link CloakFill#hide}), for every cloak that is on, glass cloaks drawn with views included (those hide nothing
 * from the world drawing, see {@link CloakHider}). A map reads the blocks themselves, so without this a cloaked build
 * showed on it. Seen from inside the cloak, the base shows on the map as it does in the world.
 * <p>
 * Switching on, the build goes from the map in a wave out from the projector, with a front of nano pixels; switching
 * off, it comes back the same way. While that happens the map is asked to redraw as fast as it can
 * ({@link #hurry}), as it otherwise takes a second or two to get round to it.
 */
public final class CloakMapHider
{
    /** Only kept with a map mod that reads it installed (it costs a made-up ground per glass cloak otherwise unneeded). */
    private static final boolean NEEDED = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaerominimap");
    private static final BlockState NANO = GlassRegistry.NANO_VEIL.defaultBlockState();
    /** How long the wave takes to cross the hidden part, and how wide its nano front is, in ticks. */
    private static final float SPREAD_TICKS = 30F, FRONT_TICKS = 6F;
    /** How long after a change the map keeps being hurried (it swaps in a new picture once it has gone round). */
    private static final long HURRY_AFTER_TICKS = 30L;

    /** One hidden part on the map: the made-up ground, and per column when the wave reaches it (ticks). */
    private record Part(CloakHider.Hidden hidden, float[] at) {}

    /** A part as the map reads it: how far the wave has got ({@link Float#MAX_VALUE}: all hidden). */
    private record Shown(CloakHider.Hidden hidden, float[] at, float shown) {}

    private static final class Cloak
    {
        GlassProjectorBlockEntity master;
        List<AABB> boxes = List.of();
        List<Part> parts = List.of();
        boolean stale;
        /** Switching off: the game time its build started coming back, and how far the wave had got then. */
        long offStart = Long.MIN_VALUE;
        float offFrom;
        float shown;
    }

    private static final Map<String, Cloak> CLOAKS = new HashMap<>();
    /** What the map mods read: replaced as a whole, never changed. */
    private static volatile Shown[] active = new Shown[0];
    private static volatile long hurryUntil = Long.MIN_VALUE;
    private static volatile long gameTime;
    private static Map<String, List<Part>> lastParts = Map.of();

    private CloakMapHider() {}

    /** The block a map is to see here instead of the real one, or null. */
    @Nullable
    public static BlockState state(int x, int y, int z)
    {
        for(Shown part : active)
        {
            CloakHider.Hidden hidden = part.hidden;
            int i = hidden.index(x, y, z);
            if(i < 0)
            {
                continue;
            }
            float at = part.at[i % (hidden.sizeX() * hidden.sizeZ())];
            if(at > part.shown)
            {
                continue; //the wave has not got here
            }
            if(part.shown != Float.MAX_VALUE && part.shown - at < FRONT_TICKS)
            {
                return NANO;
            }
            if(hidden.states()[i] != null)
            {
                return hidden.states()[i];
            }
        }
        return null;
    }

    public static boolean hidesAnything()
    {
        return active.length > 0;
    }

    /** True while a cloak's map picture changes: the map is to redraw as fast as it can. */
    public static boolean hurry()
    {
        return gameTime < hurryUntil;
    }

    static void blockChanged(BlockPos pos)
    {
        Vec3 centre = Vec3.atCenterOf(pos);
        for(Cloak cloak : CLOAKS.values())
        {
            for(Part part : cloak.parts)
            {
                if(part.hidden.looked().contains(centre))
                {
                    cloak.stale = true;
                }
            }
        }
    }

    static void tick(Minecraft mc, ClientLevel level)
    {
        if(!NEEDED)
        {
            return;
        }
        long now = level.getGameTime();
        gameTime = now;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Map<String, Cloak> wanted = new HashMap<>();
        for(GlassProjectorBlockEntity master : GlassClientState.INSTANCE.loadedProjectors)
        {
            String key = master.screenKey();
            if(master.isRemoved() || master.getLevel() != level || !master.active || !master.cloak || key.isEmpty() || !master.channel.equals(key))
            {
                continue;
            }
            TerminalPlacement placement = GlassClientState.INSTANCE.getTerminalPlacement(key);
            if(placement == null || !placement.cloak)
            {
                continue;
            }
            List<TerminalPlacement> sides = CloakHider.sides(master, placement);
            List<AABB> boxes = new ArrayList<>();
            for(TerminalPlacement side : sides)
            {
                boxes.add(side.hiddenPart());
            }
            if(boxes.stream().anyMatch(box -> box.contains(eye)))
            {
                //Inside it the base shows on the map, as it does in the world.
                CLOAKS.remove(key);
                continue;
            }
            Cloak cloak = CLOAKS.get(key);
            if(cloak == null || cloak.master != master || cloak.stale || !cloak.boxes.equals(boxes))
            {
                Cloak fresh = new Cloak();
                fresh.master = master;
                fresh.boxes = boxes;
                List<Part> parts = new ArrayList<>();
                for(TerminalPlacement side : sides)
                {
                    CloakFill.Area area = side.hiderArea();
                    if(area != null)
                    {
                        CloakHider.Hidden hidden = CloakFill.hide(level, area);
                        parts.add(new Part(hidden, goTimes(hidden, master.getBlockPos())));
                    }
                }
                fresh.parts = parts;
                cloak = fresh;
            }
            cloak.offStart = Long.MIN_VALUE;
            float on = now - master.onStart;
            cloak.shown = on >= SPREAD_TICKS + FRONT_TICKS ? Float.MAX_VALUE : Math.max(on, 0F);
            wanted.put(key, cloak);
        }

        //Cloaks no longer on: their build comes back on the map the way it went.
        Iterator<Map.Entry<String, Cloak>> it = CLOAKS.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<String, Cloak> e = it.next();
            Cloak cloak = e.getValue();
            if(wanted.containsKey(e.getKey()) || cloak.parts.isEmpty())
            {
                continue;
            }
            if(cloak.offStart == Long.MIN_VALUE)
            {
                cloak.offStart = now;
                cloak.offFrom = Math.min(cloak.shown, SPREAD_TICKS + FRONT_TICKS);
            }
            cloak.shown = cloak.offFrom - (now - cloak.offStart);
            if(cloak.shown > -FRONT_TICKS)
            {
                wanted.put(e.getKey(), cloak);
            }
        }

        List<Shown> all = new ArrayList<>();
        boolean moving = false;
        for(Cloak cloak : wanted.values())
        {
            moving |= cloak.shown != Float.MAX_VALUE;
            for(Part part : cloak.parts)
            {
                all.add(new Shown(part.hidden, part.at, cloak.shown));
            }
        }
        boolean changed = !sameParts(wanted) || moving;
        CLOAKS.clear();
        CLOAKS.putAll(wanted);
        active = all.toArray(new Shown[0]);
        if(changed)
        {
            hurryUntil = now + HURRY_AFTER_TICKS;
        }
    }

    /** True if the same cloaks with the same parts are shown as last tick (nothing for the map to catch up on). */
    private static boolean sameParts(Map<String, Cloak> wanted)
    {
        Map<String, List<Part>> now = new HashMap<>();
        for(Map.Entry<String, Cloak> e : wanted.entrySet())
        {
            now.put(e.getKey(), e.getValue().parts);
        }
        boolean same = now.equals(lastParts);
        lastParts = now;
        return same;
    }

    /**
     * Per column of the part, when the wave out from the projector reaches it: from its nearest column (at once) to its
     * furthest (after {@link #SPREAD_TICKS}), as the projector may stand some way off.
     */
    private static float[] goTimes(CloakHider.Hidden hidden, BlockPos projector)
    {
        int sx = hidden.sizeX(), sz = hidden.sizeZ();
        float[] at = new float[sx * sz];
        float near = Float.MAX_VALUE, far = 0F;
        for(int dz = 0; dz < sz; dz++)
        {
            for(int dx = 0; dx < sx; dx++)
            {
                double x = hidden.minX() + dx - projector.getX();
                double z = hidden.minZ() + dz - projector.getZ();
                float d = (float)Math.sqrt(x * x + z * z);
                at[dz * sx + dx] = d;
                near = Math.min(near, d);
                far = Math.max(far, d);
            }
        }
        for(int i = 0; i < at.length; i++)
        {
            at[i] = far <= near ? 0F : (at[i] - near) / (far - near) * SPREAD_TICKS;
        }
        return at;
    }

    static void clear()
    {
        CLOAKS.clear();
        lastParts = Map.of();
        active = new Shown[0];
    }
}
