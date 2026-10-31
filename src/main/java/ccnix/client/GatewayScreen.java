package ccnix.client;

import ccnix.gateway.GatewayFilter;
import ccnix.gateway.GatewayScreenData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * A gateway block's dialog: what it carries and has heard, and, as a switch's trunk port has, its VLANs and its native
 * VLAN, which its owner or an op may change.
 */
public final class GatewayScreen extends Screen {
    private static final int WIDTH = 320, LINE = 10, NATIVE_WIDTH = 60;

    private final GatewayScreenData data;
    private final List<FormattedCharSequence> text = new ArrayList<>();
    private EditBox vlans, nativeVlan;
    private int left, top, controls;

    GatewayScreen(GatewayScreenData data) {
        super(Component.literal(data.lines().isEmpty() ? "Network Gateway" : data.lines().get(0)));
        this.data = data;
    }

    @Override
    protected void init() {
        text.clear();
        for (var line : data.lines().subList(Math.min(1, data.lines().size()), data.lines().size())) {
            text.addAll(font.split(FormattedText.of(line), WIDTH));
        }
        left = (width - WIDTH) / 2;
        top = Math.max(8, (height - (24 + text.size() * LINE + 120)) / 2);
        controls = top + 24 + text.size() * LINE + 8;

        vlans = addRenderableWidget(new EditBox(font, left, controls + 12, WIDTH, 18, Component.literal("VLANs")));
        vlans.setMaxLength(200);
        vlans.setValue(data.vlans());
        vlans.setEditable(data.canEdit());
        nativeVlan = addRenderableWidget(new EditBox(font, left + WIDTH - NATIVE_WIDTH, controls + 48, NATIVE_WIDTH, 18,
            Component.literal("Native VLAN")));
        nativeVlan.setMaxLength(8);
        nativeVlan.setValue(data.nativeVlan());
        nativeVlan.setEditable(data.canEdit());

        int buttons = controls + 76;
        if (data.canEdit()) {
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left, buttons, WIDTH / 2 - 4, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(data.canEdit() ? left + WIDTH / 2 + 4 : left + WIDTH / 4, buttons, WIDTH / 2 - 4, 20).build());
    }

    private void save() {
        PacketDistributor.sendToServer(new GatewayFilter(data.pos(), vlans.getValue().trim(), nativeVlan.getValue().trim()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, left, top, 0xFFFFFF);
        int y = top + 16;
        for (var line : text) {
            g.drawString(font, line, left, y, 0xC0C0C0);
            y += LINE;
        }
        g.drawString(font, "VLANs this block carries (1 is the exchange):", left, controls, 0xE0E0E0);
        g.drawString(font, "\"1-4094\" all, \"2-4094\" a trunk without the exchange, \"1,10,20-29\"", left, controls + 33, 0xA0A0A0);
        g.drawString(font, "Native (untagged) VLAN, a number or \"none\":", left, controls + 53, 0xE0E0E0);
        if (!data.canEdit()) {
            g.drawString(font, "Only its owner, or an op, can change its VLANs", left, controls + 102, 0xA0A0A0);
        } else if (!data.message().isEmpty()) {
            g.drawString(font, data.message(), left, controls + 102, data.message().equals("Saved") ? 0x80FF80 : 0xFF8080);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
