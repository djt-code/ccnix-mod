package ccnix.client;

import ccnix.gateway.GatewayFilter;
import ccnix.gateway.GatewayScreenData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * A gateway block's dialog: what it carries and has heard, and its own filter (untagged, the exchange, and the VLANs
 * it passes), which its owner or an op may change.
 */
public final class GatewayScreen extends Screen {
    private static final int WIDTH = 320, LINE = 10;

    private final GatewayScreenData data;
    private final List<FormattedCharSequence> text = new ArrayList<>();
    private Checkbox untagged;
    private EditBox vlans;
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
        top = Math.max(8, (height - (24 + text.size() * LINE + 110)) / 2);
        controls = top + 24 + text.size() * LINE + 8;

        untagged = addRenderableWidget(Checkbox.builder(Component.literal("Untagged: the exchange (the gateway's network)"), font)
            .pos(left, controls).selected(data.untagged()).build());
        vlans = addRenderableWidget(new EditBox(font, left, controls + 36, WIDTH, 18, Component.literal("VLANs")));
        vlans.setMaxLength(200);
        vlans.setValue(data.vlans());
        untagged.active = data.canEdit();
        vlans.setEditable(data.canEdit());

        int buttons = controls + 64;
        if (data.canEdit()) {
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left, buttons, WIDTH / 2 - 4, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(data.canEdit() ? left + WIDTH / 2 + 4 : left + WIDTH / 4, buttons, WIDTH / 2 - 4, 20).build());
    }

    private void save() {
        PacketDistributor.sendToServer(new GatewayFilter(data.pos(), untagged.selected(), vlans.getValue().trim()));
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
        g.drawString(font, "VLANs it passes: \"1-4094\", \"10,20,100-199\", or empty for none", left, controls + 24, 0xA0A0A0);
        if (!data.canEdit()) {
            g.drawString(font, "Only its owner, or an op, can change its filter", left, controls + 90, 0xA0A0A0);
        } else if (!data.message().isEmpty()) {
            g.drawString(font, data.message(), left, controls + 90, data.message().equals("Saved") ? 0x80FF80 : 0xFF8080);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
