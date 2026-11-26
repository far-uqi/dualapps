# STATUS — Dualapps
Terakhir diperbarui: 2026-11-26 14:36 WIB — Fase 12.3 (Diagnosis Crash Clone Facebook Saat Login) selesai

## Ringkasan fase terakhir
- Fase: 12.3 — **Diagnosis only** (tanpa ubah kode/test/build/DEX) atas crash (force close) clone Facebook (`com.facebook.katana.clone`) saat layar login di HP RMX1971 (Android 11 / SDK 30).
- Bukti langsung (`adb logcat -v threadtime` pada buffer sebelumnya [8 kejadian identik 14:16–14:20 WIB] + uji kontrol diam 15 detik tanpa sentuhan layar `fb_login_crash.log` + `dexdump -d` pada `classes5.dex` & scan 31 file DEX `base.apk`):
  - **Exception verbatim:** `FATAL EXCEPTION: BgHandler` (`Process: com.facebook.katana.clone`, `PID: 23917`, `uid=10654`): `java.lang.SecurityException: Permission Denial: opening provider com.facebook.messaging.push.dedup.provider.ClientMessagePushDedupInfoProvider from ProcessRecord{a3c638a 23917:com.facebook.katana.clone/u0a654} (pid=23917, uid=10654) requires com.facebook.permission.prod.FB_APP_COMMUNICATION or com.facebook.permission.prod.FB_APP_COMMUNICATION` pada `ContentResolver.delete` → `X.0A9Z.run(:50)`.
  - **Pemicu & kontrol diam:** Crash **BUKAN** dipicu oleh passkey maupun ketuk kolom form login. Pada uji kontrol diam 15 detik tanpa menyentuh layar, proses langsung crash otomatis **3,09 detik** setelah `SimpleLoginActivity` terbuka (`14:30:20.604` → `14:30:23.696`, `pidof` kosong); kebetulan waktunya bertepatan dengan munculnya sheet passkey `SignInCredentialChooserActivity`.
  - **Bukti DEX (`classes5.dex`):** `X.0A9Z` adalah `MuteStatusCleanUpAppJob$cleanUpMuteStatusRows$1` yang men-hardcode URI `content://com.facebook.katana.ClientMessagePushDedupInfoProvider/mutestatus` (beserta `orca`/`wakizashi`/`lite`) dan memanggil `ContentResolver.delete` terhadap provider milik aplikasi resmi Meta (`com.facebook.katana` / `com.facebook.orca`) yang dilindungi permission `protectionLevel="signature"` `com.facebook.permission.prod.FB_APP_COMMUNICATION` (sertifikat Meta `c412e90e`). Karena clone ditandatangani kunci lokal Dualapps (`f2bc408c`), Android menolak akses dan job latar belakang Facebook tidak menangkap `SecurityException` tersebut.
  - **Bukan bug Fase 12.2:** Manifest clone sudah benar (`com.facebook.katana.clone.ClientMessagePushDedupInfoProvider` memakai `com.facebook.katana.clone.com.facebook.permission.prod.FB_APP_COMMUNICATION`, `granted=true`).
- Vonis: **(b) Batas sah Dualapps** — crash berasal dari hardcode URI lintas-aplikasi Meta ber-`protectionLevel="signature"` di dalam `classes5.dex` (`MuteStatusCleanUpAppJob`) yang tidak bisa diperbaiki tanpa memodifikasi DEX (di luar scope Dualapps). Perbaikan `AxmlPatcher` Fase 12.2 tetap valid dan bersih.
- Test & Build: tidak diubah/dijalankan ulang (diagnosis only; terakhir Fase 12.2 commit `a916608`, **86/86 lulus**, `app-debug.apk` `10.286.642` byte).
- Commit: satu commit di branch `adil` — `"docs(phase12.3): record Facebook login crash diagnosis in STATUS.md"`

## Ringkasan Fase 12.2 (sebelumnya)
- Fase: 12.2 — Perbaikan dua celah `AxmlPatcher.kt` hasil diagnosis Fase 12.1:
  1. `rewriteAuthorities` sekarang membandingkan tiap bagian `split(";")` dalam bentuk trim sambil mempertahankan leading/trailing whitespace aslinya (sehingga `"com.facebook.katana.quicksilver.fileprovider; com.facebook.katana.apkfileprovider"` menjadi `"com.facebook.katana.clone.quicksilver.fileprovider; com.facebook.katana.clone.apkfileprovider"`).
  2. Pass 1 (`collectDeclaredNonPrefixedPermissions`) mengumpulkan seluruh `<permission android:name="X">` yang dideklarasikan target namun tidak berawalan `<oldPackage>.` lalu me-rename dengan skema deterministik `<newPackage>.<X>`, dan Pass 2 memperbarui seluruh referensinya di manifest (`<uses-permission>`, atribut `permission`/`readPermission`/`writePermission`, atau atribut lain bernilai persis `X`) tanpa menyentuh `<uses-permission>` milik aplikasi lain.
- Test: **86/86 lulus** (80 test sebelumnya + 6 test baru Fase 12.2 di `AxmlPatcherTest`), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.286.642` byte / ~9,81 MB, `EXIT_CODE=0`, durasi total `157.21s`)
- Commit: satu commit di branch `adil` — `"fix(phase12.2): patch whitespace-separated authorities and non-prefixed permissions in AxmlPatcher"`
- Audit mandiri Fase 12.2: 0 ketidaksesuaian pada kode/test/build; `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`, `CloneUpdateChecker`, logika `isUpdate`, dan `AndroidManifest.xml` tidak disentuh; uji perangkat pada HP RMX1971 (clone Facebook + regresi Muse) menunggu eksekusi Cak.

## Ringkasan Fase 12.1 (Diagnosis)
- Fase: 12.1 — **Diagnosis only** (tanpa ubah kode/test/build) atas kegagalan pasang clone Facebook (`com.facebook.katana` → `com.facebook.katana.clone`, 14 file / 295,2 MB) dengan `STATUS_FAILURE_CONFLICT` di HP RMX1971 (Android 11 / SDK 30).
- Bukti langsung (`adb pull` 14 APK + `aapt2 dump xmltree` + `dumpsys package` seluruh 7 paket Facebook + eksekusi langsung `AxmlPatcher.patch` & `ArscPatcher.patch` lewat `FbSplitProbe` di scratch):
  - Seluruh 31 `<provider>` (32 authority) dan 7 `<permission>` hanya berada di `base.apk` (13 split lainnya punya 0 provider & 0 permission) dan **semuanya literal** (0 referensi `@string/...` → H2 gugur untuk Facebook).
  - Ditemukan **3 tabrakan nyata** yang lolos dari `AxmlPatcher.patch` dan bentrok langsung dengan `com.facebook.katana` (`signatures:[c412e90e]`, dan `com.facebook.orca`):
    1. `<permission>` `com.facebook.permission.prod.FB_APP_COMMUNICATION` (`prot=signature`) — tidak berawalan `com.facebook.katana.` (H1 terbukti), pemilik di HP: `com.facebook.katana` (`INSTALLED`) & `com.facebook.orca`.
    2. `<permission>` `com.facebook.receiver.permission.ACCESS` (`prot=signature`) — tidak berawalan `com.facebook.katana.` (H1 terbukti), pemilik di HP: `com.facebook.katana` (`INSTALLED`) & `com.facebook.orca`.
    3. `<provider>` `androidx.core.content.FileProvider` dengan `android:authorities="com.facebook.katana.quicksilver.fileprovider; com.facebook.katana.apkfileprovider"` — **bug whitespace di `AxmlPatcher.rewriteAuthorities`**: hasil `split(";")` menyisakan spasi awal pada elemen kedua (`" com.facebook.katana.apkfileprovider"`), sehingga `startsWith("com.facebook.katana.")` bernilai `false` dan authority kedua tidak ter-patch (`"com.facebook.katana.clone.quicksilver.fileprovider; com.facebook.katana.apkfileprovider"`), bentrok langsung dengan authority ` com.facebook.katana.apkfileprovider` milik `com.facebook.katana`.
  - Ukuran/jumlah 14 file (295,2 MB) **bukan** penyebab konflik (`SplitInstallSessionCoordinator` menulis, `fsync`, dan commit 14 file normal hingga mencapai verifikasi `PackageManagerService`).
  - Dialog `"Can't verify app developer"` adalah gerbang terpisah sebelum instalasi (bila ditekan Close menghasilkan `STATUS_FAILURE_ABORTED`, bukan `STATUS_FAILURE_CONFLICT`); konflik terjadi setelah pengguna memilih `"Install anyway"`.
- Vonis: **Celah engine yang bisa diperbaiki** di `AxmlPatcher` (trim whitespace pada `rewriteAuthorities` + rename deklarasi `<permission>` non-prefixed beserta referensinya di manifest), dengan catatan bahwa perilaku runtime Facebook pasca-instalasi (pemeriksaan tanda tangan Meta di DEX) tetap di luar cakupan Dualapps.
- Test & Build: tidak diubah/dijalankan ulang (diagnosis only; terakhir Fase 12 commit `3608561`, **80/80 lulus**, `app-debug.apk` `10.193.252` byte).
- Commit: satu commit di branch `adil` — `"docs(phase12.1): record Facebook STATUS_FAILURE_CONFLICT diagnosis in STATUS.md"`

## Ringkasan Fase 12 (sebelumnya)
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
