# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 08:03 WIB — Fase 9 (Diagnosis Split APK) selesai

## Ringkasan fase terakhir
- Fase: 9 — Diagnosis Split APK (penelitian saja: tanpa perubahan kode sumber, tanpa test baru, tanpa build, tanpa instalasi clone split)
- Perangkat uji diagnosis: `RMX1971` (`828bcb13`), Android 11 (SDK 30), ABI `arm64-v8a,armeabi-v7a,armeabi`
- Jumlah paket Split APK terdeteksi di HP: **106** total (**72** aplikasi user + **34** aplikasi sistem)
- Target uji Fase 10 yang dipilih (3 aplikasi user non-bank, kecil, tanpa Play Integrity):
  1. `com.rarlab.rar` (RAR) — 3 file (`base`, `config.arm64_v8a`, `config.xxhdpi`), total 3,50 MB
  2. `com.gsmartstudio.fakegps` (Fake GPS Pro) — 5 file (`base`, `config.en`, `config.in`, `config.ms`, `config.xxhdpi`), total 5,78 MB
  3. `com.apple.qrcode.reader` — 4 file (`base`, `config.arm64_v8a`, `config.en`, `config.xxhdpi`), total 11,86 MB
- Temuan kunci diagnosis (berbasis bukti perintah `pm path`, `aapt2`, `apksigner`):
  - **Semua** file split (termasuk `config.arm64_v8a`) memiliki `AndroidManifest.xml` dengan `package` asli → wajib patch AXML pada setiap split.
  - Semua split resource (`config.xxhdpi`, `config.en/in/ms`) memiliki `resources.arsc` dengan `Package name=<pkg>` → wajib patch ARSC; split ABI murni (`config.arm64_v8a`) tidak memiliki `resources.arsc` → `ApkRebuilder` perlu mendukung `requireArsc = false` untuk file split.
  - Ikon launcher bisa berada di split densitas (terbukti pada `com.gsmartstudio.fakegps`: `0x7f0800a7` `res/drawable-xxhdpi-v4/ic_launcher_foreground.png` ada di `split_config.xxhdpi.apk`, bukan hanya `base.apk`).
  - Semua split dalam satu aplikasi memakai sertifikat penandatangan yang identik → re-sign semua file dengan keystore per-perangkat Dualapps yang sama.
- Test: tetap 46/46 lulus (tidak dijalankan ulang di Fase 9 karena tidak ada perubahan kode)
- Build release terakhir (sebelum Fase 9): `app/build/outputs/apk/release/app-release.apk` (`8.569.998` byte, SHA-256 sertifikat `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`)
- Audit mandiri Fase 9: selesai; seluruh klaim empiris di laporan cocok dengan output mentah `adb`/`aapt2`/`apksigner`, sedangkan perilaku runtime `PackageInstaller` & pemasangan clone split di HP dicatat jujur sebagai BELUM TERVERIFIKASI sampai Fase 10.

## Berkas berubah fase ini
- STATUS.md (diubah — tidak ada berkas kode sumber atau test yang disentuh)

## Belum selesai / diketahui bermasalah
- Implementasi Split APK (model data, pipeline multi-APK, identitas lintas split, dan `PackageInstaller` session API) belum dimulai — menunggu persetujuan Mukidi untuk masuk Fase 10.
- Uji perangkat untuk `app-release.apk` (dialog Play Protect) dan uji instalasi clone split pertama di HP masih menjadi agenda berikutnya.

## Langkah berikutnya
- Menunggu review Cireng dan keputusan gas dari Mukidi untuk masuk ke **Fase 10: Implementasi Clone & Instalasi Split APK**.

## Keterbatasan yang berlaku
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
