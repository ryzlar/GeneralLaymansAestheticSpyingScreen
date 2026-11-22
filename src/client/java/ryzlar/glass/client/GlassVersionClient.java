package ryzlar.glass.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import ryzlar.glass.network.GlassVersionCheck;

/**
 * The client's side of {@link GlassVersionCheck}: it tells servers which protocol it speaks, and once in a world,
 * tells the player if the server's GLASS is older than theirs (it can still be played on, but holo cube simulations
 * will not line up).
 */
public final class GlassVersionClient
{
    /** How long after joining to look (the server's channels are known by then), in ticks; -1 when done. */
    private static final int CHECK_AFTER = 40;
    private static int checkIn = -1;

    private GlassVersionClient() {}

    public static void init()
    {
        //Registering a receiver is what tells servers this client speaks the protocol.
        ClientConfigurationNetworking.registerGlobalReceiver(GlassVersionCheck.ClientSpeaks.TYPE, (payload, context) -> {});
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> checkIn = CHECK_AFTER);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> checkIn = -1);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if(checkIn < 0 || --checkIn > 0)
            {
                return;
            }
            checkIn = -1;
            if(client.player != null && client.getConnection() != null && !ClientPlayNetworking.canSend(GlassVersionCheck.ServerSpeaks.TYPE))
            {
                client.player.displayClientMessage(Component.translatable("glass.msg.serverOlder", GlassVersionCheck.version())
                        .withStyle(ChatFormatting.GOLD), false);
            }
        });
    }
}
