package ccnix.client;

import ccnix.gateway.CableScreenData;
import ccnix.gateway.CableSurvey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * What a Network Configurator found on a cable network (CableSurvey): its size, what its modems share, what sits beside
 * them unshared, and the chunks it runs into that aren't loaded, a section each. Taller than the screen, the sections
 * scroll (the mouse wheel). The rest (the panel, the rows, walking, closing) is Dialog's.
 */
public final class CableScreen extends Dialog {
    private static final int W = 280, HEADING = 13, BETWEEN = 5, SCROLL = 3 * ROW;

    private final CableScreenData data;
    private Button doneButton;
    private int valueX, bodyTop, bodyHeight, contentHeight, scroll;

    /** A line of the body as laid out: a heading, a row, or a note (an empty section's), at its y in the content. */
    private record Item(int y, String heading, boolean warn, String more, Shown row, List<FormattedCharSequence> note) {
    }

    private final List<Item> items = new ArrayList<>();

    CableScreen(CableScreenData data) {
        super(Component.literal(data.title()), W);
        this.data = data;
    }

    @Override
    BlockPos pos() {
        return data.pos();
    }

    @Override
    boolean stillThere(BlockState state) {
        return CableSurvey.isCable(state);
    }

    /** The sections laid out, top to bottom; a section with no rows and nothing to say when empty is left out. */
    private void layOutItems() {
        var labels = new ArrayList<String>();
        for (var s : data.sections()) for (var r : s.rows()) labels.add(r.label());
        valueX = Math.min(valueColumn(labels), w / 2);
        items.clear();
        int y = 0;
        for (var s : data.sections()) {
            if (s.rows().isEmpty() && s.empty().isEmpty()) continue;
            if (y > 0) y += BETWEEN;
            if (!s.heading().isEmpty()) {
                items.add(new Item(y, s.heading(), s.warn(), s.more(), null, null));
                y += HEADING;
            }
            if (s.rows().isEmpty()) {
                var note = font.split(FormattedText.of(s.empty()), w - 2 * PAD);
                items.add(new Item(y, null, false, "", null, note));
                y += note.size() * ROW;
            }
            for (var r : s.rows()) {
                var row = shown(r, valueX);
                items.add(new Item(y, null, false, "", row, null));
                y += row.lines() * ROW;
            }
        }
        contentHeight = y;
    }

    @Override
    protected void init() {
        layOutItems();
        int header = PAD + 26, footer = 6 + 20 + PAD;
        bodyHeight = Math.min(contentHeight, height - 8 - header - footer);
        panelHeight = header + bodyHeight + footer;
        left = (width - w) / 2;
        top = Math.max(4, (height - panelHeight) / 2);
        bodyTop = top + header;
        scroll = Math.max(0, Math.min(scroll, contentHeight - bodyHeight));
        int half = (w - 2 * PAD - 4) / 2;
        doneButton = addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(left + (w - half) / 2, bodyTop + bodyHeight + 6, half, 20).build());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (contentHeight <= bodyHeight) return super.mouseScrolled(x, y, dx, dy);
        scroll = Math.max(0, Math.min((int) (scroll - dy * SCROLL), contentHeight - bodyHeight));
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        centred(g, title.getString(), top + PAD, TEXT);
        centred(g, data.subtitle(), top + PAD + 12, FAINT);

        List<FormattedCharSequence> tip = null;
        boolean inBody = mouseY >= bodyTop && mouseY < bodyTop + bodyHeight;
        g.enableScissor(left + PAD, bodyTop, left + w - PAD, bodyTop + bodyHeight);
        for (var it : items) {
            int y = bodyTop + it.y() - scroll;
            if (y + ROW * 4 < bodyTop || y > bodyTop + bodyHeight) continue;
            if (it.heading() != null) {
                g.drawString(font, it.heading(), left + PAD, y + 2, it.warn() ? WARN : TEXT, false);
                if (inBody && mouseY >= y && mouseY < y + HEADING && !it.more().isEmpty()) {
                    tip = font.split(FormattedText.of(it.more()), TIP_WIDTH);
                }
            } else if (it.note() != null) {
                int ny = y;
                for (var line : it.note()) {
                    g.drawString(font, line, left + PAD, ny, FAINT, false);
                    ny += ROW;
                }
            } else {
                var t = drawRow(g, it.row(), valueX, y, mouseX, inBody ? mouseY : -1000);
                if (t != null) tip = t;
            }
        }
        g.disableScissor();

        if (contentHeight > bodyHeight) {
            // a thin bar at the right edge: where the view is in the whole
            int barTop = bodyTop + scroll * bodyHeight / contentHeight;
            int barHeight = Math.max(8, bodyHeight * bodyHeight / contentHeight);
            g.fill(left + w - PAD + 2, barTop, left + w - PAD + 4, Math.min(barTop + barHeight, bodyTop + bodyHeight), 0xFF8B8B8B);
        }
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }
}
