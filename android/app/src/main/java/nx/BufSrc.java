package nx;

import android.media.MediaDataSource;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sumber data MediaPlayer untuk URL http(s) dengan buffering sendiri:
 *  - mengunduh bertahap ke file cache (read-ahead) sebesar "ahead" byte di depan posisi putar
 *    -> memuat banyak tapi sedikit-sedikit, tidak menghabiskan data/baterai;
 *  - jika koneksi putus sebentar (Wi-Fi tidur, pindah sinyal) unduhan otomatis disambung lagi dari byte terakhir
 *    -> tidak patah-patah / berhenti;
 *  - seek jauh memulai ulang unduhan dari posisi baru (HTTP Range).
 */
class BufSrc extends MediaDataSource {
    private static final int MAX_RETRY = 6;
    private final String url;
    private final java.util.Map<String, String> hdrs;
    private final File file;
    private final long ahead;
    private final Object lock = new Object();
    private RandomAccessFile rd;
    private volatile long size = -1, base = 0, filled = 0, readPos = 0, gen = 0;
    private volatile boolean closed = false, done = false, failed = false;

    BufSrc(String url, File cacheDir, long aheadBytes, java.util.Map<String, String> headers) {
        this.url = url; this.hdrs = headers == null ? new java.util.HashMap<String, String>() : headers;
        this.ahead = Math.max(512 * 1024, aheadBytes);
        this.file = new File(cacheDir, "buf-" + Integer.toHexString(url.hashCode()) + "-" + System.nanoTime() + ".bin");
    }

    /** Blok sampai header pertama diterima (ukuran file diketahui). Panggil dari thread latar. */
    boolean open() {
        try {
            rd = new RandomAccessFile(file, "rw");
            startWriter(0);
            synchronized (lock) {
                long end = System.currentTimeMillis() + 20000;
                while (!closed && !failed && size < 0 && filled == 0 && !done) {
                    long left = end - System.currentTimeMillis(); if (left <= 0) break;
                    lock.wait(Math.min(left, 200));
                }
            }
            return !failed && (size >= 0 || filled > 0 || done);
        } catch (Exception e) { return false; }
    }

    /** Persen data yang sudah ada di cache (0..100) terhadap total, -1 jika ukuran tak diketahui. */
    int bufferedPercent() { return size > 0 ? (int) Math.min(100, filled * 100 / size) : -1; }
    long bufferedUntil() { return filled; }

    private void startWriter(final long start) {
        final long g;
        synchronized (lock) { g = ++gen; base = start; filled = start; done = false; failed = false; lock.notifyAll(); }
        Thread t = new Thread(new Runnable() { public void run() { pump(g, start); } }, "alisa-buf");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        t.start();
    }

    private boolean alive(long g) { return !closed && g == gen; }

    private void pump(long g, long start) {
        long pos = start; int retry = 0;
        RandomAccessFile w = null;
        try {
            w = new RandomAccessFile(file, "rw");
            byte[] buf = new byte[64 * 1024];
            while (alive(g)) {
                HttpURLConnection c = null; InputStream in = null;
                try {
                    String cu = url;
                    int code = 0;
                    for (int hop = 0; hop < 6; hop++) {                                  // ikuti redirect manual (termasuk http<->https)
                        c = (HttpURLConnection) new URL(cu).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(12000); c.setReadTimeout(15000);
                        boolean hasUa = false;
                        for (java.util.Map.Entry<String, String> e : hdrs.entrySet()) { c.setRequestProperty(e.getKey(), e.getValue()); if ("user-agent".equalsIgnoreCase(e.getKey())) hasUa = true; }
                        if (!hasUa) c.setRequestProperty("User-Agent", P.UA);
                        if (pos > 0) c.setRequestProperty("Range", "bytes=" + pos + "-");
                        code = c.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = c.getHeaderField("Location"); c.disconnect(); c = null;
                            if (loc == null) throw new java.io.IOException("Redirect tanpa Location");
                            cu = new URL(new URL(cu), loc).toString(); continue;
                        }
                        break;
                    }
                    if (c == null) throw new java.io.IOException("Terlalu banyak pengalihan");
                    if (code >= 400) throw new java.io.IOException("HTTP " + code);
                    if (size < 0) {
                        long len = c.getContentLengthLong();
                        if (code == 206) {                                   // Content-Range: bytes a-b/total
                            String cr = c.getHeaderField("Content-Range");
                            if (cr != null && cr.indexOf('/') > 0) { try { len = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (Exception ignored) { } }
                        }
                        if (len > 0) size = len;
                        synchronized (lock) { lock.notifyAll(); }
                    }
                    in = c.getInputStream();
                    if (code == 200 && pos > 0) {                      // server tak mendukung Range: lewati byte awal
                        long skip = pos; while (skip > 0 && alive(g)) { long s = in.skip(skip); if (s <= 0) break; skip -= s; }
                    }
                    w.seek(pos); retry = 0;
                    while (alive(g)) {
                        synchronized (lock) {                          // throttle: jangan terlalu jauh di depan pemutar
                            while (alive(g) && pos - readPos > ahead) lock.wait(250);
                        }
                        if (!alive(g)) break;
                        int n = in.read(buf);
                        if (n < 0) { synchronized (lock) { if (alive(g)) { done = true; lock.notifyAll(); } } return; }
                        w.write(buf, 0, n); pos += n;
                        synchronized (lock) { if (alive(g)) { filled = pos; lock.notifyAll(); } }
                    }
                } catch (Exception e) {
                    if (!alive(g)) return;
                    if (++retry > MAX_RETRY) { synchronized (lock) { if (alive(g)) { failed = true; lock.notifyAll(); } } return; }
                    try { Thread.sleep(Math.min(5000, 700L * retry)); } catch (InterruptedException ie) { return; }
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) { }
                    if (c != null) c.disconnect();
                }
            }
        } catch (Exception e) {
            synchronized (lock) { if (alive(g)) { failed = true; lock.notifyAll(); } }
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) { }
        }
    }

    @Override public int readAt(long position, byte[] buffer, int offset, int sz) {
        if (closed) return -1;
        if (sz <= 0) return 0;
        try {
            synchronized (lock) {
                readPos = position;
                if (size >= 0 && position >= size) return -1;
                if (position < base || position > filled + 2L * 1024 * 1024) { startWriterLocked(position); }
                long end = System.currentTimeMillis() + 45000;
                while (!closed && filled <= position && !done && !failed) {
                    long left = end - System.currentTimeMillis(); if (left <= 0) return -1;
                    lock.notifyAll(); lock.wait(Math.min(left, 250));
                }
                if (closed) return -1;
                if (filled <= position) return -1;
            }
            int n = (int) Math.min((long) sz, filled - position);
            synchronized (rd) { rd.seek(position); return rd.read(buffer, offset, n); }
        } catch (Exception e) { return -1; }
    }

    private void startWriterLocked(long start) {   // dipanggil saat memegang lock; Thread baru tidak butuh lock lama
        final long g = ++gen; base = start; filled = start; done = false; failed = false; lock.notifyAll();
        final long s = start;
        Thread t = new Thread(new Runnable() { public void run() { pump(g, s); } }, "alisa-buf");
        t.setPriority(Thread.NORM_PRIORITY - 1); t.setDaemon(true); t.start();
    }

    @Override public long getSize() { return size; }

    @Override public void close() {
        closed = true;
        synchronized (lock) { lock.notifyAll(); }
        try { if (rd != null) rd.close(); } catch (Exception ignored) { }
        try { file.delete(); } catch (Exception ignored) { }
    }
}
