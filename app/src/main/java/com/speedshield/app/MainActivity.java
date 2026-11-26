package com.speedshield.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class MainActivity extends Activity {
    private static final int ASK_VPN = 1;
    private static final int BACKGROUND = 0xFF0B1020;
    private static final int CARD = 0xFF151C33;
    private static final int RAISED = 0xFF222B4A;
    private static final int WHITE = 0xFFF4F7FF;
    private static final int MUTED = 0xFF8C96B5;
    private static final int GREEN = 0xFF22E58B;

    private static volatile boolean downloading;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences settings;
    private PowerButton power;
    private TextView title, subtitle, blocked, allowed, rules, levelHelp, standard, strong, update, warning, skip;
    private LinearLayout recent;
    private String shownRecent;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            draw();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(28));

        TextView brand = text(page, "SpeedShield", 28, WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        text(page, "Fewer ads. Less tracking.", 14, MUTED);

        power = new PowerButton(this);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(220), dp(220));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        size.topMargin = dp(12);
        page.addView(power, size);
        power.setOnClickListener(v -> {
            if (AdVpnService.running) command(AdVpnService.STOP);
            else askAndStart();
        });

        title = text(page, "", 22, WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        subtitle = text(page, "", 14, MUTED);
        subtitle.setGravity(Gravity.CENTER);

        warning = text(page, "", 14, 0xFFFFC857);
        warning.setBackground(round(0xFF332A12, 16));
        warning.setPadding(dp(14), dp(12), dp(14), dp(12));
        warning.setVisibility(View.GONE);

        LinearLayout stats = new LinearLayout(this);
        page.addView(stats, spaced(18));
        blocked = stat(stats, "Blocked", GREEN);
        allowed = stat(stats, "Allowed", WHITE);
        rules = stat(stats, "Rules", WHITE);

        LinearLayout level = card(page, "Blocking level");
        LinearLayout choices = new LinearLayout(this);
        level.addView(choices, spaced(10));
        standard = choice(choices, "Standard");
        strong = choice(choices, "Strong");
        standard.setOnClickListener(v -> setStrong(false));
        strong.setOnClickListener(v -> setStrong(true));
        levelHelp = text(level, "", 13, MUTED);

        LinearLayout apps = card(page, "App refuses to work with a VPN?");
        text(apps, "Let it skip SpeedShield. Skipped apps get no ad blocking, and some can "
                + "still notice that a VPN is on.", 13, MUTED);
        skip = button(apps, "Choose apps to skip", false);
        skip.setOnClickListener(v -> chooseApps());
        button(apps, "Block ads without a VPN", false).setOnClickListener(v -> noVpnMode());

        LinearLayout keep = card(page, "Start automatically (Xiaomi)");
        text(keep, "Do all three. In VPN settings, open SpeedShield's options and turn on "
                + "Always-on VPN. Leave \"Block connections without VPN\" OFF, or your internet "
                + "will stop. Then lock SpeedShield in the recent apps screen.", 13, MUTED);
        button(keep, "1. Always-on VPN", false)
                .setOnClickListener(v -> open(new Intent(Settings.ACTION_VPN_SETTINGS)));
        button(keep, "2. Allow Autostart", false).setOnClickListener(v -> open(new Intent()
                .setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"))));
        button(keep, "3. Battery saver: No restrictions", false)
                .setOnClickListener(v -> open(appPage()));

        LinearLayout youtube = card(page, "YouTube viewer");
        text(youtube, "Watch the YouTube website inside SpeedShield. It hides feed ads and "
                + "skips video ads. The normal YouTube app is not changed.", 13, MUTED);
        TextView open = button(youtube, "Open YouTube here", true);
        open.setOnClickListener(v -> startActivity(new Intent(this, YouTubeActivity.class)));

        LinearLayout log = card(page, "Recently blocked");
        text(log, "Tap a name to allow it.", 13, MUTED);
        recent = column();
        log.addView(recent, spaced(4));

        LinearLayout tools = card(page, "Settings");
        button(tools, "Allowed domains", false).setOnClickListener(v -> editAllowed());
        update = button(tools, "Update block lists", false);
        update.setOnClickListener(v -> download(true));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void draw() {
        boolean on = AdVpnService.running;
        boolean busy = AdVpnService.loading || downloading;
        power.setState(on, busy);
        title.setText(AdVpnService.loading ? "Starting..." : on ? "You're protected" : "Protection is off");
        subtitle.setText(downloading ? "Downloading block lists..."
                : on ? "Tap the button to stop" : "Tap the button to start");
        blocked.setText(number(AdVpnService.blockedCount.get()));
        allowed.setText(number(AdVpnService.allowedCount.get()));
        rules.setText(AdVpnService.ruleCount == 0 ? "-" : number(AdVpnService.ruleCount));

        boolean isStrong = settings.getBoolean("strong", false);
        select(standard, !isStrong);
        select(strong, isStrong);
        levelHelp.setText(isStrong
                ? "Much bigger list. If an app or site breaks, allow its domain or go back to Standard."
                : "Blocks common ads and trackers with little chance of breaking things.");
        int skipped = settings.getStringSet("excluded", new HashSet<>()).size();
        skip.setText(skipped == 0 ? "Choose apps to skip" : "Skipped apps: " + skipped);
        String problem = null;
        if (on && AdVpnService.lockdown) {
            problem = "Turn OFF \"Block connections without VPN\" in VPN settings, then restart "
                    + "protection. It cuts off your internet.";
        } else if (on && privateDnsSet()) {
            problem = "Private DNS is set to a server name, so SpeedShield's filter may be "
                    + "bypassed. Set Private DNS to Off or Automatic.";
        }
        warning.setVisibility(problem == null ? View.GONE : View.VISIBLE);
        if (problem != null) warning.setText(problem);
        update.setEnabled(!downloading);
        update.setAlpha(downloading ? 0.5f : 1f);
        drawRecent();
    }

    private void drawRecent() {
        List<String> names = AdVpnService.recentBlocked();
        if (names.toString().equals(shownRecent)) return;
        shownRecent = names.toString();
        recent.removeAllViews();
        if (names.isEmpty()) text(recent, "Nothing yet", 14, WHITE);
        for (String name : names) {
            TextView row = text(recent, name, 14, WHITE);
            row.setPadding(0, dp(9), 0, dp(9));
            row.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Allow this domain?")
                    .setMessage(name)
                    .setPositiveButton("Allow", (d, w) -> {
                        String list = settings.getString("allow", "");
                        saveAllowed((list + "\n" + name).trim());
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
    }

    private boolean privateDnsSet() {
        if (Build.VERSION.SDK_INT < 28) return false;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        LinkProperties link = manager.getLinkProperties(network);
        return link != null && link.getPrivateDnsServerName() != null;
    }

    private void chooseApps() {
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo info : manager.queryIntentActivities(launcher, 0)) {
            String id = info.activityInfo.packageName;
            if (!id.equals(getPackageName())) {
                found.put(info.loadLabel(manager) + "\n" + id, id);
            }
        }
        CharSequence[] labels = found.keySet().toArray(new CharSequence[0]);
        String[] ids = found.values().toArray(new String[0]);
        Set<String> chosen = new HashSet<>(settings.getStringSet("excluded", new HashSet<>()));
        boolean[] ticked = new boolean[ids.length];
        for (int i = 0; i < ids.length; i++) ticked[i] = chosen.contains(ids[i]);

        new AlertDialog.Builder(this)
                .setTitle("Apps that skip SpeedShield")
                .setMultiChoiceItems(labels, ticked, (dialog, which, on) -> {
                    if (on) chosen.add(ids[which]);
                    else chosen.remove(ids[which]);
                })
                .setPositiveButton("Save", (d, w) -> {
                    settings.edit().putStringSet("excluded", chosen).apply();
                    restartIfRunning();
                    toast("Saved. Close and reopen those apps.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void noVpnMode() {
        String server = "dns.adguard-dns.com";
        new AlertDialog.Builder(this)
                .setTitle("Block ads without a VPN")
                .setMessage("This turns SpeedShield off and uses AdGuard's public DNS instead, so "
                        + "no VPN is running. Open Private DNS in your phone settings, choose the "
                        + "server name option and paste:\n\n" + server)
                .setPositiveButton("Copy and open settings", (d, w) -> {
                    getSystemService(ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText("Private DNS", server));
                    if (AdVpnService.running) command(AdVpnService.STOP);
                    open(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Intent appPage() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
    }

    /** Opens a settings screen, or this app's info page if the phone lacks that screen. */
    private void open(Intent screen) {
        try {
            startActivity(screen);
        } catch (RuntimeException e) {
            toast("Could not open that screen. Look for the option here or in Settings.");
            startActivity(appPage());
        }
    }

    private void editAllowed() {
        EditText box = new EditText(this);
        box.setMinLines(5);
        box.setGravity(Gravity.TOP);
        box.setHint("One domain per line");
        box.setText(settings.getString("allow", ""));
        new AlertDialog.Builder(this)
                .setTitle("Allowed domains")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> saveAllowed(box.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveAllowed(String text) {
        settings.edit().putString("allow", text).apply();
        restartIfRunning();
        toast("Saved");
    }

    private void setStrong(boolean value) {
        if (downloading || settings.getBoolean("strong", false) == value) return;
        settings.edit().putBoolean("strong", value).apply();
        if (value && !Lists.has(this, Lists.STRONG)) download(false);
        else restartIfRunning();
        draw();
    }

    /** Gets missing lists, or fresh copies of all lists in use when refreshAll is true. */
    private void download(boolean refreshAll) {
        if (downloading) return;
        downloading = true;
        Context app = getApplicationContext();
        new Thread(() -> {
            String problem = null;
            try {
                if (refreshAll || !Lists.has(app, Lists.STANDARD)) Lists.download(app, Lists.STANDARD);
                if (settings.getBoolean("strong", false)
                        && (refreshAll || !Lists.has(app, Lists.STRONG))) {
                    Lists.download(app, Lists.STRONG);
                }
            } catch (IOException e) {
                problem = e.getMessage();
                // Do not claim Strong is active when its list never arrived.
                if (!Lists.has(app, Lists.STRONG)) settings.edit().putBoolean("strong", false).apply();
            }
            String failed = problem;
            downloading = false;
            runOnUiThread(() -> {
                restartIfRunning();
                toast(failed == null ? "Block lists ready" : "Download failed: " + failed);
            });
        }).start();
    }

    private void askAndStart() {
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, ASK_VPN);
        else start();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == ASK_VPN && result == RESULT_OK) start();
    }

    private void start() {
        command(null);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        if (!Lists.has(this, Lists.STANDARD)) download(false);
    }

    private void command(String action) {
        startService(new Intent(this, AdVpnService.class).setAction(action));
    }

    private void restartIfRunning() {
        if (!AdVpnService.running) return;
        try {
            command(null);
        } catch (IllegalStateException e) {
            toast("Turn protection off and on to apply changes");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ----- small building blocks for the screen -----

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String number(int value) {
        return value >= 10000 ? String.format(Locale.US, "%dk", value / 1000) : String.valueOf(value);
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        parent.addView(view, spaced(2));
        return view;
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout box = column();
        box.setBackground(round(CARD, 20));
        box.setPadding(dp(18), dp(16), dp(18), dp(18));
        parent.addView(box, spaced(14));
        text(box, heading, 17, WHITE).setTypeface(Typeface.DEFAULT_BOLD);
        return box;
    }

    private TextView stat(LinearLayout row, String label, int color) {
        LinearLayout box = column();
        box.setBackground(round(CARD, 18));
        box.setPadding(dp(4), dp(12), dp(4), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        row.addView(box, p);
        TextView value = text(box, "0", 22, color);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        text(box, label, 12, MUTED).setGravity(Gravity.CENTER);
        return value;
    }

    private TextView choice(LinearLayout row, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(3), 0, dp(3), dp(6));
        row.addView(view, p);
        return view;
    }

    private void select(TextView view, boolean selected) {
        view.setTextColor(selected ? BACKGROUND : WHITE);
        view.setBackground(round(selected ? GREEN : RAISED, 14));
        view.setSelected(selected);
    }

    private TextView button(LinearLayout parent, String label, boolean main) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(14), dp(12), dp(14));
        view.setTextColor(main ? BACKGROUND : WHITE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),
                round(main ? GREEN : RAISED, 14), null));
        view.setClickable(true);
        view.setFocusable(true);
        parent.addView((View) view, spaced(12));
        return view;
    }
}
