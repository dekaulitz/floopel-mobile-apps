# Floopel Mobile: instruksi repositori

Gunakan skill [floopel-mobile-engineering](.agents/skills/floopel-mobile-engineering/SKILL.md) untuk implementasi, refactor, dan review Kotlin/Compose Floopel. Baca bagian referensi yang relevan dengan perubahan; koreksi dokumentasi sederhana tidak memerlukan pemeriksaan runtime.

## Batas produk

- Android Traveler: `:apps:android:traveler`, ID `com.floopel.traveler`.
- Android Partner: `:apps:android:partner`, ID `com.floopel.partner`.
- iOS Traveler: `apps/ios/traveler`, framework `TravelerKit` dari `:shared:features:traveler`.
- Partner khusus Android. Jangan memasukkan implementasi Partner ke dependensi framework Traveler.
- Entry point native menangani lifecycle, izin, dan integrasi platform. UI/logika Traveler yang dapat dibagi berada di `commonMain`.
- Traveler wajib login sebelum Home dan fitur aplikasi. Pulihkan sesi sebelum membuka konten; logout atau sesi tidak valid kembali ke login tanpa navigasi utama. Registrasi, verifikasi, dan pemulihan sandi tetap dapat dibuka dari login.
- Figma [Final Mobile Layouts](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile) adalah sumber kebenaran visual. Gunakan revisi terbaru yang relevan dan komponen yang dipetakan; ukuran, hierarki, warna, jarak, dan perilaku scroll mengikuti frame terkait. Konfirmasi konflik aturan bisnis; jangan membuat pengganti desain berdasarkan preferensi sendiri.

## Aturan inti

1. Pertahankan graf dependensi tanpa siklus. `core` tidak bergantung pada fitur atau `apps`; library tidak bergantung pada modul aplikasi.
2. UI mengakses data melalui kontrak repository atau use case yang diperlukan. API client, database, dan DTO tidak digunakan langsung oleh screen/ViewModel.
3. Business domain bebas dari Compose dan API platform. Jangan membuat use case atau modul tambahan hanya untuk membungkus satu pemanggilan tanpa manfaat.
4. Gunakan state UI yang tidak dapat dimutasi dari luar dan aliran data satu arah. State holder bertanggung jawab atas perubahan state; coroutine mengikuti lifecycle pemiliknya dan mempertahankan cancellation.
5. Simpan versi dependensi di `gradle/libs.versions.toml`. Pilih library berdasarkan kebutuhan dan dukungan seluruh target konsumennya. `implementation` adalah default; `api` hanya untuk tipe yang menjadi kontrak publik.
6. Gunakan token/komponen dari `core:designsystem`. Fitur production baru memakai resource string yang dapat dilokalkan dan semantics aksesibilitas yang sesuai.
7. Jangan menyimpan secret di kode, resource, atau BuildConfig, mencatat token/data pribadi, atau mengandalkan role UI sebagai otorisasi. Backend memverifikasi akses dan transaksi penting.
8. Perubahan mengikuti cakupan tugas. Jangan sekaligus mengganti DI, navigation, database, toolchain, atau struktur seluruh aplikasi tanpa kebutuhan perubahan tersebut.

Rincian normatif beserta alasannya:

- [Aturan engineering](.agents/skills/floopel-mobile-engineering/references/engineering-rules.md).
- [Desain arsitektur](.agents/skills/floopel-mobile-engineering/references/architecture.md).
- [Matriks verifikasi](.agents/skills/floopel-mobile-engineering/references/verification.md).
- [Referensi resmi dan keputusan lokal](.agents/skills/floopel-mobile-engineering/references/sources.md).

## Verifikasi

Gunakan Gradle Wrapper. Untuk perubahan source/build Android, jalankan build dan lint aplikasi yang terdampak; perubahan core perlu kedua aplikasi. Temukan task test nyata dengan `tasks --all` sebelum menggunakannya. Tambahkan test yang membuktikan perilaku bisnis atau regresi yang berubah; jangan membuat test yang hanya meniru implementasi.

```powershell
.\gradlew.bat :apps:android:traveler:assembleDebug :apps:android:partner:assembleDebug
.\gradlew.bat :apps:android:traveler:lintDebug :apps:android:partner:lintDebug
```

Perubahan yang memengaruhi Traveler bersama memerlukan kompilasi/pengujian iOS pada macOS/Xcode atau CI Mac. Keberhasilan Android, metadata, `NO-SOURCE`, atau target iOS yang dilewati di Windows tidak membuktikan keberhasilan iOS. Laporkan pemeriksaan yang benar-benar dijalankan, hasilnya, dan pemeriksaan yang masih diperlukan. Solusi kendala temporary Java Windows terdapat di `README.md`.

## Code Review Rules

- Laporkan kebocoran implementasi Partner ke Traveler, dependensi melingkar, dan akses data source langsung dari UI.
- Periksa kehilangan state saat lifecycle berubah, cancellation yang tertelan, dan kemungkinan transaksi ganda saat retry.
- Periksa kebocoran credential/PII dan klaim pengujian yang tidak didukung hasil eksekusi.

Aturan ini merupakan panduan kerja dan review. Guard arsitektur otomatis, CI lint/test, serta branch protection perlu konfigurasi tersendiri; keberadaan dokumen tidak membuktikan kontrol tersebut sudah aktif.
