# Verifikasi sesuai dampak perubahan

Tujuan pemeriksaan adalah membuktikan perilaku dan kompatibilitas yang berubah. Pilih baris yang relevan; perubahan kecil tidak otomatis memerlukan seluruh pemeriksaan rilis. Gunakan daftar task repo saat ini, bukan nama test yang diasumsikan dari template proyek lain.

## V1. Matriks dampak

| Perubahan | Bukti yang diperlukan |
| --- | --- |
| Prose, rules, skill | Isi konsisten, path/tautan lokal valid, YAML/frontmatter skill valid; tidak perlu rebuild aplikasi |
| UI/layout Partner saja | Build/lint Partner; review atau UI test sesuai risiko pada ukuran/theme/font yang terpengaruh |
| UI/state/data Traveler bersama | Build/lint Traveler Android; kompilasi framework/host iOS di Mac; test perilaku dan pemeriksaan UI pada platform yang terpengaruh |
| `core/model` atau `core/designsystem` | Kedua aplikasi Android dan konsumen iOS Traveler; test/unit/UI yang terkait kontrak berubah |
| Adapter Android atau native iOS | Pemeriksaan pada platform adapter; test izin/lifecycle/error yang relevan; konsumen bersama jika kontraknya berubah |
| Repository, aturan bisnis, coroutine | Test deterministik sukses/error/cancellation/state lama atau concurrency yang terkait; integrasi bila boundary berubah |
| Sesi, booking, pembayaran | Test akses/sesi, hasil tak diketahui, submit ganda, retry, dan rekonsiliasi sesuai kontrak; backend nyata bukan diasumsikan dari fake |
| Database/schema atau protokol | Test kompatibilitas, migrasi data lama, kegagalan migrasi, dan pemulihan |
| Dependency/toolchain/graf modul | Pemeriksaan kompatibilitas resmi, konfigurasi Gradle, build/lint/test seluruh konsumen terdampak termasuk iOS di Mac |
| Kandidat rilis | Bukti di atas yang relevan, build release, signing/environment, alur penting di perangkat, privacy/permission, serta mitigasi rilis |

Perubahan visual ringan dapat memakai review/render. Perubahan perilaku membutuhkan test yang dapat membedakan implementasi benar dari regresi. Jangan menganggap test yang tidak memiliki source sebagai bukti perilaku.

## V2. Android dan inventaris task

Dari root repo menggunakan PowerShell:

```powershell
.\gradlew.bat projects
.\gradlew.bat :apps:android:traveler:assembleDebug :apps:android:partner:assembleDebug
.\gradlew.bat :apps:android:traveler:lintDebug :apps:android:partner:lintDebug
```

Untuk fitur satu produk, batasi perintah ke produk tersebut bila tidak ada dependency bersama yang berubah. Untuk menemukan task test dan compile yang tersedia:

```powershell
.\gradlew.bat :shared:features:traveler:tasks --all
.\gradlew.bat :shared:core:model:tasks --all
.\gradlew.bat :apps:android:partner:tasks --all
```

`testDebugUnitTest` tersedia pada modul aplikasi Android, tetapi belum membuktikan perilaku jika belum ada test. Library Android-KMP memakai konfigurasi host/device test yang berbeda dan test tersebut tidak diaktifkan secara default oleh pluginnya. Ketika menambahkan logika yang perlu test, konfigurasikan source set, dependency runner, dan task yang sesuai; jangan mengarang task `jvmTest` untuk modul tanpa target JVM. Lihat [plugin Android-KMP](https://developer.android.com/kotlin/multiplatform/plugin) dan [pengujian KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html).

Jika kendala temporary Java Windows muncul sebelum konfigurasi proyek, gunakan penyesuaian sesi yang sudah diverifikasi dalam [README proyek](../../../../README.md#kendala-direktori-temporary-java-di-windows). Kendala mesin dicatat terpisah dari kegagalan source; jangan menanam path pengguna di file build yang dibagikan.

## V3. Traveler iOS: wajib Mac untuk bukti native

Pastikan JDK, Xcode, target, dan simulator cocok dengan katalog serta matriks kompatibilitas. Pada Mac, contoh kompilasi framework:

```sh
sh ./gradlew :shared:features:traveler:linkDebugFrameworkIosSimulatorArm64
```

Kompilasi framework saja belum membuktikan host Swift/konfigurasi aplikasi benar. Contoh build host simulator dari root repo:

```sh
xcodebuild -project apps/ios/traveler/FloopelTraveler.xcodeproj \
  -scheme FloopelTraveler -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Build phase Xcode menjalankan `embedAndSignAppleFrameworkForXcode` dengan environment Xcode. Jangan menjalankan task itu sebagai pengganti kompilasi framework biasa tanpa environment yang dibutuhkannya. Dasar: [integrasi langsung Kotlin/Xcode](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html).

Ketika test KMP sudah dikonfigurasi dan ada source, jalankan `:shared:features:traveler:iosSimulatorArm64Test` dan task test modul KMP lain yang terdampak pada Mac/simulator yang mendukung. Android yang lolos, analisis metadata, keberadaan task native, atau `allTests` di Windows dengan target dilewati tidak menggantikan test native.

Untuk perubahan distribusi, interop, signing, atau performa yang relevan, tambahkan build release/perangkat fisik. Catat perangkat dan konfigurasi pengukuran; [benchmark resmi Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/blob/master/benchmarks/multiplatform/README.md) menggunakan konfigurasi Release untuk iOS.

## V4. Bukti dan pelaporan

| Status | Arti |
| --- | --- |
| `PASS` | Pemeriksaan yang dinyatakan benar-benar dijalankan dan memenuhi kriteria |
| `FAIL` | Pemeriksaan dijalankan dan menunjukkan masalah |
| `NOT_RUN` | Pemeriksaan relevan belum dijalankan; sebutkan alasan dan apa yang diperlukan |
| `NOT_APPLICABLE` | Pemeriksaan tidak sesuai dampak perubahan; jelaskan jika tidak jelas |

`SKIPPED` dan `NO-SOURCE` dari Gradle dipertahankan sebagai keterangan eksekusi, bukan diubah menjadi test `PASS`. Pisahkan build, lint, test perilaku, pemeriksaan visual, dan audit keamanan. Laporkan warning lint yang material; jangan menyebut hasil bebas masalah hanya karena exit code nol.

Bukti minimal menyertakan perintah/skenario, modul/platform, hasil, dan batas yang relevan. Untuk performa, sertakan baseline dan pengukuran sesudah perubahan. Untuk security/migrasi, nyatakan kontrol serta kasus yang diuji. Jangan menuliskan test coverage, audit, pengujian perangkat, atau pipeline CI sebagai berhasil jika tidak dijalankan.

## V5. Gate rilis dan kondisi scaffold

Gate rilis yang relevan harus memiliki bukti lulus atau keputusan risiko dari proses tim yang berlaku. Ketiadaan Mac di mesin pengembangan ditangani melalui runner Mac atau pemeriksaan Mac berikutnya; pekerjaan yang bisa dilakukan tetap dilanjutkan, sementara gate iOS dicatat belum selesai. Penerbitan store/deployment mengikuti permintaan dan otorisasi tersendiri.

Repo saat standar ini dibuat berisi scaffold, konfigurasi build, dan layar sambutan. Belum ada suite test bisnis, integrasi backend/sesi/transaksi, pengujian performa, atau pipeline CI yang membuktikan seluruh gate di atas. Tulis kontrol yang ditambahkan pada perubahan nyata; jangan menyimpulkan production readiness dari struktur folder atau dokumen ini.
