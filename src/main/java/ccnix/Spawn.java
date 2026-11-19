package ccnix;

import java.io.IOException;
import java.util.Locale;

/** Why the server couldn't start a program (git, the TUN helper), when the reason is the server's own. */
public final class Spawn {
    private Spawn() {
    }

    /**
     * Java starts a program through a helper from the JDK on disk (jspawnhelper).  When the
     * JDK is upgraded under a running server, that helper is the new version and refuses the
     * old JVM, so no program starts until the server restarts.  That case said plainly, or
     * null for any other failure.
     */
    public static String jdkReplaced(IOException e) {
        var m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (!m.contains("spawn helper") && !m.contains("jspawnhelper")) return null;
        return "Java was upgraded under the running server, so it can't start programs until it restarts:"
                + " restart the server";
    }
}
