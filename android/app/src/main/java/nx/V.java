package nx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import com.kruzenlab.alisalite.R;
import java.util.Locale;

/**
 * Pemutar video Alisa Lite — port dari VideoPlayerActivity Nimiyo (VideoView + kontrol overlay) dengan tambahan:
 * lanjut dari posisi terakhir, ketuk dua kali untuk maju/mundur 10 dtk, kecepatan 0.5x-2x, fokus audio
 * (menjeda musik), indikator buffering, layar tetap menyala, dan mode imersif.
 */
public class V extends Activity {
    public static final String X_URI = "uri", X_TITLE = "title", X_POS = "pos";
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f};

    private VideoView vv;
    private ProgressBar loading;
    private LinearLayout top, bottom;
    private ImageButton btnPlay, btnFs;
    private TextView tvCur, tvTotal, tvTitle, btnSpeed, tvHint;
    private SeekBar seek;
    private MediaPlayer mpRef;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean userSeek = false, controls = true, hasFocus = false;
    private int speedI = 0;
    private Uri uri;
    private String key = "";
    private AudioManager am;

    private final Runnable hide = new Runnable() { public void run() { hideControls(); } };
    private final Runnable hintHide = new Runnable() { public void run() { tvHint.setVisibility(View.GONE); } };
    private final Runnable progress = new Runnable() {
        public void run() {
            if (vv != null && vv.isPlaying() && !userSeek) {
                int p = vv.getCurrentPosition(); seek.setProgress(p); tvCur.setText(fmt(p));
            }
            h.postDelayed(this, 300);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusL = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int c) {
            if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (vv != null && vv.isPlaying()) { vv.pause(); setPlayIcon(false); } }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_video);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        vv = (VideoView) findViewById(R.id.nativeVideoView);
        loading = (ProgressBar) findViewById(R.id.videoLoadingProgress);
        top = (LinearLayout) findViewById(R.id.topBarOverlay);
        bottom = (LinearLayout) findViewById(R.id.bottomControlsOverlay);
        btnPlay = (ImageButton) findViewById(R.id.btnPlayPause);
        btnFs = (ImageButton) findViewById(R.id.btnFullscreen);
        tvCur = (TextView) findViewById(R.id.tvCurrentTime);
        tvTotal = (TextView) findViewById(R.id.tvTotalDuration);
        tvTitle = (TextView) findViewById(R.id.tvVideoTitle);
        btnSpeed = (TextView) findViewById(R.id.btnSpeed);
        tvHint = (TextView) findViewById(R.id.tvHint);
        seek = (SeekBar) findViewById(R.id.videoSeekBar);
        View root = findViewById(R.id.videoRootContainer);

        String u = getIntent().getStringExtra(X_URI), t = getIntent().getStringExtra(X_TITLE);
        long startPos = getIntent().getLongExtra(X_POS, -1);
        if (u == null || u.length() == 0) { toast("Video tidak ditemukan"); finish(); return; }
        uri = Uri.parse(u); key = "v" + Integer.toHexString(u.hashCode());
        tvTitle.setText(t == null || t.trim().length() == 0 ? "Video" : t);
        if (startPos < 0) startPos = getSharedPreferences("vpos", MODE_PRIVATE).getLong(key, 0);
        final int resume = (int) startPos;

        findViewById(R.id.btnVideoBack).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        findViewById(R.id.btnVideoShare).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { share(); } });
        btnPlay.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { togglePlay(); } });
        btnFs.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setRequestedOrientation(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 6); } });
        btnSpeed.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { speedI = (speedI + 1) % SPEEDS.length; applySpeed(); schedHide(); } });

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                boolean right = e.getX() > getWindow().getDecorView().getWidth() / 2f;
                jump(right ? 10000 : -10000); return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) { gd.onTouchEvent(e); return true; } });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) tvCur.setText(fmt(p)); }
            public void onStartTrackingTouch(SeekBar s) { userSeek = true; h.removeCallbacks(hide); }
            public void onStopTrackingTouch(SeekBar s) { userSeek = false; vv.seekTo(s.getProgress()); schedHide(); }
        });

        vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer m) {
                mpRef = m; loading.setVisibility(View.GONE);
                int d = vv.getDuration(); seek.setMax(d > 0 ? d : 100); tvTotal.setText(fmt(d));
                if (resume > 3000 && d > 0 && resume < d - 5000) vv.seekTo(resume);
                try { m.setOnInfoListener(new MediaPlayer.OnInfoListener() { public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) loading.setVisibility(View.VISIBLE);
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) loading.setVisibility(View.GONE);
                    return false; } }); } catch (Throwable ignored) { }
                getFocus(); vv.start(); applySpeed(); setPlayIcon(true);
                h.removeCallbacks(progress); h.post(progress); schedHide();
            }
        });
        vv.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer m) { setPlayIcon(false); seek.setProgress(seek.getMax()); showControls(); getSharedPreferences("vpos", MODE_PRIVATE).edit().remove(key).apply(); }
        });
        vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int w, int x) { loading.setVisibility(View.GONE); openExternal(); return true; }
        });
        try { vv.setVideoURI(uri); } catch (Throwable e) { toast("Gagal memuat video"); finish(); }
        updateFsIcon();
        immersive();
    }

    /* ===================== kontrol ===================== */
    private void togglePlay() {
        if (vv.isPlaying()) { vv.pause(); setPlayIcon(false); showControls(); h.removeCallbacks(hide); }
        else { getFocus(); vv.start(); applySpeed(); setPlayIcon(true); schedHide(); }
    }

    private void setPlayIcon(boolean playing) { btnPlay.setImageResource(playing ? R.drawable.ic_video_pause : R.drawable.ic_video_play); }

    private void applySpeed() {
        float s = SPEEDS[speedI];
        btnSpeed.setText((s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x");
        if (mpRef != null && vv.isPlaying()) {                               // setPlaybackParams() saat dijeda akan memulai putar, jadi hanya saat sedang main
            try { PlaybackParams pp = mpRef.getPlaybackParams(); pp.setSpeed(s); mpRef.setPlaybackParams(pp); } catch (Throwable ignored) { }
        }
    }

    private void jump(int ms) {
        int d = vv.getDuration(), p = Math.max(0, Math.min(d > 0 ? d - 500 : Integer.MAX_VALUE, vv.getCurrentPosition() + ms));
        vv.seekTo(p); seek.setProgress(p); tvCur.setText(fmt(p));
        tvHint.setText(ms > 0 ? "+10 dtk" : "−10 dtk"); tvHint.setVisibility(View.VISIBLE);
        h.removeCallbacks(hintHide); h.postDelayed(hintHide, 700);
    }

    private void getFocus() {
        if (hasFocus) return;
        try { hasFocus = am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; } catch (Throwable ignored) { }
    }

    private void showControls() {
        controls = true; top.setVisibility(View.VISIBLE); bottom.setVisibility(View.VISIBLE);
        top.animate().alpha(1f).setDuration(180).start(); bottom.animate().alpha(1f).setDuration(180).start();
    }

    private void hideControls() {
        if (!controls || !vv.isPlaying()) return;
        controls = false;
        top.animate().alpha(0f).setDuration(240).withEndAction(new Runnable() { public void run() { if (!controls) top.setVisibility(View.GONE); } }).start();
        bottom.animate().alpha(0f).setDuration(240).withEndAction(new Runnable() { public void run() { if (!controls) bottom.setVisibility(View.GONE); } }).start();
    }

    private void toggleControls() { if (controls) { h.removeCallbacks(hide); controls = true; hideControls(); if (!vv.isPlaying()) { /* tetap tampil saat jeda */ } } else { showControls(); schedHide(); } }
    private void schedHide() { h.removeCallbacks(hide); h.postDelayed(hide, 3500); }

    private void updateFsIcon() { btnFs.setImageResource(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? R.drawable.ic_video_fullscreen_exit : R.drawable.ic_video_fullscreen); }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void share() {
        try {
            Intent i = new Intent(Intent.ACTION_SEND); i.setType("video/*"); i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Bagikan video"));
        } catch (Throwable e) { toast("Tidak bisa membagikan video ini"); }
    }

    private void openExternal() {                                            // format tak didukung: serahkan ke pemutar lain (seperti Nimiyo)
        try {
            Intent i = new Intent(Intent.ACTION_VIEW); i.setDataAndType(uri, "video/*"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Buka video dengan"));
        } catch (Throwable e) { toast("Gagal memutar format video"); }
        finish();
    }

    private void savePos() {
        try {
            int p = vv.getCurrentPosition(), d = vv.getDuration();
            SharedPreferences.Editor e = getSharedPreferences("vpos", MODE_PRIVATE).edit();
            if (d > 30000 && p > 3000 && p < d - 5000) e.putLong(key, p); else e.remove(key);
            e.apply();
        } catch (Throwable ignored) { }
    }

    private static String fmt(int ms) {
        if (ms <= 0) return "00:00";
        int s = ms / 1000, ss = s % 60, mm = (s / 60) % 60, hh = s / 3600;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%02d:%02d", mm, ss);
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); updateFsIcon(); immersive(); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @Override protected void onPause() {
        super.onPause();
        if (vv != null) { savePos(); if (vv.isPlaying()) { vv.pause(); setPlayIcon(false); } }
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (hasFocus) { try { am.abandonAudioFocus(focusL); } catch (Throwable ignored) { } }
        if (vv != null) { try { vv.stopPlayback(); } catch (Throwable ignored) { } }
        super.onDestroy();
    }
}
