# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 11:43 WIB — Fase 11 (Perbarui Clone) selesai

## Ringkasan fase terakhir
- Fase: 11 — Perbarui Clone dari aplikasi sumber yang diperbarui (`ClonedApkRecord` simpan `sourceVersionName` & `customIconPath` permanen, `CloneUpdateChecker`, mode `isUpdate = true` di `ClonePipeline` & `CloneProgressStartResolver`, badge `"Pembaruan tersedia"` + tombol **PERBARUI** di `ResultsActivity`/`ClonedApksAdapter`, judul `"Memperbarui…"` di `CloneProgressActivity`, dan 8 unit test baru)
- Test: **67/67 lulus** (59 test sebelumnya + 8 test baru Fase 11), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.157.704` byte / ~9,69 MB, `EXIT_CODE=0`, durasi total `163.98s`)
- Commit: satu commit di branch `adil` — `"feat(phase11): update existing clones from newer source version"`
- Audit mandiri Fase 11: 0 ketidaksesuaian pada kode/test/build; jalur clone baru tetap menolak paket terpasang & menjalankan auto-increment; tidak ada izin baru di `AndroidManifest.xml`; pembaruan menimpa file hasil secara atomik (staging) dan memakai keystore per-perangkat yang sama agar Android memperlakukannya sebagai update tanpa hapus data; verifikasi nyata di HP RMX1971 menunggu uji Mukidi.

## Berkas berubah fase ini
- app/src/main/java/com/dualapps/cloner/engine/CloneUpdateChecker.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/CloneUpdateTest.kt (baru)
- app/src/main/java/com/dualapps/cloner/model/CloneError.kt (diubah)
- app/src/main/java/com/dualapps/cloner/model/ClonedApkRecord.kt (diubah)
- app/src/main/java/com/dualapps/cloner/data/ClonedApksRepository.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/ClonePipeline.kt (diubah)
- app/src/main/java/com/dualapps/cloner/engine/CloneProgressStartResolver.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ConfigureCloneActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/CloneProgressActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ClonedApksAdapter.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/ResultsActivity.kt (diubah)
- app/src/main/res/layout/item_cloned_apk.xml (diubah)
- app/src/main/res/values/strings.xml (diubah)
- app/src/test/java/com/dualapps/cloner/AppFilterAndFormatTest.kt (diubah)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Uji perangkat oleh Mukidi di HP RMX1971 (Android 11 / SDK 30) untuk skenario Perbarui Clone (perbarui sumber via Play Store → tekan PERBARUI → 7/7 → pasang sebagai update → cek versi naik, data tetap, nama + ikon tidak berubah) belum dijalankan.
- Build release (`app/build/outputs/apk/release/app-release.apk`, terakhir `8.569.998` byte, SHA-256 sertifikat `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`) belum dibangun ulang pasca-Fase 10/11.

## Langkah berikutnya
- Uji perangkat oleh Mukidi di HP RMX1971 sesuai instruksi Fase 11 (`.\gradlew.bat installDebug` → perbarui aplikasi sumber via Play Store → tekan PERBARUI di layar Hasil → pasang sebagai update → verifikasi versi, data, nama, dan ikon).

## Keterbatasan yang berlaku
- Clone lama yang dibuat sebelum Fase 10/11 (tanpa `sourceVersionCode` tercatat di `.json`) dianggap `"Versi sumber tidak tercatat"` dan tombol PERBARUI nonaktif hingga clone dibuat ulang.
- Clone split yang diekstrak dari satu perangkat hanya terjamin cocok untuk perangkat dengan ABI dan densitas layar yang sama (mis. `arm64-v8a` + `xxhdpi`).
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
