package ccnix.gateway.core;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The real network as NAT mode sees it: the server's own sockets. Every callback runs on the gateway's thread, as
 * {@link Nat} does; nothing here blocks it. The mod's is {@code ccnix.gateway.JavaOutside}; tests use the same over
 * loopback.
 */
public interface Outside {
    /** A TCP connection to or from the outside. */
    interface Tcp {
        /** Queue bytes to send (never blocks). */
        void write(byte[] b);

        /** Bytes queued and not yet written: what's left of the window CC may send into. */
        long pending();

        /** No more to send: after what's queued, our half closes. */
        void shutdownOutput();

        /** Stop (true) or start again (false) reading: while CC can't take more. Starts stopped for an accept. */
        void pause(boolean paused);

        /** Drop it. */
        void close();
    }

    /** What happens on a TCP connection. */
    interface TcpEvents {
        /** Connected (only for connect). */
        void connected();

        /** Couldn't connect: refused (an RST), or not (no answer, no route). */
        void failed(boolean refused);

        void data(byte[] b);

        /** They closed their half. */
        void eof();

        /** It broke (reset, an error). */
        void broken();
    }

    /** A UDP socket. */
    interface Udp {
        /** Send to ip:port (a connected flow ignores them and sends to its peer). */
        void send(long ip, int port, byte[] b);

        void close();
    }

    /** A datagram that came in, from ip:port. */
    interface UdpIn {
        void datagram(long ip, int port, byte[] b);
    }

    /** A connection that came in on a listening port, from ip:port; set its events, then it's live. */
    interface Accept {
        TcpEvents accepted(long ip, int port, Tcp conn);
    }

    interface Closer {
        void close();
    }

    /** Connect to ip:port; events follow. */
    Tcp connect(long ip, int port, TcpEvents events);

    /** A UDP socket on any free port, sending to ip:port and hearing only from it. */
    Udp udp(long ip, int port, UdpIn in) throws IOException;

    /** Listen for TCP on port, on the server's IPv4 addresses. */
    Closer listenTcp(int port, Accept accept) throws IOException;

    /** A UDP socket on port, on the server's IPv4 addresses, hearing from anyone. */
    Udp listenUdp(int port, UdpIn in) throws IOException;

    /** Do work that may block (a name lookup) away from the gateway's thread; then(result) on it. */
    <T> void blocking(Supplier<T> work, Consumer<T> then);
}
