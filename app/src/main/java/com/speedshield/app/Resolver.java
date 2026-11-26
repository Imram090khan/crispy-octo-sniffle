package com.speedshield.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Looks up allowed domains. It asks several DNS servers at the same time, uses
 * the first good answer, and remembers answers for a short while.
 */
public class Resolver {
    /** Keeps a socket outside the VPN. */
    public interface Protector {
        boolean protect(DatagramSocket socket);
    }

    private static final int MAX_CACHED = 2000;
    /** Wait this long, then send once more and wait for the second period. */
    private static final int[] WAITS_MS = {800, 2200};

    private static final class Saved {
        final byte[] answer;
        final long until;

        Saved(byte[] answer, long until) {
            this.answer = answer;
            this.until = until;
        }
    }

    private final Protector protector;
    private final Map<String, Saved> cache = new LinkedHashMap<String, Saved>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Saved> eldest) {
            return size() > MAX_CACHED;
        }
    };

    public Resolver(Protector protector) {
        this.protector = protector;
    }

    /** Returns the answer, or null if no server replied. */
    public byte[] resolve(byte[] query, List<InetSocketAddress> servers) throws IOException {
        String question = Packets.question(query);
        if (question != null) {
            synchronized (cache) {
                Saved saved = cache.get(question);
                if (saved != null && System.nanoTime() - saved.until < 0) {
                    byte[] copy = saved.answer.clone();
                    copy[0] = query[0]; // match this request's ID
                    copy[1] = query[1];
                    return copy;
                }
            }
        }

        byte[] answer = ask(query, question, servers);
        int seconds = answer == null ? 0 : Packets.cacheSeconds(answer);
        if (question != null && seconds > 0) {
            synchronized (cache) {
                cache.put(question, new Saved(answer.clone(), System.nanoTime() + seconds * 1_000_000_000L));
            }
        }
        return answer;
    }

    private byte[] ask(byte[] query, String question, List<InetSocketAddress> servers) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            protector.protect(socket);
            byte[] failure = null; // "server failed" or "refused": use only if nothing better comes
            Set<Object> failed = new HashSet<>();
            byte[] buffer = new byte[4096];

            for (int wait : WAITS_MS) {
                for (InetSocketAddress server : servers) {
                    try {
                        socket.send(new DatagramPacket(query, query.length, server));
                    } catch (IOException e) {
                        // This server cannot be reached from here; the others may work.
                    }
                }
                long deadline = System.nanoTime() + wait * 1_000_000L;
                while (true) {
                    long left = (deadline - System.nanoTime()) / 1_000_000L;
                    if (left <= 0) break;
                    socket.setSoTimeout((int) left);
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    if (!servers.contains(packet.getSocketAddress())) continue;
                    byte[] answer = Arrays.copyOf(buffer, packet.getLength());
                    if (answer.length < 12 || answer[0] != query[0] || answer[1] != query[1]
                            || (answer[2] & 0x80) == 0) continue;
                    int code = answer[3] & 15;
                    if (code != 2 && code != 5) {
                        if (question == null || question.equalsIgnoreCase(Packets.question(answer))) return answer;
                        continue;
                    }
                    failure = answer;
                    failed.add(packet.getSocketAddress());
                    if (failed.size() == servers.size()) return failure;
                }
            }
            return failure;
        }
    }
}
