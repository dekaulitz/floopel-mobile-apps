# Integrasi privat Android Traveler

7 November 2026 · branch `feature/mobile-openapi-contracts` · follow-up dari baseline b45d6cee.

Pemilik mengizinkan delapan akun fixture pada database existing lokal. Preflight memverifikasi `127.0.0.1:5432/floopel_db`, schema 163. Seeder memakai crypto/blind index/bcrypt backend dan satu transaksi untuk akun Traveler sintetis. Tidak menjalankan migration/reset atau mengubah akun existing. Fixture tidak mempunyai role Admin; status email verified merupakan setup, bukan hasil uji email/OTP.

Backend port 8080 dan [tunnel ngrok](https://unawake-moonlit-reckless.ngrok-free.dev) merespons. Docker sempat memerlukan pemulihan runtime; volume database dipertahankan. Scheduler background/media pada proses API tetap nonaktif. Pemrosesan cover fixture dijalankan sebagai command terpisah yang dibatasi ke satu asset. Probe API tambahan pada port 8081 sudah dihentikan; origin aplikasi tetap tunnel ke 8080.

## Hasil nyata

| ID | Pemeriksaan | Hasil dan batas |
| --- | --- | --- |
| PRIV-01 | Login delapan fixture, profil, consent dan logout | PASS melalui native HTTP adapter; dokumen pending diterima memakai versi/hash aktual, lalu pending kosong dibaca kembali. Akun telah verified oleh fixture. Google/email/OTP bukan bagian uji. |
| PRIV-02 | Create itinerary, tambah Stop hari 3, edit `08:00`/durasi 0, readback, hapus Stop dan itinerary | PASS pada API nyata. Outsider ditolak 403/404. Kasus hasil POST hilang/idempotency itinerary tetap test deterministik, belum injeksi kegagalan transport nyata. |
| PRIV-03 | Upload/processing cover dan publication sukses | PASS API nyata: JPEG diterima 202, worker scoped menghasilkan empat WebP, status READY dibaca kembali, publish, GET file WebP 200 saat publik, snapshot tetap immutable setelah draft berubah, lalu GET public itinerary 404 sesudah unpublish. Catatan privat tetap null; durasi 0 dipertahankan. Tidak memaksa field READY. Photo Picker native dan scheduler deployment belum diuji. |
| PRIV-04 | Create Trip, working plan sama, replay key dan reconcile setelah acknowledgement/re-entry | PASS pada API nyata. Repository dibuat ulang dengan journal/alias store yang sama. Penyimpanan test host berupa memori; bukan bukti Keystore. |
| PRIV-05 | Pending application, approve/reject/withdraw dan akses antarpemohon | PASS. Pending tidak menjadi anggota atau memakai slot. Pemohon lain tidak dapat membaca aplikasi privat. |
| PRIV-06 | Kapasitas 5 peserta + 1 pengelola | PASS. Roster aktif berisi lima participant dan satu organizer. Approval calon keenam ditolak; jumlah tetap lima. |
| PRIV-07a | Anggota dikeluarkan dan replay approval | PASS. Working plan ditolak setelah removal; replay tetap historical approved dengan `currentMember=false`, jumlah tidak bertambah. |
| PRIV-07b | Notice/inbox nyata dan acknowledgement | NOT_RUN. Pengujian domain fixture untuk reader/ack/403 tetap PASS; itu tidak membuktikan materialisasi/delivery inbox privat nyata. |
| PRIV-08a | MainActivity + runtime asli + API nyata | PASS pada emulator API 35: login, create itinerary, add Spot, edit Stop pertama, durasi 0 dan dua recreation Activity. [Render hasil](traveler-android-audit/private/live-private-itinerary.png); capture CTA tidak menunggu pemuatan gambar Coil dan bukan bukti pipeline media. |
| PRIV-08b | Refresh credential native setelah penghentian proses | PASS dalam fase terpisah: runner journey berakhir, target di-force-stop, runner baru membuka MainActivity, akun fixture dipulihkan melalui Keystore/refresh API, lalu logout dari UI. PID dan process-start elapsed berbeda pada dua fase; test memeriksanya dan menyimpan bukti JSON. Ini tidak mencakup kill saat banner/upload sedang berlangsung. |
| AUTH-NAV-01 | Logout Profile, Back sistem, buka ulang dan login kedua | PASS MainActivity nyata. Root login tidak menampilkan konten/nav utama; KEYCODE_BACK menghasilkan Activity ON_STOP. Invocation berikutnya tetap login, lalu autentikasi fixture menghasilkan Home selected dan Profile tidak selected. Tab Profile dapat dibuka, kemudian logout membersihkan sesi. [Bukti Back](traveler-android-audit/private/live-private-back.json), [Home selected](traveler-android-audit/layout-20261107/live-login-home-selected.png). |

Tiga metode host live admission serta satu metode publikasi/media pada invocation terpisah lulus (0 failure/error/skip), ditambah tiga metode native live pada tiga invocation terpisah. Skenario host mencakup banyak assertion; angka ini bukan jumlah state Figma yang selesai. Raw XML/log disimpan di `.gradle/audit/traveler-android` dan `.gradle/*private*.log`; [manifest bukti](traveler-android-audit/private/results.json) mencatat hash hasil.

## Perbaikan dari pengujian/review

- Stop pertama memakai posisi server 0. Adapter kini menerimanya; UI menampilkan urutan mulai 1 dan mengonversinya kembali saat save. Host dan native nyata membuktikan jam/durasi tersimpan.
- Zona waktu Trip berasal dari Location langsung milik Spot. Reader UUID Location adalah aggregate tanpa timezone, sehingga adapter mengambil public detail by slug dan memeriksa ID yang sama. Nilai null tidak diganti zona perangkat atau ancestor.
- NAV-01: route menyimpan key create yang stabil dan reconcile memakai endpoint operasi existing. Re-entry dengan key B yang menemukan hasil key A menyimpan binding B→A terenkripsi sebelum journal dibersihkan. Binding terikat akun/origin dan dibersihkan saat logout; kapasitas 256 alias menolak penambahan alih-alih mengevict bukti recovery diam-diam. Route legacy tanpa key memperoleh identitas deterministik. Test mencakup owner recreation saat banner tertahan, A/B, legacy, storage gagal, serta pergantian akun. Kill Android saat banner berlangsung tetap NOT_RUN.
- Recovery Trip PUBLIC memakai capabilities/registration server. Tipe UNKNOWN tidak dipresentasikan sebagai PRIVATE. [Render fixture PUBLIC](traveler-android-audit/private/public-trip-recovery.png) dan test native membuktikan tipe/window pada form terkunci.
- NAV-03: Back dari builder yang dibuka melalui feedback Spot mengembalikan Spot asal; alur publikasi tetap kembali ke daftar milik pengguna. NAV-04: remove Stop keluar dari form item yang telah dihapus. Keduanya dibuktikan test state owner.
- NAV-05: refresh aktivitas/progress mengirim ulang read yang relevan, termasuk ketika konten sebelumnya sudah dimuat. Test production Session memulai Quest saat cache Passport masih kosong, memicu kegagalan GPS submit, lalu membuktikan GET progress berjalan dan failure pulih. Reader mempertahankan proof/pending/key tanpa otomatis start/submit/answer; cached Passport bukan otoritas kepemilikan.
- RV-03: pada satu hari/satu Spot, Tambah Spot menjadi primary dan Buat Trip secondary sesuai Figma 290:5233. Rencana multiday tetap memakai Buat Trip primary sesuai 113:1618. Ini koreksi prioritas CTA, bukan sign-off seluruh layout builder.
- Cache: respons `no-store`, `no-cache` dan `max-age=0` tidak digunakan sebagai snapshot berikutnya. Refresh eksplisit melewati cache; respons fresh yang tidak dapat disimpan atau ditolak menghapus entry lama dan membatasi hasil read terlambat. Test API nyata kini menerima 404 setelah unpublish. Kebijakan reuse eksplisit merujuk [RFC 9111](https://www.rfc-editor.org/rfc/rfc9111.html).
- Reader publik mempertahankan durasi 0; null tetap null dan nilai negatif ditolak. Snapshot tidak mengambil judul/deskripsi/durasi dari working draft yang telah berubah.
- Form pesan aplikasi Open Trip mengikuti batas adapter 500 karakter Unicode, termasuk disable submit ketika melebihi batas.

Suite deterministik: 377 host test PASS. Suite native dengan domain fixture: 15 test PASS, termasuk recovery PUBLIC, mandatory login, state tab sesudah login ulang, serta geometri search/tombol. Build debug/lint Traveler dan Partner PASS karena komponen design system berubah; Traveler 0 error/fatal dan 38 warning, Partner 0 error/fatal dan 2 warning. iOS NOT_RUN pada Windows. Rincian dimensi, actor, artefak dan batas visual ada pada [audit UI](traveler-ui-audit.md).

Durasi 0 menit telah dikonfirmasi pemilik valid pada itinerary privat dan publik. Kontrak 34dbaee, mapper dan hasil publikasi nyata mempertahankan 0; null tetap berarti durasi tidak ditentukan. Mandatory login diterapkan pada root aplikasi, sebelum read fitur berjalan, dengan pemulihan sesi valid dan pembatalan/clear state saat sesi berakhir.

NAV-02 (create-and-add dari picker), NAV-06 (reveal hari/item), OTP enam digit/resend, RV-01/02/04 serta kalender/rentang tanggal dan format jadwal Trip tetap OPEN. Denominator audit desain adalah 883 state Android/6.027 edge pada 30 keluarga; hasil di atas tidak menutup seluruh denominator atau menyatakan siap production. Gap produk/provider/rilis lain tetap mengikuti [handoff utama](traveler-android.md).

## Menyiapkan ulang fixture lokal

Dari root mobile, berikan path checkout backend kanonis yang mempunyai module `github.com/floopel/backend` dan environment lokal:

```powershell
.\tools\fixtures\seed-local-traveler.ps1 -BackendServicePath <backend-application-service> -CheckOnly
.\tools\fixtures\seed-local-traveler.ps1 -BackendServicePath <backend-application-service>
```

Script menyalin [template seeder](../../tools/fixtures/traveler-local.go.template) ke direktori `tmp` backend yang diabaikan Git, lalu menjalankannya dengan `go run -mod=readonly`. Seeder hanya menerima APP_ENV dev/local, DSN loopback dan schema 163. File credential harus diabaikan Git. Default output `.gradle/runtime/private-fixture.json` tidak dikomit atau dipaketkan ke APK. Rerun manifest yang sama mengembalikan `existing_fixture`; kondisi parsial ditolak untuk ditinjau. Preflight dan rerun idempotent script ini telah dijalankan, hasilnya delapan akun existing.

Fixture tetap berada di database lokal untuk pengulangan. Test membuat data hanya atas akun fixture; judul diberi penanda QA. Tidak ada pembersihan massal. Test itinerary menghapus resource yang baru dibuatnya sendiri; Trip fixture dibatalkan lewat API setelah assertion sukses. Kegagalan test dapat menyisakan resource fixture untuk diagnosis.

## Menjalankan pengujian

```powershell
$fixtureFile = (Resolve-Path .gradle/runtime/private-fixture.json).Path
.\gradlew.bat :shared:features:traveler:testAndroidHostTest -PfloopelPrivateSmoke=true "-PfloopelPrivateFixtureFile=$fixtureFile" -PfloopelApiOrigin=https://your-tunnel.ngrok-free.dev
```

Mode publik, privat admission dan privat media saling eksklusif. Run default tidak menjalankan test live. Arsipkan XML sebelum run berikutnya karena Gradle memakai direktori report yang sama.

Untuk native gunakan emulator test tersendiri, APK debug dan androidTest dari origin HTTPS yang sama. Push manifest fixture ke external-files milik aplikasi pada emulator test. Journey menerima sesi kosong atau sesi organizer fixture yang sama; akun aktif diverifikasi sebelum logout setup. Akun lain menyebabkan test gagal, tanpa clear data. Jalankan class `com.floopel.traveler.LivePrivateTravelerJourneyTest` dengan argument `floopelPrivateLive=true`. Setelah runner keluar, force-stop target tanpa clear data, lalu jalankan `com.floopel.traveler.LivePrivateSessionRestoreTest` dengan `floopelPrivateLive=true` dan `floopelRestoreCheck=true`. Fase kedua memerlukan hasil login fase pertama dan membuktikan Back sistem dari root login melalui event ON_STOP. Jalankan fase ketiga `com.floopel.traveler.LivePrivateLoginBackTest` dengan `floopelPrivateLive=true` dan `floopelLoginBackCheck=true`: aplikasi dibuka ulang, masih login, lalu login kedua memeriksa Home selected/Profile tidak selected sebelum logout. Pilih class eksplisit agar suite domain fixture terpisah. Semua class live dilewati sebelum Activity dimulai jika argument opt-in tidak tersedia.

Setelah screenshot dipull, file manifest di emulator dihapus; fase restore melakukan logout. Credential/token dan payload pribadi tidak masuk laporan, screenshot, Git, resource atau BuildConfig.


## Mengulang pipeline media/publikasi

```powershell
.\tools\fixtures\build-local-media-worker.ps1 -BackendServicePath <backend-application-service>
$fixtureFile = (Resolve-Path .gradle/runtime/private-fixture.json).Path
$workerFile = (Resolve-Path .gradle/runtime/private-media-worker.exe).Path
.\gradlew.bat :shared:features:traveler:testAndroidHostTest -PfloopelPrivateMediaSmoke=true "-PfloopelPrivateFixtureFile=$fixtureFile" "-PfloopelPrivateMediaWorker=$workerFile" -PfloopelPrivateBackendService=<backend-application-service> -PfloopelPrivateProcessorImage=sha256:<existing-local-image-id> -PfloopelApiOrigin=https://your-tunnel.ngrok-free.dev
```

Image audit memuat libvips 8.14.1 dengan ID `sha256:f00c411429baad760dc4e4025e7f3c175f2fd5a9345b627254e242c2fcfaf25d`. Command memakai image lokal yang sudah tersedia (`--pull=never`), bukan mengambil image berdasarkan nama yang berubah. Build script dan test dengan binary hasilnya telah dijalankan.

[Helper fixture](../../tools/fixtures/local-media-worker/) memeriksa environment dev/local, DSN loopback, Local ObjectStore, schema 163, identitas organizer fixture dan kepemilikan cover. Claim hanya memilih job asset tersebut; bulk lease recovery ditolak. Transisi/heartbeat/CAS, transform dan publish-ready memakai service/repository/processor backend kanonis. Container hanya menjalankan libvips, tanpa konfigurasi/credential backend atau jaringan. Rootfs read-only, capability dihapus, dan satu workspace transform sementara yang dipasang. UID container 0 digunakan untuk bind mount Windows; scheduler worker/non-root deployment bukan bagian bukti ini. Queue global, provider delivery dan observability worker tidak diuji.

Host live OkHttp mengeluarkan diagnostik Android Log stub pada JVM (`isLoggable not mocked`); assertion jaringan tetap lulus. Bukti runtime Android berasal dari runner native yang dipisahkan. Resource dari test gagal dapat tersisa hanya pada akun fixture sesuai catatan cleanup di atas.
