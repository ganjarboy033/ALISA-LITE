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

    String dlErr = "";
    /** Alasan kegagalan unduhan terakhir (untuk ditampilkan di UI). */
    @JavascriptInterface public String dlError() { return dlErr; }

    @JavascriptInterface public String dl(String url, String name, String mime, String hd) {
        dlErr = "";
        String sub = "alisadownloader/" + name;                         // dirType HARUS "Download"; subfolder lewat subPath
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setTitle(name.indexOf('/') >= 0 ? name.substring(name.lastIndexOf('/') + 1) : name);
                if (mime != null && mime.length() > 0) r.setMimeType(mime);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                if (attempt == 0) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sub);
                else r.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, name);   // cadangan: folder app (selalu bisa ditulis)
                String[] hs = hd == null ? new String[0] : hd.split("\\n");
                for (int k = 0; k < hs.length; k++) {
                    int c = hs[k].indexOf(':');
                    if (c > 0) { String key = hs[k].substring(0, c).trim(), val = hs[k].substring(c + 1).trim().replace("\r", ""); if (key.length() > 0) r.addRequestHeader(key, val); }
                }
                DownloadManager d = (DownloadManager) a.getSystemService("download");
                String id = String.valueOf(d.enqueue(r));
                if (attempt == 1) dlErr = "Disimpan di folder aplikasi (Android/data/…/Download) karena folder Download publik ditolak";
                return id;
            } catch (Throwable t) { dlErr = (t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())); }
        }
        return "";
    }

    @JavascriptInterface public String dlState(String id) {
        if (id != null && id.startsWith("f")) { long[] s = fs.get(id); return s == null ? "" : s[0] + "|" + s[1] + "|" + s[2]; }
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
        if ("download".equals(kind)) return privateDownloads();
        JSONArray out = new JSONArray(); Cursor c = null;
        try {
            Uri base; String[] projection; String sort;
            if ("image".equals(kind)) { base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE}; sort = MediaStore.Images.Media.DATE_ADDED + " DESC"; }
            else if ("video".equals(kind)) { base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; projection = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DURATION}; sort = MediaStore.Video.Media.DATE_ADDED + " DESC"; }
            else { base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; projection = new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION}; sort = MediaStore.Audio.Media.DATE_ADDED + " DESC"; }
            String relCol = Build.VERSION.SDK_INT >= 29 ? "relative_path" : "_data";
            String[] p2 = new String[projection.length + 1]; System.arraycopy(projection, 0, p2, 0, projection.length); p2[projection.length] = relCol; projection = p2;
            c = a.getContentResolver().query(base, projection, null, null, sort);
            if (c != null) while (c.moveToNext()) {
                long id=c.getLong(0); JSONObject o=new JSONObject();
                o.put("id",id); o.put("name",c.getString(1)); o.put("size",c.getLong(2)); o.put("mime",c.getString(4));
                o.put("uri",ContentUris.withAppendedId(base,id).toString());
                int d=c.getColumnIndex("duration"); if(d>=0)o.put("duration",c.getLong(d));
                int rc=c.getColumnIndex(relCol); if(rc>=0)o.put("rel",c.getString(rc));
                int ar=c.getColumnIndex("artist"); if(ar>=0)o.put("artist",c.getString(ar));
                out.put(o);
            }
        } catch(Throwable ignored) {} finally { if(c!=null)c.close(); }
        return out.toString();
    }

    /** File hasil unduhan di folder aplikasi (Android/data/<pkg>/files/Download), dipakai jika folder Download publik ditolak. */
    String privateDownloads() {
        JSONArray out = new JSONArray();
        try { scan(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), out, 0); } catch (Throwable ignored) { }
        return out.toString();
    }
    void scan(java.io.File d, JSONArray out, int depth) throws Exception {
        if (d == null || depth > 3) return;
        java.io.File[] fl = d.listFiles(); if (fl == null) return;
        for (java.io.File f : fl) {
            if (f.isDirectory()) { scan(f, out, depth + 1); continue; }
            String n = f.getName(), ext = n.lastIndexOf('.') >= 0 ? n.substring(n.lastIndexOf('.') + 1).toLowerCase() : "", mime = "";
            if (ext.matches("mp3|m4a|aac|wav|flac|ogg|opus")) mime = "audio/" + ext;
            else if (ext.matches("mp4|webm|mkv|mov|3gp|m4v")) mime = "video/" + ext;
            else if (ext.matches("jpg|jpeg|png|webp|gif")) mime = "image/" + ext;
            if (mime.length() == 0) continue;
            JSONObject o = new JSONObject();
            o.put("id", f.getAbsolutePath()); o.put("name", n); o.put("size", f.length()); o.put("mime", mime);
            o.put("uri", "file://" + f.getAbsolutePath()); o.put("rel", "private/alisadownloader"); o.put("priv", true);
            out.put(o);
        }
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
        java.io.File tmp = (java.io.File) s[0];
        try { return publish(tmp, (String) s[1], (String) s[2]); } finally { tmp.delete(); }
    }

    /** Salin file sementara ke Download/alisadownloader[/subfolder]. name boleh berisi "Folder/nama.ext". Hasil: nama akhir atau "". */
    String publish(java.io.File tmp, String rawName, String mime) {
        String sub = "", nm = rawName == null ? "" : rawName;
        int sl = nm.lastIndexOf('/');
        if (sl >= 0) { sub = safeName(nm.substring(0, sl)); nm = nm.substring(sl + 1); }
        String name = safeName(nm);
        if (mime == null || mime.length() == 0) mime = "application/octet-stream";
        try {
            java.io.InputStream in = new java.io.FileInputStream(tmp);
            java.io.OutputStream out; Uri item = null; android.content.ContentResolver cr = a.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/alisadownloader" + (sub.length() > 0 ? "/" + sub : ""));
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (item == null) { in.close(); return ""; }
                out = cr.openOutputStream(item);
            } else {
                java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "alisadownloader" + (sub.length() > 0 ? "/" + sub : ""));
                dir.mkdirs();
                out = new java.io.FileOutputStream(new java.io.File(dir, name));
            }
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            if (item != null) { android.content.ContentValues v2 = new android.content.ContentValues(); v2.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(item, v2, null, null); }
            return name;
        } catch (Throwable t) { dlErr = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()); return ""; }
    }

    /* ===== Pengunduh native (cadangan DownloadManager; dipakai TikTok & tautan bertoken/chunked) ===== */
    final java.util.concurrent.ConcurrentHashMap<String, long[]> fs = new java.util.concurrent.ConcurrentHashMap<String, long[]>();
    int fseq = 0;

    /** Mulai unduhan di thread latar. Hasil: id "f#", "" bila gagal memulai (lihat dlError()). state: dlState(id). */
    @JavascriptInterface public String fdl(final String url, final String name, final String mime, final String hd) {
        dlErr = "";
        if (Build.VERSION.SDK_INT < 29 && a.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 7415);
            dlErr = "Izinkan akses penyimpanan lalu coba lagi"; return "";
        }
        final String id = "f" + (++fseq);
        final long[] st = new long[]{2, 0, 0};          // status(2=jalan,8=selesai,16=gagal), bytes, total
        fs.put(id, st);
        Thread t = new Thread(new Runnable() { public void run() {
            java.io.File tmp = new java.io.File(a.getCacheDir(), id + ".dl");
            java.net.HttpURLConnection c = null;
            try {
                String u = url;
                for (int hop = 0; hop < 6; hop++) {
                    c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/116.0 Mobile Safari/537.36");
                    String[] hs = hd == null ? new String[0] : hd.split("\\n");
                    for (int k = 0; k < hs.length; k++) { int p = hs[k].indexOf(':'); if (p > 0) { String key = hs[k].substring(0, p).trim(), val = hs[k].substring(p + 1).trim().replace("\r", ""); if (key.length() > 0) c.setRequestProperty(key, val); } }
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) { String loc = c.getHeaderField("Location"); c.disconnect(); if (loc == null) throw new java.io.IOException("Redirect tanpa Location"); u = new java.net.URL(new java.net.URL(u), loc).toString(); c = null; continue; }
                    if (code >= 400) throw new java.io.IOException("HTTP " + code);
                    break;
                }
                if (c == null) throw new java.io.IOException("Terlalu banyak pengalihan");
                st[2] = Math.max(0, c.getContentLengthLong());
                java.io.InputStream in = c.getInputStream(); java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); st[1] += n; }
                o.close(); in.close();
                if (st[1] <= 0) throw new java.io.IOException("Respons kosong");
                String r = publish(tmp, name, mime);
                if (r.length() == 0) throw new java.io.IOException(dlErr.length() > 0 ? dlErr : "Gagal menyimpan ke Download");
                if (st[2] <= 0) st[2] = st[1];
                st[0] = 8;
            } catch (Throwable e) { dlErr = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()); st[0] = 16; }
            finally { try { if (c != null) c.disconnect(); } catch (Throwable ignored) { } tmp.delete(); }
        } }, "alisa-fdl");
        t.setDaemon(true); t.start();
        return id;
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


    /* ===================== Pemutar native (P = audio service, V = video activity) ===================== */
    /** Mulai/ganti antrean audio. json: [{id,title,artist,uri,dur}], cfg: {aheadMb,focus,noisy,speed,pitch,rev,preset,wet,repeat,shuffle}. */
    @JavascriptInterface public void aQueue(String json, int index, boolean autoplay, long pos, String cfg) {
        try {
            Intent i = new Intent(a, P.class); i.setAction(P.A_SET);
            i.putExtra("queue", json); i.putExtra("index", index); i.putExtra("autoplay", autoplay); i.putExtra("pos", pos);
            JSONObject c = (cfg == null || cfg.length() == 0) ? new JSONObject() : new JSONObject(cfg);
            i.putExtra("aheadMb", c.optInt("aheadMb", 6)); i.putExtra("focus", c.optBoolean("focus", true)); i.putExtra("noisy", c.optBoolean("noisy", true));
            i.putExtra("speed", (float) c.optDouble("speed", 1)); i.putExtra("pitch", c.optBoolean("pitch", false));
            i.putExtra("rev", c.optBoolean("rev", false)); i.putExtra("preset", c.optInt("preset", 5)); i.putExtra("wet", (float) c.optDouble("wet", 0.6));
            i.putExtra("repeat", c.optInt("repeat", 0)); i.putExtra("shuffle", c.optBoolean("shuffle", false));
            P.start(a, i);
        } catch (Throwable ignored) { }
    }
    @JavascriptInterface public void aAdd(String json) { try { Intent i = new Intent(a, P.class); i.setAction(P.A_ADD); i.putExtra("queue", json); P.start(a, i); } catch (Throwable ignored) { } }
    @JavascriptInterface public String aState() { return P.stateOf(); }
    @JavascriptInterface public String aArt() { return P.artOf(); }
    @JavascriptInterface public String aQueueList() { P p = P.inst; return p == null ? "[]" : p.queueJson(); }
    @JavascriptInterface public void aToggle() { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.toggle(); } }); }
    @JavascriptInterface public void aPlay() { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) { if (p.mp == null) p.toggle(); else p.startPlayback(); } } }); }
    @JavascriptInterface public void aPause() { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.doPause(true); } }); }
    @JavascriptInterface public void aNext() { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.next(false); } }); }
    @JavascriptInterface public void aPrev() { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.prev(); } }); }
    @JavascriptInterface public void aSeek(final long ms) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.seek(ms); } }); }
    @JavascriptInterface public void aJump(final int i) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.playIndex(i, true, 0); } }); }
    @JavascriptInterface public void aSpeed(final double s, final boolean pitch) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.setSpeed((float) s, pitch); } }); }
    @JavascriptInterface public void aReverb(final boolean on, final int preset, final double wet) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.setReverb(on, preset, (float) wet); } }); }
    @JavascriptInterface public void aMode(final int repeat, final boolean shuffle) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) { p.repeat = repeat; p.shuffle = shuffle; } } }); }
    @JavascriptInterface public void aSleep(final int minutes) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) p.setSleep(minutes); } }); }
    @JavascriptInterface public void aCfg(final int aheadMb, final boolean focus, final boolean noisy) { P.post(new Runnable() { public void run() { P p = P.inst; if (p != null) { p.aheadBytes = Math.max(1, aheadMb) * 1024L * 1024L; p.focusOn = focus; p.noisyOn = noisy; } } }); }
    @JavascriptInterface public void aStop() { try { Intent i = new Intent(a, P.class); i.setAction(P.A_STOP); P.start(a, i); } catch (Throwable ignored) { } }

    /** Video dari URL jaringan dengan header (Referer/User-Agent/Cookie) — JSON objek. */
    @JavascriptInterface public void playVideoH(String uri, String title, long pos, String hdrJson) {
        try {
            Intent i = new Intent(a, V.class); i.putExtra(V.X_URI, uri); i.putExtra(V.X_TITLE, title); i.putExtra(V.X_POS, pos); i.putExtra(V.X_HDR, hdrJson == null ? "" : hdrJson);
            a.startActivity(i);
        } catch (Throwable ignored) { }
    }

    /** Buka pemutar video native (V). pos = ms awal, -1 = lanjut dari posisi tersimpan. */
    @JavascriptInterface public void playVideo(String uri, String title, long pos) {
        try {
            Intent i = new Intent(a, V.class); i.putExtra(V.X_URI, uri); i.putExtra(V.X_TITLE, title); i.putExtra(V.X_POS, pos);
            a.startActivity(i);
        } catch (Throwable ignored) { }
    }

    @JavascriptInterface public void exit() { a.finish(); }
}
