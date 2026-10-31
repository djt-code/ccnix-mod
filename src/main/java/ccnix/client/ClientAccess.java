package ccnix.client;

import ccnix.gateway.GatewayScreenData;
import net.minecraft.client.Minecraft;

/** What the server's packets do on the client; only ever called there, so a dedicated server never loads the screens. */
public final class ClientAccess {
    private ClientAccess() {
    }

    public static void openGateway(GatewayScreenData data) {
        Minecraft.getInstance().setScreen(new GatewayScreen(data));
    }
}
