# STATUS — Dualapps
Terakhir diperbarui: 2026-11-23 12:37 WIB — Fase Build Release dengan Keystore Pribadi selesai

## Ringkasan fase terakhir
- Fase: Build Release dengan Keystore Pribadi (signingConfigs.release + `isMinifyEnabled = false` + `isShrinkResources = false`, tanpa perubahan perilaku aplikasi / izin / mesin clone)
- Test: 46/46 lulus, 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest --rerun` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build release: BUILD SUCCESSFUL — `app/build/outputs/apk/release/app-release.apk` (`8.569.998` byte / ~8,2 MB)
- Sertifikat release (`apksigner verify --print-certs`): `CN=faruqi, OU=Unknown, O=Unknown, L=Indonesia, ST=Java, C=62` (bukan `CN=Android Debug`)
- Sidik jari SHA-256 sertifikat release: `3536ee18ec6f4e72289bd09d6ff6652a1b99cde78bd998c0aabdeca4525dcd65`
- Commit: satu commit di branch `adil` — `"build(release): configure private release keystore signing without minification"`
- Audit mandiri: 0 ketidaksesuaian; tidak ada berkas rahasia (`keystore.properties`, `*.jks`, `*.keystore`) di commit, tidak ada perubahan perilaku aplikasi, dan build debug tidak tersentuh.

## Berkas berubah fase ini
- .gitignore (diubah)
- app/build.gradle.kts (diubah)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Uji perangkat oleh pemilik belum dijalankan untuk `app-release.apk`.

## Langkah berikutnya
- Uji perangkat oleh pemilik: hapus Dualapps versi debug dari HP → pasang app-release.apk → catat bunyi persis dialog Play Protect yang muncul (berbahaya / peringatan / tombol Tetap pasang ada atau tidak).

## Keterbatasan yang berlaku
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
- Keystore release pribadi disimpan di luar repositori (`C:\Users\Administrator\keystores\dualapps-release.jks`) dan tidak pernah di-commit; berkas lokal `keystore.properties` masuk `.gitignore`.
