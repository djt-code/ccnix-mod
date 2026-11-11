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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A gateway block's dialog: its state, what it is and has heard (live: it asks again each second, and the frame counts
 * show their rate), and, as a switch's trunk port has, its VLANs and native VLAN, which its owner or an op may change.
 * The rest (the panel, the rows, walking, closing) is Dialog's.
 */
public final class GatewayScreen extends Dialog {
    private static final int W = 248, POLL_TICKS = 20;
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
    private Button saveButton, doneButton;
    private String shownVlans, shownNative;             // the server's last; a box still showing it takes the next
    private int stateY, rowsY, rule, vlansY, nativeY, messageY, valueX;
    private int ticks;

    GatewayScreen(GatewayScreenData data) {
        super(Component.literal(data.title()), W);
        this.data = data;
        message = data.message();
        takeCounts(data);
    }

    @Override
    BlockPos pos() {
        return data.pos();
    }

    @Override
    boolean stillThere(BlockState state) {
        return state.getBlock() instanceof GatewayBlock;
    }

    /**
     * Newer stats, or the answer to a change: the rows and counts redrawn, the panel grown or shrunk to them. A box
     * takes the server's value only when it's the answer to a save, or it isn't being edited (not focused, and still
     * showing the last value): one being typed in is never touched, so its text and cursor stay as they are.
     */
    void update(GatewayScreenData d) {
        takeCounts(d);
        var oldVlans = shownVlans;
        var oldNative = shownNative;
        boolean buttonsChange = d.canEdit() != data.canEdit();
        data = d;
        if (!d.refresh()) message = d.message();
        if (buttonsChange) {
            rebuildWidgets();                           // (init: the boxes take the server's values)
        } else {
            take(vlans, oldVlans, d.vlans(), !d.refresh());
            take(nativeVlan, oldNative, d.nativeVlan(), !d.refresh());
            layout();
        }
        shownVlans = d.vlans();
        shownNative = d.nativeVlan();
    }

    private static void take(EditBox box, String shown, String now, boolean answer) {
        boolean untouched = !box.isFocused() && box.getValue().equals(shown);
        if ((answer || untouched) && !box.getValue().equals(now)) box.setValue(now);
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

    /** The rows as shown: the counts (with their rates), then the server's rows. */
    private List<Shown> shown() {
        var out = new ArrayList<Shown>();
        var counts = data.counts();
        for (int i = 0; i < counts.size(); i++) {
            String value = String.format("%,d", counts.get(i)) + (haveRates && rates[i] >= 0.05 ? "  " + rate(rates[i]) : "");
            out.add(shown(COUNTS[i], value, COUNTS_MORE[i], valueX));
        }
        for (var r : data.rows()) out.add(shown(r, valueX));
        return out;
    }

    /**
     * Where everything goes, the panel as tall as the rows make it. The widgets are moved, not made again, so a box
     * keeps its focus.
     */
    private void layout() {
        var labels = new ArrayList<String>();
        if (data.counts().size() == COUNTS.length) labels.addAll(List.of(COUNTS));
        for (var r : data.rows()) labels.add(r.label());
        valueX = valueColumn(labels);
        int lines = 0;
        for (var s : shown()) lines += s.lines();
        // title, where, state; the rows; a rule; the VLANs' label and box; the native VLAN; a message; the buttons
        panelHeight = PAD + 24 + 14 + lines * ROW + 2 + 6 + 12 + 22 + 24 + 12 + 20 + PAD;
        left = (width - w) / 2;
        top = Math.max(4, (height - panelHeight) / 2);
        int y = top + PAD + 24;
        stateY = y;
        rowsY = y += 14;
        rule = y += lines * ROW + 2;
        vlansY = y += 6;
        y += 12;
        vlans.setPosition(left + PAD, y);
        nativeY = y += 22;
        nativeVlan.setPosition(left + w - PAD - 60, y);
        messageY = y += 24;
        y += 12;
        int half = (w - 2 * PAD - 4) / 2;
        if (saveButton != null) saveButton.setPosition(left + PAD, y);
        doneButton.setPosition(saveButton != null ? left + PAD + half + 4 : left + (w - half) / 2, y);
    }

    @Override
    protected void init() {
        boolean edit = data.canEdit();
        String locked = edit ? "" : "\nOnly its owner, or an op, can change it.";
        vlans = addRenderableWidget(new EditBox(font, 0, 0, w - 2 * PAD, 18, Component.literal("VLANs")));
        vlans.setMaxLength(200);
        vlans.setValue(data.vlans());
        vlans.setEditable(edit);
        vlans.setTooltip(Tooltip.create(Component.literal("The VLANs this block carries: \"1-4094\" all, \"2-4094\" a"
            + " trunk without the exchange, \"1,10,20-29\". VLAN 1 is the exchange." + locked)));
        nativeVlan = addRenderableWidget(new EditBox(font, 0, 0, 60, 18, Component.literal("Native VLAN")));
        nativeVlan.setMaxLength(8);
        nativeVlan.setValue(data.nativeVlan());
        nativeVlan.setEditable(edit);
        nativeVlan.setTooltip(Tooltip.create(Component.literal("The VLAN its cable's untagged frames are on: one of its"
            + " VLANs, or \"none\" (every VLAN tagged, as back.N)." + locked)));
        shownVlans = data.vlans();
        shownNative = data.nativeVlan();

        int half = (w - 2 * PAD - 4) / 2;
        saveButton = edit
            ? addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(0, 0, half, 20).build())
            : null;
        doneButton = addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(0, 0, half, 20).build());
        layout();
    }

    private void save() {
        PacketDistributor.sendToServer(new GatewayFilter(data.pos(), vlans.getValue().trim(), nativeVlan.getValue().trim()));
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && minecraft.screen == this && ++ticks % POLL_TICKS == 0) {
            PacketDistributor.sendToServer(new GatewayPoll(data.pos()));
        }
    }

    /** Enter in a box saves, as Done would close. */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && data.canEdit() && typing()) {
            save();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
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
        for (var s : shown()) {
            var t = drawRow(g, s, valueX, y, mouseX, mouseY);
            if (t != null) tip = t;
            y += s.lines() * ROW;
        }

        rule(g, rule);
        g.drawString(font, "VLANs it carries", left + PAD, vlansY, TEXT, false);
        g.drawString(font, "Native (untagged) VLAN", left + PAD, nativeY + 5, TEXT, false);
        if (!data.canEdit()) {
            g.drawString(font, "Only its owner, or an op, can change its VLANs", left + PAD, messageY, FAINT, false);
        } else if (!message.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(message, w - 2 * PAD), left + PAD, messageY,
                message.equals("Saved") ? GOOD : BAD, false);
        }
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    private static String rate(double perSecond) {
        return "(" + (perSecond < 10 ? String.format("%.1f", perSecond) : String.format("%,d", Math.round(perSecond))) + "/s)";
    }
}
