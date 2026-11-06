# Kontrak API Mobile Traveler

Paket OpenAPI 3.1 untuk pengembangan Android Traveler dan iOS Traveler. Snapshot berasal dari kontrak backend pada branch lokal aktif `main` tanggal 6 November 2026, termasuk perubahan Apps yang belum di-commit. Paket memuat bentuk request/response, parameter, security, status/error, pagination dan schema untuk integrasi pada layer data fitur pemilik.

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
- Proyeksi security berlaku untuk consumer native. Deskripsi sumber yang membahas channel Web tetap dibaca sesuai channel; seleksi paket tidak mengubah policy cookie/CSRF pada backend.
- `Idempotency-Key`, revision/version, expected timestamp dan header multipart mengikuti operasi terkait. Request yang hasilnya belum diketahui direkonsiliasi lewat kontrak recovery pemilik; jangan membuat key baru atau retry mutasi otomatis tanpa aturan operasi.
- Public/private access, consent, ownership, membership, moderation, state dan capability tetap diperiksa backend. Role UI atau keberadaan object ID bukan izin akses.

## Bentuk data dan respons

- JSON memakai envelope `meta/error/data`. Sukses membawa `error:null`; error membawa `data:null`. Pertahankan `meta.code` dan structured details; message bukan identifier percabangan. HTTP204 tidak diparse sebagai JSON. Binary/redirect mengikuti response media type operasinya.
- Bedakan field absent, null dan array kosong. Jangan mengisi field required yang hilang dengan data fiktif. UUID, enum, timestamp, satuan dan nullable/required mengikuti schema.
- `locations[]` adalah projection server pada entitas yang mendefinisikannya; Spot tetap memakai `location_id` tunggal. Query `location_id` memilih wilayah beserta descendant menurut reader. Jangan mengirim field derived sebagai input atau mengubah geography public snapshot menjadi working plan privat.
- Body HTML Content/Quest yang memang rich text mengikuti kontrak sanitasi; field plain text tidak dianggap HTML. DTO transport dipetakan ke model domain dan state presentasi pada owner fitur.
- Descriptor media memakai logical ID, capability/variant dan metadata yang disediakan backend. Pilih variant sesuai penggunaan; jangan merakit storage key, menggunakan original/staging, atau menganggap metadata pemrosesan sama dengan delivery siap.
- Pertahankan pagination, cursor/count, source order, empty state dan error state sesuai operasi. Jangan menghitung total dari ukuran satu page atau memfilter ulang page untuk menggantikan filter server.
- Hormati `Retry-After`, no-store dan scope credential. HTTP401/403/409/422/429/503 mengikuti kode/meaning operasi; network failure atau timeout tidak membuktikan mutasi dibatalkan.

## Provenance dan pembaruan

Sumber: repository [backend Floopel](https://github.com/dekaulitz/com.github.floopel.prjct), folder `application/api-contract`. Snapshot diambil dari branch lokal `main` dengan HEAD `58a570799e47222fb0529c2e5d869bbb152f0390`. Apps mencakup perubahan lokal yang belum ada pada commit tersebut; gunakan hash setiap file pada [manifest](snapshot.json) untuk mengidentifikasi sumber dan hasil proyeksi secara tepat.

Ekspor memakai UTF-8/LF. Pada Apps, response `GET /me/trips` 200 diperluas dari Reference Object menjadi Response Object agar header `Cache-Control:no-store` tetap ada dan struktur OpenAPI 3.1 valid. Bentuk response/schema tetap dipertahankan; pointer dan alasan normalisasi dicatat pada manifest. Backend sumber tidak diubah oleh ekspor ini.

Saat sinkronisasi berikutnya, cocokkan kontrak pada branch lokal yang dituju dan kebutuhan Traveler, terapkan seleksi operasi/security pada manifest, lalu telusuri ulang komponen lintas dokumen. Perbarui hash/jumlah dan tinjau perubahan field/error/version. Validasi OpenAPI 3.1 serta seluruh relative reference dari base URI file dan periksa contoh JSON terhadap schema/format. Metadata historis `x-*` pada sumber tidak membuktikan runtime environment sudah memakai revision yang sama.

Snapshot/validasi kontrak tidak membuktikan backend telah dideploy, provider native tersedia atau aplikasi telah mengimplementasikan seluruh flow. Perubahan client berikutnya mengikuti [aturan repository](../AGENTS.md) dan verifikasi Android/iOS sesuai dampaknya.
