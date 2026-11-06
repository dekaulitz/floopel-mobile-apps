# Aturan engineering production

Aturan ini berlaku pada bagian produk yang sedang diimplementasikan atau diubah. **Wajib** berarti harus dipenuhi untuk menyatakan bagian tersebut siap rilis. **Default** dapat diganti dengan alasan yang jelas dan bukti yang sepadan. Aturan bersyarat berlaku ketika kemampuan terkait digunakan; dokumentasi baru tidak mewajibkan pemasangan semua library sekaligus.

Arsitektur dan definisi pemilik tanggung jawab berada di [architecture.md](architecture.md). Sumber dan pilihan lokal dipetakan di [sources.md](sources.md); dokumentasi vendor adalah dasar pertimbangan, sedangkan kata wajib di sini merupakan kebijakan Floopel.

## R1. Kontrak dan kualitas Kotlin

**Wajib:** public API menyampaikan makna, error, dan ownership dengan jelas. Tipe DTO/entity tidak bocor ke screen atau kontrak bisnis. Mutable state tidak dipublikasikan untuk ditulis bebas oleh konsumen. Jangan memakai `!!`, `Any`, atau cast tidak aman untuk menutupi kontrak yang belum jelas; penggunaan yang memiliki invariant nyata harus dapat dijelaskan.

**Default:** gunakan visibilitas terkecil dan constructor injection. Hindari service locator tersembunyi. Tambahkan generic base repository/ViewModel, interface, atau lapisan baru hanya jika ada kebutuhan yang mengurangi kompleksitas nyata.

## R2. Coroutine dan lifecycle

**Wajib:** gunakan structured concurrency dan scope dengan pemilik serta masa hidup yang jelas. Operasi blocking/berat harus main-safe. Jangan memakai `GlobalScope`, mengandalkan `Thread.sleep`, atau menjalankan I/O dari body composable. Dependency dispatcher/clock dibuat dapat dikendalikan pada logika yang membutuhkannya untuk test.

Cancellation bukan kegagalan bisnis: jangan telan `CancellationException`, termasuk melalui `runCatching`/catch-all. Tangani exception yang relevan di boundary dan bersihkan listener/resource saat pemiliknya selesai. Rujukan: [praktik coroutine resmi](https://developer.android.com/kotlin/coroutines/coroutines-best-practices).

## R3. Kegagalan, retry, dan transaksi

**Wajib ketika memakai remote service:** bedakan timeout/koneksi, unauthorized, validasi, konflik, dan kegagalan server sesuai kontrak API. Berikan pesan pengguna yang aman; detail teknis disanitasi untuk diagnosis. Tentukan timeout, batas retry, backoff, dan perilaku cancellation.

Retry operasi tulis mengikuti semantik idempotency backend. Timeout setelah submit berarti hasil bisa belum diketahui; periksa status/reconcile sebelum mengulang transaksi penting. Mencegah double tap di UI membantu pengalaman pengguna tetapi tidak menjamin transaksi tunggal. Refresh credential yang concurrent harus dikoordinasikan dan retry autentikasi tidak boleh berulang tanpa batas. Detail mekanisme ini adalah keputusan lokal berdasarkan kontrak backend, bukan asumsi tentang API yang belum ada.

## R4. Data dan migrasi

**Wajib ketika menyimpan data:** tetapkan sumber utama, freshness, kebijakan invalidasi, dan perilaku setelah logout/pergantian akun. Cache dan pekerjaan tertunda tidak boleh menampilkan atau mengirim data akun sebelumnya. Data demo/fake hanya untuk preview, test, atau mode demo yang jelas; jangan menggunakannya sebagai fallback sukses pada alur production.

Perubahan schema/protokol harus memiliki strategi kompatibilitas serta migrasi yang diuji dengan data lama yang representatif. Jelaskan pemulihan bila migrasi gagal; jangan menghapus data pengguna secara diam-diam untuk memperbaiki schema. Offline write queue memerlukan kontrak konflik, deduplication, dan pemulihan. [Offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first) adalah pola bersyarat, bukan kewajiban untuk semua fitur.

## R5. Keamanan dan privasi

**Wajib:** secret jangka panjang dan signing credential tidak masuk kode, resource, BuildConfig, version control, atau log. Konfigurasi build bukan tempat penyimpanan rahasia. Gunakan TLS dengan validasi certificate normal; jangan menonaktifkannya agar koneksi berhasil.

Saat sesi/autentikasi ditambahkan, simpan credential sensitif melalui fasilitas aman platform: Keychain di iOS atau penyimpanan terenkripsi dengan pengelolaan kunci Android Keystore melalui implementasi yang sesuai. Tentukan lifecycle sesi, pembersihan saat logout, dan policy backup data sensitif. Jangan membuat kriptografi sendiri. Certificate pinning membutuhkan penilaian ancaman dan rencana rotasi/recovery; bukan default universal.

Backend memverifikasi hak akses, kepemilikan resource, harga, dan transaksi. Route, deep link, role UI, serta `AppProduct` bukan otorisasi. Hindari token, header autentikasi, isi data pribadi, dan informasi pembayaran pada log/analytics/crash payload. Minta izin platform saat diperlukan kemampuan terkait dan sediakan perilaku ketika ditolak.

Dasar pemeriksaan: area storage, crypto, auth, network, platform, code, dan privacy di [OWASP MASVS](https://mas.owasp.org/MASVS/). Mengikuti aturan ini tidak sama dengan sertifikasi atau audit MASVS yang sudah selesai.

## R6. UI, desain, dan aksesibilitas

**Wajib untuk UI production yang berubah:** gunakan token dan komponen bersama, resource string yang dapat dilokalkan, serta semantics untuk kontrol interaktif. Jangan menjadikan warna satu-satunya penanda error/status. Periksa kontras, font scaling, urutan fokus, keyboard/insets, light/dark theme, dan layout pada ruang yang relevan.

Label aksesibilitas menjelaskan aksi/makna, tanpa membaca ulang teks yang sudah tersedia atau ikon dekoratif. Periksa TalkBack untuk Android dan VoiceOver untuk Traveler iOS pada alur penting terdampak. Pilihan visual/platform boleh berbeda jika meningkatkan perilaku native. Dasar: [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility) dan [semantics multiplatform](https://kotlinlang.org/docs/multiplatform/compose-accessibility.html).

## R7. Dependency dan build

**Wajib:** gunakan Gradle Wrapper, katalog versi, serta dependency yang mendukung seluruh target modul konsumennya. Jangan memakai versi dinamis `+` atau dependency snapshot/pre-release tanpa alasan dan evaluasi kompatibilitas. Dokumentasikan alasan dependency baru dan dampaknya pada target, lisensi, ukuran, serta maintenance yang relevan.

**Default:** gunakan API stabil, dependency minimum, dan `implementation`. `api` hanya untuk kontrak publik yang benar-benar memerlukannya. Toolchain mengikuti matriks Kotlin/Compose/AGP/Gradle/Xcode; contoh versi dari dokumentasi tidak otomatis mengganti katalog proyek. Consolidasi build logic menggunakan convention plugin saat duplikasi sudah membebani pemeliharaan, bukan konfigurasi silang proyek yang sulit dilacak.

## R8. Test yang membuktikan perilaku

**Wajib untuk perubahan bisnis/bug:** test melindungi perilaku yang berubah dan regresi yang mungkin terjadi. Utamakan test deterministik untuk aturan bisnis, transisi state, mapping/error, dan kontrak repository; tambahkan integrasi/platform/UI test sesuai risiko.

Gunakan test double pada boundary, data tetap, virtual time/test scheduler, dan dispatcher terkontrol ketika diperlukan. Jangan bergantung pada jaringan production, credential nyata, sleep, atau urutan test. Kasus concurrent submit, cancellation, stale result, sesi berganti, migrasi, atau retry dipilih sesuai perilaku yang diubah.

Koreksi prose atau perubahan visual lokal yang ringan dapat diverifikasi lewat review/render tanpa test boilerplate. Jangan mengejar angka coverage universal atau menciptakan test yang hanya mengulang implementasi. Lihat [pengujian KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) dan [matriks bukti](verification.md).

## R9. Performa dan diagnosis

**Wajib bila mengubah alur yang sensitif terhadap performa:** nyatakan skenario, perangkat, build, data, baseline, serta hasil pengukuran sebelum mengklaim perbaikan. Pantau startup, scrolling, memori, request, atau ukuran artifact yang terkait perubahan. Budget/SLO ditentukan dari kebutuhan produk dan pengukuran; dokumen ini tidak mengarang angka target.

Diagnosis production yang ditambahkan harus menyertakan konteks error/versi dan correlation ID yang aman bila tersedia, dengan minimisasi data, batas retensi/akses, serta kemampuan menonaktifkan verbosity. Adanya aturan ini tidak berarti crash reporting atau analytics sudah terpasang.

## R10. Bukti sebelum integrasi dan rilis

**Wajib:** build, lint, dan test relevan selesai untuk konsumen terdampak. Jangan menurunkan gate, menambahkan suppression luas, atau menghapus test untuk membuat status hijau. Suppression yang diperlukan bersifat sempit dan memiliki alasan serta tindak lanjut yang dapat ditelusuri.

Sebelum rilis, verifikasi konfigurasi environment/endpoint, signing, izin dan privacy declaration yang diperlukan, kompatibilitas/migrasi, artifact release, serta alur penting pada platform target. Rencana pemulihan memperhitungkan distribusi store yang tidak langsung; gunakan mitigasi server/feature flag hanya jika memang tersedia.

Pemeriksaan yang belum bisa dijalankan ditulis `NOT_RUN` dengan alasan dan kebutuhan tindak lanjut. Ini tidak menghalangi penyelesaian pekerjaan kode yang tersedia, tetapi gate platform yang wajib belum dapat dinyatakan lulus. Aturan proses ini tidak memberi izin otomatis untuk merge, publish, atau deploy.

## Penerapan dan perubahan kebijakan

Gunakan rule ID R1–R10 serta bagian A1–A8 pada review/ADR. Catat penyimpangan default atau utang yang ditemukan secara spesifik; perubahan lokal tidak menjadi alasan memigrasikan seluruh repo. Untuk kebutuhan yang bertentangan dengan aturan wajib, perbaiki desain atau dokumentasikan keputusan kebijakan baru beserta risiko dan verifikasinya. Instruksi pengguna dan proses tim yang berlaku tetap menentukan cakupan.

Saat ini enforcement melalui instruksi dan review. CI, static analysis, pemeriksaan graf arsitektur, serta branch protection belum disediakan oleh dokumen ini dan tidak boleh dilaporkan seolah-olah sudah terkonfigurasi.
