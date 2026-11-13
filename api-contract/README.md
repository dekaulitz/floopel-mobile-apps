# Kontrak API Mobile Traveler

Paket OpenAPI 3.1 untuk pengembangan Android Traveler dan iOS Traveler. Snapshot berasal dari kontrak backend pada branch lokal aktif `main` tanggal 6 November 2026, termasuk perubahan Apps dan identitas native yang belum di-commit. Paket memuat bentuk request/response, parameter, security, status/error, pagination dan schema untuk integrasi pada layer data fitur pemilik.

## File dan cakupan

| File | Server relatif | Operasi | Pemakaian |
| --- | --- | --- | --- |
| [openapi-mobile.json](openapi-mobile.json) | `/` | 13 | Identitas native: login, refresh, logout, Google/onboarding, registrasi, verifikasi email/OTP, username, nationality, recovery password dan context penghapusan akun. Path sudah lengkap dengan `/api/mobile/v1/auth/...`. |
| [openapi-apps.json](openapi-apps.json) | `/api/apps/v1` | 162 | API produk Traveler: katalog Location/Spot/Collection/Content, pencarian, profil/Passport, consent/legal, saved places, itinerary/publication/sharing, Trip/open trip/application, Quest/kelompok/attestation/execution, komentar/report, media dan notifikasi/push. |
| [snapshot.json](snapshot.json) | N/A | N/A | Branch/revision sumber, status working tree, hash sumber/hasil ekspor, jumlah path/operasi, seleksi operasi/security dan normalisasi. |

Total **175 operasi pada 151 path** dalam dua dokumen. Operasi Trip untuk organizer, anggota dan pelamar tetap termasuk kemampuan Traveler. Endpoint Apps `/auth/nationalities` dipertahankan karena dipakai `NativeAccountApi` pada branch lokal; `/auth/logout-all` menyediakan pengelolaan sesi Traveler dengan `nativeBearer`.

Seleksi mengeluarkan identitas Web yang mempunyai padanan native, sitemap, liveness, webhook provider, anonymous itinerary claim Web dan static page Web. Kontrak Console Admin dan Console Partner tidak disertakan. Daftar operasi yang dikeluarkan beserta alasannya tercatat pada manifest.

Security requirement hanya memuat alternatif native/public yang sudah tersedia pada kontrak sumber. Alternatif autentikasi cookie serta parameter cookie/CSRF Web dikeluarkan dari proyeksi ini. Komponen yang tidak lagi dirujuk dipangkas dengan menelusuri dependensi kedua dokumen bersama-sama; schema dan field yang masih digunakan tetap mengikuti sumber.

## URL dan penggunaan dua kontrak

Gabungkan origin API environment dengan `servers.url` dan path operasi. Jangan menambahkan `/api/apps/v1` dua kali. Server pada operation atau path item menggantikan server tingkat dokumen. Route delivery `/media/variants/{token}` dan `/media/restricted-variants/{token}` memakai override server `/`.

Contoh origin sintetis `https://api.example.invalid`:

- Native login: `POST https://api.example.invalid/api/mobile/v1/auth/login`.
- Katalog: `GET https://api.example.invalid/api/apps/v1/locations`.
- Profil pemilik: `GET https://api.example.invalid/api/apps/v1/me`.
- Eksekusi Quest: `POST https://api.example.invalid/api/apps/v1/mobile/gamification/quests/{quest_id}/start`.
- Media: `GET https://api.example.invalid/media/variants/{token}`.

Simpan kedua dokumen berdampingan. Kontrak native mereferensikan komponen bersama melalui `./openapi-apps.json`; import setiap spec dengan base URI file yang benar agar resolver/codegen menemukan referensi tersebut.

## Sesi, header dan akses

- Autentikasi native memakai operasi pada `openapi-mobile.json`, dengan `X-App-Platform: ANDROID` atau `IOS` sesuai host. Refresh/logout mengirim `refresh_token` pada body sesuai operasi. Token dan proof tidak disimpan pada source, BuildConfig, resource, log, URL atau contoh credential nyata.
- Metadata `X-App-Platform` dan `X-App-Version` berasal dari host/build. Metadata bukan bukti attestation atau otorisasi; signed channel token tetap diverifikasi server. Ikuti required/enum pada operasi, termasuk perbedaan native auth dan endpoint Apps bersama.
- Private Apps memakai `Authorization: Bearer <native_access_token>` sesuai security scheme `nativeBearer`. Onboarding memakai `nativeOnboarding`; context penghapusan akun memakai `nativeDeletionContext` sesuai kombinasi security dan header yang didefinisikan operasi.
- Credential eksekusi Quest memakai `questExecutionBearer`, terpisah dari identity access token. Dapatkan melalui challenge/attestation/credential flow yang didefinisikan spec. Onboarding dan account-deletion context juga mempunyai purpose/lifetime tersendiri.
- iOS memakai adapter App Attest existing dengan `QUEST_IOS_APP_ID` dan `QUEST_IOS_VERSIONS` yang lengkap. Server memverifikasi App ID, environment, distribution category dan versi; konfigurasi kosong menonaktifkan eksekusi iOS, konfigurasi parsial/invalid menolak startup. Snapshot tidak membuktikan konfigurasi environment atau perangkat sudah siap.
- Proyeksi security berlaku untuk consumer native. Deskripsi sumber yang membahas channel Web tetap dibaca sesuai channel; seleksi paket tidak mengubah policy cookie/CSRF pada backend.
- `Idempotency-Key`, revision/version, expected timestamp dan header multipart mengikuti operasi terkait. Request yang hasilnya belum diketahui direkonsiliasi lewat kontrak recovery pemilik; jangan membuat key baru atau retry mutasi otomatis tanpa aturan operasi.
- Public/private access, consent, ownership, membership, moderation, state dan capability tetap diperiksa backend. Role UI atau keberadaan object ID bukan izin akses.

## Bentuk data dan respons

- JSON memakai envelope `meta/error/data`. Sukses membawa `error:null`; error membawa `data:null`. Pertahankan `meta.code` dan structured details; message bukan identifier percabangan. HTTP204 tidak diparse sebagai JSON. Binary/redirect mengikuti response media type operasinya.
- Bedakan field absent, null dan array kosong. Jangan mengisi field required yang hilang dengan data fiktif. UUID, enum, timestamp, satuan dan nullable/required mengikuti schema.
- Visibilitas read itinerary mengikuti state distribusi `private`, `unlisted`, atau `public`; enum ini tidak memberi akses ke working plan pengguna lain. Guard owner atau publikasi eligible tetap berlaku.
- Durasi Stop 0 menit valid pada working plan dan publikasi. `duration_minutes:0` tetap nol pada snapshot publik; `null` berarti durasi tidak ditentukan, bukan nol.
- Hasil pencarian dan nearby Spot tanpa rating tetap mengirim `reference_rating:null`. Metadata media `placeholder.blurhash` dan `dominant_color` yang tidak tersedia dihilangkan pada pencarian, list/detail Spot, gallery dan nearby; bila hadir nilainya string.
- `GET /locations/{id}` menerima UUID atau slug. UUID menghasilkan `LocationDetail` dengan `geo`, `counts`, `children` dan `spots:[]`; slug menghasilkan `LocationPublicDetail` dengan `centroid_lat`, `centroid_lng`, `boundary` dan `timezone`. Pilih bentuk sesuai identifier yang diminta; jangan menganggap kedua proyeksi identik.
- `locations[]` adalah projection server pada entitas yang mendefinisikannya; Spot tetap memakai `location_id` tunggal. Query `location_id` memilih wilayah beserta descendant menurut reader. Jangan mengirim field derived sebagai input atau mengubah geography public snapshot menjadi working plan privat.
- Body HTML Content/Quest yang memang rich text mengikuti kontrak sanitasi; field plain text tidak dianggap HTML. DTO transport dipetakan ke model domain dan state presentasi pada owner fitur.
- Descriptor media memakai logical ID, capability/variant dan metadata yang disediakan backend. Pilih variant sesuai penggunaan; jangan merakit storage key, menggunakan original/staging, atau menganggap metadata pemrosesan sama dengan delivery siap.
- Pertahankan pagination, cursor/count, source order, empty state dan error state sesuai operasi. Jangan menghitung total dari ukuran satu page atau memfilter ulang page untuk menggantikan filter server.
- Hormati `Retry-After`, no-store dan scope credential. HTTP401/403/409/422/429/503 mengikuti kode/meaning operasi; network failure atau timeout tidak membuktikan mutasi dibatalkan.

## Handoff notifikasi ke reader berizin

Push membawa `notification_id`. Baca `GET /notifications/{notification_id}` untuk memperoleh `target_url` terkini dari registry server, lalu buka reader berdasarkan tujuan tersebut:

| Tujuan | Reader Apps | Identitas |
| --- | --- | --- |
| `/me/trip-notices/{notice_id}` | `GET /me/trip-notices/{notice_id}` | Lifecycle `member_removed`, `cancelled` atau `schedule_changed`; notice milik penerima. |
| `/trip-applications/{application_id}` | `GET /trip-applications/{application_id}` | Status pengajuan yang boleh dibaca penerima/organizer. |

`Notification.id` adalah ID inbox, berbeda dari ID source pada tujuan. Contoh `lifecycle_member_removed`, `lifecycle_schedule_changed_applicant` dan `lifecycle_cancelled_applicant` pada reader/list inbox berpasangan dengan named examples `OwnedTripNotice` pada reader status. Reader tetap memeriksa ownership dan tidak memberi akses Trip penuh kepada mantan anggota atau pemohon. `current_member` merupakan fakta terkini; pembukaan Trip penuh tetap memerlukan pemeriksaan akses server.

Kirim `PUT /notifications/{notification_id}/read` dengan **ID inbox asli** hanya setelah detail tujuan yang berizin berhasil ditampilkan. Loading, error, target null atau tujuan tidak tersedia tidak membuat acknowledgement. GET notifikasi/status tidak menandai read.

## Pemeriksaan paket

Jalankan dari root repository:

```sh
node api-contract/check-examples.mjs
node --test api-contract/check-examples.test.mjs
```

Guard semantik ini memakai Node bawaan dan menolak `example` + `examples` bersamaan pada Media Type/Parameter Object. Guard melengkapi validasi OpenAPI 3.1, resolver lintas dokumen dan validasi contoh JSON Schema/format; bukan pengganti pemeriksaan tersebut.

## Provenance dan pembaruan

Sumber: repository [backend Floopel](https://github.com/dekaulitz/com.github.floopel.prjct), folder `application/api-contract`. Snapshot diambil dari branch `feature/local-session-delivery-20261107` pada commit `1658157a5c076714bfe1ca55919d74ae7bb0027c`. Kontrak dan dependensi yang dicatat sudah berada pada commit sumber tersebut; gunakan hash setiap file pada [manifest](snapshot.json) untuk mengidentifikasi sumber dan hasil proyeksi secara tepat.

Ekspor memakai UTF-8/LF. Koreksi kontrak sudah diterapkan pada sumber lokal; dependensi DTO placeholder pencarian/Spot dan hash guard juga tercatat pada manifest. Response `GET /me/trips` 200 sudah berbentuk Response Object pada kontrak kanonis, dengan `Cache-Control:no-store`, sehingga ekspor tidak lagi memerlukan normalisasi response khusus. Field request/response yang dipertahankan cocok dengan sumber setelah seleksi channel native.

Saat sinkronisasi berikutnya, cocokkan kontrak pada branch lokal yang dituju dan kebutuhan Traveler, terapkan seleksi operasi/security pada manifest, lalu telusuri ulang komponen lintas dokumen. Perbarui hash/jumlah dan tinjau perubahan field/error/version. Validasi OpenAPI 3.1 serta seluruh relative reference dari base URI file dan periksa contoh JSON terhadap schema/format. Metadata historis `x-*` pada sumber tidak membuktikan runtime environment sudah memakai revision yang sama.

Snapshot/validasi kontrak tidak membuktikan backend telah dideploy, provider native tersedia atau aplikasi telah mengimplementasikan seluruh flow. Perubahan client berikutnya mengikuti [aturan repository](../AGENTS.md) dan verifikasi Android/iOS sesuai dampaknya.
