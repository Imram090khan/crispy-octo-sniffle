package com.speedshield.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local VPN that only receives DNS lookups. Ad domains get "does not exist";
 * everything else is forwarded. Videos and other traffic never pass through it.
 */
public class AdVpnService extends VpnService {
    static final String STOP = "stop";
    static final AtomicInteger blockedCount = new AtomicInteger();
    static final AtomicInteger allowedCount = new AtomicInteger();
    static volatile boolean running;
    static volatile boolean loading;
    static volatile int ruleCount;
    /** True if Android blocks everything that does not go through the VPN. */
    static volatile boolean lockdown;
    private static final ArrayDeque<String> recent = new ArrayDeque<>();

    private static final String TAG = "SpeedShield";
    private Thread worker;
    private volatile List<InetSocketAddress> servers;
    private volatile long serversCheckedAt;

    static List<String> recentBlocked() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private static void remember(String name) {
        synchronized (recent) {
            recent.remove(name);
            recent.addFirst(name);
            while (recent.size() > 6) recent.removeLast();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            switchOff();
            return START_NOT_STICKY;
        }
        // Must come first: Android allows only a few seconds to show the notification.
        showNotification();
        stopWorker();
        setWanted(true);
        running = true;
        loading = true;
        worker = new Thread(this::run, "dns-filter");
        worker.start();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        switchOff();
    }

    @Override
    public void onDestroy() {
        stopWorker();
        super.onDestroy();
    }

    private void switchOff() {
        setWanted(false);
        stopWorker();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Remembers whether to start again after the phone restarts. */
    private void setWanted(boolean wanted) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("wanted", wanted).apply();
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                "protection", "Protection status", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "protection")
                .setSmallIcon(R.drawable.stat)
                .setContentTitle("SpeedShield is on")
                .setContentText("Blocking ads and trackers")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(1, notification);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not show the notification", e);
        }
    }

    private void stopWorker() {
        running = false;
        loading = false;
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker = null;
    }

    private void run() {
        // Many lookups can wait at once; if the queue overflows, the oldest are dropped.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(32, 32, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.DiscardOldestPolicy());
        pool.allowCoreThreadTimeOut(true);
        Resolver resolver = new Resolver(socket -> protect(socket));
        Thread self = Thread.currentThread();
        try {
            BlockList list = loadList();
            if (self.isInterrupted()) return;
            ruleCount = list.size();

            Builder builder = new Builder()
                    .setSession("SpeedShield")
                    .setMtu(4200)
                    .addAddress("10.111.0.1", 32)
                    .addDnsServer("10.111.0.2")
                    .addRoute("10.111.0.2", 32)
                    .allowFamily(OsConstants.AF_INET6);
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            // Apps the user chose to leave outside the VPN.
            for (String app : getSharedPreferences("settings", MODE_PRIVATE)
                    .getStringSet("excluded", Collections.<String>emptySet())) {
                try {
                    builder.addDisallowedApplication(app);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "App no longer installed: " + app, e);
                }
            }

            try (ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null) return;
                loading = false;
                lockdown = Build.VERSION.SDK_INT >= 29 && isLockdownEnabled();
                FileInputStream in = new FileInputStream(tun.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
                StructPollfd poll = new StructPollfd();
                poll.fd = tun.getFileDescriptor();
                poll.events = (short) OsConstants.POLLIN;
                byte[] buffer = new byte[32767];

                while (running && !self.isInterrupted()) {
                    if (Os.poll(new StructPollfd[]{poll}, 500) <= 0) continue;
                    int length = in.read(buffer);
                    if (length < 0) break; // the VPN connection was closed
                    if (length == 0) continue;
                    byte[] packet = Arrays.copyOf(buffer, length);

                    byte[] reset = Packets.tcpReset(packet, length);
                    if (reset != null) {
                        send(out, reset);
                        continue;
                    }
                    byte[] query = Packets.dnsPayload(packet, length);
                    if (query == null) continue;
                    String name = Packets.queryName(query);
                    if (name == null) continue;

                    if (list.isBlocked(name)) {
                        blockedCount.incrementAndGet();
                        remember(name);
                        send(out, Packets.reply(packet, Packets.blockedAnswer(query)));
                    } else {
                        allowedCount.incrementAndGet();
                        pool.execute(() -> forward(resolver, out, packet, query));
                    }
                }
            }
        } catch (IOException | ErrnoException | RuntimeException e) {
            Log.e(TAG, "Filter stopped", e);
        } finally {
            pool.shutdownNow();
            // If nobody asked this thread to stop, it failed: switch the service off.
            if (!self.isInterrupted()) {
                running = false;
                loading = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private void forward(Resolver resolver, FileOutputStream out, byte[] packet, byte[] query) {
        try {
            byte[] answer = resolver.resolve(query, upstream());
            if (answer != null) send(out, Packets.reply(packet, answer));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Lookup failed", e);
        }
    }

    /** The current network's own DNS servers plus two public ones. Rechecked often. */
    private List<InetSocketAddress> upstream() throws IOException {
        List<InetSocketAddress> current = servers;
        if (current != null && System.nanoTime() - serversCheckedAt < 5_000_000_000L) return current;

        Set<InetSocketAddress> found = new LinkedHashSet<>();
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities can = manager.getNetworkCapabilities(network);
            if (can == null || can.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !can.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            LinkProperties link = manager.getLinkProperties(network);
            if (link == null) continue;
            for (InetAddress address : link.getDnsServers()) {
                if (found.size() < 3) found.add(new InetSocketAddress(address, 53));
            }
        }
        found.add(new InetSocketAddress(InetAddress.getByName("1.1.1.1"), 53));
        found.add(new InetSocketAddress(InetAddress.getByName("8.8.8.8"), 53));

        current = new ArrayList<>(found);
        servers = current;
        serversCheckedAt = System.nanoTime();
        return current;
    }

    private void send(FileOutputStream out, byte[] packet) {
        synchronized (out) {
            try {
                out.write(packet);
            } catch (IOException e) {
                Log.w(TAG, "Could not write reply", e);
            }
        }
    }

    private BlockList loadList() throws IOException {
        BlockList list = new BlockList();
        try (Reader r = new InputStreamReader(getAssets().open("blocklist.txt"), StandardCharsets.UTF_8)) {
            list.load(r);
        }
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        loadFile(list, Lists.STANDARD);
        if (settings.getBoolean("strong", false)) loadFile(list, Lists.STRONG);
        list.allow(settings.getString("allow", ""));
        return list;
    }

    private void loadFile(BlockList list, String name) throws IOException {
        File file = new File(getFilesDir(), name);
        if (!file.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            list.load(r);
        }
    }
}
