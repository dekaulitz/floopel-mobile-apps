# Traveler Android: notifikasi, profil, Spot, dan navbar

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts` · baseline `5a79bffc4ba798d20529e5912a3fcb1307c0b92a`.

Perubahan ini mengikuti Figma sebagai sumber desain untuk komponen yang disebutkan pemilik. Foto, identitas, jumlah, relasi, capability dan status tetap berasal dari API. Data contoh Figma tidak dijadikan fallback runtime. Peta tetap ditunda sesuai keputusan pemilik. Ini pemeriksaan bagian aplikasi yang berubah, bukan sign-off seluruh aplikasi atau kesiapan rilis.

## Acuan dan implementasi

| Area | Frame Figma | Perubahan |
| --- | --- | --- |
| Primary navigation | [5:380](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=5-380) | Satu komponen bersama untuk Home, Community, Explorer, Open Trip dan Profile. Ikon Material Rounded 24 dp, indikator 56×34 dp, label 11 sp/15 sp, aktif Medium dan primary. Lima target selebar sama, bar sekitar 77 dp pada font normal dengan pembulatan pixel native; tinggi dapat bertambah saat font besar. Subpage memakai Back tanpa navbar; login tidak menampilkan navbar. System gesture/status bar tetap milik OS. |
| Inbox | [386:7783](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=386-7783), 389:7778–9760 | Toolbar 56 dp, filter 48 dp, kartu radius 14, ilustrasi SVG resmi, loading/empty/error/append-retry/opening/unavailable dan preview. Timestamp memakai locale UI dan zona waktu perangkat. Device notification menampilkan state provider unavailable 389:9760 karena FCM belum dihubungkan. |
| Profil pribadi | [5:368](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=5-368), 116:2506–3461 | Header ikut scroll, avatar 72, aksi compact, Passport/progress dari batas level server, empat row Perjalanan dan tiga row Akun. Settings, editor, minat, keamanan, kontak darurat dan preferensi memakai repository existing. Field preferensi yang tidak didukung server tidak dibuat bernilai false. |
| Profil publik | [22:514](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=22-514): 22:522/662/799/936/1088 | Avatar 64, identitas/Passport, tab owner Itinerary/Pribadi/Saved Spot/Badge; visitor hanya konten publik. Itinerary privat memakai repository owner; Saved dapat dihapus berdasarkan konfirmasi server. Badge memakai pagination API. Pergantian tab mengulang read header/progress yang dibatalkan agar loading tidak tersangkut. |
| Semua Spot | [24:674](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=24-674): 24:681/887 | Header/search/scope, grup kategori, thumbnail 88 dp dan rating list. Scope lokasi diterapkan API sebelum pagination; scoped empty tidak beralih ke global. Total server digunakan bila tersedia; jumlah kategori hanya ditampilkan saat seluruh halaman telah dimuat. Tepat 1.000 komentar tetap `1.000`, sesudahnya compact. |
| Spot Detail | [39:916](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=39-916): 36:696, 37:983/762 | Toolbar Back/Saved/Share, hero 240, galeri thumbnail 96, kategori/nama/lokasi/rating, tambah ke rencana dan komentar. Nearby horizontal, Quest/Collection/artikel berupa slider, Open Trip vertikal. Relasi Quest/artikel/Trip memakai ID Spot, bukan daftar global atau tebakan lokasi. Sheet komentar terikat bawah, maksimum 680 dp termasuk inset navigasi native, thread/balasan/like, composer dan draft yang bertahan ketika sheet ditutup. |

Context high-fidelity, screenshot dan Code Connect diperiksa. Komponen yang cocok di `core:designsystem` dipakai/ditambah tanpa dependency, DI, navigation framework atau toolchain baru. Aset statis inbox disimpan lokal dengan ukuran root SVG asli; tidak ada URL aset temporer Figma pada runtime. Media API tetap dinamis. Map dan status bar ilustratif Figma tidak dipakai sebagai gambar layar aplikasi.

## Perilaku dan batas data

- Inbox list, GET target, preview dengan `target_url=null`, target gagal 403/404, URL tak didukung dan respons terlambat tidak mengubah status baca. Hanya reader tujuan yang berizin dan benar-benar telah merender yang dapat mengakui ID inbox asli. Lookup terikat route/actor; ID lookup yang berbeda ditolak.
- Read inbox yang gagal setelah cached empty menampilkan error dan Retry, bukan state kosong lama. Kegagalan append mempertahankan baris yang sudah berhasil dibaca dan hanya mengulang GET. Filter memakai classification server.
- Owner-only controls memerlukan identitas akun dan username publik yang cocok serta read yang selesai; backend tetap memverifikasi akses. Visitor tidak mengambil private plans/Saved Spot. Badge dan Saved menggunakan cursor/page repository yang tersedia.
- Komentar membatasi 1–100 Unicode code point, tidak mengirim ulang otomatis, mencegah submit ganda selama pending dan mempertahankan draft. Network/5xx/hasil tak diketahui memblokir kirim ulang intent yang sama dan menampilkan pesan di dalam modal; Retry membaca thread. Draft tidak masuk saved navigation. Like dan total komentar memakai konfirmasi server. Recheck legal dilakukan sebelum POST.
- Copy kurasi legacy yang secara tepat memiliki blok ID/EN dipilih sesuai locale. Recognition dibatasi pada konten `curated` dan format yang tidak ambigu; konten pengguna tidak dipotong. Preview kartu maksimum dua baris, reader tetap menerima body terpilih penuh. Ini kompatibilitas format legacy, bukan kontrak localization backend baru.

## Verifikasi

Perintah Gradle Wrapper pada working tree final:

```powershell
.\gradlew.bat :shared:features:traveler:testAndroidHostTest :apps:android:traveler:assembleDevelopmentDebug :apps:android:partner:assembleDevelopmentDebug :apps:android:traveler:assembleDevelopmentDebugAndroidTest :apps:android:traveler:lintDevelopmentDebug :apps:android:partner:lintDevelopmentDebug --no-parallel --max-workers=2
```

Hasil dan log final dicatat pada [manifest](traveler-android-audit/figma-notifications-profile-spots-20261107/results.json). Screenshot fixture native memakai production Compose/ViewModel dengan repository domain sintetis pada emulator API 35, 400×900 dp, tanpa ketergantungan jaringan untuk foto fixture. Gambar placeholder di capture fixture disengaja; smoke MainActivity/backend nyata dilaporkan terpisah. Screenshot Figma bukan aset implementasi dan test fixture tidak masuk APK production.

| Pemeriksaan final | Hasil |
| --- | --- |
| Host Traveler | **PASS: 441 test**, 0 failure/error/skipped |
| Native Android | **PASS: 43 test** dalam satu eksekusi final: 13 Figma, 13 journey, 13 lokasi, 2 publikasi, 1 font, 1 autentikasi |
| Build developmentDebug Traveler + Partner dan APK androidTest | **PASS** |
| Lint developmentDebug | **PASS**: Traveler 42 warning, Partner 2 warning, masing-masing 0 error; warning lama tidak disembunyikan |
| UI/native | Ukuran dan seleksi navbar di semua lima tab, icon/indicator geometry, label 200%, Back tanpa navbar, profil 130%, modal terikat bawah, inbox/empty/error/lookup dan draft komentar diperiksa |
| iOS/provider/production release | **NOT_RUN** atau belum terkonfigurasi sebagaimana rincian di bawah |

Kegagalan yang ditemukan selama iterasi diperbaiki sebelum eksekusi final: error refresh setelah cached empty, posisi modal yang sebelumnya terikat area atas akibat constraint pada dialog, line-height/ruang label navbar dan langkah uji logout yang masih mencari tombol di halaman lama. Test lazy content memakai API scroll node; capture modal mengambil window perangkat agar kedua root tercakup.

Smoke MainActivity memakai login akun fixture existing ke API ngrok (public read HTTP 200), dengan jeda minimal lima detik setiap transisi: lima root tab, profil pribadi/publik, inbox kosong, state perangkat belum tersedia, Semua Spot, Spot Jalan Braga dan read komentar nyata. Kartu lokasi kini memakai nama detail lokasi sebagai judul dan ancestors terpisah, sehingga provinsi/negara tidak berulang. Galeri nyata berhasil memuat gambar API. Tidak ada kirim komentar, like, simpan profil/preferensi atau mutasi fitur lain; login membuat sesi normal. Viewport emulator dipulihkan ke 1080×2400, density 420 dan font 100%. [Ringkasan smoke](traveler-android-audit/figma-notifications-profile-spots-20261107/live-smoke.json) memisahkan lima tab sebelum koreksi label lokasi terakhir dari smoke Spot/comment final; suite native final memverifikasi kelima tab pada APK final.

Batch ini menambahkan 21 state test untuk filtering/ack/cancellation/actor, owner/visitor/tab race, geographic scope, relasi Spot, komentar dan outcome tak diketahui, konfirmasi write serta batas XP/tanggal. Tiga test copy kurasi dan assertion format count melindungi koreksi copy. Suite native menguji geometri/interaksi inbox, profil, semua Spot, Spot Detail/modal dan navbar, ditambah regresi journey, lokasi, publikasi, autentikasi dan font.

## Pekerjaan yang belum dibuktikan atau belum dihubungkan

- iOS framework/host/test: **NOT_RUN**, Windows; formatter iOS baru dan common UI memerlukan Mac/Xcode/CI Mac. Android tidak membuktikan iOS.
- Push delivery/registrasi token FCM belum terhubung; state unavailable disengaja. Tidak ada klaim izin OS/FCM aktif. Peta tetap ditunda pemilik.
- Ubah foto profil, bahasa dan hapus akun belum dihubungkan pada UI baru; kontrolnya dinonaktifkan. Share mengirim teks aktual melalui native host; canonical public web URL belum tersedia. Status/progress Quest aktif pada kartu relasi Spot belum ditampilkan karena kontrak yang dipakai belum memberi stable Quest ID untuk korelasi; CTA membuka reader.
- Pengujian native menggunakan repository double untuk kondisi error/konkurensi; ini bukan bukti POST komentar/preferences/profile pada backend nyata, email/provider delivery atau worker. Smoke nyata batch ini bersifat read/navigation.
- Form akun baru masih menyimpan draft lokal di composition; pergantian configuration dapat kehilangan perubahan belum disimpan. Password tidak dipersistenkan. Draft komentar dikelola ViewModel dan bertahan saat sheet ditutup, tetapi bukan jaminan pemulihan process death.
- TalkBack/VoiceOver, perangkat fisik, seluruh mode gelap/ukuran layar, nested moderation/hidden comment state dan like-error UI belum diaudit menyeluruh. Quest Detail/checkpoint five-star serta gap builder/Trip/reader lain dari audit sebelumnya masih terpisah.

Edit `.gitignore` (`.env/`) dan `styles.xml` yang sudah ada sebelum batch dipertahankan, tidak dimasukkan ke commit ini. `styles.xml` ikut terkompilasi sehingga hash-nya dicatat sebagai konteks artifact. Source backend/schema tidak diubah; database tidak di-reset atau dimigrasikan. Smoke login memakai sesi normal dari akun fixture existing. Merge dan rilis tidak dilakukan.
