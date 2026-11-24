package ryzlar.glass.network;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ryzlar.glass.Glass;

/**
 * Keeps players on a server on the same GLASS as the server, where it matters: both sides work out holo cube
 * simulations (what can be stood on, how big they are), and a client that disagrees with its server is pulled back
 * all the time.
 * <p>
 * Each side tells the other which {@link #PROTOCOL} it speaks by registering a channel named after it. A client that
 * does not (an older GLASS, or none) cannot join a server, with a message saying which version to get. A newer client
 * on an older server can play, and is told the server is older (see the client's GlassVersionClient).
 */
public final class GlassVersionCheck
{
    /**
     * Goes up whenever client and server have to match. 1: 1.0.0 (which had no check). 2: holo cube scans twenty-one
     * blocks across, shown at their real size. 3: the nano veil block and the nano particle (an older client is missing
     * them, and would be turned away with the game's own registry error instead of this message).
     */
    public static final int PROTOCOL = 3;

    /** Registered by clients that speak {@link #PROTOCOL}, in the configuration phase. Never actually sent. */
    public record ClientSpeaks() implements CustomPacketPayload
    {
        public static final Type<ClientSpeaks> TYPE = new Type<>(Glass.id("client_protocol_" + PROTOCOL));
        public static final StreamCodec<ByteBuf, ClientSpeaks> CODEC = StreamCodec.unit(new ClientSpeaks());

        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    /** Registered by servers that speak {@link #PROTOCOL}, in the play phase. Never actually sent. */
    public record ServerSpeaks() implements CustomPacketPayload
    {
        public static final Type<ServerSpeaks> TYPE = new Type<>(Glass.id("server_protocol_" + PROTOCOL));
        public static final StreamCodec<ByteBuf, ServerSpeaks> CODEC = StreamCodec.unit(new ServerSpeaks());

        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return TYPE;
        }
    }

    private GlassVersionCheck() {}

    /** This GLASS's version, as players see it on Modrinth (1.1.0+1.21.8). */
    public static String version()
    {
        return FabricLoader.getInstance().getModContainer(Glass.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    public static void init()
    {
        PayloadTypeRegistry.configurationS2C().register(ClientSpeaks.TYPE, ClientSpeaks.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerSpeaks.TYPE, ServerSpeaks.CODEC);
        //Registering a receiver is what tells clients this server speaks the protocol.
        ServerPlayNetworking.registerGlobalReceiver(ServerSpeaks.TYPE, (payload, context) -> {});
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if(!ServerConfigurationNetworking.canSend(handler, ClientSpeaks.TYPE))
            {
                //Plain text: a client without this GLASS has no translation for it.
                handler.disconnect(Component.literal("This server uses GLASS " + version() + ". Install the same version of GLASS to join: "
                        + "modrinth.com/mod/glass-2"));
            }
        });
    }
}
