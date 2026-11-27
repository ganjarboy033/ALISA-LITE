package nx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.PresetReverb;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import com.kruzenlab.alisalite.R;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mesin audio Alisa Lite. Berbasis MusicPlaybackService milik Nimiyo (MediaPlayer + MediaSession + PresetReverb),
 * dengan perbaikan untuk masalah patah-patah / berhenti saat aplikasi ditinggalkan:
 *  - antrean & lagu berikutnya dijalankan DI SINI (native), bukan lewat JavaScript WebView yang dibekukan di latar belakang;
 *  - foreground service tipe mediaPlayback dimulai seketika, CPU wake lock (setWakeMode) + Wi-Fi lock untuk sumber jaringan;
 *  - audio focus (telepon/notifikasi/aplikasi lain) + auto-pause saat headset dicabut;
 *  - buffering sendiri untuk URL (BufSrc): read-ahead terkontrol + sambung ulang otomatis;
 *  - MixTools: kecepatan 60-140% (nada ikut berubah bila diminta) + reverb preset.
 */
public class P extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener {
    static final String CH = "alisa_player";
    static final int NID = 8811;
    static final String A_SET = "nx.player.SET", A_ADD = "nx.player.ADD", A_TOGGLE = "nx.player.TOGGLE", A_NEXT = "nx.player.NEXT", A_PREV = "nx.player.PREV", A_STOP = "nx.player.STOP";
    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0 Mobile Safari/537.36";
    static volatile P inst;

    static class T { String id = "", title = "Audio", artist = "", uri = "", hdr = ""; long dur = 0; }

    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final ExecutorService exArt = Executors.newSingleThreadExecutor();
    final Random rnd = new Random();

    ArrayList<T> q = new ArrayList<T>();
    int idx = -1, retries = 0, skips = 0;
    MediaPlayer mp;
    BufSrc src;
    boolean prepared = false, wantPlay = false, fg = false, resumeOnFocus = false, paramsDirty = false;
    long startPos = 0, loadGen = 0;
    String status = "idle", err = "";

    float speed = 1f; boolean pitch = false;
    boolean revOn = false, revOk = true; int revPreset = 5; float revWet = 0.6f; PresetReverb rv;
    int repeat = 0; boolean shuffle = false;
    long sleepAt = 0; boolean sleepEnd = false;
    long aheadBytes = 6L * 1024 * 1024; boolean focusOn = true, noisyOn = true;

    AudioManager am; boolean hasFocus = false;
    WifiManager.WifiLock wl;
    MediaSession ms;
    Bitmap art; String artB64 = ""; int artVer = 0;

    final Runnable tick = new Runnable() {
        public void run() {
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; doPause(true); }
            if (playing()) h.postDelayed(this, 500);
        }
    };

    final AudioManager.OnAudioFocusChangeListener focusL = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(final int c) {
            h.post(new Runnable() { public void run() {
                if (c == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; hasFocus = false; doPause(false); }
                else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (playing()) { resumeOnFocus = true; doPause(false); } }
                else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { vol(0.25f); }
                else if (c == AudioManager.AUDIOFOCUS_GAIN) { vol(1f); if (resumeOnFocus) { resumeOnFocus = false; startPlayback(); } }
            } });
        }
    };

    final BroadcastReceiver noisy = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { if (noisyOn && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) doPause(true); }
    };

    /* ===================== siklus hidup ===================== */
    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        makeChannel();
        try {
            ms = new MediaSession(this, "AlisaPlayer");
            ms.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { startPlayback(); }
                @Override public void onPause() { doPause(true); }
                @Override public void onSkipToNext() { next(false); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long pos) { seek(pos); }
                @Override public void onStop() { stopAll(); }
            });
            ms.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            ms.setActive(true);
        } catch (Throwable ignored) { }
        try { registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); } catch (Throwable ignored) { }
        ex.execute(new Runnable() { public void run() {                      // bersihkan sisa cache buffering lama
            try { File[] f = getCacheDir().listFiles(); if (f != null) for (File x : f) if (x.getName().startsWith("buf-") && x.getName().endsWith(".bin")) x.delete(); } catch (Throwable ignored) { }
        } });
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        ensureForeground();                                                  // wajib segera (batas 5 dtk) agar tidak dimatikan sistem
        if (in == null || in.getAction() == null) { if (q.isEmpty()) stopSelf(); return START_NOT_STICKY; }
        String a = in.getAction();
        if (A_SET.equals(a)) {
            aheadBytes = Math.max(1, in.getIntExtra("aheadMb", 6)) * 1024L * 1024L;
            focusOn = in.getBooleanExtra("focus", true); noisyOn = in.getBooleanExtra("noisy", true);
            speed = in.getFloatExtra("speed", speed); pitch = in.getBooleanExtra("pitch", pitch);
            revOn = in.getBooleanExtra("rev", revOn); revPreset = in.getIntExtra("preset", revPreset); revWet = in.getFloatExtra("wet", revWet);
            repeat = in.getIntExtra("repeat", repeat); shuffle = in.getBooleanExtra("shuffle", shuffle);
            setQueue(in.getStringExtra("queue"), in.getIntExtra("index", 0), in.getBooleanExtra("autoplay", true), in.getLongExtra("pos", 0));
        } else if (A_ADD.equals(a)) addQueue(in.getStringExtra("queue"));
        else if (A_TOGGLE.equals(a)) toggle();
        else if (A_NEXT.equals(a)) next(false);
        else if (A_PREV.equals(a)) prev();
        else if (A_STOP.equals(a)) stopAll();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        loadGen++;
        cleanupPlayer();
        if (rv != null) { try { rv.release(); } catch (Throwable ignored) { } rv = null; }
        abandonFocus();
        try { unregisterReceiver(noisy); } catch (Throwable ignored) { }
        if (ms != null) { try { ms.setActive(false); ms.release(); } catch (Throwable ignored) { } ms = null; }
        ex.shutdownNow(); exArt.shutdownNow();
        inst = null;
        super.onDestroy();
    }

    /* ===================== API statis untuk bridge ===================== */
    static void start(Context c, Intent i) {
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); } catch (Throwable ignored) { }
    }
    static void post(Runnable r) { P p = inst; if (p != null) p.h.post(r); }
    static String stateOf() { P p = inst; return p == null ? "{\"on\":false}" : p.stateJson(); }
    static String artOf() { P p = inst; return p == null ? "" : p.artB64; }

    /* ===================== antrean ===================== */
    void setQueue(String json, int index, boolean autoplay, long pos) {
        ArrayList<T> n = parse(json);
        if (n.isEmpty()) return;
        q = n; skips = 0;
        playIndex(Math.max(0, Math.min(index, q.size() - 1)), autoplay, pos);
    }

    void addQueue(String json) { ArrayList<T> n = parse(json); if (!n.isEmpty()) { q.addAll(n); if (idx < 0) playIndex(0, true, 0); } }

    ArrayList<T> parse(String json) {
        ArrayList<T> n = new ArrayList<T>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i); T t = new T();
                t.id = o.optString("id", "" + i); t.title = o.optString("title", "Audio"); t.artist = o.optString("artist", "");
                t.uri = o.optString("uri", ""); t.dur = o.optLong("dur", 0);
                JSONObject hh = o.optJSONObject("hdr"); if (hh != null) t.hdr = hh.toString();
                if (t.uri.length() > 0) n.add(t);
            }
        } catch (Throwable ignored) { }
        return n;
    }

    void playIndex(int i, boolean autoplay, long pos) {
        if (i < 0 || i >= q.size()) return;
        idx = i; wantPlay = autoplay; startPos = pos; retries = 0;
        prepare();
    }

    void prepare() {
        cleanupPlayer();
        status = "loading"; err = "";
        final T t = q.get(idx);
        final long g = ++loadGen;
        art = null; artB64 = ""; artVer++;
        meta(); notif();
        loadArt(t, g);
        ex.execute(new Runnable() { public void run() {
            BufSrc bs = null;
            try {
                final MediaPlayer m = new MediaPlayer();
                m.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build());
                m.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                if (t.uri.startsWith("http://") || t.uri.startsWith("https://")) {
                    bs = new BufSrc(t.uri, getCacheDir(), aheadBytes, hdrMap(t.hdr));
                    if (!bs.open()) { bs.close(); throw new Exception("Tidak bisa membuka sumber (cek koneksi)"); }
                    m.setDataSource(bs);
                } else if (t.uri.startsWith("content://")) m.setDataSource(P.this, Uri.parse(t.uri));
                else m.setDataSource(t.uri.startsWith("file://") ? t.uri.substring(7) : t.uri);
                final BufSrc fbs = bs;
                h.post(new Runnable() { public void run() {
                    if (g != loadGen) { try { m.release(); } catch (Throwable ignored) { } if (fbs != null) fbs.close(); return; }
                    mp = m; src = fbs;
                    m.setOnPreparedListener(P.this); m.setOnCompletionListener(P.this); m.setOnErrorListener(P.this); m.setOnInfoListener(P.this);
                    try { m.prepareAsync(); } catch (Throwable e) { fail("Gagal menyiapkan audio"); }
                } });
            } catch (final Throwable e) {
                if (bs != null) bs.close();
                h.post(new Runnable() { public void run() { if (g == loadGen) fail(e.getMessage() == null ? "Gagal membuka audio" : e.getMessage()); } });
            }
        } });
    }

    static java.util.Map<String, String> hdrMap(String json) {
        java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
        try { if (json != null && json.length() > 2) { JSONObject o = new JSONObject(json); java.util.Iterator<String> it = o.keys(); while (it.hasNext()) { String k = it.next(); m.put(k, o.optString(k)); } } } catch (Throwable ignored) { }
        return m;
    }

    void cleanupPlayer() {
        prepared = false;
        if (mp != null) {
            try { mp.setOnPreparedListener(null); mp.setOnCompletionListener(null); mp.setOnErrorListener(null); mp.setOnInfoListener(null); } catch (Throwable ignored) { }
            try { mp.reset(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
            mp = null;
        }
        if (src != null) { src.close(); src = null; }
        releaseWifi();
    }

    void fail(String msg) {
        status = "error"; err = msg == null ? "Error" : msg;
        meta(); notif();
        if (q.size() > 1 && skips < q.size() - 1) {                          // lewati lagu yang gagal, jangan berhenti total
            skips++;
            h.postDelayed(new Runnable() { public void run() { if ("error".equals(status)) next(true); } }, 900);
        } else { wantPlay = false; }
    }

    /* ===================== listener MediaPlayer ===================== */
    @Override public void onPrepared(MediaPlayer m) {
        if (m != mp) return;
        prepared = true; retries = 0; skips = 0; status = "ready";
        int d = 0; try { d = m.getDuration(); } catch (Throwable ignored) { }
        if (d > 0 && idx >= 0 && idx < q.size()) q.get(idx).dur = d;
        if (startPos > 0) { try { m.seekTo((int) startPos); } catch (Throwable ignored) { } }
        paramsDirty = false;
        applyEffects();
        meta();
        if (wantPlay) startPlayback(); else { status = "paused"; session(); notif(); }
    }

    @Override public void onCompletion(MediaPlayer m) {
        if (m != mp) return;
        if (sleepEnd) { sleepEnd = false; sleepAt = 0; doPause(true); return; }
        if (repeat == 2) { try { m.seekTo(0); m.start(); } catch (Throwable ignored) { } return; }
        next(true);
    }

    @Override public boolean onError(MediaPlayer m, int what, int extra) {
        if (m != mp) return true;
        if (what == -38 || extra == -38) return true;                        // ilegal state (sisa perubahan state), abaikan
        long p = 0; try { if (prepared) p = m.getCurrentPosition(); else p = startPos; } catch (Throwable ignored) { }
        prepared = false;
        if (retries < 2 && idx >= 0) { retries++; startPos = p; wantPlay = true; prepare(); return true; }   // coba sambung lagi dari posisi terakhir
        fail("Error pemutar (" + what + "/" + extra + ")");
        return true;
    }

    @Override public boolean onInfo(MediaPlayer m, int what, int extra) {
        if (m != mp) return true;
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) { status = "buffering"; session(); }
        else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) { if (playing()) status = "playing"; session(); }
        return true;
    }

    /* ===================== kontrol ===================== */
    boolean playing() { try { return mp != null && prepared && mp.isPlaying(); } catch (Throwable e) { return false; } }

    void startPlayback() {
        if (mp == null || !prepared) { wantPlay = true; return; }
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) sleepAt = 0;      // timer tidur yang sudah lewat saat dijeda
        if (focusOn && !requestFocus()) { wantPlay = false; status = "paused"; session(); notif(); return; }
        try {
            applyParams();
            mp.start();
            vol(1f);
        } catch (Throwable e) { fail("Gagal memutar"); return; }
        wantPlay = true; status = "playing";
        if (src != null) acquireWifi();
        ensureForeground();
        session(); notif();
        h.removeCallbacks(tick); h.post(tick);
    }

    void doPause(boolean abandon) {
        try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { }
        wantPlay = false; status = prepared ? "paused" : status;
        releaseWifi();
        if (abandon) abandonFocus();
        h.removeCallbacks(tick);
        session();
        try { stopForeground(false); fg = false; } catch (Throwable ignored) { }
        notif();
    }

    void toggle() {
        if (mp == null && idx >= 0 && idx < q.size()) { wantPlay = true; prepare(); return; }
        if (playing()) doPause(true); else startPlayback();
    }

    void next(boolean auto) {
        if (q.isEmpty()) return;
        int n;
        if (shuffle && q.size() > 1) { do { n = rnd.nextInt(q.size()); } while (n == idx); }
        else {
            n = idx + 1;
            if (n >= q.size()) {
                if (repeat == 1 || !auto) n = 0;
                else { finishQueue(); return; }
            }
        }
        playIndex(n, true, 0);
    }

    void prev() {
        if (q.isEmpty()) return;
        if (prepared && pos() > 4000) { seek(0); return; }
        int n = idx - 1; if (n < 0) n = q.size() - 1;
        playIndex(n, true, 0);
    }

    void finishQueue() {                                                     // daftar selesai & repeat mati: kembali ke awal lagu, jeda
        wantPlay = false;
        try { if (mp != null && prepared) { if (mp.isPlaying()) mp.pause(); mp.seekTo(0); } } catch (Throwable ignored) { }
        status = "paused"; releaseWifi(); abandonFocus(); h.removeCallbacks(tick); session();
        try { stopForeground(false); fg = false; } catch (Throwable ignored) { }
        notif();
    }

    void seek(long ms) {
        if (mp != null && prepared) { try { mp.seekTo((int) Math.max(0, ms)); } catch (Throwable ignored) { } } else startPos = Math.max(0, ms);
        session();
    }

    void setSpeed(float s, boolean p) {
        speed = Math.max(0.5f, Math.min(2f, s)); pitch = p;
        if (playing()) applyParams(); else paramsDirty = true;               // setPlaybackParams() pada MediaPlayer yang dijeda akan memulai putar -> tunda sampai resume
        session();
    }

    void applyParams() {
        if (mp == null || !prepared) return;
        if (speed == 1f && !pitch && !paramsDirty) return;
        try {
            PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(speed);
            pp.setPitch(pitch && speed != 1f ? speed : 1f);
            mp.setPlaybackParams(pp);
            paramsDirty = speed != 1f;
        } catch (Throwable ignored) { }
    }

    void setReverb(boolean on, int preset, float wet) {
        revOn = on; revPreset = Math.max(1, Math.min(6, preset)); revWet = Math.max(0f, Math.min(1f, wet));
        applyEffects();
    }

    void applyEffects() {
        if (mp == null) return;
        try {
            if (revOn) {
                if (rv == null) rv = new PresetReverb(0, 0);
                rv.setPreset((short) revPreset);
                rv.setEnabled(true);
                mp.attachAuxEffect(rv.getId());
                mp.setAuxEffectSendLevel(revWet);
                revOk = true;
            } else if (rv != null) {
                mp.setAuxEffectSendLevel(0f);
                rv.setEnabled(false);
            }
        } catch (Throwable t) { revOk = false; }                             // perangkat tak mendukung aux effect
    }

    void setSleep(int minutes) {
        sleepEnd = minutes < 0;
        sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60000L : 0;
    }

    void vol(float v) { try { if (mp != null) mp.setVolume(v, v); } catch (Throwable ignored) { } }

    void stopAll() {
        loadGen++;
        cleanupPlayer();
        q.clear(); idx = -1; wantPlay = false; status = "idle"; sleepAt = 0; sleepEnd = false;
        abandonFocus(); h.removeCallbacks(tick);
        if (ms != null) { try { ms.setActive(false); } catch (Throwable ignored) { } }
        try { stopForeground(true); } catch (Throwable ignored) { }
        fg = false;
        try { ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID); } catch (Throwable ignored) { }
        stopSelf();
    }

    /* ===================== fokus audio / wifi ===================== */
    boolean requestFocus() {
        if (hasFocus) return true;
        try { hasFocus = am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
        catch (Throwable e) { hasFocus = true; }
        return hasFocus;
    }
    void abandonFocus() { if (hasFocus) { try { am.abandonAudioFocus(focusL); } catch (Throwable ignored) { } hasFocus = false; } }

    void acquireWifi() {
        try {
            if (wl == null) { wl = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "alisa:play"); wl.setReferenceCounted(false); }
            if (!wl.isHeld()) wl.acquire();
        } catch (Throwable ignored) { }
    }
    void releaseWifi() { try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { } }

    /* ===================== state untuk UI ===================== */
    long pos() { try { return mp != null && prepared ? mp.getCurrentPosition() : startPos; } catch (Throwable e) { return startPos; } }
    long dur() { try { if (mp != null && prepared) { int d = mp.getDuration(); if (d > 0) return d; } } catch (Throwable ignored) { } return idx >= 0 && idx < q.size() ? q.get(idx).dur : 0; }

    String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", idx >= 0 && !q.isEmpty());
            o.put("playing", playing()); o.put("status", status); o.put("idx", idx); o.put("n", q.size());
            if (idx >= 0 && idx < q.size()) { T t = q.get(idx); o.put("id", t.id); o.put("title", t.title); o.put("artist", t.artist); }
            o.put("pos", pos()); o.put("dur", dur());
            BufSrc b = src; long d = dur();
            o.put("buf", b != null && d > 0 && b.bufferedPercent() >= 0 ? b.bufferedPercent() : (prepared ? 100 : 0));
            o.put("speed", speed); o.put("pitch", pitch); o.put("rev", revOn); o.put("revOk", revOk); o.put("preset", revPreset); o.put("wet", revWet);
            o.put("repeat", repeat); o.put("shuffle", shuffle);
            o.put("sleep", sleepEnd ? -1 : (sleepAt > 0 ? Math.max(0, (sleepAt - System.currentTimeMillis()) / 1000) : 0));
            o.put("art", artVer); o.put("err", err);
        } catch (Throwable ignored) { }
        return o.toString();
    }

    String queueJson() {
        JSONArray a = new JSONArray();
        try { for (T t : q) { JSONObject o = new JSONObject(); o.put("id", t.id); o.put("title", t.title); o.put("artist", t.artist); o.put("dur", t.dur); a.put(o); } } catch (Throwable ignored) { }
        return a.toString();
    }

    /* ===================== sampul album ===================== */
    void loadArt(final T t, final long g) {
        if (t.uri.startsWith("http")) return;
        exArt.execute(new Runnable() { public void run() {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (t.uri.startsWith("content://")) r.setDataSource(P.this, Uri.parse(t.uri));
                else r.setDataSource(t.uri.startsWith("file://") ? t.uri.substring(7) : t.uri);
                byte[] pic = r.getEmbeddedPicture();
                if (pic == null) return;
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                int s = 1; while (o.outWidth / (s * 2) >= 360 && o.outHeight / (s * 2) >= 360) s *= 2;
                o = new BitmapFactory.Options(); o.inSampleSize = s;
                final Bitmap b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                if (b == null) return;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int w = b.getWidth(), hh = b.getHeight(), sq = Math.min(w, hh);
                Bitmap sm = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, (w - sq) / 2, (hh - sq) / 2, sq, sq), 256, 256, true);
                sm.compress(Bitmap.CompressFormat.JPEG, 72, bo);
                final String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                h.post(new Runnable() { public void run() { if (g == loadGen) { art = b; artB64 = b64; artVer++; meta(); notif(); } } });
            } catch (Throwable ignored) { }
            finally { try { r.release(); } catch (Throwable ignored) { } }
        } });
    }

    /* ===================== MediaSession & notifikasi ===================== */
    void meta() {
        if (ms == null) return;
        try {
            MediaMetadata.Builder b = new MediaMetadata.Builder();
            if (idx >= 0 && idx < q.size()) {
                T t = q.get(idx);
                b.putString(MediaMetadata.METADATA_KEY_TITLE, t.title).putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist).putLong(MediaMetadata.METADATA_KEY_DURATION, dur());
            }
            if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            ms.setMetadata(b.build());
        } catch (Throwable ignored) { }
    }

    void session() {
        if (ms == null) return;
        try {
            boolean pl = playing();
            int st = "buffering".equals(status) || "loading".equals(status) ? PlaybackState.STATE_BUFFERING : (pl ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
            ms.setPlaybackState(new PlaybackState.Builder().setActions(acts).setState(st, pos(), pl ? speed : 0f).build());
        } catch (Throwable ignored) { }
    }

    void makeChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "Alisa Pemutar", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false); c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(c);
        }
    }

    PendingIntent act(String a, int code) {
        Intent i = new Intent(this, P.class); i.setAction(a);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, i, fl);
    }

    Notification build() {
        T t = idx >= 0 && idx < q.size() ? q.get(idx) : null;
        boolean pl = playing();
        Intent open = new Intent(this, M.class); open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent ci = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (ms != null) st.setMediaSession(ms.getSessionToken());
        b.setStyle(st).setSmallIcon(R.drawable.ic_music_note).setContentTitle(t == null ? "Alisa Lite" : t.title)
            .setContentText(err.length() > 0 && "error".equals(status) ? err : (t == null || t.artist.length() == 0 ? "Alisa Pemutar" : t.artist))
            .setContentIntent(ci).setDeleteIntent(act(A_STOP, 9)).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(pl).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Sebelumnya", act(A_PREV, 1)).build())
            .addAction(new Notification.Action.Builder(pl ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, pl ? "Jeda" : "Putar", act(A_TOGGLE, 2)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Berikutnya", act(A_NEXT, 3)).build());
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    void ensureForeground() {
        if (fg) return;
        try {
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NID, n);
            fg = true;
        } catch (Throwable ignored) { }
    }

    void notif() {
        try {
            if (fg) { ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NID, build()); }
            else if (idx >= 0) { ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NID, build()); }
        } catch (Throwable ignored) { }
    }
}
