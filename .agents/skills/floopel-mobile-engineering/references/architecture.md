# Desain arsitektur Floopel Mobile

Ditinjau: 7 November 2026. Standar pengembangan yang diadopsi untuk repo ini. Android Traveler kini memakai runtime/session dan UI commonMain; Partner dan host iOS masih scaffold. Status aktual serta bukti platform dicatat pada [handoff Android](../../../../docs/handoff/traveler-android.md). Bagian evolusi tetap merupakan arah pengembangan, bukan bukti modul atau integrasi tersedia.

## A1. Batas produk dan komposisi

| Produk/platform | Host | Root UI / komposisi |
| --- | --- | --- |
| Android Traveler | `apps/android/traveler` | `shared/features/traveler`, `TravelerApp` |
| Android Partner | `apps/android/partner` | `shared/features/partner`, `PartnerApp` |
| iOS Traveler | `apps/ios/traveler` | `TravelerKit` → `MainViewController` → `TravelerApp` |

Host menangani bootstrap native, izin, callback platform, dan wiring layanan platform. Root produk menyusun tema, navigasi, dan entry point fitur. `:shared:features:traveler` saat ini juga berfungsi sebagai root produk; dependensi ke entry point fitur baru diperbolehkan dari root ini.

Partner tidak menjadi dependency root Traveler atau transitif framework iOS. Model bersama seperti `AppProduct` boleh mengenal nama produk; keberadaannya bukan pemisahan sesi maupun pemeriksaan otorisasi.

**Alasan:** satu UI Traveler mengurangi implementasi ganda, sedangkan root produk terpisah menjaga distribusi, identitas aplikasi, dan dependensi tetap terkontrol. Ini adaptasi Floopel dari [pola modularisasi Android](https://developer.android.com/topic/modularization/patterns) dan [struktur source set KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html).

## A2. Arah dependensi

```text
Host Android Traveler ──> Root Traveler ──> fitur Traveler / kontrak bersama
Host iOS Traveler     ──> TravelerKit   ──> Root Traveler yang sama
Host Android Partner  ──> Root Partner  ──> fitur Partner / kontrak bersama

Presentation ──> domain / kontrak repository
Data         ──> domain / kontrak repository
Presentation ──> core:designsystem ──> core:model
```

Panah menunjukkan dependensi kode, bukan urutan request. Wiring memilih implementasi `data` dan memasukkannya melalui constructor/factory ke pemilik kontrak. Tidak ada dependensi balik dari `domain` ke `data` atau dari `core` ke fitur/aplikasi. Graf Gradle wajib tanpa siklus.

Fitur mandiri tidak mengimpor implementasi internal fitur lain. Koordinasi dilakukan root produk melalui callback, route, atau kontrak kecil yang memang dibutuhkan. Pisahkan modul `api` hanya ketika kontrak tersebut benar-benar dikonsumsi lintas modul; jangan mengubah seluruh implementasi menjadi publik untuk mengatasi dependensi.

Gunakan `internal`/`private` untuk adapter, DTO, data source, dan helper implementasi. `implementation` adalah default Gradle; `api` sesuai ketika tipe dependency muncul di kontrak publik. Prinsipnya merujuk [pemisahan API/implementation Gradle](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation); jangan menerapkan plugin `java-library` ke modul KMP hanya untuk mengikuti contoh dokumentasinya.

## A3. Tanggung jawab lapisan

| Area | Memiliki | Tidak memiliki |
| --- | --- | --- |
| `presentation` | Screen, state UI, state holder/ViewModel, handler aksi, route | API client, SQL, token storage, keputusan harga/transaksi final |
| `domain` | Model bisnis, kontrak repository yang dibutuhkan, kebijakan/use case | Compose, Context/Activity, UIKit, DTO transport, entity database |
| `data` | Implementasi repository, mapper, sumber remote/lokal, sinkronisasi | Navigasi layar, styling, penyimpanan state komposisi |
| `core/model` | Tipe stabil lintas kebutuhan dengan makna yang benar-benar sama | Kumpulan semua model fitur, DTO backend, tipe UI seluruh aplikasi |
| `core/designsystem` | Token semantik, tema, komponen visual reusable | Repository, ViewModel, navigasi produk, aturan booking |

Gunakan **lapisan yang jelas + MVVM/UDF untuk UI yang memiliki state**. Screen statis tidak wajib memiliki ViewModel. Use case bermanfaat untuk kebijakan bisnis, orkestrasi beberapa repository, atau logika yang digunakan ulang; satu metode repository tidak otomatis memerlukan wrapper use case.

Di scaffold, `data/domain/presentation` adalah batas paket dalam satu modul. Compiler belum memaksa semua aturan antarpaket. Pemisahan menjadi modul domain/data/presentation dilakukan ketika isolasi dependency, kepemilikan tim, reuse, atau kebutuhan build membenarkannya. Ini pilihan lokal; [rekomendasi Android](https://developer.android.com/topic/architecture/recommendations) sendiri memperlakukan domain/use case sebagai kebutuhan bersyarat.

## A4. Source set dan integrasi native

- `commonMain`: kode dan dependency yang tersedia untuk semua target modul. Tipe Android/JVM-only dan UIKit tidak masuk ke kontraknya.
- `androidMain`: adapter Android pada modul KMP. Modul Partner biasa tetap memakai `src/main` dan tidak perlu target iOS.
- `iosMain`: adapter iOS serta bridge `UIViewController`. Gunakan hierarchy default; perubahan `dependsOn` manual harus memiliki kebutuhan yang terjelaskan.
- Preferensikan interface + constructor/factory untuk layanan platform yang perlu diganti saat test. Gunakan `expect/actual` ketika memang memerlukan deklarasi lintas platform; jangan membuat percabangan platform tersebar di domain.
- Root Traveler menghasilkan satu framework komposisi. Ekspor Swift/Objective-C hanya API yang diperlukan host; jangan mengekspor seluruh graf dependency secara transitif.

Gunakan `com.android.kotlin.multiplatform.library` untuk library KMP dan modul aplikasi Android terpisah. Cocokkan Kotlin, Compose Compiler, Compose, AGP, Gradle, dan Xcode dengan [plugin Android-KMP](https://developer.android.com/kotlin/multiplatform/plugin) serta [matriks kompatibilitas](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html). Versi pada katalog repo adalah sumber konfigurasi, bukan versi contoh dari artikel terbaru.

## A5. State, lifecycle, dan navigasi

UI menerima state dan mengirim aksi ke pemilik state. Pisahkan route yang mengikat dependency/lifecycle dari screen yang menerima data serta callback. Expose `StateFlow<UiState>` read-only dari ViewModel/state holder; mutation tetap di pemiliknya. Terapkan collection yang mengikuti lifecycle dengan library multiplatform yang kompatibel.

Modelkan keadaan yang benar-benar mungkin. Contohnya, refresh gagal saat konten lama tersedia tidak harus menghapus konten tersebut. Hindari boolean terpisah yang dapat menghasilkan kombinasi mustahil. Coroutine, subscription, dan resource native mengikuti masa hidup screen, sesi, atau aplikasi yang dinyatakan secara eksplisit.

Hasil penting seperti booking berhasil tidak boleh hanya dikirim melalui channel/event sekali lewat yang dapat hilang ketika UI tidak aktif. Pertahankan hasil sebagai state yang dapat dipulihkan dan, bila perlu, dikonfirmasi/diakui; backend tetap menjadi sumber status transaksi. Efek UI sementara boleh mengikuti callback/efek lifecycle ketika kehilangan efek tersebut dapat diterima.

Navigasi dimiliki root produk/flow. Kirim ID atau argumen kecil; jangan memasukkan DTO besar, token, atau state mutable ke route. Deep link dan pemulihan layar memvalidasi argumen, sesi, serta akses sebelum memuat data. Android back dan iOS back/gesture diuji sebagai perilaku platform, bukan diasumsikan sama.

Dasar: [arsitektur Compose](https://developer.android.com/develop/ui/compose/architecture), [ViewModel multiplatform](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html), dan [lifecycle multiplatform](https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html).

## A6. Data, konsistensi, dan kontrak

Tetapkan satu sumber data utama per jenis data, termasuk aturan freshness dan kegagalan. Repository menyembunyikan kebijakan sumber lokal/remote. Model transport, persistence, domain, dan UI boleh berbeda; mapper diperlukan ketika makna/formatnya berbeda, bukan sebagai ritual penamaan.

Offline adalah keputusan per kemampuan produk. Jika katalog harus dapat dibaca offline, penyimpanan lokal menjadi sumber pembacaan dan remote memperbaruinya melalui repository. Harga akhir, ketersediaan, otorisasi, dan status pembayaran/booking tetap dikonfirmasi server. Jangan menambahkan antrean tulis offline untuk transaksi tanpa kontrak idempotency, konflik, dan pemulihan yang jelas. Lihat [panduan offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first).

Gunakan ID stabil dan tipe sesuai makna. Untuk uang, tetapkan currency, representasi presisi, serta aturan rounding; jangan memakai floating point biner untuk perhitungan transaksi. Untuk waktu, bedakan instant dengan tanggal/jam lokal dan simpan konteks timezone ketika dibutuhkan. DTO yang nullable karena format jaringan tidak otomatis menjadi kontrak domain nullable di semua lapisan. Detail ini adalah keputusan desain Floopel yang harus disesuaikan kontrak backend.

## A7. Desain UI yang dapat berkembang

Warna, typography, spacing, shape, dan komponen bersama dimiliki `core:designsystem`. Gunakan nama token berdasarkan peran, misalnya warna aksi utama/error dan jarak antarseksi. Perbedaan branding Traveler/Partner dipasang melalui tema; fitur tidak menyebarkan nilai brand secara hardcoded. Warna scaffold saat ini belum menjadi hasil audit desain/aksesibilitas.

Screen production memperhitungkan loading, empty, content, error, serta status offline bila relevan. Teks yang terlihat pengguna memakai resource yang dapat dilokalkan; string demo saat ini tidak menjadi pola untuk fitur baru. Format angka, uang, tanggal, RTL, dan plural mengikuti locale yang ditentukan produk.

Layout menyesuaikan ruang jendela, safe area/insets, keyboard, orientasi, dan ukuran font. Komponen interaktif memiliki label, role, state, serta urutan fokus yang sesuai; ikon dekoratif tidak menambah pembacaan screen reader. Verifikasi TalkBack dan VoiceOver pada alur terdampak. Dasar: [aksesibilitas Compose](https://developer.android.com/develop/ui/compose/accessibility) dan [aksesibilitas multiplatform](https://kotlinlang.org/docs/multiplatform/compose-accessibility.html).

Pengukuran performa menggunakan build yang mewakili rilis dan perangkat yang dinyatakan. Lakukan I/O di luar main thread, gunakan lazy collection dan key stabil untuk daftar ketika diperlukan, dan optimalkan recomposition berdasarkan pengukuran. Jangan menambahkan `@Stable`/`@Immutable` untuk menutupi data mutable atau menetapkan angka SLO tanpa baseline. Rujukan optimasi: [praktik performa Compose](https://developer.android.com/develop/ui/compose/performance/bestpractices).

## A8. Evolusi dan catatan keputusan

Mulai dari modul yang ada. Ekstrak fitur berdasarkan kemampuan bisnis yang dapat berkembang sendiri, bukan satu modul per screen. Buat `core/network`, `core/database`, shared testing, convention plugin, atau composition root produk terpisah saat ada kebutuhan konkret; nama-nama tersebut belum merupakan modul yang tersedia.

Tuliskan ADR di `docs/adr/NNNN-nama-keputusan.md` ketika mengubah batas produk/modul utama, kontrak publik yang memengaruhi banyak konsumen, pilihan penyimpanan/sinkronisasi, framework DI/navigation, atau komitmen platform. Perubahan rutin yang mengikuti keputusan yang ada tidak membutuhkan ADR baru.

ADR memuat konteks, pilihan yang dipertimbangkan, keputusan dan alasan, konsekuensi/biaya, migrasi/rollback, serta cara memverifikasi. Pemilihan `NNNN` mengikuti nomor yang tersedia, bukan nilai tetap. Persetujuan tambahan hanya mengikuti proses tim atau otorisasi nyata yang berlaku; kewajiban mencatat ADR sendiri tidak menciptakan approval baru.
