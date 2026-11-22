# STATUS — Dualapps
Terakhir diperbarui: 2026-11-22 16:19 WIB — Fase 2 selesai

## Ringkasan fase terakhir
- Fase: 2 — Mesin patch AXML + ARSC + AlignedZipWriter
- Test: 18/18 lulus (perintah: ./gradlew testDebugUnitTest)
- Build: BUILD SUCCESSFUL (./gradlew assembleDebug)
- Commit: satu commit di branch adil — "feat(phase2): implement AXML, ARSC, and AlignedZipWriter engine"
- Audit mandiri: 2 ketidaksesuaian ditemukan, 2 sudah diperbaiki, 0 belum

## Berkas berubah fase ini
- app/build.gradle.kts (diubah)
- app/src/main/java/com/dualapps/cloner/engine/AxmlStringPool.kt (baru)
- app/src/main/java/com/dualapps/cloner/engine/AxmlPatcher.kt (baru)
- app/src/main/java/com/dualapps/cloner/engine/ArscPatcher.kt (baru)
- app/src/main/java/com/dualapps/cloner/engine/AlignedZipWriter.kt (baru)
- app/src/main/java/com/dualapps/cloner/engine/ApkRebuilder.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/BinaryFixtureBuilder.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/AxmlStringPoolTest.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/AxmlPatcherTest.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/ArscPatcherTest.kt (baru)
- app/src/test/java/com/dualapps/cloner/engine/AlignedZipWriterTest.kt (baru)
- STATUS.md (baru)

## Belum selesai / diketahui bermasalah
- Pengujian hasil patch AXML/ARSC/AlignedZipWriter pada APK nyata di perangkat Android fisik (verifikasi `PackageManager.getPackageArchiveInfo` dan instalasi) belum berjalan — masuk Fase 3 dan uji terima perangkat.

## Langkah berikutnya
- Fase 3: implementasi keystore unik per perangkat (BouncyCastle), signing v1+v2 + ApkVerifier (apksig), verifikasi `PackageManager.getPackageArchiveInfo`, layar Atur Clone + Proses Clone, dan intent pasang.

## Keterbatasan yang berlaku
- Ganti label aplikasi hasil clone belum dijamin (label manifest sering berupa referensi @string) — kandidat Fase 2 lanjutan SETELAH mesin terbukti.
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
