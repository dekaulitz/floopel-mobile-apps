---
name: floopel-mobile-engineering
description: "Implement, refactor, or review Floopel Kotlin Multiplatform and Compose features, module boundaries, and Android/iOS build changes using the repository's engineering standards."
---

# Floopel Mobile Engineering

Kerjakan perubahan Floopel yang dapat ditinjau dan diverifikasi, dengan batas Android Traveler, Android Partner, dan iOS Traveler tetap jelas. Skill ini menetapkan cara kerja; status siap rilis harus didukung bukti untuk perubahan yang sedang dikerjakan.

## Orientasi dan pemilihan referensi

Temukan root repositori dari direktori kerja, lalu baca `AGENTS.md`, file Gradle modul terkait, dan `gradle/libs.versions.toml`. Cocokkan implementasi serta test yang tersedia sebelum mengasumsikan library, backend, atau task tertentu sudah ada. Path proyek tidak boleh di-hardcode ke komputer pembuat skill.

- Untuk perubahan source, baca aturan terkait di [engineering-rules.md](references/engineering-rules.md).
- Untuk struktur modul, state, data, native adapter, atau desain UI, baca bagian terkait di [architecture.md](references/architecture.md).
- Pilih pemeriksaan melalui [verification.md](references/verification.md); baca matriks terlebih dahulu, lalu hanya perintah yang diperlukan.
- Saat menambah/mengubah library, API platform, atau menjelaskan alasan keputusan, gunakan [sources.md](references/sources.md) sebagai titik awal dan periksa dokumentasi resmi untuk versi yang benar-benar digunakan.

## Invarian Floopel

- Traveler berbagi UI/logika Android dan iOS melalui `:shared:features:traveler`; framework native bernama `TravelerKit`.
- Partner tetap Android-only. `AppProduct` membedakan produk/tema, bukan bukti hak akses pengguna.
- `core` tidak mengetahui fitur/aplikasi. `commonMain` tidak menggunakan API Android, Java/JVM-only, atau UIKit secara langsung.
- Domain tidak bergantung pada UI/transport/persistence. Pilih abstraksi dan pemisahan modul berdasarkan kebutuhan nyata, bukan jumlah folder.

## Alur kerja

1. Tentukan produk, platform, perilaku yang diminta, serta modul yang terdampak. Untuk bug, cari pemicu dan perilaku yang salah. Lanjutkan dengan asumsi yang aman; minta informasi hanya jika kontrak produk/data yang belum tersedia menentukan hasil.
2. Tentukan pemilik state dan data. Untuk pekerjaan yang mengubah arsitektur, jelaskan singkat arah dependensi, kontrak publik, sumber data utama, serta konsekuensi platform. Catat ADR bila memenuhi kriteria di referensi arsitektur.
3. Implementasikan satu perubahan yang utuh dalam cakupan tugas. Ikuti pola yang sudah sesuai di repo; jangan menambahkan DI/navigation/database baru atau memindahkan semua modul untuk mengerjakan perubahan lokal.
4. Untuk alur asynchronous, tangani kondisi yang relevan: loading, konten/empty, kegagalan, pembatalan, retry, dan state lama. Untuk operasi penting, tetapkan perilaku saat respons hilang atau permintaan berulang; jangan menyamakan timeout dengan transaksi pasti gagal.
5. Jalankan pemeriksaan sesuai dampak. Common code Traveler memerlukan bukti Android dan iOS; jika Mac tidak tersedia, selesaikan pemeriksaan yang tersedia dan tandai verifikasi iOS `NOT_RUN` beserta alasannya. Jangan melaporkan skipped atau `NO-SOURCE` sebagai test lulus.
6. Tinjau kontrak publik, dependensi, data sensitif, serta dokumentasi yang perlu diperbarui. Laporkan hasil dan batasnya tanpa menyebut aplikasi siap production hanya karena build berhasil.

## Kalibrasi cakupan

| Permintaan | Penerapan |
| --- | --- |
| Tambah pencarian destinasi Traveler | Tetapkan kontrak data, state pencarian dan cancellation; gunakan adapter platform jika diperlukan; verifikasi Android/iOS. |
| Ubah komponen tombol bersama | Gunakan token dan semantics; periksa konsumen Traveler/Partner dan platform yang terpengaruh; tidak perlu use case/repository baru. |
| Upgrade Kotlin/Compose/AGP | Periksa matriks versi resmi, plugin KMP, Gradle, serta Xcode; bangun seluruh konsumen yang terdampak. |
| Perbaiki typo dokumentasi | Periksa isi dan tautan; tidak perlu menambah test runtime atau menjalankan build aplikasi. |

## Hasil kerja

Jelaskan perubahan dan alasan desainnya, modul/platform terdampak, hasil verifikasi yang benar-benar dijalankan, serta risiko atau gate rilis yang belum selesai. Untuk review, prioritaskan masalah perilaku dan batas arsitektur dengan lokasi file; jangan mengklaim kepatuhan keamanan atau performa tanpa pemeriksaan yang mendukungnya.
