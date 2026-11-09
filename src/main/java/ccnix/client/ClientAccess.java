package ccnix.client;

import ccnix.gateway.GatewayScreenData;
import net.minecraft.client.Minecraft;

/** What the server's packets do on the client; only ever called there, so a dedicated server never loads the screens. */
public final class ClientAccess {
    private ClientAccess() {
    }

    /** A gateway block's dialog: updated if it's the one open, else opened (a refresh only updates). */
    public static void gateway(GatewayScreenData data) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof GatewayScreen open && open.pos().equals(data.pos())) {
            open.update(data);
        } else if (!data.refresh()) {
            mc.setScreen(new GatewayScreen(data));
        }
    }
}
