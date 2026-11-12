package com.mattintech.acme.store;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * "Acme Apps": a minimal stand-in for a company's in-house app store.
 *
 * It installs bundled APKs with the PackageInstaller session API, the same way
 * a real in-house or third-party store does. Because it is not the device's
 * EMM/DPC, Android developer verification applies to these installs on
 * certified devices. Verification checks the app being installed, not the
 * store, so each listed app needs its own registration.
 */
public class MainActivity extends Activity {

    private static final String ACTION_STATUS = "com.mattintech.acme.store.INSTALL_STATUS";
    private static final String EXTRA_PKG = "pkg";

    /** One app listed in the store. */
    static final class Listing {
        final String name, pkg, asset, meta, desc;
        final int icon;
        final boolean registered;
        Button button;
        TextView status;

        Listing(String name, String pkg, String asset, int icon, boolean registered, String meta, String desc) {
            this.name = name; this.pkg = pkg; this.asset = asset; this.icon = icon;
            this.registered = registered; this.meta = meta; this.desc = desc;
        }
    }

    // "registered" only drives the badge in the UI. The actual verification
    // result on a test device is forced with:
    //   adb shell pm set-developer-verification-result <package> 3 1   (pass)
    //   adb shell pm set-developer-verification-result <package> 3 2   (reject)
    private final Listing[] listings = {
        new Listing("Acme Timesheets", "com.mattintech.acme.timesheets", "timesheets.apk",
                R.drawable.timesheets_icon, true,
                "v1.0 · Internal · All staff",
                "Log hours and submit your weekly timesheet."),
        new Listing("Acme Field Agent", "com.mattintech.acme.fieldagent", "fieldagent.apk",
                R.drawable.fieldagent_icon, false,
                "v1.0 · Internal · Field staff",
                "Work orders, parts lookup, and job sign-off for Acme field technicians."),
    };

    private final Map<String, Listing> byPkg = new HashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Listing l = byPkg.get(intent.getStringExtra(EXTRA_PKG));
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
                return;
            }
            if (l == null) return;
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            StringBuilder sb = new StringBuilder();
            sb.append(code == PackageInstaller.STATUS_SUCCESS ? "Installed ✓" : "Install failed")
              .append("\nstatus: ").append(statusName(code));
            if (message != null) sb.append("\nmessage: ").append(message);
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    if (key.toLowerCase().contains("verif")) {
                        sb.append("\n").append(key).append(": ").append(extras.get(key));
                    }
                }
            }
            showStatus(l, sb.toString(), code == PackageInstaller.STATUS_SUCCESS);
            l.button.setEnabled(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (Listing l : listings) byPkg.put(l.pkg, l);
        setContentView(buildUi());
        IntentFilter filter = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void install(Listing l) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            showStatus(l, "Allow Acme Apps to install apps, then tap Install again.", false);
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        l.button.setEnabled(false);
        showStatus(l, "Installing…", true);
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(l.pkg);
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = getAssets().open(l.asset);
                     OutputStream out = session.openWrite("base.apk", 0, -1)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent callback = new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_PKG, l.pkg);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(this, sessionId, callback, flags);
                session.commit(pi.getIntentSender());
            }
        } catch (Exception e) {
            showStatus(l, "Install failed\n" + e, false);
            l.button.setEnabled(true);
        }
    }

    private static String statusName(int code) {
        switch (code) {
            case PackageInstaller.STATUS_SUCCESS: return "SUCCESS";
            case PackageInstaller.STATUS_FAILURE: return "FAILURE";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "FAILURE_BLOCKED";
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "FAILURE_ABORTED";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "FAILURE_INVALID";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "FAILURE_CONFLICT";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "FAILURE_STORAGE";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "FAILURE_INCOMPATIBLE";
            default: return String.valueOf(code);
        }
    }

    private void showStatus(Listing l, String text, boolean ok) {
        l.status.setVisibility(View.VISIBLE);
        l.status.setText(text);
        l.status.setTextColor(ok ? Color.parseColor("#0a4f87") : Color.parseColor("#b91c1c"));
    }

    // ---- UI (built in code so the project needs no layout files) ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(String color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#f2f5f8"));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.parseColor("#0f6fbb"));
        header.setPadding(dp(20), dp(48), dp(20), dp(20));
        TextView title = new TextView(this);
        title.setText("Acme Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = new TextView(this);
        sub.setText("Acme Corp internal app store");
        sub.setTextColor(Color.parseColor("#cfe5f7"));
        sub.setTextSize(14);
        header.addView(title);
        header.addView(sub);
        root.addView(header);

        for (Listing l : listings) root.addView(buildCard(l), cardParams());

        TextView note = new TextView(this);
        note.setText("Demo only. Acme Apps installs do-nothing test apps the same way a company app store would. "
                + "Registration status is simulated on test devices with adb.");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#64748b"));
        note.setPadding(dp(20), dp(4), dp(20), dp(24));
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(16), dp(16), 0);
        return lp;
    }

    private View buildCard(Listing l) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(rounded("#ffffff", 14));
        card.setElevation(dp(2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(l.icon);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        row.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        TextView appName = new TextView(this);
        appName.setText(l.name);
        appName.setTextSize(19);
        appName.setTypeface(Typeface.DEFAULT_BOLD);
        appName.setTextColor(Color.parseColor("#090e17"));
        TextView meta = new TextView(this);
        meta.setText(l.meta);
        meta.setTextSize(13);
        meta.setTextColor(Color.parseColor("#64748b"));
        TextView badge = new TextView(this);
        badge.setText(l.registered ? "✓  Registered developer" : "!  Not registered");
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.parseColor(l.registered ? "#047857" : "#b45309"));
        badge.setBackground(rounded(l.registered ? "#ecfdf5" : "#fffbeb", 6));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(6);
        names.addView(appName);
        names.addView(meta);
        names.addView(badge, badgeLp);
        row.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);

        TextView desc = new TextView(this);
        desc.setText(l.desc);
        desc.setTextSize(15);
        desc.setTextColor(Color.parseColor("#334155"));
        desc.setPadding(0, dp(12), 0, dp(14));
        card.addView(desc);

        l.button = new Button(this);
        l.button.setText("Install");
        l.button.setAllCaps(false);
        l.button.setTextColor(Color.WHITE);
        l.button.setTextSize(16);
        l.button.setBackground(rounded("#0f6fbb", 10));
        l.button.setOnClickListener(v -> install(l));
        card.addView(l.button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        l.status = new TextView(this);
        l.status.setTextSize(13);
        l.status.setTypeface(Typeface.MONOSPACE);
        l.status.setPadding(0, dp(12), 0, 0);
        l.status.setVisibility(View.GONE);
        card.addView(l.status);
        return card;
    }
}
