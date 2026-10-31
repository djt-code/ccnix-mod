package ccnix.gateway.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * One TCP connection NAT mode carries: a CC host at one end, a Java socket to the outside at the other. To the CC host
 * this is the outside host itself (its address and port), speaking the same least-of-RFC-9293 TCP as lib/net.lua,
 * ported line for line: no options (MSS 536), only the next byte expected is taken, go-back-N after an RTO that
 * doubles, keepalive probes, a short TIME_WAIT. What differs: the bytes CC sends go into the socket instead of an
 * inbox (the window is what the socket hasn't written yet), and an outbound connection answers CC's SYN only once
 * the socket has connected (refused: an RST; no answer: nothing, and CC's SYN times out as it would).
 * <p>
 * Not thread-safe: the gateway's thread only.
 */
final class NatTcp {
    static final int MSS = 536, RCV_WND = 8192, OUT_MAX = 65536, RETRIES = 8, KEEPCNT = 3;
    static final long RTO = 1000, RTO_MAX = 16000, KEEPIDLE = 10_000, TIMEWAIT = 4000;

    /** CONNECTING: CC's SYN is in, the outside socket is connecting. SYN_SENT: our SYN to CC (a port forward). */
    enum State { CONNECTING, SYN_SENT, SYN_RCVD, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT_1, FIN_WAIT_2, TIME_WAIT, CLOSED }

    final Nat nat;
    final long ccIp, myIp;
    final int ccPort, myPort;
    State state;
    Outside.Tcp conn;
    long iss, sndUna, sndNxt, sndMax, rcvNxt = -1;
    int sndWnd = MSS, tries, probes;
    long rto = RTO, rtxAt, lastHeard, lastSent, lastUsed, timeWait;
    boolean closing, finAcked, peerFin, passive, paused, lowWindow;
    final Buf out = new Buf();                          // bytes from snd.una on
    String err;

    NatTcp(Nat nat, long ccIp, int ccPort, long myIp, int myPort, State state) {
        this.nat = nat;
        this.ccIp = ccIp;
        this.ccPort = ccPort;
        this.myIp = myIp;
        this.myPort = myPort;
        this.state = state;
        long now = nat.now();
        iss = nat.random32();
        sndUna = iss;
        sndNxt = sndMax = add(iss, 1);
        lastHeard = lastSent = lastUsed = rtxAt = now;
    }

    static long add(long a, long n) {
        return (a + n) & 0xFFFFFFFFL;
    }

    static long minus(long a, long b) {
        long d = (a - b) & 0xFFFFFFFFL;
        return d >= 0x80000000L ? d - 0x100000000L : d;
    }

    int window() {
        return (int) Math.max(0, RCV_WND - (conn == null ? 0 : conn.pending()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Out to CC
    // ---------------------------------------------------------------------------------------------------------------

    private void emit(long seq, boolean syn, boolean fin, boolean rst, byte[] data) {
        Map<String, Object> seg = new HashMap<>();
        seg.put("sport", myPort);
        seg.put("dport", ccPort);
        seg.put("seq", seq);
        if (rcvNxt >= 0) seg.put("ack", rcvNxt);
        if (syn) seg.put("syn", true);
        if (fin) seg.put("fin", true);
        if (rst) seg.put("rst", true);
        int wnd = window();
        seg.put("wnd", wnd);
        seg.put("data", data == null ? new byte[0] : data);
        lowWindow = wnd < MSS;
        lastSent = nat.now();
        nat.send(myIp, ccIp, "tcp", seg);
    }

    private void ack() {
        emit(sndNxt, false, false, false, null);
    }

    private void sent(int n) {
        if (sndUna == sndNxt) rtxAt = nat.now();
        sndNxt = add(sndNxt, n);
        if (minus(sndNxt, sndMax) > 0) sndMax = sndNxt;
    }

    private void push() {
        if (state == State.CONNECTING || state == State.SYN_SENT || state == State.SYN_RCVD || state == State.CLOSED) return;
        while (true) {
            int off = (int) minus(sndNxt, sndUna);
            long room = minus(add(sndUna, sndWnd), sndNxt);
            int n = (int) Math.max(0, Math.min(Math.min(MSS, room), out.size() - off));
            if (n == 0) break;
            long seq = sndNxt;
            sent(n);
            emit(seq, false, false, false, out.slice(off, n));
        }
        if (closing && !finAcked && sndNxt == add(sndUna, out.size())) {
            long seq = sndNxt;
            sent(1);
            emit(seq, false, true, false, null);
        }
    }

    private void settle() {
        if (state == State.CONNECTING || state == State.SYN_SENT || state == State.SYN_RCVD || state == State.CLOSED
            || state == State.TIME_WAIT) return;
        if (peerFin && finAcked) {
            if (passive) {
                finish(null);
                return;
            }
            state = State.TIME_WAIT;
            timeWait = nat.now();
        } else if (peerFin) {
            state = closing ? State.LAST_ACK : State.CLOSE_WAIT;
        } else if (closing) {
            state = finAcked ? State.FIN_WAIT_2 : State.FIN_WAIT_1;
        } else {
            state = State.ESTABLISHED;
        }
    }

    void finish(String why) {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        err = why;
        if (conn != null) conn.close();
        nat.closed(this);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From the outside
    // ---------------------------------------------------------------------------------------------------------------

    /** The outside socket connected: answer CC's SYN. */
    void connected() {
        if (state != State.CONNECTING) return;
        state = State.SYN_RCVD;
        rtxAt = nat.now();
        emit(iss, true, false, false, null);
    }

    /** It couldn't: refused, an RST for CC's SYN; else nothing (CC's SYN times out). */
    void failed(boolean refused) {
        if (state != State.CONNECTING) return;
        if (refused) emit(0, false, false, true, null);
        finish(refused ? "refused" : "unreachable");
    }

    void data(byte[] b) {
        if (state == State.CLOSED || closing) return;
        lastUsed = nat.now();
        out.append(b);
        push();
        if (!paused && out.size() > OUT_MAX) {
            paused = true;
            conn.pause(true);
        }
    }

    /** The outside closed its half: what's queued goes, then a FIN. */
    void close() {
        if (state == State.CLOSED || closing) return;
        closing = true;
        push();
        settle();
    }

    /** Drop it, telling CC (RST). */
    void abort(String why) {
        if (state == State.CLOSED) return;
        if (state != State.CONNECTING && state != State.SYN_SENT) emit(sndNxt, false, false, true, null);
        finish(why);
    }

    /** A port forward: open the connection to the CC host. */
    void open() {
        rtxAt = nat.now();
        emit(iss, true, false, false, null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From CC
    // ---------------------------------------------------------------------------------------------------------------

    void in(Map<String, Object> d) {
        var data = Ipv4.bytes(d.get("data"));
        if (data == null) return;
        long seq = Ipv4.u32(d.get("seq"));
        Long ackNo = d.containsKey("ack") ? Ipv4.u32(d.get("ack")) : null;
        boolean syn = Boolean.TRUE.equals(d.get("syn")), fin = Boolean.TRUE.equals(d.get("fin")), rst = Boolean.TRUE.equals(d.get("rst"));
        int wnd = d.get("wnd") instanceof Number n ? n.intValue() : sndWnd;
        long now = nat.now();
        lastHeard = now;
        probes = 0;

        if (state == State.CONNECTING) {                // (their SYN again, while the socket connects)
            if (rst) finish("reset");
            return;
        }
        if (state == State.SYN_SENT) {
            if (ackNo != null && ackNo != sndNxt) {
                nat.resetFor(ccIp, myIp, d);
                return;
            }
            if (rst) {
                if (ackNo != null) finish("refused");
            } else if (syn && ackNo != null) {
                rcvNxt = add(seq, 1);
                sndUna = ackNo;
                sndWnd = wnd;
                state = State.ESTABLISHED;
                tries = 0;
                rto = RTO;
                ack();
                conn.pause(false);
                push();
            }
            return;
        }

        long off = minus(seq, rcvNxt);
        int len = data.length + (syn ? 1 : 0) + (fin ? 1 : 0);
        boolean ok = (len == 0 && off == 0) || (off <= 0 && off + len > 0);
        if (rst) {
            if (off == 0) finish(closing || peerFin ? null : "reset");
            return;
        }
        if (syn) {                                      // (their SYN again: our SYN-ACK was lost)
            if (state == State.SYN_RCVD) emit(iss, true, false, false, null);
            else ack();
            return;
        }
        if (!ok) {
            ack();
            return;
        }
        if (ackNo == null) return;

        if (state == State.SYN_RCVD) {
            if (ackNo != add(iss, 1)) {
                nat.resetFor(ccIp, myIp, d);
                return;
            }
            sndUna = ackNo;
            state = State.ESTABLISHED;
        }
        long acked = minus(ackNo, sndUna);
        if (acked > 0 && minus(ackNo, sndMax) <= 0) {
            if (closing && acked > out.size()) finAcked = true;
            out.drop((int) Math.min(acked, out.size()));
            sndUna = ackNo;
            tries = 0;
            rto = RTO;
            rtxAt = now;
            if (minus(sndNxt, ackNo) < 0) sndNxt = ackNo;
            if (paused && out.size() < OUT_MAX / 2) {
                paused = false;
                conn.pause(false);
            }
        }
        sndWnd = wnd;
        if (sndWnd == 0) tries = 0;                     // (they're there, just full: keep probing)

        if (state != State.TIME_WAIT && !peerFin) {
            int skip = (int) -off;
            int take = Math.max(0, Math.min(data.length - skip, window()));
            if (take > 0) {
                conn.write(Arrays.copyOfRange(data, skip, skip + take));
                rcvNxt = add(rcvNxt, take);
                lastUsed = now;
            }
            if (fin && skip + take == data.length) {
                peerFin = true;
                passive = !closing;
                rcvNxt = add(rcvNxt, 1);
                conn.shutdownOutput();
            }
            if (len > 0) ack();
        }
        push();
        settle();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Time
    // ---------------------------------------------------------------------------------------------------------------

    void tick(long now, long idleMs) {
        if (state == State.CLOSED || state == State.CONNECTING) return;
        boolean waiting = state == State.SYN_SENT || state == State.SYN_RCVD || out.size() > 0 || (closing && !finAcked);
        if (state == State.TIME_WAIT) {
            if (now - timeWait >= TIMEWAIT) finish(null);
            return;
        }
        if (now - lastUsed >= idleMs) {
            abort("idle");
            return;
        }
        if (waiting && now - rtxAt >= rto) {
            tries++;
            rtxAt = now;
            rto = Math.min(rto * 2, RTO_MAX);
            if (tries > RETRIES) {
                finish(state == State.SYN_SENT ? "timed out" : "lost");
            } else if (state == State.SYN_SENT || state == State.SYN_RCVD) {
                emit(iss, true, false, false, null);
            } else if (sndWnd == 0 && sndNxt == sndUna) {
                emit(add(sndUna, -1), false, false, false, null);     // a window probe
            } else {
                sndNxt = sndUna;                        // go back N
                push();
            }
        } else if (!waiting && now - Math.max(lastHeard, lastSent) >= KEEPIDLE) {
            probes++;
            if (probes > KEEPCNT) finish("lost");
            else emit(add(sndNxt, -1), false, false, false, null);
        } else if (lowWindow && window() >= MSS) {
            ack();                                      // (the window opened: say so)
        }
    }

    /** A byte queue: append at the end, drop from the front. */
    static final class Buf {
        private byte[] b = new byte[1024];
        private int start, end;

        int size() {
            return end - start;
        }

        void append(byte[] x) {
            if (end + x.length > b.length) {
                int n = size();
                var nb = n + x.length > b.length ? new byte[Math.max(b.length * 2, n + x.length)] : b;
                System.arraycopy(b, start, nb, 0, n);
                b = nb;
                start = 0;
                end = n;
            }
            System.arraycopy(x, 0, b, end, x.length);
            end += x.length;
        }

        void drop(int n) {
            start += n;
            if (start == end) start = end = 0;
        }

        byte[] slice(int off, int n) {
            return Arrays.copyOfRange(b, start + off, start + off + n);
        }
    }
}
