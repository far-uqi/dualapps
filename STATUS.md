# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 10:31 WIB — Fase 10.2 (Perbaikan Verifikasi Akhir Split APK) selesai

## Ringkasan fase terakhir
- Fase: 10.2 — Perbaikan verifikasi akhir file split di `ClonePipeline.kt` (`verifySplitArchive` via AXML + ARSC bila ada, mempertahankan `PackageManager.getPackageArchiveInfo()` khusus untuk `base.apk`) + 3 unit test baru di `SplitApkCloneAndInstallTest.kt`
- Test: **59/59 lulus** (56 test sebelumnya + 3 test baru Fase 10.2), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.236.233` byte / ~9,76 MB, `EXIT_CODE=0`, durasi total `176.9s`)
- Commit: satu commit di branch `adil` — `"fix(phase10.2): verify split archives via AXML/ARSC instead of getPackageArchiveInfo"`
- Audit mandiri Fase 10.2: 0 ketidaksesuaian pada kode/test/build; hanya 1 berkas produksi diubah (`ClonePipeline.kt`); `AxmlPatcher`, `ApkRebuilder`, `ApkSigningEngine`, `ApkInstaller.kt`, dan jalur APK tunggal v1.0 tidak disentuh; pengujian perangkat nyata di HP RMX1971 masih menunggu uji Mukidi.

## Berkas berubah fase ini
- app/src/main/java/com/dualapps/cloner/engine/ClonePipeline.kt (diubah)
- app/src/test/java/com/dualapps/cloner/engine/SplitApkCloneAndInstallTest.kt (diubah)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Uji perangkat ulang oleh Mukidi di HP RMX1971 (Android 11 / SDK 30) untuk clone Muse split (`com.facebook.aura`) dan `com.rarlab.rar` (3 file) + instalasi via sesi `PackageInstaller` belum dijalankan pasca-Fase 10.2.
- Build release (`app/build/outputs/apk/release/app-release.apk`, terakhir `8.569.998` byte, SHA-256 sertifikat `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`) belum dibangun ulang pasca-Fase 10/10.2.

## Langkah berikutnya
- Uji perangkat oleh Mukidi di HP RMX1971: pasang `app-debug.apk` Fase 10.2 (`.\gradlew.bat installDebug`), ulangi Uji 1 — Muse split (`com.facebook.aura`) dan Uji 2 — RAR (`com.rarlab.rar`, 3 file), pasang via sesi `PackageInstaller`, buka clone, cek nama + ikon launcher, dan laporkan hasilnya.

## Keterbatasan yang berlaku
- Clone split yang diekstrak dari satu perangkat hanya terjamin cocok untuk perangkat dengan ABI dan densitas layar yang sama (mis. `arm64-v8a` + `xxhdpi`).
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
