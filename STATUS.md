# STATUS — Dualapps
Terakhir diperbarui: 2026-11-27 16:45 WIB — Fase 17.1 (Diagnosis Gagal Uninstall) selesai

## Ringkasan fase terakhir (Diagnosis Only — tanpa perubahan kode)
- Fase: 17.1 — Diagnosis gagal uninstall pada jalur `"Delete file & uninstall app"` (video Cak 27 Nov 2026 16:11–16:24 WIB di RMX1971: setelah `"Yes, delete"`, dialog uninstall sistem tidak pernah muncul dan langsung tampil toast `"Deletion cancelled."`):
  - **Status reproduksi perangkat:** `adb devices -l` (via `C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe`) mengembalikan daftar perangkat kosong (HP RMX1971 tidak tersambung saat sesi ini), sehingga **logcat langsung dari RMX1971 belum didapat** dan menunggu verifikasi 1x dari Cak.
  - **Akar masalah utama (terbukti dari kode + spesifikasi resmi Android API 28+, menunggu konfirmasi 1 baris logcat di RMX1971):**
    1. `app/build.gradle.kts` (baris 26) menetapkan `targetSdk = 34` (≥ API 28 / Android 9), sedangkan `app/src/main/AndroidManifest.xml` (baris 5–9) **hanya mendeklarasikan `QUERY_ALL_PACKAGES` dan `REQUEST_INSTALL_PACKAGES` — TIDAK mendeklarasikan `<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />`**.
    2. Sejak Android 9 (API 28), `com.android.packageinstaller` (`UninstallerActivity`) menolak permintaan `Intent.ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` (maupun `PackageInstaller.uninstall()`) dari aplikasi dengan `targetSdk >= 28` yang tidak memiliki izin normal `REQUEST_DELETE_PACKAGES`. Intent tetap berhasil di-resolve dan di-launch (tidak melempar exception di `ResultsActivity.kt:444-446`), namun `UninstallerActivity` langsung memanggil `finish()` (`RESULT_CANCELED`) tanpa menampilkan dialog satu frame pun.
    3. Callback `uninstallLauncher` (`ResultsActivity.kt:58-95`) mengabaikan `result.resultCode`/`result.data` dan langsung mengecek `isPackageInstalledOnDevice(record.clonePackageName)`. Karena paket masih terpasang, `CloneDeleteCoordinator.completeAfterSystemUninstall(..., isStillInstalledLive = true)` (baris 129–133) selalu memetakan semua kegagalan/penolakan sistem menjadi `CancelledAtSystemDialog` → toast `"Deletion cancelled."`.
  - **Temuan sekunder (tampilan "terpilih default" di Popup 1):** Tidak ada state seleksi default di `ResultsActivity.kt:336-378`. Tampilan kuning pada `"Delete file & uninstall app"` berasal murni dari styling statis XML di `app/src/main/res/layout/dialog_delete_options.xml` baris 72 (`android:background="@drawable/bg_neo_button_yellow"`) dan baris 97 (`android:textColor="@color/ink_primary"`), sedangkan `"Delete file only"` (baris 37 & 62) memakai `bg_neo_button_white` & `ink_muted`.
  - **Rekomendasi untuk Fase 17.2 (setelah konfirmasi logcat RMX1971):**
    1. Tambahkan `<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />` di `AndroidManifest.xml` (izin normal/install-time tanpa dialog izin runtime; perlu persetujuan Cak/Cireng karena sebelumnya manifest dibatasi 2 izin).
    2. Perbaiki pembacaan hasil di `ResultsActivity.kt` (`uninstallLauncher`) agar tidak buta menyamakan dialog ditolak sistem dengan pembatalan pengguna (cek `ActivityResult` + deteksi bila activity langsung kembali tanpa interaksi/tanpa izin).
    3. Ubah `optionDeleteFileAndUninstall` di `dialog_delete_options.xml` baris 72 & 97 menjadi `bg_neo_button_white` & `ink_muted` agar kedua opsi tampil netral tanpa pilihan default.
- Berkas berubah fase ini: `STATUS.md` saja (0 berkas kode/test/layout/manifest diubah).
- Commit: satu commit di branch `adil` — `"docs(phase17.1): diagnose uninstall failure"`

## Ringkasan Fase 17 (sebelumnya)
- Fase: 17 — Alur hapus 2-langkah dengan opsi uninstall sistem (seluruh 5 keputusan Cak dikunci, semua teks UI Bahasa Inggris, tanpa menyentuh mesin clone/patch/signing/checker):
  1. **Popup 1 (`dialog_delete_options.xml`)** — muncul saat tombol `DELETE` ditekan pada kartu clone di `ResultsActivity`; memeriksa status terpasang secara LIVE via `PackageManager.getPackageInfo`. Bila clone terpasang, tampilkan 2 opsi neo-brutalism beserta sub-teks konsekuensi & ukuran berkas terformat (`FormatUtils.formatFileSize`): **"Delete file only"** (`"Frees {size}. The installed app stays on your device."`) dan **"Delete file & uninstall app"** (`"Frees {size} + app storage. The app will be removed from your device."`). Bila clone belum terpasang, opsi uninstall **disembunyikan** (`View.GONE`).
  2. **Popup 2 (`dialog_confirm_delete.xml`)** — konfirmasi akhir dengan tombol **"Cancel"** dan **"Yes, delete"** yang mengulang konsekuensi spesifik opsi terpilih (termasuk catatan bahwa untuk memasang ulang perlu clone ulang pada mode *file only*, dan peringatan permanen pada mode *file & uninstall*). Tap di luar dialog atau tombol Back = batal (tidak ada berkas yang dihapus).
  3. **Urutan eksekusi aman (`CloneDeleteCoordinator` + `ActivityResultLauncher`)** — pada **"Delete file & uninstall app"**, sistem meluncurkan dialog uninstall Android (`Intent.ACTION_DELETE` dengan `EXTRA_RETURN_RESULT`) **terlebih dahulu**, lalu memverifikasi status live `PackageManager` setelah dialog kembali. Bila paket masih terpasang (dibatalkan/gagal), **semuanya dibatalkan** (berkas APK, metadata JSON, dan ikon kustom tetap utuh + toast `"Deletion cancelled."`). Berkas hanya dihapus setelah uninstall terkonfirmasi berhasil; daftar dan tab `"Installed ✓"` langsung diperbarui dari `PackageManager`.
- Test: **122/122 lulus** (114 test sebelumnya + 8 test baru Fase 17 di `CloneDeleteFlowTest`, termasuk `Phase15UiAndEnglishGuardTest` lulus 100%), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.262.554` byte / ~9,79 MB, `EXIT_CODE=0`, durasi total `249.66s`)
- Commit: satu commit di branch `adil` — `"feat(phase17): delete flow with uninstall option"`
- Audit mandiri Fase 17: 0 ketidaksesuaian pada kode/test/build; berkas terlarang (`AxmlPatcher`, `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`, `CloneCompatibilityChecker`, `DexStringReader`, `CloneUpdateChecker`, `ClonePipeline`, `AndroidManifest.xml`, `build.gradle.kts`) tidak disentuh; uji perangkat alur hapus 2-langkah + dialog uninstall sistem Android menunggu verifikasi Cak di RMX1971.

## Ringkasan Fase 16 (sebelumnya)
- Fase: 16 — Penyeragaman gaya ketiga tab note label + pembatalan navigasi otomatis pasca-pasang + perbaikan transisi loading Clone Results:
  1. Ketiga tab kini memakai **satu bentuk note label yang sama persis** (folder tab sudut atas 10dp, sudut bawah 0dp, border 2.5dp, menempel langsung ke tepi atas kartu via `layout_marginBottom="-2.5dp"` pada container baris tab): `CLONE RESULT` ungu (`bg_neo_folder_tab_purple.xml`), `Installed ✓` teal (`bg_neo_folder_tab_teal.xml`) tepat di kanan `CLONE RESULT` bersebelahan tanpa celah spacer, dan `COMPATIBILITY WARNING` kuning (`bg_neo_folder_tab_yellow.xml`). Drawable pill lama `bg_neo_tab_installed_pill.xml` dihapus.
  2. Navigasi otomatis `delay(1500L)` ke `ResultsActivity` dihapus dari `CloneProgressActivity` (`InstallProgressTracker.shouldAutoNavigateToResults` selalu `false`); setelah pemasangan berhasil, layar proses tetap menampilkan `"Installed ✓"` + tombol `OPEN` + `INSTALL` + tombol sekunder manual **`VIEW RESULTS`** (`btnViewResults`).
  3. Transisi loading → daftar di `ResultsActivity` diperbaiki agar `layoutLoading` selalu disembunyikan (`View.GONE`) **sebelum** `adapter.submitList(items)` dipanggil dan `rvResults.itemAnimator = null` sehingga tidak ada frame tumpang tindih.
- Test: **114/114 lulus** (109 test sebelumnya + 5 test baru Fase 16 di `Phase16TabAndNoAutoNavTest`), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.253.588` byte / ~9,78 MB, `EXIT_CODE=0`, durasi total `136.71s`)
- Commit: satu commit di branch `adil` — `"fix(phase16): unify tab style and remove auto-navigation"`
- Audit mandiri Fase 16: 0 ketidaksesuaian pada kode/test/build; berkas mesin (`AxmlPatcher`, `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`, `CloneCompatibilityChecker`, `DexStringReader`, `CloneUpdateChecker`, `ClonePipeline`, `AndroidManifest.xml`, `build.gradle.kts`) tidak disentuh; tampilan visual tab, layar proses tanpa loncat otomatis, dan transisi loading bersih menunggu verifikasi perangkat Cak di RMX1971.

## Ringkasan Fase 15 (sebelumnya)
- Fase: 15 — Polish tampilan & konversi bahasa penuh ke Bahasa Inggris (tanpa mengubah logika mesin clone/patch/signing/checker):
  1. Badge `"Installed ✓"` dipindahkan dari pojok kanan-atas dalam kartu menjadi **tab pill membulat penuh** (`tvInstalledTab` dengan `bg_neo_tab_installed_pill.xml`: radius `999dp`, border `2.5dp`, shadow `4dp`, teal `#1ED3A6`) di atas kartu sejajar baris tab folder `CLONE RESULT` (`item_cloned_apk.xml`, `ClonedApksAdapter.kt`); badge dalam kartu `tvInstalledBadge` dihapus.
  2. Tombol `OPEN` (`btnOpen` di `item_cloned_apk.xml` & `btnOpenApp` di `activity_clone_progress.xml`) diperbaiki dari sebelumnya keliru memakai drawable tab folder `bg_neo_folder_tab_teal` (sudut bawah lurus tanpa shadow) menjadi drawable tombol neo-brutalism standar baru `bg_neo_button_teal.xml` (radius `12dp` di keempat sudut, border `2.5dp`, hard shadow `4dp`, `paddingEnd`/`paddingBottom` `4dp`, identik struktur dengan `bg_neo_button_yellow` / `bg_neo_button_white`).
  3. **Seluruh teks UI & pesan error dikonversi ke Bahasa Inggris** (`strings.xml`, `CloneError.kt`, `InstallProgressTracker.kt`, `SplitInstallSessionCoordinator.kt`, `SplitInstallResultReceiver.kt`, `PackageNameValidator.kt`, `ApkBundleExtractor.kt`, `ApkFileSourceValidator.kt`, label `CloneStep` & `OVERRIDE_WARNING_NOTE` di `ClonePipeline.kt`, serta teks pesan `CloneCompatibilityChecker.kt`).
- Test: **109/109 lulus** (106 test sebelumnya + 3 test baru Fase 15 di `Phase15UiAndEnglishGuardTest`), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.227.853` byte / ~9,75 MB, `EXIT_CODE=0`, durasi total `212.7s`)
- Commit: satu commit di branch `adil` — `"feat(phase15): polish installed badge, OPEN button, all-English UI"`
- Audit mandiri Fase 15: 0 ketidaksesuaian pada kode/test/build; tidak ada perubahan logika pada mesin clone/patch/signing/checker maupun `AndroidManifest.xml` / `build.gradle.kts`; seluruh tampilan visual (tab pill `"Installed ✓"`, tombol `OPEN`, dan seluruh layar Bahasa Inggris) menunggu verifikasi perangkat Cak di RMX1971.

## Ringkasan Fase 14 (sebelumnya)
- Fase: 14 — Perbaikan UX 5 poin pasca-clone & pemasangan (tanpa mengubah mesin clone/patch/signing/checker maupun desain neo-brutalism):
  1. Kartu penyelesaian eksplisit setelah 7/7 di `CloneProgressActivity` (`Clone selesai dibuat ✓` + nama + package + ukuran/jumlah file + `Ketuk PASANG untuk memasang clone ini di perangkatmu.`).
  2. Perbaikan transisi fase `InstallProgressTracker` (`PREPARING` → `WAITING_CONFIRMATION` `"Menunggu konfirmasi pemasangan…"` → `INSTALLING` `"Memasang…"` → `Succeeded` `"Terpasang ✓"` / `Failed` tanpa spinner macet) melalui kombinasi `onPause()`/`onResume()` saat kembali dari dialog sistem + `PackageInstaller.SessionCallback` (`onActiveChanged`/`onProgressChanged`) di `SplitApkInstaller`.
  3. Status `"Terpasang ✓"` + tombol `"BUKA"` (`getLaunchIntentForPackage`) di `CloneProgressActivity` & setiap kartu `ResultsActivity` (tombol `PASANG` tetap tersedia untuk pasang ulang).
  4. Navigasi otomatis (~1,5 detik setelah `Terpasang ✓`) dari `CloneProgressActivity` ke `ResultsActivity` bila pemasangan dipicu dari layar proses (`InstallOrigin.CLONE_PROGRESS`); bila dipicu dari `ResultsActivity` (`InstallOrigin.RESULTS`), pembaruan dilakukan di tempat tanpa navigasi.
  5. Indikator loading (`layoutLoading` dengan spinner + `"Memuat daftar clone…"`) di `ResultsActivity` sebelum data `ClonedApksRepository` selesai dimuat (tanpa flash daftar kosong).
- Test: **106/106 lulus** (102 test sebelumnya + 4 test baru Fase 14 di `PostCloneAndInstallUxTest`), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.228.044` byte / ~9,75 MB, `EXIT_CODE=0`, durasi total `54.48s`)
- Commit: satu commit di branch `adil` — `"feat(phase14): polish post-clone and install UX"`
- Audit mandiri Fase 14: 0 ketidaksesuaian pada kode/test/build; seluruh berkas mesin (`AxmlPatcher`, `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`, `CloneCompatibilityChecker`, `DexStringReader`, `CloneUpdateChecker`, `ClonePipeline`, `AndroidManifest.xml`, `build.gradle.kts`) tidak disentuh; uji visual & alur pasca-clone di HP RMX1971 menunggu eksekusi Cak.

## Ringkasan Fase 13.2 (sebelumnya)
- Fase: 13.2 — Perbaikan false positive `CloneCompatibilityChecker` hasil diagnosis Fase 13.1:
  - Pemisahan keparahan di `CloneCompatibilityChecker.kt`: `CompatibilityRule.A` (`BLOCKING`, provider signature milik target sendiri) menggagalkan clone dengan `CloneError.INCOMPATIBLE_APP`; `CompatibilityRule.B` (`WARNING`, provider signature lintas-aplikasi seperti Muse → `katana.UserValuesProvider`) memperingatkan lewat kartu kuning `cardWarning` (`PERINGATAN KOMPATIBILITAS`) dan membiarkan clone lanjut normal; bila A+B muncul bersamaan, `BLOCKING` (A) menang.
  - Persistensi catatan di `ClonedApkRecord.compatibilityWarning` (JSON backward-compatible) dan ditampilkan di layar Hasil (`item_cloned_apk.xml` / `ClonedApksAdapter.kt`).
  - Tombol override satu kali **"Tetap clone (risiko crash)"** pada kartu error `INCOMPATIBLE_APP` di `CloneProgressActivity.kt`, mengalirkan `skipCompatibilityCheck = true` lewat `CloneProgressStartResolver` → `ClonePipeline` tanpa pengaturan permanen, dan mencatat override di `compatibilityWarning`.
- Test: **102/102 lulus** (95 test sebelumnya + 7 test baru Fase 13.2: 5 di `CloneCompatibilityCheckerTest`, 2 di `ClonePipelineTest`), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.198.556` byte / ~9,73 MB, `EXIT_CODE=0`, durasi total `89.85s`)
- Commit: satu commit di branch `adil` — `"fix(phase13.2): separate Rule A block from Rule B warning and add override"`
- Audit mandiri Fase 13.2: 0 ketidaksesuaian pada kode/test/build; berkas terlarang (`DexStringReader`, `AxmlPatcher`, `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`, `CloneUpdateChecker`, mode `isUpdate`, `AndroidManifest.xml`, `build.gradle.kts`) tidak disentuh; uji perangkat pada HP RMX1971 (Facebook blokir + override, Muse 7/7 dengan kartu peringatan Aturan B, regresi ChatGPT split) menunggu eksekusi Cak.

## Ringkasan Fase 13.1 (Diagnosis)
- Fase: 13.1 — **Diagnosis only** (tanpa ubah kode/test/build/DEX) atas false positive `CloneCompatibilityChecker` (Fase 13) pada Muse (`com.facebook.aura`, `versionName=17.1.0.28.160`, `versionCode=1062201892`, 2 file: `base.apk` 62,4 MB + `split_browser_engine.apk` 7,4 MB).
- Bukti langsung (`adb pull` 2 APK Muse + scan 7 DEX `base.apk` + `dexdump -d classes5.dex` + `aapt2 dump xmltree` manifest `katana`):
  - Seluruh 50 URI `content://` (45 URI keluarga Meta AccessLibrary, termasuk `content://com.facebook.katana.provider.UserValuesProvider/user_values`) hanya berada di `base.apk -> classes5.dex` (0 di 6 DEX lainnya dan 0 DEX di `split_browser_engine.apk`).
  - Dari seluruh URI tersebut, hanya `com.facebook.katana.provider.UserValuesProvider` yang memiliki atribut manifest `android:permission="com.facebook.katana.provider.ACCESS"` (`protectionLevel=signature`) di `com.facebook.katana` (provider lain seperti `FirstPartyUserValuesProvider` dan `UsdidValuesProvider` tidak memiliki atribut `android:permission` di manifest).
  - **H1 TERBUKTI 100% (`try/catch` eksplisit di 3 lapis):** URI `content://com.facebook.katana.provider.UserValuesProvider/user_values` disimpan dalam registri statis Meta AccessLibrary (`LX/EhU;.A0F` → `LX/EhU;.A00..A03`), lalu dipakai oleh `MAHatchNativeAuthAppStartLogger.fetchWaEligibilityFlags` (startup) dan `X.EhL.A07` lewat `X.EhL.A00` (acquire `ContentProviderClient`) & `X.EhN.A00` (`ContentProviderClient.query`). Ketiganya membungkus pemanggilan provider dalam `try/catch` yang menangkap `Ljava/lang/SecurityException;`, `Landroid/os/RemoteException;`, `Ljava/lang/Exception;`, dan `<any>`.
  - Bahkan Muse ASLI (`com.facebook.aura`, sertifikat `7021f46`) memiliki sertifikat berbeda dari Facebook (`com.facebook.katana`, sertifikat `c412e90e`), sehingga Muse asli pun sudah bergantung pada `catch (SecurityException)` tersebut dan tidak pernah crash.
- Vonis: **H1 terbukti (false positive dari Aturan B lintas-aplikasi)** — karena URI disimpan di registri statis (`LX/EhU;`) terpisah dari pemanggil `ContentProviderClient` (`X.EhL.A00`/`X.EhN.A00`), deteksi `try/catch` intra-metode saja tidak cukup. Rekomendasi perbaikan untuk Fase 13.2: **(b) bedakan Aturan A (provider signature milik target sendiri, risiko crash tinggi seperti Facebook) vs Aturan B (provider lintas-aplikasi seperti Muse/gservices yang umumnya dibungkus `try/catch`) + sediakan tombol override `"Tetap clone (risiko crash)"`** pada dialog/layar peringatan agar pengguna bisa melanjutkan sendiri.
- Test & Build: tidak diubah/dijalankan ulang (diagnosis only; terakhir Fase 13 commit `efe860f`, **95/95 lulus**, `app-debug.apk` `10.300.802` byte).
- Commit: satu commit di branch `adil` — `"docs(phase13.1): record Muse false-positive diagnosis in STATUS.md"`

## Ringkasan Fase 13 (sebelumnya)
- Fase: 13 — Pemeriksaan kompatibilitas pra-clone fail-fast (read-only DEX, tanpa modifikasi bytecode):
  - `DexStringReader.kt` (baru): parser read-only tabel `string_ids` DEX (versi `035`–`041`, mendukung fast-path prefix `content://` untuk hemat memori pada APK besar, fail-open pada DEX rusak/magic tak dikenal).
  - `CloneCompatibilityChecker.kt` (baru): mengekstrak semua URI `content://` hardcode dari `classes*.dex` seluruh APK sumber (base + split) dan memeriksa terhadap Aturan A (provider di manifest target dengan permission `protectionLevel` `signature` / `signatureOrSystem`) dan Aturan B (provider lintas-aplikasi terpasang via abstraksi `ContentProviderResolver` / `AndroidContentProviderResolver`).
  - `CloneError.INCOMPATIBLE_APP` (baru, total 25 enum): dilempar dari `ClonePipeline` pada Langkah 1 (`VALIDATE_SOURCE`) sebelum patching AXML dimulai, menampilkan authority + permission + akibat langsung di kartu error layar proses tanpa menghasilkan APK clone.
- Test: **95/95 lulus** (86 test sebelumnya + 9 test baru Fase 13: 2 di `DexStringReaderTest`, 5 di `CloneCompatibilityCheckerTest`, 2 di `ClonePipelineTest`), 0 gagal, 0 dilewati (perintah: `.\gradlew.bat testDebugUnitTest assembleDebug` dengan `JAVA_HOME=C:\Users\Administrator\.jdks\jdk-17.0.21.1+2`)
- Build debug: **BUILD SUCCESSFUL** — `app/build/outputs/apk/debug/app-debug.apk` (`10.300.802` byte / ~9,82 MB, `EXIT_CODE=0`, durasi total `71.67s`)
- Commit: satu commit di branch `adil` — `"feat(phase13): pre-clone compatibility check warns and aborts incompatible apps"`
- Audit mandiri Fase 13: 0 ketidaksesuaian pada kode/test/build; berkas terlarang (`AxmlPatcher`, `ArscPatcher`, `AlignedZipWriter`, `ApkRebuilder`, `CloneIdentityPatcher`, `ApkSigningEngine`, `CloneUpdateChecker`, mode `isUpdate`, `AndroidManifest.xml`, `build.gradle.kts`) tidak disentuh; uji perangkat pada HP RMX1971 (tolak Facebook + regresi Muse & ChatGPT split) menunggu eksekusi Cak.

## Ringkasan Fase 12.3 (Diagnosis)
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
