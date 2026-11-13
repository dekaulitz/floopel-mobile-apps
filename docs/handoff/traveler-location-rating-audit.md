# Rating dan komponen lokasi Android

7 November 2026 · PR #1 · `feature/mobile-openapi-contracts` · baseline `0763ae2ef45e7e43e378b62c7073895683b62ed4`.

Batch ini menanggapi koreksi pemilik pada rating, Jelajahi lokasi dan detail lokasi. Figma menjadi acuan komponen. **Peta ditunda oleh pemilik**: “petanya nanti dulu. komponen yang lain saja di perbaiki”. Seksi peta belum dirender; tidak ditambahkan provider, dependensi atau peta ilustratif. Karena itu batch ini tidak menutup parity seluruh halaman lokasi.

## Acuan dan perubahan

| Komponen | Figma | Implementasi dan bukti |
| --- | --- | --- |
| Rating Home | [302:4856](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=302-4856) | Bintang 16 dp, gap 4 dp, teks 12/16 sp. Kartu Home 304×104 dp memakai judul 14/19 sp. [Capture](traveler-android-audit/location-rating-20261107/home-rating-compact.png). |
| Rating detail Spot | [36:805](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=36-805) | Bintang/chat 16 dp, gap 6 dp, nilai 14 medium dan komentar 14 regular pada baris 19 sp. Tinggi baris dipertahankan tanpa trim leading; grup dapat membungkus saat font besar. [Normal](traveler-android-audit/location-rating-20261107/spot-detail-rating.png), [130%](traveler-android-audit/location-rating-20261107/spot-detail-rating-font-130.png). |
| Kartu Jelajahi lokasi | [797:11125](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=797-11125) | Foto API 160×208 dp/radius 12, gradient, judul dan induk terdekat dari hierarki lokasi. [Capture](traveler-android-audit/location-rating-20261107/home-location-parent.png). |
| Semua Lokasi | [81:993](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=81-993), [negara 81:1151](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=81-1151), [kota 81:1291](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=81-1291), [pencarian 81:1446](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=81-1446) | Baris teks/ikon menggantikan katalog foto generik. Pilihan negara, daftar provinsi, breadcrumb kota dan pencarian global tetap memakai ID API. [Provinsi](traveler-android-audit/location-rating-20261107/locations-regions.png), [kota](traveler-android-audit/location-rating-20261107/locations-cities.png), [negara](traveler-android-audit/location-rating-20261107/locations-country-picker.png), [pencarian](traveler-android-audit/location-rating-20261107/locations-search.png). |
| Detail lokasi, selain peta | [53:945](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=53-945), [53:1149](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=53-1149) | Identitas lokasi dan jalur induk menggantikan hero/aksi Spot. Rail Spot, Quest belum diambil, Open Trip tersedia dan artikel memiliki sumber serta read state sendiri. [Identitas/Spot](traveler-android-audit/location-rating-20261107/location-detail-identity-and-spots.png), [Quest 130%](traveler-android-audit/location-rating-20261107/location-quest-rail-font-130.png), [Trip](traveler-android-audit/location-rating-20261107/location-trip-rail.png), [artikel](traveler-android-audit/location-rating-20261107/location-article-rail.png). |

Komponen `FloopelDiscoveryCard` pada [53:1096](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g?node-id=53-1096) dipakai sesuai Code Connect dan diperluas dengan konfigurasi consumer: kartu 280 dp, padding/gap 8 dp, thumbnail 80×80 dp/radius 8, serta tipografi instance. Default consumer lain dipertahankan. Ikon menggunakan `FloopelIcons` yang dipetakan Code Connect ke glyph Material rounded existing. Tidak ada aset statis baru; foto tetap data API/consumer. Placeholder pada capture fixture bukan foto Figma atau bukti provider gambar nyata.

Nilai rating tetap berasal dari referensi AI server; provenance tersedia pada semantics aksesibilitas. Nilai unknown/suppressed/invalid tidak menjadi rating nol. Jumlah komentar memakai nilai server; nol hanya tampil jika memang diketahui nol. Format koma desimal dan hitungan ringkas mengikuti resource. Tanggal Trip menggunakan tanggal lokal server, dengan format ringkas dan tahun tetap jelas saat rentang melewati tahun.

## State dan batas data

Parent lokasi disimpan terpisah dari daftar cakupan konten. Adapter membangun urutan induk terdekat hingga negara, menduplikasi fetch induk bersama dalam satu batch, memeriksa ID respons dan menghentikan siklus. Batas defensif delapan induk menghentikan chain server yang rusak tanpa negara akhir. Kegagalan parent opsional tidak mengarang nama.

Pilihan negara membaca seluruh halaman negara yang disetujui API, terpisah dari referensi nationality. Search dan pagination mempertahankan konteks. Request lama dibatalkan; guard route/generation tetap menolak hasil terlambat. Back dari detail/picker mengembalikan query asal. Breadcrumb negara diikat eksplisit ke negara yang sedang tampil, termasuk sesudah pencarian region lintas negara; root dengan `countryId` kosong tidak diperlakukan sebagai wildcard. [Regresi lintas negara](traveler-android-audit/location-rating-20261107/locations-cross-country-breadcrumb.png).

Seksi Quest memakai endpoint **belum diambil** yang sama pada rail dan See All/pagination. Open Trip mempertahankan `location_id` pada search/cursor. Artikel terkait mengirim filter `type=article` ke endpoint konten lokasi dan menolak respons berjenis lain. UI tetap melalui kontrak repository; tidak mengakses HTTP/DTO/database langsung.

## Verifikasi dan batas bukti

[Manifest hasil](traveler-android-audit/location-rating-20261107/results.json) memuat log, hash source/APK dan screenshot. Perintah memakai Gradle Wrapper serta workaround temporary Java Windows yang didokumentasikan pada README.

- **410 host test PASS**, 0 failure/error/skipped. Sembilan belas tambahan mencakup format angka/tanggal, parent lokasi, filter artikel, pilihan/pagination negara, Back/query, request lama yang tidak kooperatif, cooldown retry, error/empty rail dan navigasi lintas negara.
- **13 native test lokasi/rating PASS**: geometri Home/rating/detail, hierarki/picker/search/Back/breadcrumb, error/empty/recovery, rating unknown/suppressed, nama panjang 200% serta detail/Quest 130%.
- **20 native regresi PASS**: Quest state, journey, authentication, font katalog dan intro layar kecil. Semua native pada batch ini memakai production ViewModel/Compose dengan **domain doubles**; bukan backend/provider nyata.
- `assembleDebug` Traveler dan Partner serta APK androidTest Traveler **PASS**. `lintDebug` keduanya **PASS** tanpa error/fatal; Traveler 42 warning, Partner 2. Toolchain tidak di-upgrade.
- Android 15/API 35, `emulator-5580`, 400×900 dp/1080×2430 px/density 432, light theme, locale sistem en-US/resource default Indonesia. Konfigurasi perangkat awal 1080×2400/density 420 dipulihkan sesudah audit.
- iOS framework/host/test **NOT_RUN**: Windows, perlu Mac/Xcode. TalkBack, perangkat fisik dan parity seluruh aplikasi/font/state belum diverifikasi.

`source-manifest.json` mencatat bytes working tree terpilih, bukan klaim seluruh APK identik dengan suatu commit. Perubahan `styles.xml` yang sudah ada sebelum batch ikut dikompilasi dan hash-nya dicatat sebagai konteks build; perubahan tersebut tidak dimasukkan ke commit batch ini. `.gitignore` yang sudah berubah juga dipertahankan di working tree.

Smoke manual pada **MainActivity terpasang** menggunakan sesi akun fixture lokal yang sudah ada dan API ngrok: [rating Spot dengan data nyata](traveler-android-audit/location-rating-20261107/live-spot-rating.png), [detail Kota Bandung](traveler-android-audit/location-rating-20261107/live-location-detail.png), [artikel lokasi](traveler-android-audit/location-rating-20261107/live-location-articles.png), serta [daftar provinsi](traveler-android-audit/location-rating-20261107/live-location-regions.png). Picker negara, provinsi Jawa Barat dan kota/kabupaten juga dibuka. Setiap perpindahan diberi jeda 5 detik. Ini pemeriksaan visual/navigasi terbatas pada perangkat 1080×2400/density 420, terpisah dari 33 test fixture; bukan tambahan test otomatis atau pengujian seluruh state. Tidak ada mutasi backend/reset database pada smoke komponen ini.

Snapshot startup, state Quest, API privat dan layout sebelumnya tetap utuh. VD-LOC-01 mendapat implementasi hierarki/komponen pada batch ini; VD-LOC-02 mendapat koreksi **selain peta**. Backlog lama merupakan snapshot review, bukan daftar temuan yang otomatis semuanya ditutup. Explorer map/sheet, gallery Spot, reader artikel/Collection lengkap dan gap lain di luar komponen yang diminta tetap memerlukan tindak lanjut.
