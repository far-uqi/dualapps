# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 12:50 WIB — Fase 12 (Clone dari berkas APKS/XAPK + Perbaikan Progress Pasang) selesai

## Ringkasan fase terakhir
- Fase: 12 — Clone dari berkas `.apks` / `.xapk` (via tombol `+` / `pickApkLauncher` dengan MIME `*/*`, ekstraksi & validasi `ApkBundleExtractor` ke `cacheDir/picked_apks/bundle_<uuid>/`, penolakan `.obb` via `BUNDLE_HAS_OBB`, identifikasi base vs split lewat AXML tanpa parsing `toc.pb` dan tanpa `PackageManager.getPackageArchiveInfo` pada split, pemakaian ulang `ClonePipeline` & `SplitApkInstaller` Fase 10, label asal `"BERKAS APKS SUMBER"` / `"BERKAS XAPK SUMBER"` + `"HASIL CLONE • dari berkas APKS/XAPK"`), perbaikan indikator progres pemasangan non-blocking (`InstallProgressTracker` berbasis `StateFlow` pada `ResultsActivity` & `CloneProgressActivity` untuk Split APK maupun APK tunggal), 5 `CloneError` baru (total 24), dan 13 unit test baru.
- Test: **80/80 lulus** (67 test sebelumnya + 13 test baru Fase 12), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.193.252` byte / ~9,72 MB, `EXIT_CODE=0`, durasi total `26.94s`)
- Commit: satu commit di branch `adil` — `"feat(phase12): clone from APKS/XAPK files and show install progress"`
- Audit mandiri Fase 12: 0 ketidaksesuaian pada kode/test/build; mesin patching (`AxmlPatcher`, `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`), logika update Fase 11 (`CloneUpdateChecker`, `isUpdate` di `ClonePipeline`), dan `AndroidManifest.xml` tidak disentuh; tidak ada dependensi Gradle baru; uji nyata pada HP RMX1971 (Android 11 / SDK 30) menunggu eksekusi Cak.

## Berkas berubah fase ini
- app/src/main/java/com/dualapps/cloner/data/ApkBundleExtractor.kt (baru)
- app/src/main/java/com/dualapps/cloner/util/InstallProgressTracker.kt (baru)
- app/src/test/java/com/dualapps/cloner/data/ApkBundleAndInstallProgressTest.kt (baru)
- app/src/main/java/com/dualapps/cloner/model/CloneError.kt (diubah)
- app/src/main/java/com/dualapps/cloner/model/ClonedApkRecord.kt (diubah)
- app/src/main/java/com/dualapps/cloner/data/ApkFileSourceValidator.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/ClonePipeline.kt (diubah)
- app/src/main/java/com/dualapps/cloner/util/SplitApkInstaller.kt (diubah)
- app/src/main/java/com/dualapps/cloner/util/SplitInstallResultReceiver.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/MainActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ConfigureCloneActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/CloneProgressActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ResultsActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ClonedApksAdapter.kt (diubah)
- app/src/main/res/layout/activity_results.xml (diubah)
- app/src/main/res/layout/activity_clone_progress.xml (diubah)
- app/src/main/res/values/strings.xml (diubah)
- app/src/test/java/com/dualapps/cloner/AppFilterAndFormatTest.kt (diubah)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Uji perangkat oleh Cak di HP RMX1971 (Android 11 / SDK 30) untuk skenario Fase 12 (clone dari berkas `.apks`, clone dari berkas `.xapk`, verifikasi indikator progres `"Memasang…"` → `"Menunggu konfirmasi pemasangan…"` → hilang otomatis, serta verifikasi penolakan berkas bukan APK/APKS/XAPK) belum dijalankan.
- Build release (`app/build/outputs/apk/release/app-release.apk`, terakhir `8.569.998` byte, SHA-256 sertifikat `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`) belum dibangun ulang pasca-Fase 10/11/12.

## Langkah berikutnya
- Uji perangkat oleh Cak di HP RMX1971 sesuai instruksi Fase 12 (`.\gradlew.bat installDebug` → uji clone `.apks` & `.xapk` via tombol `+` → uji tampilan indikator progres pemasangan saat tekan PASANG di layar Hasil dan layar Proses).

## Keterbatasan yang berlaku
- Berkas `.xapk` atau `.apks` yang mengandung berkas `.obb` ditolak secara eksplisit (`BUNDLE_HAS_OBB`) karena di luar cakupan Dualapps v2.0.
- Untuk berkas `.xapk` tanpa `manifest.json`, ekstraktor melakukan fallback otomatis memindai semua entri `*.apk` dalam ZIP (diperlakukan sebagai bundle APKS).
- Clone lama yang dibuat sebelum Fase 10/11 (tanpa `sourceVersionCode` tercatat di `.json`) dianggap `"Versi sumber tidak tercatat"` dan tombol PERBARUI nonaktif hingga clone dibuat ulang.
- Clone split yang diekstrak dari satu perangkat atau bundle hanya terjamin cocok untuk perangkat dengan ABI dan densitas layar yang sama (mis. `arm64-v8a` + `xxhdpi`).
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
