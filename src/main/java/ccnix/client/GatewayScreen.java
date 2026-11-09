package ccnix.client;

import ccnix.gateway.GatewayBlock;
import ccnix.gateway.GatewayFilter;
import ccnix.gateway.GatewayPoll;
import ccnix.gateway.GatewayScreenData;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A gateway block's dialog, drawn as the game's own container panels are: its state, what it is and has heard (live:
 * it asks again each second, and the frame counts show their rate), and, as a switch's trunk port has, its VLANs and
 * native VLAN, which its owner or an op may change. The pointer over a row says more. It closes, as a container does,
 * when its player walks away or the block goes.
 */
public final class GatewayScreen extends Screen {
    private static final int W = 248, PAD = 8, ROW = 11, VALUE_X = 92, TIP_WIDTH = 220, POLL_TICKS = 20;
    // the game's container colours: panel, its light and dark edges, label text, quieter text
    private static final int PANEL = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, DARK = 0xFF555555, EDGE = 0xFF000000;
    private static final int TEXT = 0x404040, FAINT = 0x7A7A7A, GOOD = 0x1F7A1F, BAD = 0xA02020;
    private static final String[] COUNTS = { "Frames in", "Frames out", "From other blocks", "Filtered" };
    private static final String[] COUNTS_MORE = {
        "Frames heard on this block's cable that went to the gateway",
        "Frames this block put on its cable",
        "Of those out, frames another block heard and this one carried on (the exchange, a VLAN)",
        "Frames dropped here: a VLAN this block doesn't carry, or one tagged for its native VLAN",
    };

    private GatewayScreenData data;
    private String message;
    private long[] lastCounts;
    private long lastAt;
    private final double[] rates = new double[COUNTS.length];
    private boolean haveRates;
    private EditBox vlans, nativeVlan;
    private String shownVlans, shownNative;             // the server's last; a box still showing it takes the next
    private int left, top, panelHeight, stateY, rowsY, rule, vlansY, nativeY, messageY;
    private int ticks;

    GatewayScreen(GatewayScreenData data) {
        super(Component.literal(data.title()));
        this.data = data;
        message = data.message();
        takeCounts(data);
    }

    BlockPos pos() {
        return data.pos();
    }

    /** Newer stats, or the answer to a change: the rows and counts redrawn, the boxes kept if edited meanwhile. */
    void update(GatewayScreenData d) {
        takeCounts(d);
        var oldVlans = shownVlans;
        var oldNative = shownNative;
        String typedVlans = vlans.getValue(), typedNative = nativeVlan.getValue();
        boolean reshaped = d.rows().size() != data.rows().size() || d.counts().size() != data.counts().size()
            || d.canEdit() != data.canEdit();
        data = d;
        if (!d.refresh()) message = d.message();
        if (reshaped) rebuildWidgets();                 // (init: the boxes take the server's values)
        vlans.setValue(typedVlans.equals(oldVlans) ? d.vlans() : typedVlans);
        nativeVlan.setValue(typedNative.equals(oldNative) ? d.nativeVlan() : typedNative);
        shownVlans = d.vlans();
        shownNative = d.nativeVlan();
    }

    /** The counts' rates since the last ones. */
    private void takeCounts(GatewayScreenData d) {
        long now = Util.getMillis();
        var c = d.counts();
        if (c.size() != COUNTS.length) {
            lastCounts = null;
            haveRates = false;
            return;
        }
        if (lastCounts != null && now > lastAt) {
            for (int i = 0; i < COUNTS.length; i++) rates[i] = Math.max(0, (c.get(i) - lastCounts[i]) * 1000.0 / (now - lastAt));
            haveRates = true;
        }
        lastCounts = new long[COUNTS.length];
        for (int i = 0; i < COUNTS.length; i++) lastCounts[i] = c.get(i);
        lastAt = now;
    }

    @Override
    protected void init() {
        int rows = data.counts().size() + data.rows().size();
        // title, where, state; the rows; a rule; the VLANs' label and box; the native VLAN; a message; the buttons
        panelHeight = PAD + 24 + 14 + rows * ROW + 2 + 6 + 12 + 22 + 24 + 12 + 20 + PAD;
        left = (width - W) / 2;
        top = Math.max(4, (height - panelHeight) / 2);
        int y = top + PAD + 24;
        stateY = y;
        rowsY = y += 14;
        rule = y += rows * ROW + 2;
        vlansY = y += 6;
        y += 12;

        boolean edit = data.canEdit();
        String locked = edit ? "" : "\nOnly its owner, or an op, can change it.";
        vlans = addRenderableWidget(new EditBox(font, left + PAD, y, W - 2 * PAD, 18, Component.literal("VLANs")));
        vlans.setMaxLength(200);
        vlans.setValue(data.vlans());
        vlans.setEditable(edit);
        vlans.setTooltip(Tooltip.create(Component.literal("The VLANs this block carries: \"1-4094\" all, \"2-4094\" a"
            + " trunk without the exchange, \"1,10,20-29\". VLAN 1 is the exchange." + locked)));
        nativeY = y += 22;
        nativeVlan = addRenderableWidget(new EditBox(font, left + W - PAD - 60, y, 60, 18, Component.literal("Native VLAN")));
        nativeVlan.setMaxLength(8);
        nativeVlan.setValue(data.nativeVlan());
        nativeVlan.setEditable(edit);
        nativeVlan.setTooltip(Tooltip.create(Component.literal("The VLAN its cable's untagged frames are on: one of its"
            + " VLANs, or \"none\" (every VLAN tagged, as back.N)." + locked)));
        shownVlans = data.vlans();
        shownNative = data.nativeVlan();
        messageY = y += 24;
        y += 12;

        int half = (W - 2 * PAD - 4) / 2;
        if (edit) {
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(left + PAD, y, half, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(edit ? left + PAD + half + 4 : left + (W - half) / 2, y, half, 20).build());
    }

    private void save() {
        PacketDistributor.sendToServer(new GatewayFilter(data.pos(), vlans.getValue().trim(), nativeVlan.getValue().trim()));
    }

    @Override
    public void tick() {
        super.tick();
        var mc = minecraft;
        if (mc == null || mc.player == null || mc.level == null) return;
        if (mc.player.distanceToSqr(Vec3.atCenterOf(data.pos())) > 64
            || !(mc.level.getBlockState(data.pos()).getBlock() instanceof GatewayBlock)) {
            onClose();
            return;
        }
        if (++ticks % POLL_TICKS == 0) PacketDistributor.sendToServer(new GatewayPoll(data.pos()));
    }

    /** Enter in a box saves, as Done would close. */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && data.canEdit()
            && (vlans.isFocused() || nativeVlan.isFocused())) {
            save();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        panel(g, left, top, W, panelHeight);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        centred(g, title.getString(), top + PAD, TEXT);
        centred(g, data.subtitle(), top + PAD + 12, FAINT);

        // its state: a lamp, and what it means
        int lamp = switch (data.state()) {
            case GatewayScreenData.WORKING -> 0xFF3CC83C;
            case GatewayScreenData.STANDBY -> 0xFFE8A82A;
            default -> 0xFFD83A3A;
        };
        g.fill(left + PAD, stateY, left + PAD + 7, stateY + 7, 0xFF373737);
        g.fill(left + PAD + 1, stateY + 1, left + PAD + 6, stateY + 6, lamp);
        g.drawString(font, data.stateText(), left + PAD + 11, stateY, TEXT, false);

        List<FormattedCharSequence> tip = null;
        int y = rowsY;
        var counts = data.counts();
        for (int i = 0; i < counts.size(); i++) {
            String value = String.format("%,d", counts.get(i)) + (haveRates && rates[i] >= 0.05 ? "  " + rate(rates[i]) : "");
            var t = row(g, COUNTS[i], value, COUNTS_MORE[i], y, mouseX, mouseY);
            if (t != null) tip = t;
            y += ROW;
        }
        for (var r : data.rows()) {
            var t = row(g, r.label(), r.value(), r.more(), y, mouseX, mouseY);
            if (t != null) tip = t;
            y += ROW;
        }

        // engraved, as the game's panels divide
        g.fill(left + PAD, rule, left + W - PAD, rule + 1, 0xFF8B8B8B);
        g.fill(left + PAD, rule + 1, left + W - PAD, rule + 2, LIGHT);
        g.drawString(font, "VLANs it carries", left + PAD, vlansY, TEXT, false);
        g.drawString(font, "Native (untagged) VLAN", left + PAD, nativeY + 5, TEXT, false);
        if (!data.canEdit()) {
            g.drawString(font, "Only its owner, or an op, can change its VLANs", left + PAD, messageY, FAINT, false);
        } else if (!message.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(message, W - 2 * PAD), left + PAD, messageY,
                message.equals("Saved") ? GOOD : BAD, false);
        }
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    /** A label and its value (cut to fit); what to say if the pointer is over it, else null. */
    private List<FormattedCharSequence> row(GuiGraphics g, String label, String value, String more, int y, int mouseX, int mouseY) {
        int room = W - PAD - VALUE_X;
        String shown = value;
        if (font.width(value) > room) shown = font.plainSubstrByWidth(value, room - font.width("...")) + "...";
        g.drawString(font, label, left + PAD, y, FAINT, false);
        g.drawString(font, shown, left + VALUE_X, y, TEXT, false);
        boolean over = mouseX >= left + PAD && mouseX < left + W - PAD && mouseY >= y - 1 && mouseY < y + ROW - 1;
        if (!over) return null;
        var lines = new ArrayList<FormattedCharSequence>();
        if (!shown.equals(value)) lines.addAll(font.split(FormattedText.of(value), TIP_WIDTH));
        for (var line : more.split("\n")) if (!line.isEmpty()) lines.addAll(font.split(FormattedText.of(line), TIP_WIDTH));
        return lines.isEmpty() ? null : lines;
    }

    private static String rate(double perSecond) {
        return "(" + (perSecond < 10 ? String.format("%.1f", perSecond) : String.format("%,d", Math.round(perSecond))) + "/s)";
    }

    private void centred(GuiGraphics g, String s, int y, int colour) {
        g.drawString(font, s, left + (W - font.width(s)) / 2, y, colour, false);
    }

    /** A panel as the game's containers have: a dark outline with its corners cut, light top and left, dark bottom and right. */
    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        int r = x + w, b = y + h;
        g.fill(x + 2, y, r - 2, y + 1, EDGE);
        g.fill(x + 2, b - 1, r - 2, b, EDGE);
        g.fill(x, y + 2, x + 1, b - 2, EDGE);
        g.fill(r - 1, y + 2, r, b - 2, EDGE);
        g.fill(x + 1, y + 1, x + 2, y + 2, EDGE);
        g.fill(r - 2, y + 1, r - 1, y + 2, EDGE);
        g.fill(x + 1, b - 2, x + 2, b - 1, EDGE);
        g.fill(r - 2, b - 2, r - 1, b - 1, EDGE);
        g.fill(x + 1, y + 2, r - 1, b - 2, PANEL);
        g.fill(x + 2, y + 1, r - 2, y + 2, PANEL);
        g.fill(x + 2, b - 2, r - 2, b - 1, PANEL);
        g.fill(x + 2, y + 1, r - 3, y + 3, LIGHT);
        g.fill(x + 1, y + 2, x + 3, b - 3, LIGHT);
        g.fill(x + 3, b - 3, r - 2, b - 1, DARK);
        g.fill(r - 3, y + 3, r - 1, b - 2, DARK);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
