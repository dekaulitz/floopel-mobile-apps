# Traveler Android — Passport, Semua Spot, Detail Spot dan tab Profil publik

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts`.

Checkpoint ini terikat pada `c85046b`. [Follow-up status pilihan Passport](traveler-passport-semantics-audit.md) menambahkan semantics pilihan/grup dan pengujian fokus berikutnya; log, PNG dan manifest checkpoint ini tetap historis.

Batch ini menindaklanjuti tiga tautan Figma pemilik dan temuan tab Profil publik yang membuat halaman bergerak ketika dipilih. Checkpoint Settings/akun sebelumnya tetap berada pada commit `488ec54` dan auditnya tidak ditulis ulang. Detail Spot pada batch ini mengikuti frame utama 36:696 dari tautan terbaru pemilik, sehingga menggantikan geometri varian 308:4706 pada checkpoint sebelumnya.

## Acuan visual

| Bagian | Frame dan penerapan |
| --- | --- |
| [Semua Spot](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=24-674) | Android 24:681 dan 24:887. Gutter 16 dp, foto 88×88 dp/radius 12 dp, padding vertikal baris 8 dp, judul 17/22,95 sp, kategori dan chip scope terpilih. Scope geografis diterapkan API sebelum pagination; jumlah kategori hanya ditampilkan bila hasil lengkap. Home global tidak diberi chip lokasi contoh yang tidak berasal dari konteks navigasi. |
| [Detail Spot](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=39-916) | Android 36:696 dan scroll 37:983. Toolbar 48 dp dengan aksi Simpan/Bagikan/Lainnya; foto utama 240 dp penuh lebar; thumbnail 96 dp dengan gap 8 dp dan gutter 20 dp; kategori 12 sp, judul 28 sp, kartu lokasi radius 14 dp, rating detail 16 dp. Tambahkan ke rencana sebelum Tentang Spot dan tombol komentar. Nearby 280 dp dengan media 72×80 dp; kartu Open Trip vertikal dengan media 72×96 dp dan tombol katalog. Rail Quest/Collection/artikel tetap di dalam gutter 20 dp sesuai frame. |
| [Passport Digital](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=271-4282) | 274:4284 ringkasan, 4414 selesai, 4546 badge, 4711 statistik, 4840 kosong, 4973 loading, 5072 gagal, 5175 identitas gagal, 5304 barcode besar, 5409 fokus badge dan 275:4314 barcode awal. Kartu primary/radius 24 dp, padding 20 dp, watermark sumber Figma, slot QR 128/248 dp dan kode 120/240 dp. |
| Tab Profil publik | Identitas, progres, Kelola profil dan tab tetap berada pada posisi yang sama selama pergantian panel. Toolbar tetap di atas sesuai keputusan pemilik sebelumnya. |

## State dan data

- Pergantian tab Profil publik hanya membatalkan/memuat request panel terpilih. Identitas dan progres tidak dimuat ulang. Respons header yang sedang berjalan tetap terikat pada pengguna dan generasi sesi yang sama meskipun tab berubah. Respons panel yang dibatalkan tidak bisa menimpa panel baru; retry error panel tidak memuat ulang header. Tinggi minimum area panel mempertahankan posisi scroll ketika daftar panjang berubah menjadi loading/hasil pendek.
- Passport membaca profil dan progress lewat kontrak repository. ID badge dan riwayat penyelesaian terbatas dari API dipertahankan pada proyeksi domain. Statistik tidak menganggap daftar riwayat terbatas sebagai jumlah penyelesaian seumur hidup. Lanjutkan memakai pembacaan partisipasi existing; tidak mengambil Quest baru. Tombol berbagi penyelesaian hanya tersedia ketika API memberikan `share_available=true`.
- QR dibentuk offline dari `passportSummaryJson`: hanya `username`, `level`, `total_xp`. Level 1/XP 0 adalah data valid. QR tidak memuat email, token, otorisasi atau receipt reward. Profil gagal dimuat mempertahankan progres yang valid tetapi menyembunyikan QR sampai retry identitas berhasil. Perubahan payload membuang matriks sebelumnya; cancellation dipertahankan.
- QR memakai piksel persegi dan quiet zone putih minimal empat modul pada semua sisi. Watermark berada di belakang kartu dan di luar area QR putih. Kode fixture Figma, nama contoh, badge contoh dan foto contoh tidak menjadi data/fallback runtime.
- Peta tetap ditunda sesuai instruksi pemilik. Tidak ada SDK/provider atau ilustrasi peta pengganti yang ditambahkan. Tombol komentar, simpan, bagikan, tambah rencana dan navigasi terkait memakai alur existing.

## Dependensi dan aset

Encoder [QRCode-Kotlin 4.5.0](https://github.com/g0dkar/qrcode-kotlin/tree/v4.5.0) dipakai melalui API matriks mentah dan dirender komponen `core:designsystem`. Tidak diperlukan provider jaringan atau Compose UI library tambahan. Artefak KMP menerbitkan varian Android dan ketiga target iOS yang dipakai proyek; ketersediaan artefak bukan bukti kompilasi iOS. Versi berada di katalog Gradle. [ZXing 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4) hanya menjadi dependensi instrumentation untuk membaca piksel hasil render secara independen. Toolchain, compileSdk dan Compose proyek tidak di-upgrade.

[Manifest aset](traveler-android-audit/passport-spots-20261107/assets.json) mencatat tujuh SVG tanpa modifikasi: dua ikon toolbar Spot, dua watermark dan tiga ilustrasi Passport. Ikon umum dan tombol menggunakan komponen design system yang dipetakan Figma. Gambar badge berasal dari API; placeholder hanya digunakan saat artwork tidak tersedia.

## Verifikasi

Hasil dan fingerprint akhir dicatat pada [manifest batch](traveler-android-audit/passport-spots-20261107/results.json). Screenshot yang dipublikasikan menggunakan akun/domain sintetis. Capture data akun lokal tidak dimasukkan ke repositori.

| Pemeriksaan | Status |
| --- | --- |
| Build dan lint DevelopmentDebug Traveler + Partner | PASS; lint 0 error/fatal, Traveler 45 warning dan Partner 2 warning. |
| Host Traveler | 477 test PASS, 0 failure/error/skipped. |
| Instrumentation emulator 5580 | 94 test PASS dalam 189,913 detik, 0 failure. Termasuk 12 Passport/QR, 3 tab Profil publik, 14 Figma/Spot/navbar serta regresi journey/lokasi/publikasi/font/autentikasi/Quest/akun/dokumen. |
| iOS framework, host dan test | NOT_RUN, Windows. Perlu Mac/Xcode atau CI Mac untuk perubahan shared/core dan dependensi QR. |

Native fixture memakai viewport 400×900 dp, density 432, dengan skenario font 100/130/200%. Resolusi asli 1080×2400/density 420/font 100% dipulihkan sesudah test. Paket development `com.floopel.traveler.dev`; sesi fixture lokal dipertahankan.

Sebanyak 27 PNG sintetis disertakan. Fingerprint source/APK diperiksa sebelum dan sesudah runner; tidak berubah. Resource ID/EN memiliki 735 key string per bahasa; kesamaan key, placeholder format dan ketiadaan karakter pengganti diperiksa. Dua iterasi awal pemeriksaan geometri Spot membetulkan selector test di dalam Surface/LazyRow; satu ekspektasi judul toolbar Quest yang masih mengikuti varian Spot lama juga diperbarui. Hasil akhir di atas berasal dari satu runner penuh setelah koreksi tersebut.

Build lokal memasukkan perubahan `apps/android/traveler/src/main/res/values/styles.xml` yang sudah ada sebelum batch ini. Hash input itu dicatat pada manifest; file tersebut dan edit `.gitignore` milik pemilik tidak dimasukkan ke commit.

MainActivity memulihkan sesi fixture lokal pada APK akhir. Health lokal/tunnel serta GET profil, progress Passport, Spot, Saved Spot, badge dan itinerary publik menghasilkan 200. Passport awal level 1/XP 0 menampilkan QR kecil dan besar. Semua Spot serta Detail Jalan Braga memuat gambar API. Pergantian Saved Spot → Badge → Itinerary mempertahankan koordinat header dan ketiga tab; tidak ada GET ulang identitas/progress header selama pergantian tersebut. Emulator 5580 ditinggalkan pada Profil publik/Itinerary. Jeda lima detik dipakai per flow; ringkasan request pada manifest meredaksi identitas/ID.

Smoke batch ini dibatasi pada pembacaan/navigasi; tidak mengirim mutasi komentar, save, admission Quest atau perubahan akun nyata. Peta, delivery FCM, signing/provider production, perangkat fisik dan sign-off seluruh aplikasi tetap di luar hasil verifikasi ini.
