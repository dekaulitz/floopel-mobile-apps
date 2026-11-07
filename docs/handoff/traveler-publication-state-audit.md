# State publikasi sesudah mutation

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts` · baseline `cda0cc5b4d2d9d698a38bed953ff17e02b387c99`.

PUB-STATE-02 terlihat pada walkthrough: publish berhasil, ringkasan masih menyebut satu Spot tetapi halaman juga menampilkan pesan rencana kosong. Ini kesalahan state client. Owner mutation sesuai kontrak tidak wajib mengirim `stop_count`, `stops` atau `updated_at`; tidak diperlukan perubahan backend/schema untuk memperbaikinya.

## Perilaku yang dikoreksi

- Mapper mempertahankan `stop_count` yang absent/null sebagai **unknown**, terpisah dari nilai nol eksplisit. Nilai negatif ditolak. Domain publication metadata kini memakai `Int?`.
- Fakta kosong/tidak kosong berasal dari detail itinerary yang `stopsLoaded`, ID-nya sesuai route dan distribution, serta read-nya tidak sedang loading/gagal. Metadata tanpa jumlah Stop tidak menimpa fakta detail. Loading/error tidak ditampilkan sebagai rencana kosong.
- Sesudah publish/unpublish dikonfirmasi, client membaca ulang detail dan metadata owner untuk mendapatkan revision terkini. Pending publication dibersihkan setelah publish sukses. GET ulang mengikuti guard route/generation dan cancellation existing.
- Publish dinonaktifkan selama metadata/detail belum siap atau gagal. Error read menawarkan Retry yang hanya membaca; hasil POST yang sudah diketahui sukses tidak diubah menjadi unknown outcome dan tidak diulang. Validasi ulang Stop sebelum write tetap berjalan. Syarat acknowledgement dan cover READY dipertahankan.

Tidak ada perubahan layout publikasi secara menyeluruh atau klaim parity Figma baru. Komposisi publikasi, switch izin salin dan gap visual lain tetap tercatat pada audit UI. Batas peta yang ditunda pemilik tidak berubah. Penanganan journal untuk definite revision conflict/409 merupakan pekerjaan terpisah, bukan ditutup oleh batch ini.

## Bukti

[Manifest](traveler-android-audit/publication-state-20261107/results.json) mengikat log, source/APK dan capture ke batch ini.

- **417 host test PASS**, 0 failure/error/skipped: satu regresi adapter untuk absent/zero/invalid count serta enam regresi ViewModel untuk rekonsiliasi owner revision, read failure/retry tanpa write ulang, detail failure sebagai unknown, respons terlambat setelah pindah itinerary/logout, dan unpublish. Guard empty plan existing tetap lulus.
- **2 test UI publikasi + 13 regresi journey PASS**, memakai production ViewModel/Compose dengan domain doubles. [Publish tetap satu Spot](traveler-android-audit/publication-state-20261107/confirmed-not-empty.png), [owner read gagal](traveler-android-audit/publication-state-20261107/owner-read-failed.png), [pulih lewat GET](traveler-android-audit/publication-state-20261107/owner-read-recovered.png). Counter mutation tetap satu; tidak ada unpublish pada skenario retry.
- Gradle Wrapper: `testAndroidHostTest`, Traveler `assembleDebug`, `assembleDebugAndroidTest` dan `lintDebug` **PASS**; lint 42 warning, 0 error/fatal. Core/Partner tidak berubah; Partner tidak dibangun ulang pada batch ini. Bukti kedua aplikasi dari batch rating/lokasi tetap menjadi checkpoint terpisah.
- Native: Android 15/API 35, emulator 5580, 1080×2430/density 432/400×900 dp, font 100%, light, locale en-US/resource default Indonesia. Konfigurasi awal 1080×2400/density 420 dipulihkan sesudah test.
- iOS framework/host/test **NOT_RUN** di Windows; perlu Mac/Xcode. TalkBack/perangkat fisik dan semua state/capability publikasi belum diverifikasi.

Source manifest mencatat bytes working tree terpilih. Edit `styles.xml` yang sudah ada ikut dikompilasi, hash-nya dicatat sebagai konteks build dan tidak dimasukkan ke commit ini; `.gitignore` existing juga dipertahankan. Bukti 410 host/33 native rating-lokasi serta checkpoint sebelumnya tidak dilabel ulang sebagai rerun batch ini.
