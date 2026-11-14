package ccnix.client;

import ccnix.gateway.GatewayPoll;
import net.minecraft.client.Minecraft;

/**
 * The server the client is playing on. A client with ccnix can join a server without it (the payloads are optional),
 * and there ccnix stands aside: its items aren't in the creative tabs, and Esc closes a computer's screen.
 */
public final class Remote {
    private Remote() {
    }

    /** Whether the server has ccnix: its payloads were agreed on joining. */
    public static boolean hasCcnix() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(GatewayPoll.TYPE);
    }
}
