package live.budeme.ptz;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Exposed to the controller page as window.PTZApp. Only runs when the user taps "Update":
 * downloads the APK from GitHub and hands it to the Android installer, which always asks for confirmation.
 * Progress goes back to the page through window.ptzUpdStatus(code, info).
 */
public final class Updater {
    private static final String ACTION = "live.budeme.ptz.INSTALL_STATUS";
    private final Activity activity;
    private final WebView web;
    private volatile boolean busy = false;
    private BroadcastReceiver receiver;

    Updater(Activity activity, WebView web) { this.activity = activity; this.web = web; }

    @JavascriptInterface public String version() {
        try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    @JavascriptInterface public void installUpdate(final String url) {
        if (busy) return;
        if (url == null || !url.startsWith("https://github.com/marianputsko/ptz_controller/")) { status("error", "bad url"); return; }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            status("perm", "");
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                } catch (Exception e) { status("error", e.getMessage()); }
            });
            return;
        }
        busy = true;
        new Thread(() -> {
            try { download(url); }
            catch (Exception e) { status("error", String.valueOf(e.getMessage())); }
            finally { busy = false; }
        }, "Updater").start();
    }

    private void download(String url) throws Exception {
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) { // GitHub redirects to its file storage
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            break;
        }
        long total = c.getContentLengthLong();
        PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        if (total > 0) params.setSize(total);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try (InputStream in = c.getInputStream()) {
            OutputStream out = session.openWrite("update.apk", 0, total);
            try {
                byte[] buf = new byte[65536];
                long done = 0; int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != lastPct && pct % 10 == 0) { lastPct = pct; status("progress", pct + " %"); }
                }
                session.fsync(out);
            } finally {
                out.close(); // must be closed before commit
            }
            registerReceiver();
            Intent i = new Intent(ACTION).setPackage(activity.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(activity, id, i, flags);
            session.commit(p.getIntentSender());
        } catch (Exception e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
            c.disconnect();
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { activity.startActivity(confirm); status("confirm", ""); } catch (Exception e) { status("error", e.getMessage()); }
                    }
                } else if (st != PackageInstaller.STATUS_SUCCESS) {
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    status(st == PackageInstaller.STATUS_FAILURE_ABORTED ? "" : "error", msg == null ? "" : msg);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, f);
    }

    void release() {
        if (receiver != null) { try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {} receiver = null; }
    }

    private void status(String code, String info) {
        final String js = "window.ptzUpdStatus&&window.ptzUpdStatus(" + Tally.Json.str(code) + "," + Tally.Json.str(info) + ")";
        activity.runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }
}
