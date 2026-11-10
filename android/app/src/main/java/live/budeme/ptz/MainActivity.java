package live.budeme.ptz;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * Full-screen landscape WebView showing the PTZ controller, served by {@link LocalServer} on 127.0.0.1.
 * The cameras are reached directly from the page; the switcher tally comes from the built-in server.
 */
public class MainActivity extends Activity {
    static final int PORT = 18080;
    private static LocalServer server; // one per process, survives activity recreation
    private WebView web;
    private Updater updater;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.parseColor("#171B20")));

        startServer();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#171B20"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"127.0.0.1".equals(r.getUrl().getHost()); // stay inside the controller
            }
        });
        updater = new Updater(this, web);
        web.addJavascriptInterface(updater, "PTZApp");
        setContentView(web);
        hideBars();
        if (state != null) web.restoreState(state);
        else web.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    private void startServer() {
        if (server != null) return;
        final SharedPreferences prefs = getSharedPreferences("ptz", MODE_PRIVATE);
        server = new LocalServer(PORT, new LocalServer.Store() {
            @Override public String get(String key, String def) { return prefs.getString(key, def); }
            @Override public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
            @Override public byte[] asset(String path) throws Exception {
                try (InputStream in = getAssets().open(path)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[16384]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    return out.toByteArray();
                } catch (FileNotFoundException e) { return null; }
            }
        });
        try { server.start(); } catch (Exception e) { server = null; }
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (web != null) web.saveState(out); }

    /** Back closes the settings panel instead of leaving the app in the middle of a show. */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript(
            "(function(){var s=document.getElementById('settings');if(s&&s.style.display==='flex'){document.getElementById('closeSet').click();}})()", null);
    }

    @Override protected void onDestroy() {
        if (updater != null) { updater.release(); updater = null; }
        if (web != null) { web.destroy(); web = null; }
        if (isFinishing() && server != null) { server.stop(); server = null; }
        super.onDestroy();
    }
}
