# Traveler Android — Settings, penghapusan akun, dokumen dan Spot

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts`.

Batch ini memperbaiki temuan pemilik pada toolbar Profile/Profil publik/itinerary, Settings, penghapusan akun, Detail Spot dan padding Open Trip pada Detail lokasi. Figma tetap menjadi acuan visual. Data, eligibility, versi dokumen, status dan tanggal berasal dari repository/API; contoh Figma tidak menjadi fallback runtime.

## Acuan dan keputusan pemilik

| Bagian | Frame Android / implementasi |
| --- | --- |
| Toolbar Profile, Profil publik dan itinerary | 5:368, 22:522, 113:1618. Pemilik secara eksplisit meminta ketiganya tetap di atas dan sejajar, termasuk saat scroll dan Back. `FloopelTopBar` bersama memiliki satu pemilik insets di root. Hierarki dan ukuran judul tiap halaman dipertahankan. |
| Settings dan bahasa | 116:2506, 116:3269. Dialog radio memakai komponen design system; pilihan diterapkan langsung dan disimpan oleh host Android. |
| Penghapusan akun | 319:5536/6076 verifikasi request/cancel; 132:2209/2297 review; 132:2121/2649 blocker; 132:2385 deadline; 319:5965 pending tanpa deadline; 132:2561 berjalan; 319:5665–6908 feedback/progres dan pembatalan. |
| Persetujuan dokumen | 136:2187/2451 daftar/feedback; 268:3920 versi berubah; reader API 266:4105/4858. Pemilik mengonfirmasi bahwa Settings tetap menampilkan dokumen aktif untuk dibaca ketika tidak ada pending, tanpa meminta persetujuan ulang. |
| Detail Spot | Section 308:4703, screen 308:4706/7391, galeri terisi 308:5931/6018, state tersimpan 328:8971. Galeri terisi 200 dp; kosong/loading/gagal 140 dp; thumbnail 64 dp; gutter 20 dp. |
| Detail lokasi — Open Trip | 53:945/1149. Gutter luar 16 dp dipakai satu kali; image 76×80 dp, gap 12 dp; judul panjang maksimal dua baris dan CTA tetap terbaca. |

## Perilaku dan batas state

- Menu bahasa dan penghapusan sekarang membuka alur nyata. Menu profil pribadi, minat, kontak darurat, keamanan, notifikasi/privasi dan dokumen tetap melalui kontrak domain. Editor foto masih dinonaktifkan; tidak dilabeli selesai hanya karena row lain dapat dibuka.
- Android menggunakan locale aplikasi `id`/`en`, default Indonesia pada instalasi tanpa pilihan sebelumnya. Resource Traveler memiliki 692 string per bahasa dan satu array bulan; resource core memiliki satu string per bahasa. Key, placeholder format dan karakter pengganti diperiksa. Pergantian locale menghapus cache pembacaan bahasa lama. Pemilihan locale native iOS belum dihubungkan.
- Penghapusan memakai `ErasureViewModel` terpisah dari navigasi normal. Verifikasi baru membaca eligibility; tidak mengirim hapus. Blocker Trip/profil, checkbox review, deadline yang benar-benar tersedia, pending tanpa deadline, status berjalan, pembatalan dan feedback ditampilkan sesuai state server. Proof terbatas tidak menjadi sesi normal. Request yang diterima atau dikonfirmasi melalui GET membuang identitas normal; pembatalan kembali ke login.
- Request/cancel tidak diulang otomatis. Hasil tak diketahui direkonsiliasi melalui GET; retry eksplisit mempertahankan key dalam sesi alur yang sama. Late callback setelah penutupan/pergantian actor tidak mengaktifkan proof. Penolakan password verifikasi tidak otomatis membuang Bearer yang valid: probe GET normal tetap memakai kebijakan pemilik sesi. Probe 429/503/network mempertahankan sesi dan throttle; penolakan identitas yang definitif tetap menghapusnya.
- Halaman dokumen memuat pending exact version dari server. Reader memakai objek `contentHtml/version/locale/hash` yang sama dengan payload consent. Settings tanpa pending membaca dokumen aktif melalui GET saja; checkbox dan submit tidak tersedia. Sebelum submit dan sesudahnya pending diperiksa ulang. Versi berubah mereset acknowledgement; respons hilang memerlukan pemeriksaan status sebelum retry. Tidak ada consent otomatis ketika membuka reader.
- Detail Spot memakai lokasi/plain rating, tombol Simpan/Bagikan setara, label Tersimpan, jumlah komentar dari API dan aksi Tambah ke rencana. Galeri data tetap dinamis. Menu laporan memakai repository moderation existing; katalog belum memiliki receipt reader untuk rekonsiliasi laporan, sehingga outcome tak diketahui menahan submit ulang selama draft actor tersebut masih berada dalam memori. Tidak ada klaim recovery laporan setelah process death.
- Peta tetap ditunda sesuai instruksi pemilik. Flow native memisahkan CTA Tambah ke rencana dan About yang bertumpuk pada positioning prototype supaya keduanya tetap bisa diakses. Tidak ada SDK atau ilustrasi peta pengganti baru.

## Verifikasi

Hasil akhir, daftar suite, fingerprint source/APK, log dan screenshot sintetis tersedia pada [manifest batch](traveler-android-audit/settings-spot-20261107/results.json). Checkpoint sebelumnya tidak diganti atau dihitung sebagai rerun batch ini.

| Pemeriksaan | Hasil |
| --- | --- |
| Gradle Wrapper: assembleDevelopmentDebug Traveler + Partner, APK androidTest Traveler | PASS |
| lintDevelopmentDebug kedua aplikasi | PASS, 0 error/fatal; Traveler 45 warning, Partner 2 warning. Toolchain tidak di-upgrade. |
| testAndroidHostTest Traveler | 476 test PASS, 0 failure/error/skipped. Termasuk 7 test transport erasure, 7 state erasure dan 5 state dokumen; test probe mencakup 200/429/503/network serta identitas definitif invalid. |
| Runner instrumentation emulator 5580 | 78 test PASS dalam 126,315 detik, termasuk 16 test akun dan 3 test dokumen serta regresi layout/journey/Quest/lokasi/publikasi/font/autentikasi. Production Compose/ViewModel dengan domain doubles; tidak mengirim mutasi akun nyata. |
| iOS framework/host/test | NOT_RUN, Windows. Perlu Mac/Xcode atau CI Mac untuk shared UI/core dan kontrak runtime yang berubah. |

Viewport native 400×900 dp (1080×2430, density 432), font sistem 100%; skenario font tambahan memakai Compose density tersendiri. Source dan APK dibekukan selama runner dan diperiksa ulang. Resolusi asli emulator 1080×2400/density 420/font 100% dipulihkan sesudah test. Paket yang dipasang `com.floopel.traveler.dev`.

Build memasukkan edit lokal `apps/android/traveler/src/main/res/values/styles.xml` yang sudah ada sebelum batch ini. Fingerprint-nya dicatat sebagai input eksternal; file itu dan edit `.gitignore` milik pemilik tidak dimasukkan ke commit. Bukti APK lokal harus dibaca bersama konteks ini.

## Smoke backend nyata

Database/backend lokal dan tunnel ngrok kembali aktif setelah Docker direstart pemilik. Health lokal/tunnel serta pembacaan Quest memberi 200. MainActivity memulihkan sesi akun fixture organizer, membuka Home dan Detail Spot dengan gambar API. Menu Settings diperiksa dengan jeda lima detik per langkah. Verifikasi penghapusan dengan akun fixture menghasilkan blocker Trip aktif melalui preflight server; tidak ada request hapus atau cancellation yang dikirim. Pilihan English tetap aktif setelah force-stop/reopen, kemudian dikembalikan ke Bahasa Indonesia. Pembacaan dokumen aktif pada APK akhir dicatat terpisah dalam manifest.

Pengujian mutasi hapus/cancel/consent pada batch ini memakai transport/domain sintetis. Tidak ada penghapusan akun, persetujuan dokumen, perubahan sandi atau pengiriman laporan nyata. Peta, FCM/provider delivery, production signing/provider, TalkBack/VoiceOver dan perangkat fisik tetap belum diverifikasi. Android PASS tidak menyatakan parity iOS atau sign-off seluruh aplikasi.

## Aset dan resource

[Manifest aset](traveler-android-audit/settings-spot-20261107/assets.json) mencatat sepuluh SVG lokal, hash, root geometry, frame sumber, slot dan callsite. Ikon Shield/ChevronRight/ArrowBack serta checkbox legal memakai komponen yang dipetakan Code Connect; status bar/gesture dimiliki Android. Screenshot Figma tidak dipakai sebagai aset implementasi. PNG audit dihasilkan dari UI fixture sintetis, dengan nama/hashes terpisah dari screenshot smoke akun lokal yang tidak dipublikasikan.

Locale menggunakan [panduan Android per-app languages](https://developer.android.com/guide/topics/resources/app-languages) dan [Compose Multiplatform resources](https://kotlinlang.org/docs/multiplatform/compose-localize-strings.html). Penyimpanan pilihan dan resource mengikuti pemilik platform; kontrak repository menentukan bahasa konten yang diminta dan locale yang benar-benar dilayani.
