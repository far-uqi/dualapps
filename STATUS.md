# STATUS — Dualapps
Terakhir diperbarui: 2026-11-23 10:36 WIB — Fase 8 selesai

## Ringkasan fase terakhir
- Fase: 8 — Perbaikan restart `CloneProgressActivity` setelah kembali dari layar Settings "Install unknown apps" (`CloneProgressStartResolver`, `onSaveInstanceState`, cek `ClonedApksRepository`) & tahan state kosong `MainActivity` sebelum pemuatan pertama selesai
- Test: 46/46 lulus, 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build: BUILD SUCCESSFUL (`app/build/outputs/apk/debug/app-debug.apk`)
- Commit: satu commit di branch `adil` — `"fix(phase8): prevent CloneProgressActivity restart after settings return and guard MainActivity initial empty state"`
- Audit mandiri: 0 ketidaksesuaian fungsional; tidak ada izin baru di `AndroidManifest.xml`.

## Berkas berubah fase ini
- app/src/main/java/com/dualapps/cloner/engine/CloneProgressStartResolver.kt (baru)
- app/src/main/java/com/dualapps/cloner/ui/CloneProgressActivity.kt (diubah)
- app/src/main/java/com/dualapps/cloner/ui/MainActivity.kt (diubah)
- app/src/test/java/com/dualapps/cloner/engine/CloneProgressStartResolverTest.kt (baru)
- STATUS.md (diubah)

## Belum selesai / diketahui bermasalah
- Verifikasi perangkat oleh Cak untuk skenario ulang: clone → PASANG → aktifkan izin "Install unknown apps" → back → pastikan `CloneProgressActivity` tetap di state Selesai (tidak mengulang dari 1/7).

## Langkah berikutnya
- Pasang `app-debug.apk` terbaru ke HP via `.\gradlew.bat installDebug` lalu uji ulang skenario kembali dari layar izin instalasi.

## Keterbatasan yang berlaku
- Aplikasi target yang menolak hasil clone (pemeriksaan tanda tangan / Play Integrity / lisensi / DRM) adalah hasil akhir yang sah, bukan bug yang harus dicarikan celahnya.
- Dualapps tidak pernah menonaktifkan Google Play Protect atau package verifier secara programatis; panduan di layar Hasil bersifat statis dan keputusan sepenuhnya di tangan pengguna.
