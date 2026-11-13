# Splash dan intro Traveler Android

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts` · package `com.floopel.traveler`.

Figma final menjadi acuan untuk Splash dan empat halaman intro. Ini koreksi startup Android; [backlog layar lain](traveler-android-audit/layout-20261107/open-gaps.json) tetap berlaku.

## Acuan visual

| Frame Figma | Implementasi |
| --- | --- |
| [164:2263, instalasi pertama](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2263), [164:2351, belum login](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2351), [164:2439, sudah login](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2439) | Primary `#3730A3`, mark resmi putih 96×96 di tengah seluruh window. Tidak ada wordmark atau spinner tambahan. |
| [164:2527](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2527) | Perjalanan yang lebih bermakna; CTA Lanjut. |
| [164:2615](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2615) | Temukan cerita di setiap tempat; Kembali dan Lanjut. |
| [164:2703](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2703) | Rencanakan, lalu pergi bersama; Kembali dan Lanjut. |
| [164:2791](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=164-2791) | Jadikan jelajah sebuah petualangan; Kembali dan Lanjut ke login. |

Mark intro 36×36, ilustrasi SVG resmi 280×236, gutter 24 dp, jarak 20 dp, heading 28 sp Bold/37,8 dan body 15 sp/20,25 mengikuti frame. Dots memakai 24×8 untuk langkah aktif dan 8×8 untuk lainnya, gap 8. CTA 48 dp: halaman pertama memenuhi 352 dp; halaman berikutnya Kembali 100 dp, gap 12 dan primary 240 dp pada viewport 400 dp. Halaman dapat scroll ketika window/font membutuhkan ruang. Status/navigation bar memakai OS, tanpa menggambar status bar Figma sebagai aset.

## State dan penyimpanan

`TravelerStartupRepository` adalah kontrak lokal, terpisah dari sesi/API. Adapter Android menyimpan `intro_version`, `intro_step` dan `intro_completed` dalam preferences instalasi non-sensitif. Progres disimpan sebelum pindah halaman. Hanya CTA terakhir menandai selesai. Logout tidak menghapus progres; akun/backend tidak mendapat field baru. Tidak ada Skip, swipe, autoplay atau penundaan minimum 0,8 detik di runtime production.

Sesi dipulihkan sebelum intro atau fitur diputuskan. Instalasi baru tanpa sesi menuju intro lalu Login. Instalasi yang sudah menyelesaikan intro dan belum login langsung menuju Login. Sesi valid mempertahankan route yang berizin dan menandai instalasi sebagai returning. Error penyimpanan memiliki retry eksplisit; cancellation tidak diubah menjadi sukses. Login wajib dan sanitasi stack/tab dari audit sebelumnya tetap berlaku.

APK debug menyediakan scope QA yang tervalidasi melalui intent. Scope mengisolasi preferences instalasi, sesi Keystore dan journal; digest default tetap kompatibel dengan file pengguna existing. Delay baca lokal untuk skenario lambat hanya diizinkan dalam scope debug tersebut. Release mengabaikan extras. Pengujian tidak memakai `pm clear`, reset emulator, atau reset database.

## Integrasi native dan dependency

`core-splashscreen:1.2.0` dipasang sebelum `super.onCreate`, dengan theme launch dan `postSplashScreenTheme`. Mark SVG asli tetap di Compose Resources; VectorDrawable native dihasilkan dengan konverter penuh `Svg2Vector` dari Android SDK 32.1.0 tanpa edit path. Compat memakai canvas 288 dp dengan inset 96 dp. Android 12+ memakai inset proporsional sepertiga karena sistem melakukan pre-render pada viewport adaptive yang berbeda; inset absolut membuat ikon kosong. Android 13+ juga memakai `icon_preferred`. Mark native tidak terpotong mask dan tetap 96 dp. Ini adaptasi format/wrapper native, bukan ilustrasi baru.

AndroidX Core 1.17.0 menyediakan `WindowCompat.enableEdgeToEdge`: inisialisasi decor dan pencegahan color view native yang mengonsumsi insets. Versi ini dipin untuk kebutuhan tersebut; Kotlin, Compose dan AGP tidak diganti. Dasar: [API WindowCompat](https://developer.android.com/reference/androidx/core/view/WindowCompat#enableEdgeToEdge(android.view.Window)), [rilis Core](https://developer.android.com/jetpack/androidx/releases/core#1.17.0), dan [panduan Splash Android](https://developer.android.com/develop/ui/views/launch/splash-screen/). Perubahan hanya menambah dependency native Android (lisensi AndroidX Apache 2.0), tanpa dependency baru pada framework iOS/Partner.

Pada emulator Android 15, recreation awal mengecilkan root Compose menjadi 1080×2237 dan mengekspos background window pada system bar. Panggilan `setDecorFitsSystemWindows(false)` dipindahkan sebelum inisialisasi decor: pada mode enforcement, panggilan sesudahnya tidak lagi memperbarui listener window yang dipertahankan. Urutan ini mengikuti diagnosis dari [source PhoneWindow API 35](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/com/android/internal/policy/PhoneWindow.java) dan [perbaikan preserved window AOSP](https://android.googlesource.com/platform/frameworks/base/+/3c3036aedc52e2eae0ce76e683dc11a0a859c0a7). Uji regresi kemudian mempertahankan root 1080×2430, system bar transparan dan inset konten satu kali sesudah recreation. Ini bukti emulator tersebut, bukan klaim semua versi Android.

## Verifikasi

Hasil final, hash source/APK, geometri dan PNG/video dicatat pada [manifest startup](traveler-android-audit/startup-20261107/results.json). Bukti layout 377 test pada checkpoint sebelumnya tetap menjadi snapshot terpisah.

- Build debug dan lint Traveler serta Partner PASS. Host deterministik 383 test PASS, tanpa failure/error/skipped.
- Native final 24 test PASS: 16 regresi dengan domain fixture, empat MainActivity startup/progres, tiga fase privat nyata dan satu invalid refresh melalui backend lokal/ngrok. Progres yang belum selesai dipulihkan pada PID baru; completed installation tidak mengulang intro. Small window 320×640 dp/font 130% dapat scroll ke CTA.
- [Rekaman normal](traveler-android-audit/startup-20261107/cold-start-normal.mp4) menunjukkan cold start tanpa delay QA dan empat intro menuju Login. [Rekaman baca lokal lambat](traveler-android-audit/startup-20261107/cold-start-slow.mp4) memakai delay QA 600 ms; delay ini tidak ada pada runtime production. PNG membedakan [OS splash](traveler-android-audit/startup-20261107/os-splash.png) dan [Compose splash](traveler-android-audit/startup-20261107/startup-branded.png), serta [intro 1](traveler-android-audit/startup-20261107/intro-1.png), [2](traveler-android-audit/startup-20261107/intro-2.png), [3](traveler-android-audit/startup-20261107/intro-3.png), [4](traveler-android-audit/startup-20261107/intro-4.png).
- Mark OS pada frame rekaman terukur sekitar 258×258 px/96 dp, center (540,1215) pada window 1080×2430. Geometri Compose memakai assertion 96/36 dan 280×236 dp. Rasterisasi/antialias native mengikuti OS.
- Tujuh file default sesi/journal yang diperiksa memiliki hash identik sebelum/sesudah pengujian scope privat. Manifest credential sementara di emulator dihapus. Tidak ada reset data atau migration.

Startup native bersifat opt-in sebelum Activity dimulai: `floopelStartupCheck=true`, dengan `floopelStartupScope=qa-intro-<unik>`. Jalankan `TravelerStartupJourneyTest`, force-stop, lalu `TravelerStartupReopenTest` pada scope yang sama. Untuk progres belum selesai, gunakan scope baru pada `TravelerStartupProgressCheckpointTest`, force-stop, lalu `TravelerStartupProgressResumeTest`. Test privat mengikuti [setup fixture dan tiga fase](traveler-private-audit.md), dengan `floopelRuntimeScope=qa-private-<unik>` yang sama; invalid refresh memakai scope baru dan `floopelInvalidRestoreCheck=true`. Runner `com.floopel.traveler.test/androidx.test.runner.AndroidJUnitRunner`; semua operasi device dibatasi ke `emulator-5580`.

iOS framework/host/test, VoiceOver, TalkBack manual, perangkat fisik dan matriks API Android selain emulator audit belum dijalankan. Sukses Android tidak membuktikan iOS; host iOS masih scaffold. Tidak ada klaim startup performance atau sign-off seluruh desain.
