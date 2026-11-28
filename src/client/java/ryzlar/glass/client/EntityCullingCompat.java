package ryzlar.glass.client;

import java.lang.reflect.Field;
import net.fabricmc.loader.api.FabricLoader;
import ryzlar.glass.Glass;

/**
 * Entity Culling works out, from the player's own eye, which mobs and block entities are behind blocks, and skips
 * drawing them. A screen's or a cloak's view draws the world again from somewhere else (the terminal, or round the
 * cloak), where those mobs may well be in sight: while GLASS draws its views, Entity Culling's own switches turn its
 * culling off, and they are set back straight after. Found by reflection: GLASS does not need Entity Culling.
 */
public final class EntityCullingCompat
{
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("entityculling");
    private static Object config;
    private static Field skipEntities;
    private static Field skipBlockEntities;
    private static boolean failed;
    private static boolean savedEntities, savedBlockEntities, paused;

    private EntityCullingCompat() {}

    private static boolean ready()
    {
        if(!LOADED || failed)
        {
            return false;
        }
        if(config != null)
        {
            return true;
        }
        try
        {
            Class<?> base = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
            Object instance = base.getField("instance").get(null);
            if(instance == null)
            {
                return false; //not set up yet: try again next frame
            }
            Object found = base.getField("config").get(instance);
            skipEntities = found.getClass().getField("skipEntityCulling");
            skipBlockEntities = found.getClass().getField("skipBlockEntityCulling");
            config = found;
            return true;
        }
        catch(ReflectiveOperationException | RuntimeException e)
        {
            failed = true;
            Glass.LOGGER.warn("GLASS could not find Entity Culling's switches: mobs may be missing from screens and cloaks", e);
            return false;
        }
    }

    /** Before GLASS draws its views: Entity Culling's culling off (what the player's eye cannot see, a view may). */
    public static void pause()
    {
        if(paused || !ready())
        {
            return;
        }
        try
        {
            savedEntities = skipEntities.getBoolean(config);
            savedBlockEntities = skipBlockEntities.getBoolean(config);
            skipEntities.setBoolean(config, true);
            skipBlockEntities.setBoolean(config, true);
            paused = true;
        }
        catch(IllegalAccessException e)
        {
            failed = true;
        }
    }

    /** After GLASS drew its views: Entity Culling's switches as they were. */
    public static void resume()
    {
        if(!paused)
        {
            return;
        }
        paused = false;
        try
        {
            skipEntities.setBoolean(config, savedEntities);
            skipBlockEntities.setBoolean(config, savedBlockEntities);
        }
        catch(IllegalAccessException e)
        {
            failed = true;
        }
    }
}
