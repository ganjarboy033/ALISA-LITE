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
