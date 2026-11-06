# Kontrak API untuk aplikasi Floopel Mobile

Snapshot OpenAPI 3.1 untuk pengembangan Android Traveler, iOS Traveler, dan referensi Android Partner. File berasal dari kontrak backend Floopel pada 6 November 2026. Kontrak ini menyediakan bentuk request/response, parameter, security, status/error, pagination dan schema yang diperlukan client; integrasi API pada scaffold aplikasi tetap dikerjakan pada layer data fitur pemilik.

## File dan cakupan

| File | Server relatif | Operasi | Pemakaian |
| --- | --- | --- | --- |
| [openapi-mobile.json](openapi-mobile.json) | `/` | 13 | Identitas native: login, refresh, logout, Google/onboarding, registrasi, verifikasi email/OTP, username, nationality, recovery password dan context penghapusan akun. Path sudah lengkap dengan `/api/mobile/v1/auth/...`. |
| [openapi-apps.json](openapi-apps.json) | `/api/apps/v1` | 183 | Surface Apps lengkap: katalog Location/Spot/Collection/Content, pencarian/rekomendasi, profil/Passport, consent/legal, saved places, itinerary/publication/sharing, Trip/open trip/application, Quest/kelompok/attestation/execution, komentar/report dan notifikasi/push. |
| [openapi-console-partner.json](openapi-console-partner.json) | `/api/console-partner/v1` | 1 | Kontrak Partner yang tersedia saat snapshot: `GET /auth/csrf`. Ini belum menyediakan API bisnis atau autentikasi native Partner. |
| [snapshot.json](snapshot.json) | N/A | N/A | Revision sumber, status working tree, hash sumber/hasil ekspor, jumlah path/operasi dan normalisasi yang diterapkan. |

Total 197 operasi pada 171 path dalam tiga dokumen. Surface Apps dipertahankan lengkap agar schema/response/dependency tidak hilang saat fitur mobile berkembang. Operasi khusus browser, sitemap atau webhook/provider yang ada di dalamnya tetap mengikuti tujuan serta security operasinya; keberadaannya bukan instruksi agar aplikasi native memanggilnya. Kontrak console-admin tidak termasuk kebutuhan aplikasi mobile ini.

Partner Android belum mempunyai kontrak bisnis/native yang lengkap pada sumber backend. Jangan membuat endpoint, sesi Partner, booking atau payment berdasarkan asumsi dari scaffold atau token Traveler. Penambahan kemampuan tersebut memerlukan kontrak backend yang tersedia dan scope tersendiri.

## URL dan penggunaan dua kontrak Traveler

Gabungkan origin API environment dengan `servers.url` dan path operasi. Jangan menambahkan `/api/apps/v1` dua kali. Pada OpenAPI, server pada operation atau path item menggantikan server tingkat dokumen; perhatikan override ini untuk route di luar prefix Apps.

Contoh origin sintetis `https://api.example.invalid`:

- Native login: `POST https://api.example.invalid/api/mobile/v1/auth/login`.
- Katalog: `GET https://api.example.invalid/api/apps/v1/locations`.
- Profil pemilik: `GET https://api.example.invalid/api/apps/v1/me`.
- Eksekusi Quest: `POST https://api.example.invalid/api/apps/v1/mobile/gamification/quests/{quest_id}/start`.

Simpan kedua dokumen Traveler berdampingan. Kontrak native mereferensikan komponen bersama melalui `./openapi-apps.json`; membuka file secara terpisah tanpa sibling tersebut dapat menggagalkan resolver/codegen. Import setiap spec dengan base URI file yang benar. Tidak dibuat kontrak gabungan atau versi endpoint baru.

## Sesi, header dan akses

- Autentikasi native memakai operasi pada `openapi-mobile.json`, dengan `X-App-Platform: ANDROID` atau `IOS` sesuai host. Refresh/logout mengirim `refresh_token` pada body sesuai operasi. Token dan proof tidak disimpan pada source, BuildConfig, resource, log, URL atau contoh credential nyata.
- Metadata `X-App-Platform` dan `X-App-Version` berasal dari host/build. Metadata bukan bukti attestation atau otorisasi; signed channel token tetap diverifikasi server. Ikuti required/enum pada operasi, termasuk perbedaan native auth dan endpoint Apps bersama.
- Private Apps yang mendukung native memakai `Authorization: Bearer <native_access_token>` dengan security scheme `nativeBearer`. Jangan memakai cookie login Web sebagai sesi native atau mengirim cookie auth bersamaan dengan Bearer. Operasi browser tetap memiliki cookie/CSRF policy sendiri.
- Credential eksekusi Quest memakai `questExecutionBearer`, terpisah dari identity access token. Dapatkan melalui challenge/attestation/credential flow yang didefinisikan spec; identity token biasa tidak menggantikannya. Onboarding dan account-deletion context juga mempunyai purpose/lifetime tersendiri.
- CSRF berlaku sesuai operasi/channel yang didefinisikan kontrak. Jangan menyalin kebutuhan cookie/CSRF browser secara global ke seluruh native request atau menonaktifkan guard backend.
- `Idempotency-Key`, revision/version, expected timestamp dan header multipart mengikuti operasi terkait. Request yang hasilnya belum diketahui direkonsiliasi lewat kontrak recovery pemilik; jangan membuat key baru atau retry mutasi otomatis tanpa aturan operasi.
- Public/private access, consent, ownership, membership, moderation, state dan capability tetap diperiksa backend. Role UI atau keberadaan object ID bukan izin akses.

## Bentuk data dan respons

- JSON memakai envelope `meta/error/data`. Sukses membawa `error:null`; error membawa `data:null`. Pertahankan `meta.code` dan structured details; message bukan identifier percabangan. HTTP204 tidak diparse sebagai JSON. Binary/XML/redirect mengikuti response media type operasinya.
- Bedakan field absent, null dan array kosong. Jangan mengisi field required yang hilang dengan data fiktif. UUID, enum, timestamp, satuan dan nullable/required mengikuti schema.
- `locations[]` adalah projection server pada entitas yang mendefinisikannya; Spot tetap memakai `location_id` tunggal. Query `location_id` memilih wilayah beserta descendant menurut reader. Jangan mengirim field derived sebagai input atau mengubah geography public snapshot menjadi working plan privat.
- Body HTML Content/Quest yang memang rich text mengikuti kontrak sanitasi; field plain text tidak dianggap HTML. DTO transport dipetakan ke model domain dan state presentasi pada owner fitur.
- Descriptor media memakai logical ID, capability/variant dan metadata yang disediakan backend. Pilih variant sesuai penggunaan; jangan merakit storage key, menggunakan original/staging, atau menganggap metadata pemrosesan sama dengan delivery siap.
- Pertahankan pagination, cursor/count, source order, empty state dan error state sesuai operasi. Jangan menghitung total dari ukuran satu page atau memfilter ulang page untuk menggantikan filter server.
- Hormati `Retry-After`, no-store dan scope credential. HTTP401/403/409/422/429/503 mengikuti kode/meaning operasi; network failure atau timeout tidak membuktikan mutasi dibatalkan.

## Provenance dan pembaruan

Sumber: repository [backend Floopel](https://github.com/dekaulitz/com.github.floopel.prjct), folder `application/api-contract`. Snapshot ini diambil dari working tree dengan HEAD `58a570799e47222fb0529c2e5d869bbb152f0390`; Apps mencakup perubahan lokal yang belum ada pada commit tersebut. Karena itu revision HEAD saja tidak mengidentifikasi isi snapshot; gunakan hash setiap file di [manifest](snapshot.json).

File native dan Partner mempertahankan data kontrak sumber. Ekspor memakai UTF-8/LF. Pada Apps, satu response `GET /me/trips` 200 diperluas dari Reference Object menjadi Response Object agar header `Cache-Control:no-store` tetap ada dan struktur OpenAPI 3.1 valid. Isi response/schema tidak dibuang atau dilonggarkan; pointer dan alasan normalisasi dicatat pada manifest. File backend asli tidak diubah oleh PR snapshot ini.

Saat sinkronisasi berikutnya, cocokkan kontrak sumber terkini dan consumer yang terdampak, pertahankan relative reference, perbarui manifest/hash serta tinjau perubahan security/field/error/version. Validasi OpenAPI 3.1 dan resolver ketiga dokumen dari base URI file, lalu periksa contoh JSON yang berubah terhadap schema/format. Metadata historis `x-*` pada sumber tidak membuktikan runtime environment sudah memakai revision yang sama.

Snapshot/validasi kontrak tidak membuktikan backend telah dideploy, provider native tersedia atau aplikasi telah mengimplementasikan seluruh flow. Perubahan client berikutnya mengikuti [aturan repository](../AGENTS.md) dan verifikasi Android/iOS sesuai dampaknya.
