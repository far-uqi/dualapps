# STATUS — Dualapps
Terakhir diperbarui: 2026-11-23 09:30 WIB — Fase 5 selesai

## Ringkasan fase terakhir
- Fase: 5 — Identitas Clone: ikon custom (pemilih galeri, crop tengah persegi, resize mdpi–xxxhdpi PNG) + patch nama launcher (AXML literal & `@string` di `resources.arsc` UTF-8/UTF-16)
- Test: 37/37 lulus, 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build: BUILD SUCCESSFUL (`app/build/outputs/apk/debug/app-debug.apk`)
- Commit: satu commit di branch `adil` — `"feat(phase5): add custom clone icon picker, launcher label patching, and identity pipeline tests"`
- Audit mandiri: 0 ketidaksesuaian fungsional; catatan keterbatasan tetap (uji `BitmapFactory`/galeri Android nyata & pemasangan APK hasil patch identitas di perangkat fisik belum dijalankan di sesi ini).

## Berkas berubah fase ini
- app/src/main/java/com/dualapps/cloner/model/CloneError.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/AxmlPatcher.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/ArscPatcher.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/ClonePipeline.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/CloneIdentityPatcher.kt (baru)
- app/src/main/java/com/dualapps/cloner/util/IconProcessor.kt (baru)
- app/src/main/java/com/dualapps/cloner/ui/ConfigureCloneActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/CloneProgressActivity.kt (diubah)
- app/src/main/res/layout/activity_configure_clone.xml (diubah)
- app/src/main/res/values/strings.xml (diubah)
- app/src/test/java/com/dualapps/cloner/AppFilterAndFormatTest.kt (diubah)
- app/src/test/java/com/dualapps/cloner/engine/BinaryFixtureBuilder.kt (diubah)
- app/src/test/java/com/dualapps/cloner/engine/CloneIdentityPatcherTest.kt (baru)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Decode gambar nyata via `BitmapFactory` / `ActivityResultContracts.GetContent`, serta pemasangan APK hasil patch identitas (nama launcher & ikon custom) belum diuji langsung di perangkat Android fisik pasca-Fase 5.
- Aplikasi dengan adaptive icon yang foreground-nya murni vector XML (tanpa entri PNG sama sekali di APK) akan gagal dengan `ICON_PATCH_FAILED` bila user memilih ikon custom, sesuai aturan gagal eksplisit (tidak diam-diam memakai ikon asli).

## Langkah berikutnya
- Pasang `app-debug.apk` terbaru ke HP via `.\gradlew.bat installDebug` dan uji clone dengan nama baru + ikon custom dari galeri.

## Keterbatasan yang berlaku
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
