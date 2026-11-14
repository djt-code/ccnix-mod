package ccnix.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Esc on a computer's screen goes to the computer, as CC's keys.escape (vi leaves insert mode with it, readline takes
 * it as Alt), instead of closing the screen; Shift+Esc closes it, as Esc alone did. The key goes to the server as CC
 * sends every other key, so only the client needs this. CC's screen classes aren't in its API jar: the screen's input
 * is found by name, and if CC ever renames it, Esc just closes the screen again. On a server without ccnix, Esc closes
 * the screen, as there.
 */
public final class EscapeKey {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SCREEN = "dan200.computercraft.client.gui.AbstractComputerScreen";

    private static boolean looked, hinted;
    private static Field input;             // AbstractComputerScreen.computerInput (a UserComputerInput)
    private static Method keyDown, keyUp;
    private static Object held;             // the input Esc went down on: its key up goes there too

    private EscapeKey() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EscapeKey::pressed);
        NeoForge.EVENT_BUS.addListener(EscapeKey::released);
    }

    private static void pressed(ScreenEvent.KeyPressed.Pre e) {
        if (e.getKeyCode() != GLFW.GLFW_KEY_ESCAPE || Screen.hasShiftDown() || !Remote.hasCcnix()) return;
        var in = inputOf(e.getScreen());
        if (in == null) return;
        try {
            keyDown.invoke(in, GLFW.GLFW_KEY_ESCAPE);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LOG.warn("ccnix: couldn't give Esc to the computer; it closes the screen", ex);
            input = null;
            return;
        }
        held = in;
        e.setCanceled(true);
        if (!hinted) {
            hinted = true;
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("ccnix.escape.hint"), false);
        }
    }

    private static void released(ScreenEvent.KeyReleased.Pre e) {
        if (e.getKeyCode() != GLFW.GLFW_KEY_ESCAPE || held == null) return;
        try {
            keyUp.invoke(held, GLFW.GLFW_KEY_ESCAPE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // (the computer's screen has gone: nothing holds the key)
        }
        held = null;
        e.setCanceled(true);
    }

    /** The screen's computer input, if it's a computer's screen (a computer, turtle or pocket computer's). */
    private static Object inputOf(Screen screen) {
        if (!looked) {
            for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
                if (!c.getName().equals(SCREEN)) continue;
                looked = true;
                try {
                    var f = c.getDeclaredField("computerInput");
                    f.setAccessible(true);
                    keyDown = f.getType().getMethod("keyDown", int.class);
                    keyUp = f.getType().getMethod("keyUp", int.class);
                    input = f;
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    LOG.warn("ccnix: CC's computer screen has no input to give Esc to; Esc closes it", ex);
                }
                break;
            }
        }
        if (input == null || !input.getDeclaringClass().isInstance(screen)) return null;
        try {
            return input.get(screen);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }
}
