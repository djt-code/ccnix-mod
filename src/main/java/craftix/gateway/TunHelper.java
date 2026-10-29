package craftix.gateway;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The helper process that holds the TUN device (helper/craftix-tun.c), so the server itself needs no privileges.
 * Packets cross its stdin and stdout as two bytes of length (big-endian) and the packet. If it exits it is started
 * again, after 1 s, doubling to a minute while it keeps failing.
 */
final class TunHelper {
    private static final Logger LOG = LogUtils.getLogger();

    private final List<String> command;
    private final Consumer<byte[]> in;
    private volatile boolean running;
    private volatile Process process;
    private volatile OutputStream out;
    private volatile String state = "starting";
    private volatile long dropped;
    private Thread thread;
    private String lastProblem;

    /** helper: the program, with any arguments before the device's name. */
    TunHelper(String helper, String device, Consumer<byte[]> in) {
        command = command(helper, device);
        this.in = in;
    }

    static List<String> command(String helper, String device) {
        var command = new ArrayList<>(Arrays.asList(helper.trim().split("\\s+")));
        command.add(device);
        return command;
    }

    List<String> command() {
        return command;
    }

    void start() {
        running = true;
        thread = new Thread(this::loop, "craftix-gateway-tun");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        var p = process;
        if (p != null) p.destroy();
        if (thread != null) thread.interrupt();
    }

    String state() {
        return state + (dropped > 0 ? "; " + dropped + " packets dropped while it wasn't running" : "");
    }

    /** A packet for the TUN device (dropped if the helper isn't running). */
    void write(byte[] packet) {
        var o = out;
        if (o == null) {
            dropped++;
            return;
        }
        synchronized (this) {
            try {
                o.write(packet.length >> 8);
                o.write(packet.length);
                o.write(packet);
                o.flush();
            } catch (IOException e) {
                dropped++;                              // (it's gone: the loop starts it again)
            }
        }
    }

    private void loop() {
        long backoff = 1000;
        var name = String.join(" ", command);
        while (running) {
            long started = System.currentTimeMillis();
            Process p = null;
            try {
                p = new ProcessBuilder(command).start();
            } catch (IOException e) {
                state = "can't start " + name + ": " + e.getMessage();
                if (!Objects.equals(lastProblem, e.getMessage())) LOG.warn("Craftix Gateway: {}", state);
                lastProblem = e.getMessage();
            }
            if (p != null) {
                lastProblem = null;
                process = p;
                out = new BufferedOutputStream(p.getOutputStream());
                state = "running (" + name + ", pid " + p.pid() + ")";
                LOG.info("Craftix Gateway: started the TUN helper: {}", name);
                var errors = p.getErrorStream();
                var err = new Thread(() -> logLines(errors), "craftix-gateway-tun-stderr");
                err.setDaemon(true);
                err.start();
                try {
                    read(p.getInputStream());
                } catch (IOException e) {
                    // the pipe broke: it's going, or gone
                }
                out = null;
                process = null;
                p.destroy();
                if (!running) break;
                try {
                    state = "exited with status " + p.waitFor() + ", starting again";
                } catch (InterruptedException e) {
                    break;
                }
                LOG.warn("Craftix Gateway: the TUN helper {}", state);
            }
            if (!running) break;
            if (System.currentTimeMillis() - started > 60_000) backoff = 1000;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 60_000);
        }
        state = "stopped";
    }

    private void read(InputStream stream) throws IOException {
        var d = new DataInputStream(new BufferedInputStream(stream));
        try {
            while (true) {
                var packet = new byte[d.readUnsignedShort()];
                d.readFully(packet);
                in.accept(packet);
            }
        } catch (EOFException e) {
            // it exited
        }
    }

    private static void logLines(InputStream stream) {
        try (var r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) LOG.info("Craftix Gateway: {}", line);
        } catch (IOException ignored) {
        }
    }
}
