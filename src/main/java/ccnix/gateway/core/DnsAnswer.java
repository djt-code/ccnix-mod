package ccnix.gateway.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DNS answered at the gateway's address in NAT mode on Windows, the one exception to the gateway being transport
 * only (ADR-0002): Windows has no resolver address to relay to, so A and PTR questions are answered from its
 * resolver. Anything else gets an empty answer; NXDOMAIN when there's no such name.
 */
public final class DnsAnswer {
    static final int TTL = 60;

    /** The system's resolver: a name's IPv4 addresses (none: no such name), an address's name (null: none). */
    public interface Resolver {
        List<Long> addresses(String name);

        String name(long ip);
    }

    /** A question: its id, flags, the question section as it came, the name and the type. */
    public record Question(int id, int flags, byte[] section, String name, int type) {
    }

    private DnsAnswer() {
    }

    /** The question in a query, or null if it isn't one we can answer (not a query, not one question). */
    public static Question question(byte[] q) {
        if (q.length < 12 || (q[2] & 0x80) != 0 || Ipv4.u16(q, 4) != 1) return null;
        var labels = new ArrayList<String>();
        int pos = 12;
        while (true) {
            if (pos >= q.length) return null;
            int len = q[pos] & 255;
            if (len == 0) break;
            if (len >= 64 || pos + 1 + len > q.length) return null;
            labels.add(new String(q, pos + 1, len, StandardCharsets.ISO_8859_1));
            pos += 1 + len;
        }
        if (pos + 5 > q.length) return null;
        var section = new byte[pos + 5 - 12];
        System.arraycopy(q, 12, section, 0, section.length);
        return new Question(Ipv4.u16(q, 0), Ipv4.u16(q, 2), section, String.join(".", labels), Ipv4.u16(q, pos + 1));
    }

    /** The reply to a question, looking up what it asks (this blocks: call it off the gateway's thread). */
    public static byte[] answer(Question q, Resolver resolver) {
        var answers = new ArrayList<byte[]>();
        int rcode = 0;
        if (q.type() == 1) {
            var addrs = resolver.addresses(q.name());
            if (addrs.isEmpty()) rcode = 3;
            for (var a : addrs) answers.add(record(1, new byte[]{ (byte) (a >> 24), (byte) (a >> 16), (byte) (a >> 8), (byte) (long) a }));
        } else if (q.type() == 12) {
            long ip = reverse(q.name());
            var name = ip < 0 ? null : resolver.name(ip);
            if (name == null) rcode = 3;
            else answers.add(record(12, encode(name)));
        }
        var out = new ByteArrayOutputStream();
        put16(out, q.id());
        put16(out, 0x8080 | (q.flags() & 0x0100) | rcode);    // a reply, recursion available (and desired if it was)
        put16(out, 1);
        put16(out, answers.size());
        put16(out, 0);
        put16(out, 0);
        out.writeBytes(q.section());
        for (var a : answers) out.writeBytes(a);
        return out.toByteArray();
    }

    /** "d.c.b.a.in-addr.arpa" as a.b.c.d, or -1. */
    static long reverse(String name) {
        var n = name.toLowerCase();
        if (!n.endsWith(".in-addr.arpa")) return -1;
        var parts = n.substring(0, n.length() - ".in-addr.arpa".length()).split("\\.");
        if (parts.length != 4) return -1;
        return Ipv4.parseIp(parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0]);
    }

    /** An answer record for the question's name (a pointer to it, at 12). */
    private static byte[] record(int type, byte[] rdata) {
        var out = new ByteArrayOutputStream();
        put16(out, 0xC00C);
        put16(out, type);
        put16(out, 1);
        put16(out, 0);
        put16(out, TTL);
        put16(out, rdata.length);
        out.writeBytes(rdata);
        return out.toByteArray();
    }

    static byte[] encode(String name) {
        var out = new ByteArrayOutputStream();
        for (var label : name.replaceAll("\\.$", "").split("\\.")) {
            var b = label.getBytes(StandardCharsets.ISO_8859_1);
            if (b.length == 0 || b.length > 63) continue;
            out.write(b.length);
            out.writeBytes(b);
        }
        out.write(0);
        return out.toByteArray();
    }

    private static void put16(ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }
}
