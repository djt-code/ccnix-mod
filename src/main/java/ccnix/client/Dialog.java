package ccnix.client;

import ccnix.gateway.DialogRow;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the mod's dialogs share: a panel drawn as the game's containers are, rows of a label and a value (the label
 * column as wide as its widest label, a value wrapping onto as many lines as it needs, more said on the pointer), and
 * the way they behave. While no box is being typed in, the player can still walk (a click anywhere but in a box leaves
 * the box); a dialog closes, as a container does, when they're more than 8 blocks from its block or the block goes.
 */
abstract class Dialog extends Screen {
    static final int PAD = 8, ROW = 11, GAP = 8, TIP_WIDTH = 220;
    // the game's container colours: panel, its light and dark edges, label text, quieter text
    static final int PANEL = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, DARK = 0xFF555555, EDGE = 0xFF000000;
    static final int TEXT = 0x404040, FAINT = 0x7A7A7A, GOOD = 0x1F7A1F, BAD = 0xA02020, WARN = 0x8A5A00;

    /** The panel's width, and where it is (layout sets them). */
    final int w;
    int left, top, panelHeight;

    /** A row as shown: its label, its value cut into lines that fit, what the pointer over it says. */
    record Shown(String label, List<FormattedCharSequence> value, String more) {
        int lines() {
            return Math.max(1, value.size());
        }
    }

    Dialog(Component title, int w) {
        super(title);
        this.w = w;
    }

    /** The block it's about. */
    abstract BlockPos pos();

    /** Whether the block it's about is still the kind it was. */
    abstract boolean stillThere(BlockState state);

    /** Where the value column starts, from the panel's left: past the widest of these labels. */
    int valueColumn(List<String> labels) {
        int widest = 0;
        for (var l : labels) widest = Math.max(widest, font.width(l));
        return PAD + widest + GAP;
    }

    /** A row with its value cut to fit the column at valueX. */
    Shown shown(String label, String value, String more, int valueX) {
        return new Shown(label, font.split(FormattedText.of(value), w - PAD - valueX), more);
    }

    Shown shown(DialogRow r, int valueX) {
        return shown(r.label(), r.value(), r.more(), valueX);
    }

    /** A row drawn at y; what to say if the pointer is over it, else null. */
    List<FormattedCharSequence> drawRow(GuiGraphics g, Shown s, int valueX, int y, int mouseX, int mouseY) {
        g.drawString(font, s.label(), left + PAD, y, FAINT, false);
        int ly = y;
        for (var line : s.value()) {
            g.drawString(font, line, left + valueX, ly, TEXT, false);
            ly += ROW;
        }
        boolean over = mouseX >= left + PAD && mouseX < left + w - PAD && mouseY >= y - 1 && mouseY < y + s.lines() * ROW - 1;
        if (!over) return null;
        var tip = new ArrayList<FormattedCharSequence>();
        for (var line : s.more().split("\n")) if (!line.isEmpty()) tip.addAll(font.split(FormattedText.of(line), TIP_WIDTH));
        return tip.isEmpty() ? null : tip;
    }

    /** A line engraved across the panel, as the game's panels divide. */
    void rule(GuiGraphics g, int y) {
        g.fill(left + PAD, y, left + w - PAD, y + 1, 0xFF8B8B8B);
        g.fill(left + PAD, y + 1, left + w - PAD, y + 2, LIGHT);
    }

    void centred(GuiGraphics g, String s, int y, int colour) {
        g.drawString(font, s, left + (w - font.width(s)) / 2, y, colour, false);
    }

    /** The keys that move the player: still theirs while a dialog is open and no box is being typed in. */
    private static KeyMapping[] movementKeys(Options o) {
        return new KeyMapping[] { o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint };
    }

    boolean typing() {
        return getFocused() instanceof EditBox;
    }

    /**
     * NeoForge gives the movement keys the in-game context, which counts them as let go while any screen is open,
     * whatever they're set to. While a dialog is open they have this one instead: active with no screen, or with a
     * dialog open and no box being typed in. Otherwise it's the in-game context's (the controls screen still sees
     * them clash with the same keys).
     */
    private static final IKeyConflictContext WALKING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            var screen = Minecraft.getInstance().screen;
            return screen == null || screen instanceof Dialog d && !d.typing();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this || KeyConflictContext.IN_GAME.conflicts(other);
        }
    };

    /** The movement keys' own contexts, while this has them walking; given back when it closes. */
    private final Map<KeyMapping, IKeyConflictContext> kept = new HashMap<>();

    @Override
    public void added() {
        super.added();
        // (called before init, which is what sets minecraft)
        for (var k : movementKeys(Minecraft.getInstance().options)) {
            if (k.getKeyConflictContext() == WALKING) continue;
            kept.put(k, k.getKeyConflictContext());
            k.setKeyConflictContext(WALKING);
        }
    }

    @Override
    public void removed() {
        kept.forEach(KeyMapping::setKeyConflictContext);
        kept.clear();
        super.removed();
    }

    /**
     * A screen open takes the keyboard, and the game stops reading the movement keys; while no box has the focus,
     * each tick sets them from the keys held, as if no screen were open, so its player can walk (and walk away). A box
     * being typed in has them all let go.
     */
    private void passMovement(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        for (var k : movementKeys(mc.options)) {
            var key = k.getKey();
            k.setDown(!typing() && key.getType() == InputConstants.Type.KEYSYM && key != InputConstants.UNKNOWN
                && InputConstants.isKeyDown(window, key.getValue()));
        }
    }

    /** Whether it's still open: closed if its player has walked away or its block is gone. */
    boolean stillOpen() {
        var mc = minecraft;
        if (mc == null || mc.player == null || mc.level == null) return false;
        passMovement(mc);
        if (mc.player.distanceToSqr(Vec3.atCenterOf(pos())) > 64 || !stillThere(mc.level.getBlockState(pos()))) {
            onClose();
            return false;
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        stillOpen();
    }

    /** With no box focused, a movement key is the player's (tick passes it on), not the dialog's: no button pressed. */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!typing() && minecraft != null) {
            for (var k : movementKeys(minecraft.options)) if (k.matches(key, scan)) return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** A click anywhere but in a box takes the focus off the boxes (a button still does what it does). */
    @Override
    public boolean mouseClicked(double x, double y, int button) {
        boolean used = super.mouseClicked(x, y, button);
        if (!(getFocused() instanceof EditBox box && box.isMouseOver(x, y))) setFocused(null);
        return used;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        panel(g, left, top, w, panelHeight);
    }

    /** A panel as the game's containers have: a dark outline with its corners cut, light top and left, dark bottom and right. */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
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
