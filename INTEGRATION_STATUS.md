# Alisa Lite — Native integration V4 (Galeri & File Manager)

## Selesai pada source code revisi ini
- Meminta izin baca media berdasarkan versi Android: Android 13+ meminta akses gambar, video, dan audio; Android 14+ juga mendukung izin visual terpilih. Android 6–12 menggunakan izin penyimpanan baca yang sesuai.
- Setelah hasil izin diterima, pustaka MediaStore dimuat ulang otomatis.
- Galeri/pustaka menampilkan katalog media lokal audio, video, dan gambar dari MediaStore. Menekan item membuka file dengan aplikasi yang tersedia di perangkat.
- Tombol **Jelajahi folder** membuka Android Storage Access Framework (SAF), bukan meminta akses menyeluruh ke seluruh penyimpanan.
- Izin folder yang dipilih disimpan secara persisten, sehingga folder terakhir dapat dibuka lagi setelah aplikasi dijalankan ulang.
- File manager dapat menelusuri folder bertingkat, kembali ke folder sebelumnya, melihat nama/jenis/ukuran file, dan membuka file dengan aplikasi Android yang sesuai.
- Pengguna dapat memilih folder lain atau melepas akses folder tersimpan.
- UI utama Alisa Lite tetap menggunakan desain V2; tidak diganti dengan tampilan Mori.
- Lokasi unduhan DownloadManager tetap `Download/alisadownloader`.

## Batasan yang masih ada
- File manager pada tahap ini adalah penjelajah dan pembuka file. Operasi salin, pindah, hapus, rename, kompres, dan ekstrak belum dibuat.
- Android membatasi isi MediaStore berdasarkan izin yang diberikan. Pada Android 14, jika pengguna hanya memberi akses foto/video terpilih, daftar galeri mengikuti batas tersebut.
- Item MediaStore dibuka menggunakan aplikasi penampil/pemutar yang terpasang. Pemutar Media3 internal dan MediaSession belum diintegrasikan.
- Floating bubble/overlay belum diintegrasikan.
- Modul Mori masih disimpan sebagai sumber referensi; tidak seluruh alat Mori sudah terhubung.

## Pemeriksaan yang dilakukan
- `node --check web/app.js` — lulus.
- `node --check web/engine.js` — lulus.
- Parsing `AndroidManifest.xml` — lulus.
- Gradle/Java compilation dan pengujian pada perangkat Android belum dijalankan pada lingkungan ini. Jalankan workflow GitHub Actions untuk memastikan APK berhasil dibangun.

## Catatan keamanan dan privasi
- Akses file memakai SAF dan izin folder yang dipilih pengguna. Aplikasi tidak meminta izin `MANAGE_EXTERNAL_STORAGE` untuk membaca seluruh penyimpanan.
- Izin folder tersimpan dapat dilepas dari menu Jelajahi folder.

---

# V5 — Tools Mori terhubung (ringan)

## Menu Mori yang sudah diperiksa dan statusnya di Alisa Lite
| Menu Mori | Status di Alisa Lite |
|---|---|
| Home (downloader) | Tetap memakai engine scraper Nexora bawaan (tidak diubah) |
| Gallery | Sudah ada sebagai **Pustaka** (MediaStore + SAF, V3/V4) |
| History | Sudah ada sebagai **Unduhan**; ditambah *Batas riwayat* di Setelan → Lanjutan |
| Settings | Setelan lama + panel **Lanjutan** baru: Hanya Wi-Fi, Getar halus, Mode ringkas, tampil/sembunyi tab Pustaka & Tools, batas riwayat, User-Agent alat |
| Tools → Video to Audio | **Aktif** (WAV 16-bit, maks 200 MB) |
| Tools → Image Converter | **Aktif** (JPG/PNG/WebP, kualitas + skala) |
| Tools → Compress PDF | **Aktif** (pdf.js + pdf-lib dimuat dari CDN saat pertama dipakai, tidak dibundel) |
| Tools → Web Media Sniffer | **Aktif** (DOM + OG meta + JSON/script scan) |
| Tools → TikTok User Finder | **Aktif** (kartu baru di Tools) |
| Tools → Username Checker | **Aktif** (20 platform + 9 domain, 5 permintaan paralel) |
| Tools → Anime Scene Search | **Aktif** (trace.moe, URL atau file) |
| Tools → Safelinku Resolver | **Diganti** resolver redirect biasa (lihat catatan di bawah) |
| Batch download | **Aktif** sebagai alat (banyak tautan → scraper bawaan → DownloadManager) |

## Backend native yang ditambahkan (Java)
- `WebChromeClient.onShowFileChooser` di `M.java` — tanpa ini `<input type=file>` tidak membuka apa pun di WebView (berdampak juga ke Pustaka & Pemutar).
- `B.saveBegin/saveChunk/saveEnd` — simpan hasil alat ke `Download/alisadownloader` (MediaStore di Android 10+, file langsung di Android 7–9 dengan permintaan izin tulis).
- `B.haptic`, `B.keepAwake`, `B.netType` — getar halus tanpa izin tambahan, jaga layar saat memproses, deteksi Wi-Fi/seluler.
- Tidak ada izin Android baru di `AndroidManifest.xml`.

## Menjaga APK tetap ringan
- Seluruh kode alat ada di satu file `web/tools.js` (~50 KB mentah) yang **dimuat hanya saat menu Tools dibuka**; startup tidak berubah.
- Library PDF Mori (pdf.js, worker, pdf-lib ≈ 2 MB) **tidak dibundel**. Dimuat dari cdnjs dengan versi terkunci (pdf-lib 1.17.1, pdf.js 3.11.174) hanya saat Kompres PDF dipakai.
- Tidak membawa Capacitor, 10 file bahasa Mori (~350 KB), landing page, folder iOS, maupun `libmorisec.so`.
- Scraper/engine OTA tidak disentuh.

## Yang sengaja tidak dibawa dari Mori
- **Safelinku/sfl.gl bypass**: kode asli memalsukan token sesi untuk melewati alur iklan layanan tersebut. Diganti resolver redirect biasa.
- **Rotasi IP palsu di TikTok Finder** (header `X-Forwarded-For` acak untuk menghindari kuota). Dihapus; jika layanan penuh, pengguna diminta mencoba lagi nanti.
- Belum dibawa: 9 bahasa tambahan, biometrik/kunci aplikasi, floating bubble/share overlay, pemutar Media3, sound pack, auto-backup. Semuanya menambah ukuran atau memerlukan modul native besar.

## Pemeriksaan
- `node --check` untuk `app.js`, `engine.js`, `tools.js`, `server.js` — lulus.
- Uji logika dengan jaringan tiruan (resolver, TikTok, 29 pemeriksa username, DoH domain) — lulus.
- **Belum dijalankan:** kompilasi Java/Gradle dan pengujian di perangkat (tidak ada Android SDK di lingkungan ini). Jalankan workflow GitHub Actions lalu uji tiap alat di HP, terutama simpan file di Android 9 ke bawah dan Kompres PDF (butuh internet pertama kali).

---

# V6 — Pemutar Nimiyo + MixTools + galeri digabung ke tab Pemutar

## Penyebab "patah-patah / berhenti saat keluar app" (hasil membaca kode Nimiyo v2.4.0)
1. **Lagu berikutnya diserahkan ke JavaScript.** `MusicPlaybackService.handleNextAction()` hanya mengirim event "next" ke WebView. Saat app di latar belakang WebView dibekukan, event tidak sampai, lagu berhenti di akhir track. → Di Alisa, antrean, shuffle, repeat, dan auto-next berjalan **di service native** (`nx/P.java`).
2. **Tidak ada `setWakeMode`/WifiLock** untuk sumber jaringan, wake lock hanya diambil saat state diperbarui → CPU/Wi-Fi tidur → patah-patah. → `setWakeMode(PARTIAL_WAKE_LOCK)` + `WifiLock` saat memutar URL.
3. **Tidak ada audio focus / handling headset dicabut.** → ditambahkan (bisa dimatikan di Setelan → Pemutar).
4. **`onError` ditelan diam-diam** (pemutar berhenti tanpa pesan). → coba ulang dari posisi terakhir 2×, lalu lewati ke lagu berikutnya + pesan error.
5. **Buffer URL = bawaan MediaPlayer (kecil).** → `nx/BufSrc.java`: unduh bertahap ke cache disk (read-ahead 2/6/16 MB, bisa dipilih di Setelan → Pemutar), sambung otomatis dari byte terakhir jika koneksi putus, seek jauh memakai HTTP Range. Memuat banyak tapi sedikit-sedikit.
6. **Izin `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + tipe service `mediaPlayback`** dideklarasikan, `startForeground` dipanggil seketika di `onStartCommand` (batas 5 detik Android).
7. Catatan: Nimiyo membuat `PresetReverb` tanpa izin `MODIFY_AUDIO_SETTINGS` di manifest, sehingga reverb native kemungkinan gagal diam-diam (itu sebabnya mereka punya jalur Web Audio). Alisa menambahkan izinnya dan melaporkan `revOk=false` ke UI bila perangkat tidak mendukung.

## Yang dibawa dari Nimiyo
- Mesin audio: MediaPlayer + MediaSession + notifikasi media + PresetReverb + PlaybackParams (kecepatan/pitch).
- **MixTools**: Speed FX 60–140% (nada ikut berubah gaya slowed/nightcore) dan Reverb FX dengan XY-pad (Y = ukuran ruang → preset SmallRoom…LargeHall, X = wet). Pemetaan preset sama dengan Nimiyo.
- Pemutar video native (VideoView + overlay) dengan layout/ikon asli Nimiyo, ditambah: lanjut dari posisi terakhir, ketuk dua kali ±10 dtk, kecepatan 0.5–2×, fokus audio (musik otomatis dijeda), indikator buffering.

## Tab Pemutar (galeri digabung)
Kartu pemutar bergaya Mori (sampul besar + latar blur, kontrol bulat, bar buffer) di atas galeri: filter Semua/Musik/Video/Gambar, pencarian, urutan, sampul/thumbnail gambar, pemuatan bertahap. Ketuk musik → antrean native; video → pemutar video native; gambar → penampil dengan geser. Penjelajah folder (SAF) ikut pindah ke sini. Tab Pustaka dihapus.

## Ringan
- Tidak ada dependensi baru (tanpa Media3/ExoPlayer). Tambahan Java ≈ 40 KB sebelum kompilasi; `player.js` dimuat hanya saat tab Pemutar dibuka atau saat service musik masih aktif.
- Panel Setelan → Pemutar: Buffer (Hemat/Seimbang/Besar), jeda saat headset dicabut, hormati fokus audio.

## Belum diverifikasi / batasan
- Java belum dikompilasi dan belum diuji di perangkat (tidak ada Android SDK di sini). Uji JS dengan bridge tiruan lulus.
- Reverb bergantung pada dukungan aux-effect perangkat (OEM tertentu tidak mendukung).
- `MediaPlayer` tetap bukan ExoPlayer: untuk stream HLS/DASH atau codec langka, Media3 akan lebih baik, tetapi menambah ±1–2 MB APK.
- Antrean dikirim maksimum 300 lagu per ketukan (batas ukuran Intent).
- Thumbnail gambar memakai `content://` langsung di `<img>`; bila WebView menolak, tampil ikon ▧.
- Mode "+ File" (blob) hanya aktif di browser; di Android dipakai galeri/Folder karena service native butuh URI `content://`.
