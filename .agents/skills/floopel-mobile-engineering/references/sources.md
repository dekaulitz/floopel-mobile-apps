# Referensi dan alasan keputusan

Sumber primer diperiksa pada **6 November 2026**. Isi skill dan aturan merupakan sintesis untuk kebutuhan Floopel, bukan salinan standar vendor maupun bukti sertifikasi production. Ketika mengubah versi/API, periksa ulang halaman resmi untuk versi yang digunakan; tanggal pemeriksaan bukan jaminan kompatibilitas selamanya.

## Sumber resmi

| ID | Referensi | Dasar yang digunakan |
| --- | --- | --- |
| S01 | [OpenAI: Build skills](https://learn.chatgpt.com/docs/build-skills) | Manifest `SKILL.md`, pemuatan referensi saat diperlukan, penemuan repo melalui `.agents/skills`, dan metadata opsional. |
| S02 | [OpenAI: AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md) | Instruksi repositori ringkas dan berlapis; file rules biasa tidak otomatis menjadi instruksi startup tanpa rujukan. |
| S03 | [Android: Architecture recommendations](https://developer.android.com/topic/architecture/recommendations) | Pemisahan UI/data, repository, state/UDF, serta penggunaan domain/use case sesuai kompleksitas. |
| S04 | [Android: Common modularization patterns](https://developer.android.com/topic/modularization/patterns) | Cohesion/coupling, peran app/feature/core, dan batas implementasi. Strategi modul perlu mengikuti kebutuhan proyek. |
| S05 | [Android: Compose UI Architecture](https://developer.android.com/develop/ui/compose/architecture) | State turun dan aksi naik, state hoisting, serta pemisahan UI dari pemilik state. |
| S06 | [Kotlin: Source set hierarchy](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html) | Ketersediaan API mengikuti target; gunakan hierarchy default dan tempatkan adapter pada source set yang sesuai. |
| S07 | [Android: Android-KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin) | Plugin library KMP, aplikasi Android terpisah, serta konfigurasi test host/device yang perlu diaktifkan. |
| S08 | [Kotlin: Compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) | Validasi kombinasi Kotlin, Gradle, AGP, dan Xcode saat toolchain berubah. |
| S09 | [Kotlin: Compose compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) | Siklus Compose terpisah dan Compose Compiler harus sesuai versi Kotlin. |
| S10 | [Kotlin: Multiplatform ViewModel](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html) | Dukungan state holder/ViewModel bersama tanpa memaksakan API Android-only pada common code. |
| S11 | [Kotlin: Lifecycle](https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html) | LifecycleOwner bersama dan pemetaan lifecycle ke platform native. |
| S12 | [Android: Coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) | Main safety, pengujian dispatcher/scope, encapsulation state, dan preservation cancellation. |
| S13 | [Android: Build an offline-first app](https://developer.android.com/topic/architecture/data-layer/offline-first) | Repository mengoordinasikan sumber lokal/remote ketika fitur memerlukan offline, termasuk masalah sinkronisasi. |
| S14 | [Android: Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility) | Semantics, label, traversal, serta pemeriksaan aksesibilitas Android. |
| S15 | [Kotlin: Compose accessibility](https://kotlinlang.org/docs/multiplatform/compose-accessibility.html) | Makna, role, state, dan semantics pada UI multiplatform. |
| S16 | [OWASP MASVS](https://mas.owasp.org/MASVS/) | Kategori pemeriksaan keamanan/privasi mobile; kontrol perlu diuji melalui assessment yang relevan. |
| S17 | [Gradle: API and implementation separation](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation) | Batas kontrak dependency dan pengurangan kebocoran implementation ke classpath konsumen. |
| S18 | [Gradle: Structuring builds](https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html) | Batas multi-project yang bermakna dan convention plugin untuk build logic yang digunakan ulang. |
| S19 | [Kotlin: Test your multiplatform app](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) | Test common dan test khusus platform membutuhkan runner/target yang benar. |
| S20 | [Android: Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices) | Hoisting perhitungan dan key/layout yang sesuai; optimasi mengikuti kebutuhan kerja UI. |
| S21 | [JetBrains: Compose Multiplatform benchmarks](https://github.com/JetBrains/compose-multiplatform/blob/master/benchmarks/multiplatform/README.md) | Pengukuran pada target native dan konfigurasi Release untuk benchmark iOS. |
| S22 | [Kotlin: Direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html) | Framework dibangun/dihubungkan melalui build phase Xcode dengan environment yang sesuai. |

## Keputusan Floopel dan tradeoff

| Keputusan lokal | Kenapa dipilih | Biaya / batas | Dasar |
| --- | --- | --- | --- |
| Satu skill engineering dengan referensi terpisah | Workflow konsisten tanpa selalu memuat seluruh detail standar | Referensi harus tetap sesuai implementasi | S01–S02 |
| Skill dan rules disimpan di repo | Perubahan dapat ditinjau/version-control dan dibagikan ke tim | Instruksi perlu dijaga ringkas; belum merupakan enforcement CI | S01–S02 |
| Android Traveler/Partner terpisah, Traveler berbagi dengan iOS | Mengikuti tiga distribusi yang diminta dan mengurangi UI Traveler ganda | Setiap platform tetap membutuhkan bukti native sendiri | S04, S06–S07, S19 |
| Lapisan jelas, MVVM/UDF untuk stateful UI | Ownership state dan boundary data lebih mudah ditelusuri/diuji | Tidak semua screen memerlukan ViewModel atau use case | S03, S05, S10–S11 |
| Modul tumbuh mengikuti kemampuan bisnis | Isolasi perubahan tanpa ledakan modul kosong | Pemisahan terlalu lambat juga perlu ditinjau saat dependency/ownership membesar | S04, S17–S18 |
| Offline per fitur; transaksi dikonfirmasi server | Katalog dapat tahan gangguan jaringan tanpa menjanjikan transaksi offline yang belum didukung | Freshness, konflik, idempotency, dan rekonsiliasi perlu kontrak produk/backend | S13, S16 + keputusan integritas Floopel |
| Design system bersama dengan branding per produk | Perubahan komponen lebih konsisten dan dapat digunakan ulang | Semantics, font/insets, dan perilaku native tetap diuji per platform | S14–S15, S20 |
| Verifikasi menurut dampak dan status eksplisit | Menghindari rebuild berlebihan serta klaim test yang tidak pernah berjalan | Infrastruktur CI/Mac tetap perlu disediakan untuk gate terkait | S07, S19, S21–S22 |

Penggunaan kata **wajib**, aturan ID R1–R10/A1–A8, penamaan modul Floopel, kriteria ADR, representasi uang/waktu, kebijakan retry/transaksi, dan gate rilis adalah keputusan proyek. Sumber vendor mendukung prinsipnya; sumber tersebut tidak menetapkan seluruh detail Floopel secara literal. Tidak ada angka coverage, performa, atau jumlah modul universal yang diasumsikan sebagai standar production.

Perbarui catatan ini ketika landasan keputusan berubah. Untuk perubahan toolchain, sertakan versi yang dipilih dan bukti build lintas platform dalam perubahan terkait; jangan memperbarui aplikasi hanya agar mengikuti nomor versi terbaru pada halaman sumber.
