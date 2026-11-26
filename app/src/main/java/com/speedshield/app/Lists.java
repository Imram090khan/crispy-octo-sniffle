package com.speedshield.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads the free community block lists. */
final class Lists {
    static final String STANDARD = "big-list.txt";
    static final String STRONG = "strong-list.txt";

    private Lists() {}

    static boolean has(Context context, String name) {
        return new File(context.getFilesDir(), name).exists();
    }

    static void download(Context context, String name) throws IOException {
        String address = STRONG.equals(name)
                ? "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/pro-onlydomains.txt"
                : "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        File temp = new File(context.getFilesDir(), name + ".tmp");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Server error");
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            if (!temp.renameTo(new File(context.getFilesDir(), name))) {
                throw new IOException("Could not save");
            }
        } finally {
            connection.disconnect();
            temp.delete();
        }
    }
}
