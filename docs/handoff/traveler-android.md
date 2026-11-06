# Traveler Android — implementasi dan audit

7 November 2026 · PR #1, branch feature/mobile-openapi-contracts · baseline kontrak 2fc35ab37091b4ea78a37174bb1058477b9a1900.

Android Traveler kini memakai runtime API native dan UI Compose di commonMain. Fokus implementasi adalah Quest, itinerary/publikasi dan Trip/admission, dilanjutkan reader publik, Saved Spot, kelompok dan inbox. Ini baseline yang dapat dibangun serta ditinjau, bukan sign-off seluruh desain atau kandidat rilis. Partner dan host iOS masih scaffold; merge/rilis tidak dilakukan.

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
| Publikasi 113:2058, share 431:8111 | itinerary + distribution/media journal | Revision + dua acknowledgement, cover READY, empty guard, publish/unpublish, tautan rotate/revoke | Intent/key/body tetap setelah respons hilang; emulator menolak PROCESSING; write backend privat NOT_RUN |
| Trip 315:15847, form 114:1739 | trip | Working itinerary yang sama, create/recover, banner sebagai hasil parsial, roster/lifecycle, undangan | Invitee tidak mengambil working plan; banner gagal tidak membuat Trip kedua; data fixture |
| Open Trip 5:326, detail 316:5228 | trip | Katalog/cursor publik, route publik, apply/pending, approve/reject/withdraw, reader pemilik | Guest API nyata PASS; write privat memakai test kontrak/state owner |
| Spot 36:696 | catalog | Detail/gallery, lokasi, rich body, tambah ke rencana/hari, Saved | Detail native data nyata + emulator penambahan hari 3/footer |
| Community 5:242, public plan 290:6459, profil 22:522 | catalog/PublicScreens.kt | Publikasi/kurasi/Open Trip, snapshot publik terpisah, clone, profil publik | Guest feed kontrak PASS; bukan akses working plan privat |
| Kelompok 397:7924 dan reader | quest/QuestGroupScreens.kt | Create/read, undangan, anggota/progres, manager transfer, leave/disband | Journal terikat actor; join tidak otomatis start Quest pribadi; E2E belum diuji |
| Inbox 386:7783 | root/ViewModel + NotificationRoute.kt | Inbox → allowlist target → reader berizin → ack ID inbox asli setelah render | Emulator removed/403; test revoked/logout/stale; GET/list tidak otomatis ack |
| Login 165:3376, registrasi 165:3640 | account + native host | Password/Google, registrasi 4 langkah/legal versi-hash, verify, forgot, profil | Referensi/legal API nyata PASS; login privat/email/provider NOT_RUN |

Aturan bisnis tetap: maksimal 5 peserta + 1 pengelola; pending application/invitation bukan membership dan tidak memesan slot. Kemampuan mengikuti server. Trip memakai working itinerary yang sama, bukan copy. Media processing tidak dianggap READY.

## Arsitektur dan keamanan

- UI memakai repository domain; DTO, HTTP dan sesi implementasi internal. Core tidak bergantung pada fitur/apps. Partner tidak masuk graf Traveler.
- Host menangani lifecycle, izin presisi/GPS, Play Integrity, Credential Manager, Photo Picker, share dan Back. Runtime dimiliki ViewModel native; adapter Activity lokasi di-rebind setelah recreation.
- Refresh token dan journal memakai Android Keystore AES-GCM, dipisahkan menurut origin, tanpa backup. Saved navigation hanya memuat ID/konteks, tanpa password/token/foto/URI.
- Tidak ada retry tulis otomatis. Publication/group/media menyimpan intent sebelum I/O. Media journal menyimpan SHA-256/mime/ukuran/key, bukan bytes. Setelah process death, pengguna memilih kembali foto yang sama untuk retry dengan key lama. Operasi expired diblokir agar tidak diam-diam dikirim dengan key baru.
- Respons 401/403/404 menghapus konten read lama. Generation/route mencegah respons terlambat menimpa layar baru. Cancellation diteruskan.
- Rich body menampilkan subset inert teks/format/link yang diizinkan, tanpa WebView/script/form/embed. Teks plain tetap plain. UI memakai Compose Resources dan token design system.

## Backend dan tunnel aktual

Sesuai instruksi pemilik, binary backend final handover diaktifkan pada 127.0.0.1:8080 dengan worker background/media dinonaktifkan untuk audit. Source/env tidak diubah; audit tidak menjalankan migration, seed/reset, atau mutasi privat. Tunnel existing: https://unawake-moonlit-reckless.ngrok-free.dev. Public read lokal/tunnel mengembalikan 200; URL media payload memakai origin HTTPS yang sama. URL ini sementara, bukan konfigurasi production.

Build audit memakai properti floopelApiOrigin dengan origin tunnel. Debug default memakai emulator loopback. Provider kosong menampilkan keadaan belum tersedia; tidak ada token/proof sintetis di runtime production. Konfigurasi ada di [README](../../README.md#konfigurasi-android-traveler).

## Verifikasi yang dijalankan

| Pemeriksaan | Hasil aktual |
| --- | --- |
| assembleDebug Traveler + Partner | PASS; kedua aplikasi dibangun karena core berubah |
| lintDebug Traveler + Partner | PASS tanpa error. Traveler 38 warning, Partner 2: mayoritas saran upgrade, juga OldTargetApi, MissingApplicationIcon, UseKtx. Toolchain tidak di-upgrade |
| testAndroidHostTest | 357 test PASS; 0 failure/error/skipped, baseline 330 + regresi/state/recovery baru |
| Host dengan floopelLiveSmoke=true dan origin tunnel | 4 public-read test PASS; 0 failure/error/skipped. Discovery/detail/search, UUID/slug, Quest/Open Trip guest, referensi registrasi/legal |
| connectedDebugAndroidTest dan runner adb untuk screenshot final | 8 journey PASS pada connected run dan runner final memakai domain fixture; 0 skipped. Runner ulang menyimpan screenshot yang terhapus ketika Gradle membersihkan APK test |
| Font 130% dan mode gelap | Masing-masing 2 skenario (cover/gutter dan feedback/day) PASS; bukan seluruh aplikasi. Emulator dipulihkan ke font 100%/mode terang |
| Guard kontrak Node | check-examples.mjs PASS + 4 test guard PASS |
| Batas layer/graf, pola credential, diff check | PASS pada pemeriksaan lokal; bukan audit keamanan menyeluruh atau kontrol CI otomatis |
| iOS framework/host/test | NOT_RUN: Windows, perlu Mac/Xcode; host iOS belum dihubungkan runtime |

Emulator: Android 15/API 35, 1080×2400, density 420, package com.floopel.traveler. Test native memakai fixture eksplisit, tanpa membuktikan login/transaksi/provider nyata. APK aktual juga dibuka melalui runtime ngrok untuk Home, Spot dan login.

Bukti: [audit dan screenshot](traveler-android-audit/README.md), [manifest hasil](traveler-android-audit/results.json). XML/report mentah berada di output build dan arsip lokal .gradle/audit/traveler-android.

## Sisa gap dan gate

| Bagian | Status / kebutuhan berikutnya |
| --- | --- |
| Write backend privat | NOT_RUN: pilihan akun/fixture test dari pemilik masih menunggu. Tidak memakai credential pemilik atau membuat akun pada database yang belum ditentukan |
| Google Sign-In / Play Integrity / GPS nyata | Adapter ada; registrasi package/signature, project/client ID dan konfigurasi backend perlu disediakan serta diuji pada distribusi/perangkat sesuai |
| Photo Picker → upload → media READY nyata | Adapter/journal/status/polling terhubung, test deterministik ada. E2E belum dijalankan karena akun privat dan worker media audit nonaktif |
| Maps Explorer dan FCM | Belum dihubungkan; perlu provider/config dan uji izin/delivery/deep link. Inbox in-app sudah tersedia |
| Kunjungan manual Trip | Kontrak operasi/persistensi belum tersedia; tidak membuat endpoint/status palsu atau menyamakannya dengan proof Quest |
| Lanjutan UI | Settings/edit akun/security/emergency/interests/avatar, komentar/report, filter tanggal/lokasi Open Trip, advanced day/reorder, serta pagination lanjutan kelompok belum seluruhnya dihubungkan. Adapter bukan bukti UI selesai |
| QA perangkat | TalkBack, keyboard/fokus menyeluruh, tablet/layar kecil, perangkat fisik, process-death platform/storage/provider dan performa belum diaudit lengkap |
| Rilis | Ikon adaptive, signing, production origin/provider, release build, privacy/store review, CI/branch protection dan gate produk belum dituntaskan |
| iOS dan Partner | Tahap berikutnya sesuai prioritas pemilik; Android PASS bukan bukti iOS |
| Commerce | Harga/booking/payment tetap proposal, tidak diimplementasikan |

Tidak ada klaim seluruh 1.767 state Figma selesai atau siap production. PR menyajikan implementasi prioritas, test yang dapat diulang, dan gap untuk iterasi berikutnya.
