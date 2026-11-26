# Alisa Lite — KRUZENLAB

Fondasi UI Alisa Lite berbasis Nimiyo–Nexora V2. Identitas visual menggunakan logo Alisa, tema Dark/Light/AMOLED, aksen warna, mini-player, dan navigasi enam bagian.

## Identitas
- Nama tampilan: Alisa Lite
- Package ID target: `com.kruzenlab.alisalite`
- Developer: KRUZENLAB
- UI: HTML, CSS, JavaScript murni di Android WebView

## Bagian yang tersedia pada fondasi ini
- Beranda downloader Nexora (engine scraper bawaan tetap dipertahankan)
- Unduhan/riwayat (menggunakan sistem riwayat yang sudah ada)
- Pustaka lokal berbasis pemilih file WebView/browser dan antrean pemutar
- Pemutar audio/video berbasis elemen media HTML yang sudah ada
- Katalog UI Media Tools untuk integrasi lanjutan
- Theme Studio, setelan, OTA scraper

## Status integrasi native — belum selesai
Pustaka Android Mori belum digabungkan ke native bridge Nimiyo pada paket ini. Karena itu, pemindaian MediaStore penuh, akses folder SAF, layanan unduh foreground Mori, share overlay/floating bubble, biometrik, dan tools native belum boleh dianggap aktif. Kartu Tools menjelaskan status ini dan tidak memalsukan keberhasilan.

Package ID target `com.kruzenlab.alisalite` sudah diterapkan pada proyek Gradle Android yang disertakan. APK dasar Nimiyo lama tidak dipakai lagi oleh workflow baru. Native bridge yang tersedia masih bridge Nexora minimal (`NX`); modul Mori belum digabungkan ke bridge ini. Build APK dari workflow baru menggunakan Gradle dan package ID Alisa Lite, tetapi belum berarti semua fitur Mori sudah aktif.

## Build frontend bundle
```bash
node scripts/build-bundle.js 2 "Alisa Lite foundation"
gradle -p android assembleRelease
node --check web/app.js
node --check web/engine.js
node --check server/server.js
```

Workflow GitHub Actions membangun APK native dari `android/` dan mengunggah artifact `alisa-lite-apk`. Build ini sudah menggunakan package ID Alisa Lite; fitur native Mori tetap merupakan tahap integrasi berikutnya.

## Lisensi
Nimiyo/Nexora dan Mori memiliki lisensi serta kewajiban atribusi masing-masing. Mori menggunakan GPL-3.0-or-later; distribusi kode turunan yang menggabungkan Mori wajib mematuhi ketentuan GPL, termasuk penyediaan source yang sesuai. Lihat `THIRD_PARTY_NOTICES.md`.
