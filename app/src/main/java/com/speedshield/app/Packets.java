package com.speedshield.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads and writes the small IPv4/UDP/DNS packets that pass through the local VPN. */
public final class Packets {
    private Packets() {}

    /** Returns the DNS message inside an IPv4 UDP packet to port 53, or null. */
    public static byte[] dnsPayload(byte[] p, int length) {
        if (length < 28 || (p[0] >> 4 & 15) != 4 || p[9] != 17) return null;
        int ip = (p[0] & 15) * 4;
        if (ip < 20 || length < ip + 8 || read16(p, ip + 2) != 53) return null;
        int udp = read16(p, ip + 4);
        if (udp < 8 + 12 || ip + udp > length) return null;
        return Arrays.copyOfRange(p, ip + 8, ip + udp);
    }

    /** The domain being asked for, or null if the message is malformed. */
    public static String queryName(byte[] dns) {
        if (dns.length < 17 || read16(dns, 4) < 1) return null;
        StringBuilder name = new StringBuilder();
        int i = 12;
        while (i < dns.length) {
            int size = dns[i++] & 255;
            if (size == 0) return i + 4 <= dns.length ? name.toString() : null;
            if (size > 63 || i + size > dns.length) return null;
            if (name.length() > 0) name.append('.');
            for (int end = i + size; i < end; i++) name.append((char) (dns[i] & 255));
        }
        return null;
    }

    /** A "this domain does not exist" answer. Call only after queryName succeeded. */
    public static byte[] blockedAnswer(byte[] query) {
        int end = 12;
        while (query[end] != 0) end += (query[end] & 255) + 1;
        byte[] answer = Arrays.copyOf(query, end + 5);
        answer[2] = (byte) (0x80 | (query[2] & 1)); // response, keep "recursion desired"
        answer[3] = (byte) 0x83;                    // recursion available, NXDOMAIN
        write16(answer, 4, 1);                      // one question
        write16(answer, 6, 0);
        write16(answer, 8, 0);
        write16(answer, 10, 0);
        return answer;
    }

    /** Wraps a DNS answer in a packet going back to whoever sent the request. */
    public static byte[] reply(byte[] request, byte[] dns) {
        int ip = (request[0] & 15) * 4;
        byte[] out = new byte[28 + dns.length];
        out[0] = 0x45;
        write16(out, 2, out.length);
        out[8] = 64;
        out[9] = 17;
        System.arraycopy(request, 16, out, 12, 4); // source = old destination
        System.arraycopy(request, 12, out, 16, 4); // destination = old source
        write16(out, 10, checksum(out, 20));
        write16(out, 20, read16(request, ip + 2));
        write16(out, 22, read16(request, ip));
        write16(out, 24, 8 + dns.length);          // UDP checksum 0 = unused in IPv4
        System.arraycopy(dns, 0, out, 28, dns.length);
        return out;
    }

    /**
     * Refuses a TCP connection at once, so the phone does not sit waiting for a
     * timeout. Returns null if the packet is not TCP or needs no answer.
     */
    public static byte[] tcpReset(byte[] p, int length) {
        if (length < 40 || (p[0] >> 4 & 15) != 4 || p[9] != 6) return null;
        int ip = (p[0] & 15) * 4;
        if (ip < 20 || length < ip + 20) return null;
        int flags = p[ip + 13] & 255;
        if ((flags & 0x04) != 0) return null; // never answer a reset

        byte[] out = new byte[40];
        out[0] = 0x45;
        write16(out, 2, 40);
        out[8] = 64;
        out[9] = 6;
        System.arraycopy(p, 16, out, 12, 4);
        System.arraycopy(p, 12, out, 16, 4);
        write16(out, 10, checksum(out, 20));
        write16(out, 20, read16(p, ip + 2));
        write16(out, 22, read16(p, ip));
        if ((flags & 0x10) != 0) {            // they sent an ACK: reuse its number
            System.arraycopy(p, ip + 8, out, 24, 4);
            out[33] = 0x04;
        } else {
            int data = Math.max(0, read16(p, 2) - ip - (p[ip + 12] >> 4 & 15) * 4);
            long next = ((long) read16(p, ip + 4) << 16 | read16(p, ip + 6))
                    + data + (flags >> 1 & 1) + (flags & 1);
            write16(out, 28, (int) (next >>> 16));
            write16(out, 30, (int) next);
            out[33] = 0x14;
        }
        out[32] = 0x50;

        byte[] pseudo = new byte[32];
        System.arraycopy(out, 12, pseudo, 0, 8);
        pseudo[9] = 6;
        pseudo[11] = 20;
        System.arraycopy(out, 20, pseudo, 12, 20);
        write16(out, 36, checksum(pseudo, 32));
        return out;
    }

    /** The question part of a message, or null if it is malformed. */
    static String question(byte[] dns) {
        if (dns.length < 17 || read16(dns, 4) != 1) return null;
        int end = skipName(dns, 12);
        if (end < 0 || end + 4 > dns.length) return null;
        return new String(dns, 12, end + 4 - 12, StandardCharsets.ISO_8859_1);
    }

    /** How long an answer may be reused, in seconds. 0 means do not keep it. */
    static int cacheSeconds(byte[] dns) {
        String question = question(dns);
        int code = dns.length > 3 ? dns[3] & 15 : -1;
        if (question == null || (dns[2] & 2) != 0 || (code != 0 && code != 3)) return 0;
        int i = 12 + question.length();
        int answers = read16(dns, 6);
        long shortest = answers == 0 ? 30 : 300;
        for (int n = 0; n < answers; n++) {
            i = skipName(dns, i);
            if (i < 0 || i + 10 > dns.length) return 0;
            long ttl = (long) read16(dns, i + 4) << 16 | read16(dns, i + 6);
            shortest = Math.min(shortest, ttl);
            i += 10 + read16(dns, i + 8);
            if (i > dns.length) return 0;
        }
        return (int) shortest;
    }

    /** Position just after a (possibly compressed) name, or -1. */
    private static int skipName(byte[] b, int i) {
        while (i < b.length) {
            int size = b[i] & 255;
            if (size == 0) return i + 1;
            if (size >= 192) return i + 2 <= b.length ? i + 2 : -1;
            if (size > 63) return -1;
            i += size + 1;
        }
        return -1;
    }

    static int checksum(byte[] data, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) sum += read16(data, i);
        while (sum >> 16 != 0) sum = (sum & 0xffff) + (sum >> 16);
        return ~sum & 0xffff;
    }

    static int read16(byte[] b, int i) {
        return (b[i] & 255) << 8 | (b[i + 1] & 255);
    }

    private static void write16(byte[] b, int i, int value) {
        b[i] = (byte) (value >> 8);
        b[i + 1] = (byte) value;
    }
}
