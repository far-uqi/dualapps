# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 09:47 WIB — Fase 10.1 (Diagnosis `VERIFY_PACKAGE_MISMATCH` pada Clone Split) selesai

## Ringkasan fase terakhir
- Fase: 10.1 — Diagnosis `VERIFY_PACKAGE_MISMATCH` pada clone Split APK (penelitian saja: tanpa perubahan kode sumber, tanpa test baru, tanpa build)
- Perangkat & gejala uji: HP `RMX1971` (`828bcb13`, Android 11 / SDK 30), clone `com.facebook.aura` → `com.facebook.aura.clone3` (2 file: `base.apk` + `split_browser_engine.apk`), langkah 1–6 OK, gagal di langkah 7 (`VERIFY_PACKAGE_MISMATCH`: `Verifikasi akhir split 'split_browser_engine.apk' gagal: diharapkan 'com.facebook.aura.clone3', terbaca 'null'`).
- Akar masalah (terbukti dengan `adb pull`, `aapt2`, `logcat`, dan `SplitProbe` pada kelas terkompilasi Fase 10):
  - **Hipotesis A terbukti (bukan bug `AxmlPatcher`/`ApkRebuilder`):** `ApkRebuilder` + `AxmlPatcher` menghasilkan manifest split 100% valid baik pada `split_browser_engine.apk` (`com.facebook.aura.clone3`, `split="browser_engine"`) maupun split `com.rarlab.rar` (`split_config.arm64_v8a.apk` & `split_config.xxhdpi.apk`), seluruhnya ter-parse bersih oleh `aapt2 dump badging` & `aapt2 dump xmltree`.
  - Penyebab `null` adalah `ClonePipeline.kt:341-352` memanggil `AndroidArchivePackageReader` (`PackageManager.getPackageArchiveInfo(apkPath, 0)`) pada file split. Di Android (AOSP `ParsingPackageUtils.parseMonolithicPackage`), `getPackageArchiveInfo()` mem-parse berkas sebagai APK monolitik/base dan **mengembalikan `null` untuk semua APK yang memiliki atribut `split="..."`**.
  - Di unit test JVM Fase 10 (`SplitApkCloneAndInstallTest`), `archivePackageReader` memakai lambda stub yang membaca AXML langsung, sehingga keterbatasan `getPackageArchiveInfo()` pada file split baru terungkap saat uji perangkat nyata.
- Usulan perbaikan Fase 10.2: pertahankan `getPackageArchiveInfo()` untuk `base.apk` (APK tunggal & base split), dan ganti verifikasi langkah 7 khusus **file split** dengan pembacaan langsung `AndroidManifest.xml` (`AxmlPatcher.readPackageName`) + `resources.arsc` bila ada (`ArscPatcher.readPackageNames`), ditambah 2–3 unit test baru yang mensimulasikan `archivePackageReader` mengembalikan `null` untuk file split.
- Test: tetap **56/56 lulus** (tidak dijalankan ulang di Fase 10.1 karena tidak ada perubahan kode)
- Build debug terakhir (Fase 10, commit `3ad1a54`): `app/build/outputs/apk/debug/app-debug.apk` (`10.148.532` byte / ~9,68 MB)
- Build release terakhir (sebelum Fase 10): `app/build/outputs/apk/release/app-release.apk` (`8.569.998` byte, SHA-256 sertifikat `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`)
- Audit mandiri Fase 10.1: selesai; seluruh klaim Q2–Q4 terbukti dari output perintah nyata (`adb pull`, `aapt2`, `SplitProbe`, kode `ClonePipeline.kt`); pemanggilan langsung `getPackageArchiveInfo()` pada split mentah di luar app belum diuji lewat test harness terpisah (karena larangan ubah kode/build), namun terbukti tidak langsung dari `logcat` PID `18713` + fakta bahwa `signed_base.apk` lolos sedangkan `signed_split_browser_engine.apk` mengembalikan `null` meski manifest hasil patch 100% valid di `aapt2`.

## Berkas berubah fase ini
- STATUS.md (diubah — tidak ada berkas kode sumber, test, atau build yang disentuh)

## Belum selesai / diketahui bermasalah
- Perbaikan verifikasi akhir file split di `ClonePipeline.kt` menunggu gas Mukidi untuk dikerjakan di Fase 10.2.
- Uji perangkat untuk clone split (`com.facebook.aura` + target Fase 9) akan diulang setelah Fase 10.2 selesai.

## Langkah berikutnya
- Menunggu review Cireng dan keputusan gas Mukidi untuk **Fase 10.2: Perbaiki verifikasi akhir split APK di `ClonePipeline.kt` + unit test + uji ulang perangkat**.

## Keterbatasan yang berlaku
- Clone split yang diekstrak dari satu perangkat hanya terjamin cocok untuk perangkat dengan ABI dan densitas layar yang sama (mis. `arm64-v8a` + `xxhdpi`).
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
