# Dualapps

> **English:** Dualapps is a personal Android app cloner. Pick an installed app and create a second copy with a new package name, so two accounts can run side by side. Everything runs on your device — no account, no cloud, no ads.

[⬇️ Unduh APK terbaru (v1.0)](https://github.com/far-uqi/dualapps/releases/tag/v1.0)

Dualapps adalah aplikasi Android untuk meng-clone aplikasi yang sudah terpasang di HP kamu menjadi salinan kedua dengan nama paket baru (`.clone`, `.clone2`, ...), supaya dua akun bisa jalan berdampingan. Dibuat untuk pemakaian pribadi, tanpa iklan, tanpa akun, tanpa cloud — semua proses berjalan di perangkatmu sendiri.

## ✨ Fitur (v1.0)

- **Tanpa iklan** — tidak ada iklan, tidak ada akun, tidak ada pelacakan. Semua proses berjalan di perangkatmu.
- **Clone aplikasi terpasang** — pilih aplikasi dari daftar, atur nama clone dan nama paket otomatis (`.clone`, `.clone2`, ...), lalu clone dengan progress 7 langkah yang bisa dibatalkan.
- **Nama & ikon custom** — ganti nama launcher dan pilih gambar dari galeri sebagai ikon clone.
- **Clone dari berkas APK** — tombol `+` untuk memilih berkas `.apk` dari penyimpanan dan meng-clone-nya.
- **Daftar hasil** — semua hasil clone tercatat dengan tombol Pasang, Bagikan, dan Hapus.
- **Panduan Play Protect** — petunjuk di layar Hasil untuk memasang clone yang ditandai Play Protect.
- **Pencarian & filter** — cari aplikasi berdasarkan nama/paket, tampilkan/sembunyikan aplikasi sistem.

## ⬇️ Unduh & Pasang

1. Unduh APK terbaru dari halaman [Releases](https://github.com/far-uqi/dualapps/releases/latest) — berkas `dualapps-v1.0.apk`.
2. Buka berkasnya di HP. Jika diminta, izinkan **"Install unknown apps"** untuk sumber ini (browser/file manager yang kamu pakai).
3. Jika Play Protect menampilkan peringatan, ketuk **"Detail selengkapnya" → "Tetap pasang"**.
4. Buka Dualapps dari launcher.

Persyaratan: Android 8.0 (API 26) atau lebih baru.

## 📱 Cara Pakai

1. Di layar **Pilih Aplikasi**, cari dan pilih aplikasi yang mau di-clone.
2. Di layar **Atur Clone**, atur nama clone (contoh: "Muse 2") dan nama paket (otomatis `.clone`, `.clone2`, ...). Bila mau, pilih gambar dari galeri sebagai ikon custom.
3. Ketuk **CLONE** dan tunggu sampai 7 langkah selesai.
4. Di layar Hasil, ketuk **PASANG** untuk memasang clone. Jika Play Protect bertanya, pilih **"Tetap pasang"** (lihat panduan di layar Hasil).

Catatan: clone ditandatangani ulang dengan keystore unik yang dibuat di perangkatmu sendiri saat pertama dipakai. Karena tanda tangannya berubah, sebagian aplikasi mungkin menampilkan peringatan Play Protect — itu normal.

## ⚠️ Keterbatasan

- **Split APK belum didukung** (aplikasi yang terpasang sebagai beberapa berkas APK). Aplikasi seperti itu ditandai "Split — belum didukung". Dukungan split APK direncanakan untuk v2.0.
- **Kode DEX tidak disentuh** — hanya manifest, resources, dan tanda tangan yang diubah.
- Aplikasi yang memeriksa tanda tangan, Play Integrity, lisensi, atau DRM **dapat menolak hasil clone**. Itu hasil akhir yang sah, bukan bug.
- Tidak ada dukungan khusus untuk aplikasi bank/pembayaran.

## 📜 Aturan Pakai & Kebijakan

Dengan mengunduh dan memakai Dualapps, kamu menyetujui:

1. **Pemakaian pribadi saja.** Dualapps dibuat untuk pemakaianmu sendiri di perangkatmu sendiri.
2. **Hanya clone aplikasi yang sudah terpasang di perangkatmu.** Jangan meng-clone aplikasi yang bukan milikmu atau yang tidak kamu miliki.
3. **Jangan mendistribusikan hasil clone.** Jangan membagikan, mengunggah, atau menjual APK hasil clone — terutama aplikasi berbayar.
4. **Jangan dipakai untuk pembajakan.** Dilarang memakai Dualapps untuk melewati lisensi, DRM, Play Integrity, pemeriksaan tanda tangan, atau pembayaran aplikasi.
5. **Hormati developer aplikasi.** Clone bukan pengganti membeli aplikasi; dukung developer dengan memakai aplikasi resmi bila memungkinkan.
6. **Play Protect tetap aktif.** Dualapps tidak pernah menonaktifkan Play Protect atau package verifier secara programatis. Keputusan memasang clone sepenuhnya di tanganmu.
7. **Risiko ditanggung pengguna.** Hasil clone mungkin tidak berfungsi untuk sebagian aplikasi, dan akunmu di aplikasi target bisa terkena pembatasan dari pihak aplikasi tersebut. Pengembang Dualapps tidak bertanggung jawab atas hal itu.

## 🔒 Privasi

- Tanpa akun, tanpa cloud, tanpa iklan, tanpa pelacakan.
- Semua proses clone berjalan di perangkatmu; tidak ada data yang dikirim ke mana pun.
- Keystore penanda tangan dibuat di dalam aplikasi di perangkatmu dan tidak pernah dibundel ke dalam APK Dualapps.

## 🛠️ Build dari Source

```sh
# Butuh JDK 17 dan Android SDK (compileSdk 34)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Stack: Android native Kotlin (Views + ViewBinding, tanpa Compose), Gradle 8.7, AGP 8.5.2, minSdk 26.

## 📄 Lisensi

Proyek ini dilisensikan di bawah [Lisensi MIT](LICENSE) — bebas dipakai, diubah, dan disebarluaskan dengan tetap mencantumkan lisensi dan pemberitahuan hak cipta.

## ⚖️ Disclaimer

Dualapps adalah proyek pribadi dan tidak berafiliasi dengan Google atau developer aplikasi mana pun. Semua merek dan nama aplikasi adalah milik pemiliknya masing-masing. Gunakan dengan bijak dan patuhi aturan pakai di atas.

## 🗺️ Rencana

- **v1.0** (saat ini): clone APK tunggal + nama & ikon custom + clone dari berkas APK.
- **v2.0** (rencana): dukungan split APK.
