package com.speedshield.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Decides whether a domain is blocked. A listed domain also covers its subdomains. */
public class BlockList {
    private final Set<String> blocked = new HashSet<>();
    private final Set<String> allowed = new HashSet<>();

    /** Reads "domain" lines or hosts-file lines such as "0.0.0.0 domain". */
    public void load(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            // Splitting is slow, so only do it for lines with more than one word.
            if (line.indexOf(' ') >= 0 || line.indexOf('\t') >= 0) {
                String[] parts = line.split("\\s+");
                if (!parts[0].equals("0.0.0.0") && !parts[0].equals("127.0.0.1")) continue;
                line = parts[1];
            }
            String domain = line.toLowerCase(Locale.ROOT);
            if (domain.contains(".") && !domain.equals("0.0.0.0")) blocked.add(domain);
        }
    }

    public void allow(String text) {
        for (String domain : text.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (!domain.isEmpty()) allowed.add(domain);
        }
    }

    public boolean isBlocked(String domain) {
        String name = domain.toLowerCase(Locale.ROOT);
        return !matches(allowed, name) && matches(blocked, name);
    }

    public int size() {
        return blocked.size();
    }

    private static boolean matches(Set<String> set, String name) {
        while (true) {
            if (set.contains(name)) return true;
            int dot = name.indexOf('.');
            if (dot < 0) return false;
            name = name.substring(dot + 1);
        }
    }
}
