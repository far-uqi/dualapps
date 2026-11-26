# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 08:25 WIB — Fase 10 (Implementasi Clone & Instalasi Split APK) selesai

## Ringkasan fase terakhir
- Fase: 10 — Implementasi Clone & Instalasi Split APK end-to-end (model data split + `versionCode`, pipeline multi-APK, identitas lintas split, `PackageInstaller` session API + `SplitInstallResultReceiver`, UI daftar/atur/progres/hasil, dan 10 unit test baru)
- Test: **56/56 lulus** (46 test v1.0 + 10 test baru Fase 10), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.148.532` byte / ~9,68 MB, `EXIT_CODE=0`, durasi total `260.62s`)
- Commit: satu commit di branch `adil` — `"feat(phase10): implement split APK clone and PackageInstaller session install"`
- Audit mandiri Fase 10: 0 ketidaksesuaian pada kode/test/build; jalur APK tunggal v1.0 (`ApkInstaller.kt`) tidak dirusak; tidak ada izin baru di `AndroidManifest.xml`; perilaku runtime `PackageInstaller.Session` dan pemasangan clone split di HP RMX1971 masih menunggu uji perangkat Mukidi.

## Berkas berubah fase ini
- app/src/main/java/com/dualapps/cloner/model/SplitApkFile.kt (baru)
- app/src/main/java/com/dualapps/cloner/util/SplitInstallSessionCoordinator.kt (baru)
- app/src/main/java/com/dualapps/cloner/util/SplitApkInstaller.kt (baru)
- app/src/main/java/com/dualapps/cloner/util/SplitInstallResultReceiver.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/SplitApkCloneAndInstallTest.kt (baru)
- app/src/main/AndroidManifest.xml (diubah)
- app/src/main/java/com/dualapps/cloner/model/InstalledApp.kt (diubah)
- app/src/main/java/com/dualapps/cloner/model/ClonedApkRecord.kt (diubah)
- app/src/main/java/com/dualapps/cloner/model/CloneError.kt (diubah)
- app/src/main/java/com/dualapps/cloner/data/InstalledAppsRepository.kt (diubah)
- app/src/main/java/com/dualapps/cloner/data/ClonedApksRepository.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/ApkRebuilder.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/CloneIdentityPatcher.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/ClonePipeline.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/MainActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/InstalledAppsAdapter.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ConfigureCloneActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/CloneProgressActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ClonedApksAdapter.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ResultsActivity.kt (diubah)
- app/src/main/res/layout/activity_configure_clone.xml (diubah)
- app/src/main/res/layout/activity_clone_progress.xml (diubah)
- app/src/main/res/values/strings.xml (diubah)
- app/src/test/java/com/dualapps/cloner/AppFilterAndFormatTest.kt (diubah)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Verifikasi perangkat oleh Mukidi di HP RMX1971 (Android 11 / SDK 30) untuk clone + instalasi Split APK via `PackageInstaller.Session` (target: `com.facebook.aura` + minimal 1 dari `com.rarlab.rar` / `com.gsmartstudio.fakegps` / `com.apple.qrcode.reader`) belum dijalankan.
- Build release terakhir sebelum Fase 10: `app/build/outputs/apk/release/app-release.apk` (`8.569.998` byte, SHA-256 sertifikat `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`) — belum dibangun ulang pasca-Fase 10.

## Langkah berikutnya
- Uji perangkat oleh Mukidi di HP RMX1971: pasang `app-debug.apk` Fase 10, clone Muse (`com.facebook.aura`, split) dan minimal 1 dari 3 target Fase 9 (`com.rarlab.rar` / `com.gsmartstudio.fakegps` / `com.apple.qrcode.reader`), pasang via sesi `PackageInstaller`, buka clone, cek nama + ikon launcher, dan laporkan hasilnya.

## Keterbatasan yang berlaku
- Clone split yang diekstrak dari satu perangkat hanya terjamin cocok untuk perangkat dengan ABI dan densitas layar yang sama (mis. `arm64-v8a` + `xxhdpi`).
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
