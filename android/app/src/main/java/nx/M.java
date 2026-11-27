package nx;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** Activity tunggal: WebView yang memuat assets/index.html + jembatan native "NX". */
public class M extends Activity {
    WebView w;
    ValueCallback<Uri[]> fileCb;   // pemilih file untuk <input type=file> (alat, pustaka, pemutar)

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(1);                       // tanpa title bar
        getWindow().setStatusBarColor(0xFF0C1020);
        getWindow().setNavigationBarColor(0xFF0C1020);
        w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.setBackgroundColor(0xFF0C1020);
        w.addJavascriptInterface(new B(this), "NX");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7416);
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), 7414); return true; }
                catch (Throwable t) { fileCb = null; return false; }
            }
        });
        setContentView(w);
        Intent i = getIntent();
        String u = "file:///android_asset/index.html";
        if ("android.intent.action.SEND".equals(i.getAction())) {
            String t = i.getStringExtra("android.intent.extra.TEXT");
            if (t != null) u = u + "?s=" + Uri.encode(t);
        }
        w.loadUrl(u);
        if (android.os.Build.VERSION.SDK_INT >= 23) w.postDelayed(() -> { if (w != null) w.evaluateJavascript("window.__nativeReady&&window.__nativeReady()", null); }, 500);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String t = i.getStringExtra("android.intent.extra.TEXT");
        if (t != null) w.evaluateJavascript("window.__share&&__share(decodeURIComponent(\"" + Uri.encode(t) + "\"))", null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7414) {
            if (fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null; }
            return;
        }
        if (requestCode == 7412 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri=data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch(Throwable ignored) {}
            getSharedPreferences("alisa_files",0).edit().putString("tree_uri",uri.toString()).apply();
            if(w!=null) { String u=org.json.JSONObject.quote(uri.toString()); w.evaluateJavascript("window.__folderChosen&&window.__folderChosen("+u+")",null); }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==7411 && w!=null) w.evaluateJavascript("window.__mediaPermissionResult&&window.__mediaPermissionResult()",null);
    }

    @Override public void onBackPressed() {
        if (w.getUrl() == null) { super.onBackPressed(); return; }
        w.evaluateJavascript("window.__back&&__back()", null);
    }
}
