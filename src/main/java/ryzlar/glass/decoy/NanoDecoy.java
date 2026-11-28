package ryzlar.glass.decoy;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import ryzlar.glass.GlassRegistry;

/**
 * The Nano Decoy: a small canister you throw. Where it lands, its lens pops up and it projects a copy of you made of
 * nano cubes that monsters go for instead of you.
 */
public final class NanoDecoy
{
    public static final Item NANO_DECOY = GlassRegistry.registerItem("nano_decoy", properties -> new NanoDecoyItem(properties.stacksTo(4).rarity(Rarity.UNCOMMON)));

    public static final EntityType<NanoDecoyBall> BALL = GlassRegistry.registerEntity("nano_decoy",
            EntityType.Builder.<NanoDecoyBall>of(NanoDecoyBall::new, MobCategory.MISC).sized(0.45F, 0.4F).clientTrackingRange(8).updateInterval(1));

    public static final EntityType<NanoDecoyFigure> FIGURE = GlassRegistry.registerEntity("nano_decoy_figure",
            EntityType.Builder.<NanoDecoyFigure>of(NanoDecoyFigure::new, MobCategory.MISC).sized(0.6F, 1.8F).clientTrackingRange(10));

    private NanoDecoy() {}

    public static void init()
    {
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(FIGURE, NanoDecoyFigure.attributes());
        ItemGroupEvents.modifyEntriesEvent(GlassRegistry.TAB).register(entries -> entries.accept(NANO_DECOY));
    }
}
