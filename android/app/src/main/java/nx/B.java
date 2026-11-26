package nx;

import android.app.Activity;
import android.content.ContentUris;
import android.provider.MediaStore;
import android.database.Cursor;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.DocumentsContract;
import android.os.Build;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import java.util.Hashtable;

/** Jembatan JS -> native (window.NX). HTTP bebas-CORS, DownloadManager, clipboard. */
public class B {
    Activity a;
    Hashtable m = new Hashtable();

    public B(Activity a) { this.a = a; }

    /** packed = method \u0001 url \u0001 headers(\n) \u0001 body \u0001 timeoutMs ; hasil diambil lewat poll(id) */
    @JavascriptInterface public void req(String id, String packed) {
        new Thread(new H(m, id, packed)).start();
    }

    @JavascriptInterface public String poll(String id) { return (String) m.remove(id); }

    @JavascriptInterface public String dl(String url, String name, String mime, String hd) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(name);
            r.setMimeType(mime);
            r.setNotificationVisibility(1);
            r.setDestinationInExternalPublicDir("Download/alisadownloader", name);
            String[] hs = hd.split("\n");
            for (int i = 0; i < hs.length; i++) {
                int k = hs[i].indexOf(':');
                if (k > 0) r.addRequestHeader(hs[i].substring(0, k).trim(), hs[i].substring(k + 1).trim());
            }
            DownloadManager d = (DownloadManager) a.getSystemService("download");
            return String.valueOf(d.enqueue(r));
        } catch (Throwable t) { return ""; }
    }

    /** "status|bytes|total" ; status: 1 antre, 2 jalan, 4 jeda, 8 selesai, 16 gagal */
    @JavascriptInterface public String dlState(String id) {
        try {
            DownloadManager d = (DownloadManager) a.getSystemService("download");
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(new long[]{Long.parseLong(id)});
            Cursor c = d.query(q);
            String r = "";
            if (c.moveToFirst()) {
                StringBuilder s = new StringBuilder();
                s.append(c.getInt(c.getColumnIndex("status")));
                s.append("|");
                s.append(c.getLong(c.getColumnIndex("bytes_so_far")));
                s.append("|");
                s.append(c.getLong(c.getColumnIndex("total_size")));
                r = s.toString();
            }
            c.close();
            return r;
        } catch (Throwable t) { return ""; }
    }

    @JavascriptInterface public void copy(String t) {
        try {
            ClipboardManager c = (ClipboardManager) a.getSystemService("clipboard");
            c.setPrimaryClip(ClipData.newPlainText("nx", t));
        } catch (Throwable e) { }
    }

    @JavascriptInterface public String paste() {
        try {
            ClipboardManager c = (ClipboardManager) a.getSystemService("clipboard");
            ClipData d = c.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return "";
            return d.getItemAt(0).coerceToText(a).toString();
        } catch (Throwable e) { return ""; }
    }

    @JavascriptInterface public void open(String u) {
        try {
            Intent i = new Intent("android.intent.action.VIEW", Uri.parse(u));
            i.addFlags(0x10000000);
            a.startActivity(i);
        } catch (Throwable e) { }
    }

    @JavascriptInterface public void downloads() {
        try {
            Intent i = new Intent("android.intent.action.VIEW_DOWNLOADS");
            i.addFlags(0x10000000);
            a.startActivity(i);
        } catch (Throwable e) { }
    }


    /** Katalog media lokal melalui MediaStore; hasil JSON aman untuk WebView. */
    @JavascriptInterface public String mediaList(String kind) {
        JSONArray out = new JSONArray(); Cursor c = null;
        try {
            Uri base; String[] projection; String sort;
            if ("image".equals(kind)) { base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE}; sort = MediaStore.Images.Media.DATE_ADDED + " DESC"; }
            else if ("video".equals(kind)) { base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; projection = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DURATION}; sort = MediaStore.Video.Media.DATE_ADDED + " DESC"; }
            else { base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; projection = new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION}; sort = MediaStore.Audio.Media.DATE_ADDED + " DESC"; }
            c = a.getContentResolver().query(base, projection, null, null, sort);
            if (c != null) while (c.moveToNext()) {
                long id=c.getLong(0); JSONObject o=new JSONObject();
                o.put("id",id); o.put("name",c.getString(1)); o.put("size",c.getLong(2)); o.put("mime",c.getString(4));
                o.put("uri",ContentUris.withAppendedId(base,id).toString());
                int d=c.getColumnIndex("duration"); if(d>=0)o.put("duration",c.getLong(d));
                int ar=c.getColumnIndex("artist"); if(ar>=0)o.put("artist",c.getString(ar));
                out.put(o);
            }
        } catch(Throwable ignored) {} finally { if(c!=null)c.close(); }
        return out.toString();
    }

    /** Buka media lokal melalui pemutar yang tersedia di perangkat. */
    @JavascriptInterface public void playMedia(String uri, String mime) {
        try { Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse(uri)); i.setDataAndType(Uri.parse(uri), mime==null?"*/*":mime); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(i); } catch(Throwable ignored) {}
    }

    /** Meminta pengguna memilih folder melalui Storage Access Framework. */
    @JavascriptInterface public void chooseFolder() {
        try { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); a.startActivityForResult(i, 7412); } catch(Throwable ignored) {}
    }

    @JavascriptInterface public void startBackgroundService() {
        try { Intent i=new Intent(a, nx.DownloadKeepAliveService.class); if(android.os.Build.VERSION.SDK_INT>=26)a.startForegroundService(i); else a.startService(i); } catch(Throwable ignored) {}
    }
    @JavascriptInterface public void stopBackgroundService() { try { a.stopService(new Intent(a,nx.DownloadKeepAliveService.class)); } catch(Throwable ignored) {} }


    /* ===== Simpan hasil alat (base64 per potongan -> Download/alisadownloader) ===== */
    Hashtable sv = new Hashtable();

    /** Mulai sesi simpan. Hasil: id sesi, "PERM" bila izin tulis (Android <=9) belum diberikan, "" bila gagal. */
    @JavascriptInterface public String saveBegin(String name, String mime) {
        try {
            if (Build.VERSION.SDK_INT < 29 && a.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 7415);
                return "PERM";
            }
            String id = "s" + System.nanoTime();
            java.io.File f = new java.io.File(a.getCacheDir(), id + ".part");
            sv.put(id, new Object[]{f, name, mime == null ? "application/octet-stream" : mime});
            return id;
        } catch (Throwable t) { return ""; }
    }

    @JavascriptInterface public boolean saveChunk(String id, String b64) {
        try {
            Object[] s = (Object[]) sv.get(id);
            if (s == null) return false;
            java.io.FileOutputStream o = new java.io.FileOutputStream((java.io.File) s[0], true);
            o.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT));
            o.close();
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Selesai: salin dari cache ke Download/alisadownloader. Hasil: nama file akhir, "" bila gagal. */
    @JavascriptInterface public String saveEnd(String id) {
        Object[] s = (Object[]) sv.remove(id);
        if (s == null) return "";
        java.io.File tmp = (java.io.File) s[0]; String name = safeName((String) s[1]), mime = (String) s[2];
        try {
            java.io.InputStream in = new java.io.FileInputStream(tmp);
            java.io.OutputStream out; Uri item = null; android.content.ContentResolver cr = a.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/alisadownloader");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (item == null) { in.close(); return ""; }
                out = cr.openOutputStream(item);
            } else {
                java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "alisadownloader");
                dir.mkdirs();
                out = new java.io.FileOutputStream(new java.io.File(dir, name));
            }
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            if (item != null) { android.content.ContentValues v2 = new android.content.ContentValues(); v2.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(item, v2, null, null); }
            return name;
        } catch (Throwable t) { return ""; }
        finally { tmp.delete(); }
    }

    static String safeName(String n) {
        if (n == null || n.trim().length() == 0) return "alisa-file";
        return n.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
    }

    /** Haptic ringan tanpa izin tambahan. */
    @JavascriptInterface public void haptic() {
        a.runOnUiThread(new Runnable() { public void run() { try { a.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable t) { } } });
    }

    /** Jaga layar tetap menyala (mis. saat memproses PDF/video panjang). */
    @JavascriptInterface public void keepAwake(final boolean on) {
        a.runOnUiThread(new Runnable() { public void run() {
            if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } });
    }

    /** Info jaringan: "wifi" | "cell" | "none" — dipakai opsi Hanya Wi-Fi. */
    @JavascriptInterface public String netType() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) a.getSystemService("connectivity");
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "none";
            return ni.getType() == android.net.ConnectivityManager.TYPE_WIFI ? "wifi" : "cell";
        } catch (Throwable t) { return "wifi"; }
    }

    /** Buka folder Unduhan aplikasi di file manager bila tersedia; jika tidak, daftar Unduhan sistem. */
    @JavascriptInterface public void openOutput() { downloads(); }

    @JavascriptInterface public void exit() { a.finish(); }
}
