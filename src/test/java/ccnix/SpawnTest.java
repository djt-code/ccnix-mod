package ccnix;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SpawnTest {
    @Test
    void aReplacedJdkSaysRestart() {
        // what JDK 21 said after an in-place upgrade (the 2026-11-19 dev server)
        var e = new IOException("Cannot run program \"git\": Failed to exec spawn helper: pid: 282017, exit code: 1,"
                + " error: 0 (none)");
        var why = Spawn.jdkReplaced(e);
        assertNotNull(why);
        assertTrue(why.contains("restart the server"), why);
        // older JDKs' wording
        assertNotNull(Spawn.jdkReplaced(new IOException("error=0, Failed to exec spawn helper: pid: 1, exit value: 1")));
    }

    @Test
    void otherFailuresAreLeftAlone() {
        assertNull(Spawn.jdkReplaced(new IOException("Cannot run program \"git\": error=2, No such file or directory")));
        assertNull(Spawn.jdkReplaced(new IOException((String) null)));
    }
}
