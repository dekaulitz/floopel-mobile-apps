# Integrasi privat Android Traveler

7 November 2026 · branch `feature/mobile-openapi-contracts` · follow-up dari baseline b45d6cee.

Pemilik mengizinkan delapan akun fixture pada database existing lokal. Preflight memverifikasi `127.0.0.1:5432/floopel_db`, schema 163. Seeder memakai crypto/blind index/bcrypt backend dan satu transaksi untuk akun Traveler sintetis. Tidak menjalankan migration/reset atau mengubah akun existing. Fixture tidak mempunyai role Admin; status email verified merupakan setup, bukan hasil uji email/OTP.

Backend port 8080 dan [tunnel ngrok](https://unawake-moonlit-reckless.ngrok-free.dev) merespons. Docker sempat memerlukan pemulihan runtime; volume database dipertahankan. API background/media worker tetap nonaktif. Probe API tambahan pada port 8081 sudah dihentikan; origin aplikasi tetap tunnel ke 8080.

## Hasil nyata

| ID | Pemeriksaan | Hasil dan batas |
| --- | --- | --- |
| PRIV-01 | Login delapan fixture, profil, consent dan logout | PASS melalui native HTTP adapter; dokumen pending diterima memakai versi/hash aktual, lalu pending kosong dibaca kembali. Akun telah verified oleh fixture. Google/email/OTP bukan bagian uji. |
| PRIV-02 | Create itinerary, tambah Stop hari 3, edit `08:00`/durasi 0, readback, hapus Stop dan itinerary | PASS pada API nyata. Outsider ditolak 403/404. Kasus hasil POST hilang/idempotency itinerary tetap test deterministik, belum injeksi kegagalan transport nyata. |
| PRIV-03 | Upload/processing cover dan publication sukses | NOT_RUN. Worker media nonaktif; tidak mengganti status media menjadi READY untuk meluluskan publikasi. Empty/PROCESSING guard tetap diuji memakai fixture UI. |
| PRIV-04 | Create Trip, working plan sama, replay key dan reconcile setelah acknowledgement/re-entry | PASS pada API nyata. Repository dibuat ulang dengan journal/alias store yang sama. Penyimpanan test host berupa memori; bukan bukti Keystore. |
| PRIV-05 | Pending application, approve/reject/withdraw dan akses antarpemohon | PASS. Pending tidak menjadi anggota atau memakai slot. Pemohon lain tidak dapat membaca aplikasi privat. |
| PRIV-06 | Kapasitas 5 peserta + 1 pengelola | PASS. Roster aktif berisi lima participant dan satu organizer. Approval calon keenam ditolak; jumlah tetap lima. |
| PRIV-07a | Anggota dikeluarkan dan replay approval | PASS. Working plan ditolak setelah removal; replay tetap historical approved dengan `currentMember=false`, jumlah tidak bertambah. |
| PRIV-07b | Notice/inbox nyata dan acknowledgement | NOT_RUN. Pengujian domain fixture untuk reader/ack/403 tetap PASS; itu tidak membuktikan materialisasi/delivery inbox privat nyata. |
| PRIV-08a | MainActivity + runtime asli + API nyata | PASS pada emulator API 35: login, create itinerary, add Spot, edit Stop pertama, durasi 0 dan dua recreation Activity. [Render hasil](traveler-android-audit/private/live-private-itinerary.png); capture CTA tidak menunggu pemuatan gambar Coil dan bukan bukti pipeline media. |
| PRIV-08b | Refresh credential native setelah penghentian proses | PASS dalam fase terpisah: runner journey berakhir, target di-force-stop, runner baru membuka MainActivity, akun fixture dipulihkan melalui Keystore/refresh API, lalu logout dari UI. PID dan process-start elapsed berbeda pada dua fase; test memeriksanya dan menyimpan bukti JSON. Ini tidak mencakup kill saat banner/upload sedang berlangsung. |

Tiga metode host live lulus (0 failure/error/skip), ditambah dua metode native live pada dua invocation terpisah. Skenario host mencakup banyak assertion; angka ini bukan jumlah state Figma yang selesai. Raw XML/log disimpan di `.gradle/audit/traveler-android` dan `.gradle/*private*.log`; [manifest bukti](traveler-android-audit/private/results.json) mencatat hash hasil.

## Perbaikan dari pengujian/review

- Stop pertama memakai posisi server 0. Adapter kini menerimanya; UI menampilkan urutan mulai 1 dan mengonversinya kembali saat save. Host dan native nyata membuktikan jam/durasi tersimpan.
- Zona waktu Trip berasal dari Location langsung milik Spot. Reader UUID Location adalah aggregate tanpa timezone, sehingga adapter mengambil public detail by slug dan memeriksa ID yang sama. Nilai null tidak diganti zona perangkat atau ancestor.
- NAV-01: route menyimpan key create yang stabil dan reconcile memakai endpoint operasi existing. Re-entry dengan key B yang menemukan hasil key A menyimpan binding B→A terenkripsi sebelum journal dibersihkan. Binding terikat akun/origin dan dibersihkan saat logout; kapasitas 256 alias menolak penambahan alih-alih mengevict bukti recovery diam-diam. Route legacy tanpa key memperoleh identitas deterministik. Test mencakup owner recreation saat banner tertahan, A/B, legacy, storage gagal, serta pergantian akun. Kill Android saat banner berlangsung tetap NOT_RUN.
- Recovery Trip PUBLIC memakai capabilities/registration server. Tipe UNKNOWN tidak dipresentasikan sebagai PRIVATE. [Render fixture PUBLIC](traveler-android-audit/private/public-trip-recovery.png) dan test native membuktikan tipe/window pada form terkunci.
- NAV-03: Back dari builder yang dibuka melalui feedback Spot mengembalikan Spot asal; alur publikasi tetap kembali ke daftar milik pengguna. NAV-04: remove Stop keluar dari form item yang telah dihapus. Keduanya dibuktikan test state owner.
- NAV-05: refresh aktivitas/progress mengirim ulang read yang relevan, termasuk ketika konten sebelumnya sudah dimuat. Test production Session memulai Quest saat cache Passport masih kosong, memicu kegagalan GPS submit, lalu membuktikan GET progress berjalan dan failure pulih. Reader mempertahankan proof/pending/key tanpa otomatis start/submit/answer; cached Passport bukan otoritas kepemilikan.
- RV-03: pada satu hari/satu Spot, Tambah Spot menjadi primary dan Buat Trip secondary sesuai Figma 290:5233. Rencana multiday tetap memakai Buat Trip primary sesuai 113:1618. Ini koreksi prioritas CTA, bukan sign-off seluruh layout builder.
- Form pesan aplikasi Open Trip mengikuti batas adapter 500 karakter Unicode, termasuk disable submit ketika melebihi batas.

Suite deterministik: 366 host test PASS. Suite native dengan domain fixture: 9 test PASS, termasuk recovery PUBLIC. Build debug/lint Traveler PASS; 0 error/fatal dan 38 warning lint (kategori yang sama dengan handoff awal). iOS NOT_RUN pada Windows. Core/Partner tidak berubah dalam follow-up ini.

NAV-02 (create-and-add dari picker), NAV-06 (reveal hari/item), OTP enam digit/resend dan RV-01/02/04 tetap OPEN. Denominator audit desain adalah 883 state Android/6.027 edge pada 30 keluarga; hasil di atas tidak menutup seluruh denominator atau menyatakan siap production. Gap produk/provider/rilis lain tetap mengikuti [handoff utama](traveler-android.md).

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

Mode publik dan privat saling eksklusif. Run default tidak menjalankan test live. Arsipkan XML sebelum run berikutnya karena Gradle memakai direktori report yang sama.

Untuk native gunakan emulator test tersendiri, APK debug dan androidTest dari origin HTTPS yang sama. Sebelum fase journey, bersihkan data **aplikasi test pada emulator tersebut** dan push manifest ke external-files milik aplikasi. Jalankan class `com.floopel.traveler.LivePrivateTravelerJourneyTest` dengan argument `floopelPrivateLive=true`. Setelah runner keluar, force-stop target tanpa clear data, lalu jalankan `com.floopel.traveler.LivePrivateSessionRestoreTest` dengan `floopelPrivateLive=true` dan `floopelRestoreCheck=true`. Fase kedua memerlukan hasil login fase pertama. Pilih class eksplisit agar suite domain fixture terpisah. Dua class live dilewati sebelum Activity dimulai jika argument opt-in tidak tersedia.

Setelah screenshot dipull, file manifest di emulator dihapus; fase restore melakukan logout. Credential/token dan payload pribadi tidak masuk laporan, screenshot, Git, resource atau BuildConfig.
