# Bukti audit Android Traveler

Audit lokal 7 November 2026 memakai emulator Android 15/API 35, 1080×2400, density 420. [Catatan implementasi dan gap](../traveler-android.md) membedakan kode yang tersedia, fixture, integrasi nyata dan gate rilis. [Manifest hasil](results.json) mencatat jumlah test dan hash bukti.

Follow-up: [audit privat](../traveler-private-audit.md) memuat hasil login/itinerary/admission lokal nyata, recovery dan sesi native. [Manifest follow-up](private/results.json) memisahkan 377 host, 3 host admission + 1 host media live, 15 native fixture dan fase native live. [Audit UI terbaru](../traveler-ui-audit.md) memuat screenshot pada viewport Figma 400×900 dp dan gap yang masih terbuka. Tabel di bawah adalah bukti baseline sebelumnya; Home guest lama tidak lagi menjadi entry aplikasi setelah keputusan mandatory login.

Batch [rating dan lokasi](../traveler-location-rating-audit.md) memiliki [manifest tersendiri](location-rating-20261107/results.json): 410 host/33 native test, build/lint kedua aplikasi Android, serta screenshot hierarki lokasi dan komponen Home/Spot. Peta ditunda sesuai keputusan pemilik. Bukti baseline di bawah tidak dilabel ulang sebagai hasil batch tersebut.

## Screenshot runtime dengan API nyata

- [Home](screenshots/home-real-api.png): guest, public discovery/media melalui ngrok, hero memakai aset Figma lokal.
- [Spot](screenshots/spot-real-api.png): public detail/media dan narasi server, cover full viewport.
- [Katalog lokasi](screenshots/location-catalog-real-api.png): aksi lokasi pada Home membuka katalog, tanpa menampilkan enum transport sebagai label pengguna.
- [Login](screenshots/login-native.png): UI native; belum login akun nyata. Brand mark 48×48, wordmark 112×47,0588 dan Google mark 20×20 berasal dari aset Figma lokal. Tombol Google belum aktif karena client ID belum dikonfigurasi.

Screenshot merupakan hasil render aplikasi, bukan frame Figma. URL, isi katalog dan status worker pada environment audit dapat berubah.

## Screenshot dari test domain fixture

Nama/ID “simulasi” di bawah hanya berada di source test. Gambar kosong pada fixture adalah kondisi tanpa media; bukan pengganti foto API production.

| Skenario | Bukti |
| --- | --- |
| Cover viewport, gutter judul 20 dp, builder hari | [Builder](screenshots/itinerary-builder.png) |
| Rencana baru kosong tidak dapat masuk publikasi | [Empty guard](screenshots/empty-plan-guard.png) |
| Jam 24:00 ditolak, input tetap terlihat; 08:00 dan durasi 0 diterima | [Validasi Stop](screenshots/stop-validation.png) |
| Cover PROCESSING memblokir publish walau kedua acknowledgement dipilih | [Processing guard](screenshots/publication-processing-guard.png) |
| Tambah Spot ke hari 3; footer menyisakan viewport dan membuka rencana terpilih | [Feedback](screenshots/spot-added-footer.png) |
| Removed member membaca notice sendiri tanpa kontrol anggota/Trip penuh | [Notice](screenshots/removed-member-notice.png) |
| Target 403 tetap unread dan tidak menampilkan konten privat | [Denied](screenshots/notice-forbidden.png) |
| GPS pending menampilkan retry eksplisit dan tidak menjadi form pertanyaan | [GPS retry](screenshots/gps-uncertain-retry.png) |

## Font dan tema

Dua skenario diulang pada setiap konfigurasi: geometri cover/gutter dan feedback/hari. Keduanya lulus; ini bukan audit seluruh aplikasi pada setiap konfigurasi.

- Font 130%: [builder](font130/itinerary-builder.png), [feedback](font130/spot-added-footer.png).
- Mode gelap: [builder](dark/itinerary-builder.png), [feedback](dark/spot-added-footer.png).

Emulator dipulihkan ke font 100% dan mode terang. TalkBack, perangkat fisik, tablet dan audit performa belum dijalankan.

## Mengulang pemeriksaan

Gunakan Gradle Wrapper dan konfigurasi Java/SDK di README root:

```powershell
.\gradlew.bat :shared:features:traveler:testAndroidHostTest
.\gradlew.bat :apps:android:traveler:assembleDebug :apps:android:partner:assembleDebug
.\gradlew.bat :apps:android:traveler:lintDebug :apps:android:partner:lintDebug
.\gradlew.bat :apps:android:traveler:connectedDebugAndroidTest
.\gradlew.bat :shared:features:traveler:testAndroidHostTest -PfloopelLiveSmoke=true -PfloopelApiOrigin=https://your-tunnel.ngrok-free.dev
```

Probe live hanya melakukan public read. Arsipkan XML sebelum run berikutnya karena probe dan suite host memakai direktori report yang sama. Gradle membersihkan APK test setelah connected run; untuk menyimpan PNG di luar run, pasang APK debug + androidTest dan jalankan AndroidJUnitRunner melalui adb, lalu pull direktori external-files/audit sebelum uninstall. Test tidak mengubah backend atau membutuhkan akun.

Raw XML/log lokal tersimpan pada .gradle/audit/traveler-android dan output build. iOS dan provider native tetap NOT_RUN. Status transaksi backend privat yang telah diuji diperbarui pada audit follow-up, bukan disimpulkan dari fixture baseline.
