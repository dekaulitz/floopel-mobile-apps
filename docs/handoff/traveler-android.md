# Traveler Android — implementasi dan audit

7 November 2026 · PR #1, branch feature/mobile-openapi-contracts · kontrak durasi nol 34dbaeee8ec76f81a3a19659cfe877db6ba68f86.

Android Traveler kini memakai runtime API native dan UI Compose di commonMain. Fokus implementasi adalah Quest, itinerary/publikasi dan Trip/admission, dilanjutkan reader publik, Saved Spot, kelompok dan inbox. Ini baseline yang dapat dibangun serta ditinjau, bukan sign-off seluruh desain atau kandidat rilis. Partner dan host iOS masih scaffold; merge/rilis tidak dilakukan.

Hasil follow-up akun fixture lokal, perbaikan recovery/navigasi, 377 host test pada checkpoint sebelumnya, API privat dan fase native nyata tercatat pada [audit integrasi privat](traveler-private-audit.md). Follow-up [Splash dan empat intro](traveler-startup-audit.md) menambahkan progres instalasi persisten serta verifikasi final 383 host/24 native test. [Audit UI](traveler-ui-audit.md) memisahkan koreksi komponen dari gap layout yang masih terbuka. Bukti checkpoint awal tetap dipertahankan.

## Acuan

- [Figma final](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=0-1), revisi QA 6 November 2026. Revisi terbaru relevan digunakan; konflik bisnis harus dikonfirmasi.
- Paket handover: 6.148.450 byte, 193 entry, CRC diperiksa. SHA-256 97b2fa02e25564c917751c619e4f6f71d55f13c93b22b76b0030e10770f0fb0a cocok. Inventory 1.767 root adalah screen/state, bukan 1.767 fitur. Prototype tidak membuktikan aplikasi native.
- [Provenance](traveler-source-provenance.json) mencatat source/hash adapter, domain dan sesi yang diadaptasi dari repo pemilik. UI baru berada di commonMain. Hash mengidentifikasi source sebelum adaptasi, bukan klaim target identik byte.
- [ADR runtime](../adr/0001-traveler-android-runtime.md) menjelaskan komposisi, ownership dan dependensi.

## Hasil implementasi

| Journey / node Android | Source dalam presentation | Perilaku terhubung | Bukti dan batas |
| --- | --- | --- | --- |
| Home 5:200 | home/TravelerHomeContent.kt | Rail Spot/Location/Collection/Story, sesi/Passport, Trip aktif, inbox | Screenshot data publik nyata; gambar bukan fallback fixture |
| Quest 97:1286, progress 218:2911 | quest + domain/session | Discovery/owner snapshot, start/continue, question, GPS, hasil/progres, Passport | Test kontrak/cancellation/GPS memakai provider double; provider nyata NOT_RUN |
| Builder 113:1618, form 290:4697, Stop 113:1882 | itinerary | Create/edit, hari/judul, tambah/hapus/edit Stop, HH:mm, null versus durasi 0, recovery draft/Stop | Emulator: cover 154 dp full viewport, gutter 20 dp, validasi, Back/day/feedback |
| Publikasi 113:2058, share 431:8111 | itinerary + distribution/media journal | Revision + dua acknowledgement, cover READY, empty guard, publish/unpublish, tautan rotate/revoke | Intent/key/body tetap setelah respons hilang; emulator menolak PROCESSING; API media/publikasi PASS pada audit privat; Photo Picker native NOT_RUN |
| Trip 315:15847, form 114:1739 | trip | Working itinerary yang sama, create/recover, banner sebagai hasil parsial, roster/lifecycle, undangan | Invitee tidak mengambil working plan; banner gagal tidak membuat Trip kedua; [API privat/recovery](traveler-private-audit.md) |
| Open Trip 5:326, detail 316:5228 | trip | Katalog/cursor publik, route publik, apply/pending, approve/reject/withdraw, reader pemilik | Guest API nyata PASS; follow-up write privat/kapasitas 5+1 PASS pada [audit privat](traveler-private-audit.md) |
| Spot 36:696 | catalog | Detail/gallery, lokasi, rich body, tambah ke rencana/hari, Saved | Detail native data nyata + emulator penambahan hari 3/footer |
| Community 5:242, public plan 290:6459, profil 22:522 | catalog/PublicScreens.kt | Publikasi/kurasi/Open Trip, snapshot publik terpisah, clone, profil publik | Guest feed kontrak PASS; bukan akses working plan privat |
| Kelompok 397:7924 dan reader | quest/QuestGroupScreens.kt | Create/read, undangan, anggota/progres, manager transfer, leave/disband | Journal terikat actor; join tidak otomatis start Quest pribadi; E2E belum diuji |
| Inbox 386:7783 | root/ViewModel + NotificationRoute.kt | Inbox → allowlist target → reader berizin → ack ID inbox asli setelah render | Emulator removed/403; test revoked/logout/stale; GET/list tidak otomatis ack |
| Login 165:3376, registrasi 165:3640 | account + native host | Password/Google, registrasi 4 langkah/legal versi-hash, verify, forgot, profil | Referensi/legal API nyata PASS; login privat/restore native PASS pada audit follow-up; email/provider NOT_RUN |

Aturan bisnis tetap: maksimal 5 peserta + 1 pengelola; pending application/invitation bukan membership dan tidak memesan slot. Kemampuan mengikuti server. Trip memakai working itinerary yang sama, bukan copy. Media processing tidak dianggap READY.

Keputusan pemilik 7 November: durasi Stop 0 menit valid pada working plan dan snapshot publik; null berarti belum ditentukan. Schema kanonis/bundle kini minimum 0. Aplikasi wajib login sebelum fitur, termasuk katalog yang endpoint backend-nya publik. Saat logout, tab dan stack dinormalisasi ke root login; login ulang menuju Home dengan tab Home aktif. Pemulihan sesi valid mempertahankan route yang berizin.

## Arsitektur dan keamanan

- UI memakai repository domain; DTO, HTTP dan sesi implementasi internal. Core tidak bergantung pada fitur/apps. Partner tidak masuk graf Traveler.
- Host menangani lifecycle, izin presisi/GPS, Play Integrity, Credential Manager, Photo Picker, share dan Back. Runtime dimiliki ViewModel native; adapter Activity lokasi di-rebind setelah recreation.
- Refresh token dan journal memakai Android Keystore AES-GCM, dipisahkan menurut origin, tanpa backup. Saved navigation hanya memuat ID/konteks, tanpa password/token/foto/URI.
- Tidak ada retry tulis otomatis. Publication/group/media menyimpan intent sebelum I/O. Media journal menyimpan SHA-256/mime/ukuran/key, bukan bytes. Setelah process death, pengguna memilih kembali foto yang sama untuk retry dengan key lama. Operasi expired diblokir agar tidak diam-diam dikirim dengan key baru.
- Respons 401/403/404 menghapus konten read lama. Generation/route mencegah respons terlambat menimpa layar baru. Cancellation diteruskan.
- Rich body menampilkan subset inert teks/format/link yang diizinkan, tanpa WebView/script/form/embed. Teks plain tetap plain. UI memakai Compose Resources dan token design system.

## Backend dan tunnel aktual

Sesuai instruksi pemilik, binary backend final handover diaktifkan pada 127.0.0.1:8080 dengan worker background/media dinonaktifkan untuk audit. Source/env backend tidak diubah. Audit awal melakukan public read; follow-up membuat akun fixture lokal dan menjalankan mutasi privat yang diizinkan pemilik. Tidak ada migration/reset. Tunnel existing: https://unawake-moonlit-reckless.ngrok-free.dev. Public read lokal/tunnel mengembalikan 200; URL media payload memakai origin HTTPS yang sama. URL ini sementara, bukan konfigurasi production.

Build audit memakai properti floopelApiOrigin dengan origin tunnel. Debug default memakai emulator loopback. Provider kosong menampilkan keadaan belum tersedia; tidak ada token/proof sintetis di runtime production. Konfigurasi ada di [README](../../README.md#konfigurasi-android-traveler).

## Verifikasi yang dijalankan

| Pemeriksaan | Hasil aktual |
| --- | --- |
| assembleDebug Traveler + Partner | PASS; kedua aplikasi dibangun karena core berubah |
| lintDebug Traveler + Partner | PASS tanpa error/fatal. Final startup: Traveler 42 warning, Partner 2, terutama saran upgrade, OldTargetApi, MissingApplicationIcon dan UseKtx; [laporan](traveler-android-audit/startup-20261107/results.json). Toolchain tidak di-upgrade |
| testAndroidHostTest | Final startup 383 test PASS; 0 failure/error/skipped. Checkpoint sebelumnya 377, baseline awal 357 |
| Host dengan floopelLiveSmoke=true dan origin tunnel | 4 public-read test PASS; 0 failure/error/skipped. Discovery/detail/search, UUID/slug, Quest/Open Trip guest, referensi registrasi/legal |
| connectedDebugAndroidTest dan runner adb untuk screenshot final | Baseline connected 8 journey; final runner 24 test PASS pada viewport 400×900 dp: 16 domain-fixture/regresi dan 8 MainActivity nyata (4 startup/progres, 3 privat/login/Back, 1 invalid refresh); [manifest](traveler-android-audit/startup-20261107/results.json). Angka test bukan jumlah state Figma yang ditandatangani |
| Host privat dan tiga fase native nyata | 3 host live admission dan 1 host live media/publikasi PASS, ditambah journey native, restore/logout/Back sistem setelah force-stop, dan reopen/login kedua masing-masing 1 test PASS; [rincian batas](traveler-private-audit.md) |
| Font 130% dan mode gelap | Masing-masing 2 skenario (cover/gutter dan feedback/day) PASS; bukan seluruh aplikasi. Emulator dipulihkan ke font 100%/mode terang |
| Guard kontrak Node | check-examples.mjs PASS + 4 test guard PASS |
| Batas layer/graf, pola credential, diff check | PASS pada pemeriksaan lokal; bukan audit keamanan menyeluruh atau kontrol CI otomatis |
| iOS framework/host/test | NOT_RUN: Windows, perlu Mac/Xcode; host iOS belum dihubungkan runtime |

Emulator: Android 15/API 35, package com.floopel.traveler. Batch koreksi UI memakai viewport 400×900 dp (1080×2430, density 432), locale en-US, resource default Bahasa Indonesia, font 100%; dua capture memakai Compose fontScale 2. Konfigurasi awal 1080×2400/density 420 dipulihkan setelah audit. Test fixture, API host dan MainActivity nyata dilaporkan terpisah; hasil provider lain tidak disimpulkan darinya.

Bukti: [audit dan screenshot](traveler-android-audit/README.md), [manifest hasil](traveler-android-audit/results.json). XML/report mentah berada di output build dan arsip lokal .gradle/audit/traveler-android.

## Sisa gap dan gate

| Bagian | Status / kebutuhan berikutnya |
| --- | --- |
| Write backend privat | Itinerary/Stop, Trip dan admission telah diuji memakai delapan akun fixture lokal. Publikasi/media API telah lulus; notice/inbox privat nyata masih NOT_RUN; lihat audit privat |
| Google Sign-In / Play Integrity / GPS nyata | Adapter ada; registrasi package/signature, project/client ID dan konfigurasi backend perlu disediakan serta diuji pada distribusi/perangkat sesuai |
| Photo Picker → upload → media READY nyata | Adapter/journal/status/polling terhubung, test deterministik ada. Pipeline upload/processing/publikasi API PASS dengan worker scoped; Photo Picker dan scheduler deployment belum diuji |
| Maps Explorer dan FCM | Belum dihubungkan; perlu provider/config dan uji izin/delivery/deep link. Inbox in-app sudah tersedia |
| Kunjungan manual Trip | Kontrak operasi/persistensi belum tersedia; tidak membuat endpoint/status palsu atau menyamakannya dengan proof Quest |
| Lanjutan UI | Settings/edit akun/security/emergency/interests/avatar, komentar/report, filter tanggal/lokasi Open Trip, advanced day/reorder, serta pagination lanjutan kelompok belum seluruhnya dihubungkan. Adapter bukan bukti UI selesai |
| QA perangkat | TalkBack, keyboard/fokus menyeluruh, tablet/layar kecil, perangkat fisik, process-death platform/storage/provider dan performa belum diaudit lengkap |
| Rilis | Ikon adaptive, signing, production origin/provider, release build, privacy/store review, CI/branch protection dan gate produk belum dituntaskan |
| iOS dan Partner | Tahap berikutnya sesuai prioritas pemilik; Android PASS bukan bukti iOS |
| Commerce | Harga/booking/payment tetap proposal, tidak diimplementasikan |

Tidak ada klaim seluruh 1.767 state Figma selesai atau siap production. PR menyajikan implementasi prioritas, test yang dapat diulang, dan gap untuk iterasi berikutnya.
