# Environment aplikasi Floopel

Android memakai dimension `environment` dengan flavor `development` dan `production`. Namespace tetap stabil; application ID serta penyimpanan instalasi berbeda. Konfigurasi mengikuti [Android build variants](https://developer.android.com/build/build-variants). Library KMP tetap satu; native host memasukkan konfigurasi runtime.

| Produk | Development | Production |
| --- | --- | --- |
| Traveler | `com.floopel.traveler.dev` / Floopel Traveler Dev | `com.floopel.traveler` / Floopel Traveler |
| Partner | `com.floopel.partner.dev` / Floopel Partner Dev | `com.floopel.partner` / Floopel Partner |

Template: [development](development.env.example) dan [production](production.env.example). Copy ke nama yang berakhiran `.env` jika perlu; file lokal tersebut diabaikan Git. Gradle membaca **environment proses**, bukan otomatis memuat file `.env`. Export melalui shell atau secret/environment CI. Ubah nilai lalu build ulang APK; nilai client bukan dibaca dari environment komputer ketika APK berjalan di Android.

Untuk PowerShell, salin template, isi nilai lokal, lalu impor melalui helper yang tidak mengeksekusi isi file dan tidak mencetak nilainya:

```powershell
Copy-Item config/environments/development.env.example config/environments/development.env
# Edit development.env, lalu jalankan dari shell yang akan menjalankan Gradle:
.\tools\config\import-mobile-environment.ps1 -Environment development
.\gradlew.bat :apps:android:traveler:assembleDevelopmentDebug
```

Untuk production gunakan `production.env.example`, `production.env` dan `-Environment production`. Jangan menjalankan `Copy-Item` kembali pada file yang sudah diisi. Format helper adalah `KEY=value`, komentar satu baris diawali `#`, dengan kutip tunggal/ganda opsional. Nilai diperlakukan literal: tidak ada ekspansi `$VAR`, escape shell atau komentar di akhir nilai. Helper menolak variabel environment lain dan nama duplikat sebelum mengubah environment proses. Menjalankannya lewat proses PowerShell baru tidak mengekspor nilai kembali ke shell induk; gunakan shell yang sama atau mekanisme environment/secret CI.

```powershell
$env:FLOOPEL_DEVELOPMENT_API_ORIGIN = 'https://your-development-tunnel.ngrok-free.dev'
.\gradlew.bat :apps:android:traveler:installDevelopmentDebug

$env:FLOOPEL_PRODUCTION_API_ORIGIN = 'https://your-approved-production-api.example'
.\gradlew.bat :apps:android:traveler:assembleProductionDebug
```

Origin contoh di atas bukan endpoint Floopel yang diasumsikan tersedia. Production tidak memakai fallback development. Production Traveler menolak build tanpa origin HTTPS eksplisit. Production release kedua produk juga mensyaratkan empat nilai signing produknya. Tidak ada perubahan dependency/toolchain atau konfigurasi store/signing sungguhan pada template ini.

Gunakan path absolut untuk `KEYSTORE_FILE` dan file konfigurasi provider. Build `productionDebug` tetap memakai debug signing Android; hanya `productionRelease` yang memakai signing production. Jangan mendistribusikan artifact debug sebagai rilis store.

## Konfigurasi yang sudah dikonsumsi

Ganti `<ENV>` dengan `DEVELOPMENT` atau `PRODUCTION`.

| Environment variable | Pemakaian | Status jika kosong |
| --- | --- | --- |
| `FLOOPEL_<ENV>_API_ORIGIN` | Origin API Traveler | Development memakai emulator loopback; production gagal sebelum build |
| `FLOOPEL_<ENV>_GOOGLE_SERVER_CLIENT_ID` | Google Sign-In, public server client identifier | Google Sign-In tidak tersedia |
| `FLOOPEL_<ENV>_PLAY_CLOUD_PROJECT_NUMBER` | Play Integrity, project number publik | `0`: eksekusi Quest belum dikonfigurasi |
| `FLOOPEL_TRAVELER_VERSION_CODE` / `VERSION_NAME` | Versi aplikasi Traveler | `1` / `0.1.0` |
| `FLOOPEL_PARTNER_VERSION_CODE` / `VERSION_NAME` | Versi aplikasi Partner | `1` / `0.1.0` |
| `FLOOPEL_<PRODUCT>_PRODUCTION_KEYSTORE_FILE` | File signing, `PRODUCT=TRAVELER/PARTNER` | Production release ditolak |
| `FLOOPEL_<PRODUCT>_PRODUCTION_KEYSTORE_PASSWORD` | Password keystore | Production release ditolak |
| `FLOOPEL_<PRODUCT>_PRODUCTION_KEY_ALIAS` | Alias key | Production release ditolak |
| `FLOOPEL_<PRODUCT>_PRODUCTION_KEY_PASSWORD` | Password key | Production release ditolak |

API/provider juga dapat diisi dengan properti Gradle spesifik environment: `floopelDevelopmentApiOrigin`, `floopelProductionApiOrigin`, `floopelDevelopmentGoogleServerClientId`, `floopelProductionGoogleServerClientId`, `floopelDevelopmentPlayCloudProjectNumber`, `floopelProductionPlayCloudProjectNumber`. Prioritas: properti spesifik → environment variable spesifik → default development. Properti lama `floopelApiOrigin`, `floopelGoogleServerClientId`, `floopelPlayCloudProjectNumber` menjadi fallback **development saja**.

Google/Integrity harus terdaftar untuk package dan signature flavor yang tepat. Package `.dev` memerlukan registrasi tersendiri. Identifier client/API origin dapat terlihat dalam APK. Password signing, credential backend, token akun, private key dan service-account JSON tidak menjadi BuildConfig/resource.

## Konfigurasi yang disiapkan untuk integrasi berikutnya

| Variable template | Kebutuhan | Status implementasi |
| --- | --- | --- |
| `FLOOPEL_<ENV>_GOOGLE_MAPS_API_KEY` | Key Android Maps SDK yang dibatasi package/signature dan API | Disiapkan; peta ditunda pemilik, belum dikonsumsi dan SDK belum dipasang |
| `FLOOPEL_<ENV>_GOOGLE_SERVICES_JSON` | Path `google-services.json` **client Android** untuk app ID flavor tersebut | Disiapkan; FCM transport/registration belum dihubungkan, belum dikonsumsi |
| `FLOOPEL_<ENV>_PUBLIC_WEB_ORIGIN` | Origin web publik untuk share/deep link kanonis | Disiapkan; perlu origin serta kontrak path publik, belum dikonsumsi |
| `FLOOPEL_<ENV>_IOS_GOOGLE_CLIENT_ID` | OAuth client ID khusus aplikasi iOS | Disiapkan; adapter Sign-In dan URL scheme iOS belum dihubungkan |
| `FLOOPEL_<ENV>_IOS_GOOGLE_SERVICE_INFO_PLIST` | Path konfigurasi client Firebase iOS | Disiapkan; binding Xcode/FCM/APNs belum dihubungkan |
| `FLOOPEL_<ENV>_IOS_GOOGLE_MAPS_API_KEY` | Key Maps khusus bundle ID iOS | Disiapkan; peta ditunda, belum dikonsumsi |

Mengisi ketiga variable tersebut tidak otomatis mengaktifkan Maps, FCM atau tautan publik. Saat integrasinya dikerjakan, konsumsi build/runtime, pembatasan key, binding app ID dan test provider harus ditambahkan. Inbox in-app tetap terpisah dari izin/delivery push. Template ini tidak menambahkan Sentry, analytics atau provider yang belum dipilih proyek.

iOS masih memerlukan konfigurasi host/scheme terpisah, client Firebase `.plist`, key Maps iOS dan signing Apple pada Mac/Xcode. Flavor Gradle Android tidak mengonfigurasi host iOS. Variabel iOS di atas adalah nama yang dicadangkan untuk pipeline berikutnya; implementasi dan verifikasi iOS belum dilakukan. APNs authentication key/certificate dikonfigurasi pada Firebase/server, bukan dimasukkan ke aplikasi.

Registrasi provider memerlukan langkah di console: project Firebase terpisah untuk development/production, app Android dengan package flavor yang tepat, dan SHA certificate debug/release/Play App Signing yang digunakan distribusinya. Google Sign-In memakai server client ID bertipe Web untuk audience backend; client Android didaftarkan dengan package/SHA. Maps key dibatasi ke aplikasi Android dan API yang diperlukan. `google-services.json` berisi konfigurasi client; private key service account untuk pengiriman FCM tetap di server. Rujukan: [Firebase per environment](https://firebase.google.com/docs/projects/multiprojects), [setup Android Firebase](https://firebase.google.com/docs/android/setup), [Maps API key restrictions](https://developers.google.com/maps/documentation/android-sdk/get-api-key), dan [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).

Backend tetap memiliki environment tersendiri untuk database, token/signing sesi, OAuth verification, FCM HTTP v1/service account dan pipeline media. Nilai server tidak disalin ke template mobile. Integrasi backend mengacu ke `.env.example` dan config service yang sesuai; template mobile tidak mengganti konfigurasi backend yang sedang berjalan.

Nama berikut telah dicocokkan dengan `.env.example` dan loader `internal/infrastructure/config/config.go` service lokal; isi dan lifecycle-nya dikelola pada backend per environment:

| Fitur | Environment backend terkait |
| --- | --- |
| Google native/web | `GOOGLE_ANDROID_SERVER_CLIENT_ID`, `GOOGLE_CLIENT_ID`; flow OAuth web memakai `GOOGLE_OAUTH_CLIENT_SECRET` dan `GOOGLE_OAUTH_REDIRECT_URI` |
| FCM HTTP v1 | `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS` (file service account server) |
| Quest Android / Play Integrity | `QUEST_ANDROID_PACKAGE`, `QUEST_ANDROID_CERTIFICATE_DIGESTS`, `QUEST_PLAY_SERVICE_ACCOUNT`; `QUEST_ANDROID_LOCAL_EXECUTION` khusus lokal sesuai validasi backend |
| Quest iOS / App Attest | `QUEST_IOS_APP_ID`, `QUEST_IOS_VERSIONS` |
| Aktivasi akun, OTP dan pemulihan akun lewat email | `EMAIL_PROVIDER`, `EMAIL_FROM`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `EMAIL_LINK_ALLOWED_HOSTS`; provider `log` hanya untuk lokal |
| Media avatar/cover/Trip | `MEDIA_STORE_PROVIDER`, `MEDIA_STORE_DIR`, `MEDIA_BASE_URL`, `MEDIA_WORKER_ENABLED`, `MEDIA_WORKER_POLL_MS`, `MEDIA_VIPS_EXECUTABLE`; provider Supabase memakai `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET`, `SUPABASE_PUBLIC_BUCKET` |
| Origin web / tautan email | `TRAVELER_WEB_BASE_URL`, `ADMIN_CONSOLE_BASE_URL`, `TRAVELER_DEFAULT_LOCALE`, `CORS_ALLOWED_ORIGINS` |
| Sesi, data akun dan penyimpanan | `POSTGRES_DSN`, `REDIS_ADDR`, `REDIS_PASSWORD`, `REDIS_DB`, `JWT_ES256_PRIVATE_KEY`, `JWT_ES256_PUBLIC_KEYS`, `ENC_KEY`, `BLIND_INDEX_PEPPER` |
| Penghapusan akun dan arsip privasi | `ACCOUNT_ERASURE_ARCHIVE_PROVIDER`, `ACCOUNT_ERASURE_ARCHIVE_DIRECTORY`, `ACCOUNT_ERASURE_ARCHIVE_SUPABASE_BUCKET`, `ACCOUNT_ERASURE_ARCHIVE_KEY`, `ACCOUNT_ERASURE_ARCHIVE_KEY_VERSION`, `ACCOUNT_ERASURE_GRACE_DAYS`, `EVENT_PRIVACY_POSTGRES_DSN` |
| Pekerja notifikasi / media / event | `BACKGROUND_WORKERS_DISABLED`, `OUTBOX_POLL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_ATTEMPTS`, `OUTBOX_RETRY_BASE_MS`, `OUTBOX_RETRY_MAX_MS`, `CONSUMER_POLL_MS`, `CONSUMER_BATCH_SIZE` |

Fitur itinerary, publikasi, admission Trip, Quest, profil dan inbox memakai origin API yang sama; tidak diperlukan credential per fitur di aplikasi. URL upload/hasil media berasal dari API. Database, worker dan private key Firebase/OAuth tetap berada pada service, bukan environment client APK. Daftar ini memetakan fitur mobile ke konfigurasi backend, bukan pengganti seluruh konfigurasi operasional service. Secret backend mendukung mekanisme `*_FILE` yang ada pada loader service; jangan memasukkan file secret atau nilainya ke template mobile.
