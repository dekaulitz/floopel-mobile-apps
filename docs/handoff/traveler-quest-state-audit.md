# Dua koreksi state Quest

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts` · baseline `7553dbdaf30b430c39a2a09f6e3e4e88a4684f43`.

Batch ini memperbaiki QUEST-STATE-01 dan QUEST-STATE-02 dari audit source pemilik. Acuan journey Figma: [progress 218:2911](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=218-2911), [GPS 218:3040](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=218-3040), dan [outcome 218:3491](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=218-3491). Perubahan menyentuh binding state dan callback; layout Quest existing dan gap visualnya tetap tercatat pada [audit UI](traveler-ui-audit.md).

## QUEST-STATE-01: hasil GPS antaraktivitas

Sebelumnya, mapper menampilkan `gpsResult.attempt.status` tanpa memeriksa target. Sesudah A verified, membuka B menampilkan hasil A dan menghilangkan aksi lokasi B. `NativeTravelerIdentity` sekarang memproyeksikan outcome hanya jika `result.participation.id` serta `attempt.activityId` sama dengan participation/aktivitas reader saat ini. Pada progress yang tidak memiliki aktivitas aktif, outcome tidak diproyeksikan.

Scope yang sama juga berlaku untuk flag proof aktif: ready/collecting/location unavailable dan pending submission. Proof aktif A yang dipertahankan sesudah background tidak lagi menawarkan Submit pada B. Jika hasil submit A belum pasti, B tidak menawarkan Retry A, dan collect B dinonaktifkan mengikuti guard pending global yang sudah ada di Session. `gpsCollectionBlocked` merupakan capability turunan untuk mencerminkan guard tersebut; bukan status bisnis baru.

Session tetap menyimpan hasil untuk kunjungan ulang target yang sama. Proof, pending submission dan idempotency key tidak dihapus oleh perbaikan ini. Rekonsiliasi reader tidak mengulang submit secara otomatis. Tidak ada endpoint atau proof production baru.

Tujuh regresi memakai **production TravelerSession → NativeTravelerIdentity**, dengan Ktor MockEngine, attestation dan location source khusus test: verified/rejected/expired A→progress→B→A; aktivitas dengan ID sama pada participation lain; respons submit 503 dan retry eksplisit memakai proof/key semula; serta proof aktif sesudah background yang tidak bocor ke aktivitas/participation lain. B dapat memulai dan submit proof B sesudah proof aktif A, serta memulai proof baru sesudah hasil terminal A. Kasus pending membuktikan B tidak menawarkan Submit/Retry A atau collect yang ditolak guard, intent A bertahan, dan hanya retry eksplisit pada A yang menambah submit memakai key asli.

## QUEST-STATE-02: Retry reader lokal

Sebelumnya, tombol Retry lokal membuka route aktivitas yang sama. Guard navigasi duplikat berhenti sebelum melakukan GET. Tombol tersebut kini memanggil `model.refresh`, yang membaca ulang aktivitas dari route/participation aktif tanpa menambah stack. Jalur answer, collect GPS dan submit GPS tidak dipanggil oleh retry reader.

Host regression memulai dari read 503/question kosong, lalu memeriksa satu read tambahan, stack identik, konten pulih dan Back ke progress. Native UI menekan **tombol Retry lokal** lewat test tag, menghitung read 1→2 dan mutation calls tetap 0, kemudian Back sekali menuju progress. Outer ErrorPanel bukan tombol yang dipakai oleh kasus ini.

## Bukti dan batas

Hasil, hash source/APK dan screenshot ada pada [manifest batch](traveler-android-audit/quest-state-20261107/results.json). Suite host final mencakup 391 test; native mencakup empat kasus Quest baru dan 16 regresi domain-fixture pada API 35/400×900 dp. Build/lint Traveler menggunakan Gradle Wrapper. Core dan Partner tidak berubah pada batch ini.

Native menggunakan **production ViewModel/Compose dengan domain doubles**, terpisah dari pengujian Session/mapper di host. [A verified](traveler-android-audit/quest-state-20261107/quest-gps-a-verified.png) dan [B dengan collect CTA](traveler-android-audit/quest-state-20261107/quest-gps-b-collect.png) membuktikan interaksi UI. [Proof aktif A saat membuka B](traveler-android-audit/quest-state-20261107/quest-gps-b-active-a.png) tidak menampilkan Submit; collect/submit selanjutnya memanggil target B. [Pending A saat membuka B](traveler-android-audit/quest-state-20261107/quest-gps-b-pending-a.png) tidak menampilkan Retry dan collect disabled; [kembali ke A](traveler-android-audit/quest-state-20261107/quest-gps-a-own-retry.png) menawarkan retry A. [Reader gagal](traveler-android-audit/quest-state-20261107/quest-activity-read-failed.png) dan [pulih lewat Retry lokal](traveler-android-audit/quest-state-20261107/quest-activity-read-recovered.png) membuktikan callback dan navigasi. Ini tidak membuktikan GPS, Integrity, izin lokasi atau backend Quest nyata. Tidak ada operasi database/backend pada batch ini.

iOS framework/host/test `NOT_RUN`: Windows, perlu Mac/Xcode; host iOS masih scaffold. TalkBack/perangkat fisik dan parity layout Quest lengkap belum diverifikasi. Bukti startup 383 host/24 native pada commit 7553dbd serta API privat/media pada checkpoint sebelumnya tetap menjadi snapshot terpisah. Batas manual-private Trip, maksimal 5 peserta + 1 pengelola, commerce yang masih proposal dan gap lainnya tidak berubah.
