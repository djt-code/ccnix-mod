package ccnix.gateway;

import ccnix.gateway.core.DnsAnswer;
import ccnix.gateway.core.Outside;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * NAT mode's real network: the server's own sockets, each served by virtual threads, every event posted to the
 * gateway's thread (post), so {@link ccnix.gateway.core.Nat} needs no locks. IPv4 only: listeners bind 0.0.0.0.
 */
public final class JavaOutside implements Outside {
    static final int CONNECT_TIMEOUT = 10_000;
    private final Executor post;

    /** post: runs a task on the gateway's thread. */
    public JavaOutside(Executor post) {
        this.post = post;
    }

    static InetAddress inet(long ip) {
        try {
            return InetAddress.getByAddress(new byte[]{ (byte) (ip >> 24), (byte) (ip >> 16), (byte) (ip >> 8), (byte) ip });
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** An IPv4 address as a number, or -1. */
    static long ip(InetAddress a) {
        if (!(a instanceof Inet4Address)) return -1;
        var b = a.getAddress();
        return ((b[0] & 255L) << 24) | ((b[1] & 255L) << 16) | ((b[2] & 255L) << 8) | (b[3] & 255L);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TCP
    // ---------------------------------------------------------------------------------------------------------------

    private static final byte[] SHUTDOWN = new byte[0];

    /** One socket: a writer thread draining a queue, a reader thread that can be paused. */
    private final class Conn implements Tcp {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final AtomicLong pending = new AtomicLong();
        private final Object pauseLock = new Object();
        private volatile Socket socket;
        private volatile TcpEvents events;
        private volatile boolean closed, paused;

        Conn(TcpEvents events, boolean paused) {
            this.events = events;
            this.paused = paused;
        }

        void attach(Socket s) {
            socket = s;
            if (closed) {
                quietly(s);
                return;
            }
            Thread.ofVirtual().name("ccnix-nat-write").start(this::writeLoop);
        }

        void startReading() {
            Thread.ofVirtual().name("ccnix-nat-read").start(this::readLoop);
        }

        private void writeLoop() {
            try {
                OutputStream o = socket.getOutputStream();
                while (!closed) {
                    var b = queue.take();
                    if (b == SHUTDOWN) {
                        socket.shutdownOutput();
                        return;
                    }
                    o.write(b);
                    o.flush();
                    pending.addAndGet(-b.length);
                }
            } catch (IOException e) {
                broke();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void readLoop() {
            var buf = new byte[4096];
            try {
                InputStream in = socket.getInputStream();
                while (!closed) {
                    synchronized (pauseLock) {
                        while (paused && !closed) pauseLock.wait();
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        post.execute(() -> { if (!closed) events.eof(); });
                        return;
                    }
                    var b = Arrays.copyOf(buf, n);
                    post.execute(() -> { if (!closed) events.data(b); });
                }
            } catch (IOException e) {
                broke();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void broke() {
            if (!closed) post.execute(() -> { if (!closed && events != null) events.broken(); });
        }

        @Override
        public void write(byte[] b) {
            if (closed) return;
            pending.addAndGet(b.length);
            queue.add(b);
        }

        @Override
        public long pending() {
            return pending.get();
        }

        @Override
        public void shutdownOutput() {
            if (!closed) queue.add(SHUTDOWN);
        }

        @Override
        public void pause(boolean p) {
            synchronized (pauseLock) {
                paused = p;
                pauseLock.notifyAll();
            }
        }

        @Override
        public void close() {
            closed = true;
            pause(false);
            queue.clear();
            queue.add(SHUTDOWN);                        // (wakes the writer, which sees closed)
            var s = socket;
            if (s != null) quietly(s);
        }
    }

    private static void quietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public Tcp connect(long ip, int port, TcpEvents events) {
        var c = new Conn(events, false);
        Thread.ofVirtual().name("ccnix-nat-connect").start(() -> {
            var s = new Socket();
            try {
                s.connect(new InetSocketAddress(inet(ip), port), CONNECT_TIMEOUT);
            } catch (IOException e) {
                quietly(s);
                boolean refused = e.getClass() == ConnectException.class;
                post.execute(() -> { if (!c.closed) events.failed(refused); });
                return;
            }
            c.attach(s);
            post.execute(() -> { if (!c.closed) events.connected(); });
            c.startReading();
        });
        return c;
    }

    @Override
    public Closer listenTcp(int port, Accept accept) throws IOException {
        var server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        Thread.ofVirtual().name("ccnix-nat-listen-" + port).start(() -> {
            while (!server.isClosed()) {
                try {
                    var s = server.accept();
                    long from = ip(s.getInetAddress());
                    int fromPort = s.getPort();
                    var c = new Conn(null, true);
                    c.attach(s);
                    post.execute(() -> {
                        TcpEvents ev = from < 0 ? null : accept.accepted(from, fromPort, c);
                        if (ev == null) {
                            c.close();
                        } else {
                            c.events = ev;
                            c.startReading();
                        }
                    });
                } catch (IOException e) {
                    if (server.isClosed()) return;
                }
            }
        });
        return () -> quietly(server);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // UDP
    // ---------------------------------------------------------------------------------------------------------------

    private Udp serve(DatagramChannel ch, UdpIn in) {
        Thread.ofVirtual().name("ccnix-nat-udp").start(() -> {
            var buf = ByteBuffer.allocate(65535);
            while (ch.isOpen()) {
                try {
                    buf.clear();
                    var from = (InetSocketAddress) ch.receive(buf);
                    if (from == null) continue;
                    buf.flip();
                    var b = new byte[buf.remaining()];
                    buf.get(b);
                    long ip = ip(from.getAddress());
                    if (ip >= 0) post.execute(() -> in.datagram(ip, from.getPort(), b));
                } catch (PortUnreachableException e) {
                    // (an ICMP answer to what we sent: nothing to read)
                } catch (IOException e) {
                    return;
                }
            }
        });
        return new Udp() {
            @Override
            public void send(long ip, int port, byte[] b) {
                try {
                    if (ch.isConnected()) ch.write(ByteBuffer.wrap(b));
                    else ch.send(ByteBuffer.wrap(b), new InetSocketAddress(inet(ip), port));
                } catch (IOException ignored) {
                    // (UDP: lost)
                }
            }

            @Override
            public void close() {
                quietly(ch);
            }
        };
    }

    @Override
    public Udp udp(long ip, int port, UdpIn in) throws IOException {
        var ch = DatagramChannel.open();
        ch.connect(new InetSocketAddress(inet(ip), port));
        return serve(ch, in);
    }

    @Override
    public Udp listenUdp(int port, UdpIn in) throws IOException {
        var ch = DatagramChannel.open();
        ch.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        return serve(ch, in);
    }

    @Override
    public <T> void blocking(Supplier<T> work, Consumer<T> then) {
        Thread.ofVirtual().name("ccnix-nat-blocking").start(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                return;
            }
            post.execute(() -> then.accept(result));
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The system's resolver (DNS answered at the gateway on Windows)
    // ---------------------------------------------------------------------------------------------------------------

    public static final DnsAnswer.Resolver SYSTEM_RESOLVER = new DnsAnswer.Resolver() {
        @Override
        public List<Long> addresses(String name) {
            var out = new ArrayList<Long>();
            try {
                for (var a : InetAddress.getAllByName(name)) {
                    long ip = ip(a);
                    if (ip >= 0) out.add(ip);
                }
            } catch (UnknownHostException ignored) {
            }
            return out;
        }

        @Override
        public String name(long ip) {
            var a = inet(ip);
            var n = a.getCanonicalHostName();
            return n.equals(a.getHostAddress()) ? null : n;
        }
    };
}
