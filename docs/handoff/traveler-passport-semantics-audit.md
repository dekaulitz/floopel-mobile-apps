# Passport — status pilihan untuk aksesibilitas

7 November 2026 · `feature/mobile-openapi-contracts` · follow-up commit `c85046b`.

Pemeriksaan consumer Passport menemukan bahwa Ringkasan/Badge/Statistik dan Berjalan/Selesai hanya membedakan pilihan melalui warna `secondary`. Semantics belum memuat pilihan aktif atau peran tab. Pada Figma 274:4284, kedua grup memiliki satu pilihan aktif.

`PassportScreen` sekarang mengekspos `selected` dari `ui.tab`/`ui.completed`, `Role.Tab` pada setiap pilihan dan `selectableGroup` pada setiap grup yang tampil. Status mengikuti state ViewModel yang sama dengan warna. Label, ukuran, callback dan susunan visual tetap mengikuti frame Passport. Properti tersebut mengikuti [panduan semantics Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics); layanan aksesibilitas menentukan cara menyajikannya.

Test native memeriksa grup dan peran pada semantics tree, `assertIsSelected`/`assertIsNotSelected`, tepat satu anggota aktif, target minimal 48 dp, pergantian pilihan serta Back dari badge/QR besar. Jumlah pembacaan Passport tidak bertambah dan tidak ada admission Quest dari pergantian tab. Bahasa Indonesia/English serta font 130/200% diperiksa melalui skenario yang dicatat terpisah.

| Pemeriksaan | Hasil |
| --- | --- |
| Build DevelopmentDebug Traveler/Partner dan APK instrumentation | PASS. |
| Lint kedua aplikasi | PASS, 0 error/fatal; Traveler 45 warning, Partner 2 warning. |
| Host Traveler | 477 PASS, 0 failure/error/skipped. |
| Native Passport | 14 test PASS dalam 29,307 detik pada emulator 5580/API35. Empat snapshot sintetis memuat 19 pemeriksaan grup. Source/APK tidak berubah selama runner. |
| TalkBack nyata / ucapan AT | NOT_RUN; assertion semantics bukan bukti output ucapan atau audit aksesibilitas menyeluruh. |
| iOS framework/host/test dan VoiceOver | NOT_RUN di Windows; perlu Mac/Xcode. |

[Manifest follow-up](traveler-android-audit/passport-semantics-20261107/results.json) mengikat log, source/APK dan snapshot semantics sintetis. Pemeriksaan 94 native test pada [checkpoint sebelumnya](traveler-passport-spots-audit.md) tetap historis pada `c85046b`; jumlah tersebut tidak digabung menjadi satu runner baru.

Fixture English mengatur locale proses test dan konfigurasi Android sebelum komposisi, lalu memulihkannya sesudah test. Pilihan bahasa perangkat/akun tidak diubah. Emulator dikembalikan ke 1080×2400/density 420/font 100%; MainActivity memulihkan sesi lokal dan kembali ke Profil publik.
