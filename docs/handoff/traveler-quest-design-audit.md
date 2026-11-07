# Traveler Android: katalog dan detail Quest

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts` · baseline `eb81fae354803e420139af19fed579368d2d968f`.

Batch ini menyamakan katalog dan detail Quest dengan Figma yang diminta pemilik. Ini verifikasi bagian Quest yang berubah; gameplay, GPS/provider, Partner dan parity seluruh aplikasi memiliki cakupan berbeda.

## Acuan visual dan implementasi

| Area | Frame Figma | Implementasi |
| --- | --- | --- |
| Katalog | [95:1085](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=95-1085), layar 95:1089/1253/1519/1674 | Toolbar Back 48 dp; tab Tersedia/Diambil dengan face 36 dp dalam target 48 dp; Jenis Quest dan chip Semua/Pribadi/Kelompok. Gutter 20 dp, antarbagian 20 dp, antarkartu 16 dp, radius 16 dp dan padding kartu 16 dp. Centang terpilih memakai onPrimary; border chip memakai outline. Tinggi chip dapat bertambah saat font besar. |
| Filter | 95:1395 | Halaman filter lokasi/XP, input angka dengan label terpisah, surface isi/radius 12 dp, Reset/Apply 48 dp. Draft dikelola ViewModel, bertahan ketika kembali, dan hanya diterapkan setelah Apply. Lokasi dipilih dari repository lokasi existing, bukan teks/ID hasil tebakan. |
| Detail siap diambil | [97:1282](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=97-1282), layar 97:1286 dan full-scroll 97:1483 | Toolbar Back/Share 48 dp, status, judul 28 sp, lokasi, deskripsi, preview hadiah, traveler, kuota, Ambil Quest, panduan dan aktivitas. Badge preview 64×74,667 dp, XP 26 sp dan avatar 32 dp. Hadiah tetap preview sampai server mengonfirmasi penyelesaian. |
| Detail aktif | 97:1592, full-scroll 97:1796 | Label Sedang dijalankan, progress 280×28 dp dan Lanjutkan Quest. Status aktivitas diambil dari partisipasi pemilik yang terikat pada Quest dan ID aktivitas yang sama. |
| Checkpoint Spot | 97:1543–1564 | Kartu dengan padding 16 dp dan jarak aktivitas 12 dp; foto 72×72 dp, radius 10 dp, identitas, lima bintang 12 dp berjarak 1 dp, nilai pecahan dan komentar compact. Rating tetap memiliki semantics Referensi AI. Tautan membuka public Spot reader. |

Context high-fidelity, screenshot dan Code Connect diperiksa. Komponen core yang cocok digunakan kembali. `FloopelLabeledProgressIndicator` diadaptasi dari mapped source [7fe0972](https://github.com/dekaulitz/com.github.floopel.prjct/blob/7fe0972f1b6c76715db6d011500b858eca577f50/application/mobile/core/designsystem/src/main/kotlin/com/floopel/mobile/ui/components/FloopelLabeledProgressIndicator.kt); label track/fill digambar pada area clip masing-masing dengan satu semantics progress. Fill dibatasi tepat pada fraksi progres termasuk ujung bulatnya; native regression memeriksa bahwa 2/4 tidak melewati separuh track. Token track adalah secondaryContainer/onSecondaryContainer Figma.

Badge, foto checkpoint, nama traveler dan nilai hadiah adalah data API. Artwork contoh Figma tidak dijadikan fallback produksi. Ikon menggunakan glyph core yang dipetakan; status bar/gesture area memakai OS. Tidak ada URL aset temporer Figma atau screenshot frame yang menjadi aset runtime. Placeholder pada capture fixture disengaja dan tidak membuktikan provider gambar. Tidak ada dependency, DI, navigation framework atau toolchain baru.

## Perilaku dan batas data

- Katalog akun memakai authenticated repository/API; mode, lokasi dan rentang XP dikirim sebelum pagination. Scope lokasi dari entry detail lokasi mempertahankan filter active/exclude-taken. Hasil lama ditolak setelah pergantian filter, route atau actor.
- Diambil memakai koleksi partisipasi milik akun. Kontrak lean yang tidak menyediakan stable Quest ID/slug/geografi/reward tidak dilengkapi lewat pencocokan judul. Kartu hanya menampilkan fakta yang tersedia: mode, judul dan progres; klik melanjutkan partisipasi melalui GET existing.
- Retry menyimpan halaman read yang diminta. Refresh page 1 yang gagal setelah Back mengulang page 1; append page 2 yang gagal mengulang page 2 dengan filter yang sama. Cache dipertahankan, error tidak berubah menjadi empty, dan retry tidak mengulang admission.
- Detail memasangkan public reader dengan owner state yang memiliki stable Quest ID sama. Ambil memerlukan owner not-completed, mode personal, status active, execution available, provider siap dan kuota bukan nol. Double tap diblokir; hasil terlambat tidak menavigasi akun yang berbeda. Backend tetap memverifikasi izin dan kuota.
- Lanjutkan membaca partisipasi yang sudah dimiliki, termasuk ketika kuota publik penuh/Quest berakhir. Progress invalid/unknown tidak dibuat menjadi 0%. Public preview checkpoint hanya membaca Spot yang direferensikan dan tidak mengambil jawaban/proof gameplay.
- Group Quest mempertahankan entry kelompok existing; aturan admission kelompok tidak diganti dengan admission personal. Share menggunakan teks aktual melalui native host karena canonical web URL belum tersedia.

## Verifikasi

Perintah final menggunakan Gradle Wrapper, temporary Java Windows sesuai README, origin development eksplisit, dan `--no-parallel --max-workers=2`. Cache resource generator sempat tidak konsisten; `:shared:features:traveler:clean` lalu build tanpa build/configuration cache dan `-Pkotlin.incremental=false` menyelesaikannya tanpa perubahan toolchain.

```powershell
.\gradlew.bat :shared:features:traveler:testAndroidHostTest :apps:android:traveler:assembleDevelopmentDebug :apps:android:partner:assembleDevelopmentDebug :apps:android:traveler:assembleDevelopmentDebugAndroidTest :apps:android:traveler:lintDevelopmentDebug :apps:android:partner:lintDevelopmentDebug '-Pkotlin.incremental=false' --no-configuration-cache --no-build-cache --no-parallel --max-workers=2
```

Hasil aktual, hash source/APK, log, ringkasan host dan PNG tersimpan pada [manifest batch Quest](traveler-android-audit/quest-design-20261107/results.json).

| Pemeriksaan | Hasil |
| --- | --- |
| Host Traveler | **PASS: 459 test**, 0 failure/error/skipped; termasuk 15 state test Quest baru dan 3 test native identity/session/API projection |
| Native Android final | **PASS: 57 test** dalam satu eksekusi: 10 Quest design, 4 Quest state, 13 Figma/navbar, 13 journey, 13 lokasi, 2 publikasi, 1 font dan 1 autentikasi |
| Build | **PASS** developmentDebug Traveler, Partner dan APK androidTest Traveler |
| Lint | **PASS**, Traveler 0 error/42 warning; Partner 0 error/2 warning |
| Visual/interaksi Quest | 23 capture fixture native pada emulator 5580/API 35, viewport 400×900 dp; mode terpilih pada font 100/130/200, filter/draft, quota-full, taken/active progress, checkpoint/rating, empty/error dan refresh/append retry |
| Data nyata | Lihat [status smoke](traveler-android-audit/quest-design-20261107/live-smoke.json); domain doubles bukan bukti backend/provider E2E |
| iOS | **NOT_RUN** pada Windows; common UI/core memerlukan framework, host dan test Mac/Xcode/CI Mac |

Test fixture memakai production Compose/ViewModel melalui kontrak domain dan tidak masuk APK produksi. Pemeriksaan native akhir dijalankan pada source/APK yang dibekukan; hash diperiksa ulang sebelum delivery. Kegagalan test awal berasal dari pemilihan node lazy yang belum dikomposisi dan semantics anak kartu yang tergabung; test diperbaiki menggunakan lazy scroll dan unmerged semantics tanpa melemahkan assertion ukuran/interaksi. Jarak list, case label, warna centang, outline chip, adaptasi tinggi font dan batas fill/clip label progress juga dikoreksi melalui pemeriksaan visual.

Font fixture diatur melalui `LocalDensity` pada 100/130/200%; font OS tetap 100%. Ukuran/density perangkat dikembalikan ke 1080×2400/density 420 setelah pemeriksaan. APK development final dibuka pada MainActivity, yang berada di login. Smoke Quest dengan data nyata belum dijalankan: API lokal/ngrok mengembalikan 500 ketika PostgreSQL tidak aktif. Docker Desktop gagal menginisialisasi socket inference; restart paksa/pembersihan socket ditolak pemeriksaan otomatis (`blocked by policy`) dan percobaan restart melalui CLI resmi tidak selesai. Tidak ada reset database atau perubahan schema sebagai pemulihan.

## Batas verifikasi

- Peta tetap ditunda pemilik; tidak ada provider peta baru. FCM, delivery email/push dan konfigurasi production release tidak dibuktikan batch ini.
- Admission nyata, GPS/Integrity, jawaban aktivitas, seluruh flow kelompok, physical device dan TalkBack/VoiceOver tidak dijalankan pada batch desain ini. Bukti gameplay/provider sebelumnya tetap checkpoint terpisah.
- Group/location picker yang tidak memiliki frame interaktif dalam scope ini memakai alur repository existing; ini bukan sign-off seluruh varian picker/kelompok. Share tidak menciptakan canonical public URL.
- Gap builder/Trip, reader lain dan composer komentar Spot berada di luar batch Quest. Laporan audit terpisah menandai tinggi visual composer komentar; pengujian Quest tidak menutup temuan tersebut.

Backend/schema tidak diubah, database tidak di-reset atau dimigrasikan. Edit `.gitignore` dan `styles.xml` yang sudah ada sebelum batch tetap dipertahankan dan dikecualikan dari commit. `styles.xml` ikut terkompilasi; hash konteksnya dicatat. Branch PR dipertahankan; merge dan rilis tidak dilakukan.
