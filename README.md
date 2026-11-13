# Floopel Mobile Apps

Monorepo **Kotlin Multiplatform (KMP)** dan **Compose Multiplatform**. Android Traveler memakai runtime native dan UI/logika `commonMain`. Partner tetap host Android terpisah. Host iOS Traveler masih memakai layar scaffold; wiring layanan native dan verifikasi Mac merupakan tahap berikutnya.

## Kontrak API backend

[Paket OpenAPI Mobile Traveler](api-contract/README.md) menyediakan kontrak identitas native dan API produk untuk Android/iOS Traveler, beserta panduan URL, autentikasi/header dan provenance dari branch backend lokal. Kontrak menjadi acuan adapter di layer data Traveler. Status implementasi, bukti Android, dan bagian yang masih tertunda tercatat pada [handoff Android](docs/handoff/traveler-android.md).

## Skill, rules, dan desain arsitektur

Standar engineering disimpan bersama source agar dapat ditinjau dan digunakan tim:

| Dokumen | Kegunaan |
| --- | --- |
| [Skill Floopel Mobile Engineering](.agents/skills/floopel-mobile-engineering/SKILL.md) | Workflow implementasi, refactor, dan review yang mengikuti konteks produk/platform |
| [AGENTS.md](AGENTS.md) | Instruksi repositori yang ringkas untuk pekerjaan agent |
| [Aturan engineering](.agents/skills/floopel-mobile-engineering/references/engineering-rules.md) | Aturan wajib/default untuk Kotlin, data, coroutine, keamanan, UI, test, dan rilis |
| [Desain arsitektur](.agents/skills/floopel-mobile-engineering/references/architecture.md) | Batas modul, state/lifecycle, native adapter, design system, dan evolusi modular |
| [Matriks verifikasi](.agents/skills/floopel-mobile-engineering/references/verification.md) | Pemeriksaan sesuai dampak serta bukti Android/iOS yang diperlukan |
| [Referensi dan alasan](.agents/skills/floopel-mobile-engineering/references/sources.md) | Sumber resmi, keputusan lokal, alasan, dan tradeoff; ditinjau 6 November 2026 |

Skill berada di `.agents/skills/floopel-mobile-engineering` untuk penemuan dalam konteks repo. Contoh pemakaian:

```text
Gunakan $floopel-mobile-engineering untuk menambahkan pencarian destinasi Traveler.
```

Standar ini menjadi panduan implementasi dan review. Build/test Android tidak membuktikan kesiapan provider, transaksi backend privat, iOS, signing, atau rilis. CI dan branch protection perlu konfigurasi tersendiri.

| Aplikasi | Platform | Application / bundle ID | Entry point |
| --- | --- | --- | --- |
| Traveler | Android | `com.floopel.traveler` | `apps/android/traveler` |
| Partner | Android | `com.floopel.partner` | `apps/android/partner` |
| Traveler | iOS | `com.floopel.traveler` | `apps/ios/traveler` |

## Struktur folder

```text
floopel-mobile-apps/
├── apps/
│   ├── android/
│   │   ├── traveler/                 # Android application: Traveler
│   │   │   ├── build.gradle.kts
│   │   │   └── src/main/
│   │   │       ├── AndroidManifest.xml
│   │   │       ├── kotlin/com/floopel/traveler/MainActivity.kt
│   │   │       └── res/values/        # Nama aplikasi dan Android launch theme
│   │   └── partner/                  # Android application: Partner
│   │       ├── build.gradle.kts
│   │       └── src/main/
│   │           ├── AndroidManifest.xml
│   │           ├── kotlin/com/floopel/partner/MainActivity.kt
│   │           └── res/values/
│   └── ios/
│       └── traveler/                 # Native iOS host untuk Compose Traveler
│           ├── FloopelTraveler.xcodeproj/
│           │   └── xcshareddata/xcschemes/
│           ├── Configuration/Config.xcconfig
│           └── Traveler/
│               ├── FloopelTravelerApp.swift
│               ├── ContentView.swift
│               └── Info.plist
├── shared/
│   ├── core/
│   │   ├── model/                    # Model lintas produk/platform, tanpa UI
│   │   │   ├── build.gradle.kts
│   │   │   └── src/commonMain/kotlin/com/floopel/core/model/
│   │   └── designsystem/             # Tema dan komponen Compose bersama
│   │       ├── build.gradle.kts
│   │       └── src/commonMain/kotlin/com/floopel/core/designsystem/
│   │           ├── component/
│   │           └── theme/
│   └── features/
│       ├── traveler/                 # KMP: Android + iOS
│       │   ├── build.gradle.kts
│       │   └── src/
│       │       ├── commonMain/kotlin/com/floopel/feature/traveler/
│       │       │   ├── TravelerApp.kt
│       │       │   ├── core/network/    # Transport internal milik Traveler
│       │       │   ├── feature/auth/    # Pemilik sesi internal
│       │       │   ├── data/
│       │       │   ├── domain/
│       │       │   └── presentation/
│       │       │       ├── TravelerRoot.kt
│       │       │       ├── TravelerState.kt
│       │       │       ├── TravelerViewModel.kt
│       │       │       └── home/, catalog/, quest/, itinerary/, trip/, account/
│       │       ├── androidMain/kotlin/com/floopel/feature/traveler/
│       │       └── iosMain/kotlin/com/floopel/feature/traveler/
│       │           └── MainViewController.kt
│       └── partner/                  # Android library, tidak memiliki target iOS
│           ├── build.gradle.kts
│           └── src/main/kotlin/com/floopel/feature/partner/
│               ├── PartnerApp.kt
│               ├── data/
│               ├── domain/
│               └── presentation/
│                   ├── home/PartnerHomeScreen.kt
│                   └── navigation/
├── gradle/
│   ├── libs.versions.toml            # Versi plugin, SDK, dan dependency
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

Traveler kini memiliki kontrak domain, adapter API/session, state holder dan UI untuk katalog, Quest, itinerary, publikasi, Trip/admission serta inbox. Host Android menangani Android Keystore, lokasi, Photo Picker, Credential Manager dan Back. Fixture hanya berada pada source test. Partner dan host iOS masih scaffold; booking/payment belum diimplementasikan. Lihat [ADR runtime](docs/adr/0001-traveler-android-runtime.md) dan [provenance source](docs/handoff/traveler-source-provenance.json).

## Batas tanggung jawab modul

- `apps`: konfigurasi aplikasi, lifecycle native, manifest, izin, dan signing. UI produk berada di modul fitur.
- `shared/core/model`: model yang benar-benar dipakai bersama. Tidak bergantung pada fitur atau Compose.
- `shared/core/designsystem`: warna, tema, dan komponen UI bersama. Menggunakan `AppProduct` untuk membedakan tema Traveler dan Partner.
- `shared/features/traveler`: UI dan logika Traveler yang dikompilasi untuk Android serta iOS. Framework iOS bernama `TravelerKit`.
- `shared/features/partner`: UI dan logika Partner khusus Android. Letaknya di kelompok fitur agar dapat menggunakan core bersama, tetapi modul ini tidak ikut dalam framework iOS.

Dependensi aplikasi:

```text
Android Traveler ──> features:traveler ──> core:designsystem ──> core:model
iOS Traveler     ──> TravelerKit       ──> (fitur Traveler yang sama)
Android Partner  ──> features:partner  ──> core:designsystem ──> core:model
```

Di dalam masing-masing fitur:

- `domain`: model bisnis, kontrak repository, dan use case; jaga agar bebas dari API platform dan UI.
- `data`: implementasi repository, API/DTO, penyimpanan lokal, dan mapper; mengikuti kontrak di `domain`.
- `presentation`: screen, state, ViewModel, dan navigasi; menggunakan use case atau kontrak dari `domain`.
- `commonMain`: implementasi yang tersedia di kedua platform.
- `androidMain` / `iosMain`: adapter atau implementasi khusus platform. Tambahkan direktori sumber ini di modul core bila dibutuhkan.

Untuk fitur baru yang mandiri, misalnya autentikasi atau booking, buat modul di `shared/features/<nama-fitur>` lalu daftarkan di `settings.gradle.kts`. Fitur tidak boleh bergantung pada modul `apps` atau saling bergantung secara melingkar. Infrastruktur bersama seperti network atau database dapat ditambahkan sebagai modul `shared/core` ketika sudah diperlukan.

## Toolchain

| Komponen | Versi konfigurasi |
| --- | --- |
| Kotlin / Compose Compiler | 2.4.0 |
| Compose Multiplatform | 1.11.0 |
| Material 3 Multiplatform | 1.9.0 (versinya terpisah dari Compose) |
| Android Gradle Plugin | 9.1.0 |
| Gradle Wrapper | 9.3.1 |
| Java | 17 atau versi kompatibel yang lebih baru |
| Android compile / target SDK | 36 |
| Android minimum SDK | 24 |
| iOS minimum deployment target | 16.0 |
| Target Kotlin/Native | `iosArm64`, `iosSimulatorArm64` |

AGP 9 menggunakan Kotlin bawaan untuk modul Android biasa. Modul KMP menggunakan `com.android.kotlin.multiplatform.library`, sedangkan modul aplikasi Android tetap terpisah. Referensi: [plugin Android untuk KMP](https://developer.android.com/kotlin/multiplatform/plugin), [kompatibilitas Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), dan [rilis Compose 1.11.0](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.11.0).

## Menjalankan Android

1. Buka root repositori melalui Android Studio yang mendukung AGP 9.1, lalu lakukan Gradle Sync.
2. Siapkan JDK, Android SDK Platform 36, dan Build Tools 36.0.0. Tentukan `JAVA_HOME` serta `ANDROID_HOME`, atau isi lokasi Android SDK melalui `local.properties` (tidak di-commit).
3. Pilih modul `apps.android.traveler` atau `apps.android.partner`, pilih variant `developmentDebug`, lalu jalankan di emulator/perangkat.

Build kedua APK debug dari PowerShell:

```powershell
.\gradlew.bat :apps:android:traveler:assembleDevelopmentDebug :apps:android:partner:assembleDevelopmentDebug
```

APK dihasilkan di `apps/android/<traveler|partner>/build/outputs/apk/development/debug/`. Gunakan task `:apps:android:traveler:installDevelopmentDebug` atau `:apps:android:partner:installDevelopmentDebug`. Development memakai package `.dev`; production mempertahankan ID utama. Rincian, signing, provider dan template environment ada pada [konfigurasi environment](config/environments/README.md).

### Konfigurasi Android Traveler

Traveler wajib login sebelum Home dan fitur aplikasi. Aplikasi menunggu pemulihan sesi; sesi valid dapat dilanjutkan, sedangkan logout atau sesi tidak valid kembali ke login. Registrasi, verifikasi dan pemulihan sandi tetap tersedia dari login. Figma final adalah sumber kebenaran visual; bukti koreksi komponen dan gap tersisa ada pada [audit UI](docs/handoff/traveler-ui-audit.md).

Debug default memakai backend lokal `http://10.0.2.2:8080` untuk emulator. Untuk tunnel ngrok yang aktif, ambil origin HTTPS dari ngrok lalu bangun ulang APK:

```powershell
$env:FLOOPEL_DEVELOPMENT_API_ORIGIN = 'https://your-tunnel.ngrok-free.dev'
.\gradlew.bat :apps:android:traveler:installDevelopmentDebug
```

Jangan menambahkan path `/api/apps/v1` pada origin. Production memerlukan `FLOOPEL_PRODUCTION_API_ORIGIN` atau `floopelProductionApiOrigin` berupa HTTPS; tidak mengambil fallback development. Production release juga memerlukan signing environment produknya. Cleartext debug dibatasi ke loopback/emulator; tidak ada trust-all certificate. Backend dan tunnel tetap memiliki environment tersendiri.

Identifier publik provider dipisahkan melalui `FLOOPEL_<ENV>_GOOGLE_SERVER_CLIENT_ID` dan `FLOOPEL_<ENV>_PLAY_CLOUD_PROJECT_NUMBER`, dengan `<ENV>` = `DEVELOPMENT`/`PRODUCTION`. Google Sign-In/Quest execution memerlukan registrasi untuk package flavor (`com.floopel.traveler.dev` atau `com.floopel.traveler`), signature/distribusi dan backend yang sesuai. Nilai kosong/0 menampilkan kondisi belum tersedia. Properti lama tetap menjadi fallback development saja; lihat matriks environment untuk nama lengkap dan integrasi yang masih disiapkan.

Refresh credential dan intent recovery disimpan terenkripsi dengan Android Keystore, dipisahkan menurut origin API, dikecualikan dari backup, dan dibersihkan ketika logout. Intent yang hasilnya belum diketahui tidak ditulis ulang otomatis. Untuk memulihkan unggahan setelah proses hilang, pilih kembali foto yang sama; journal mencocokkan SHA-256 dan memakai key lama, tanpa menyimpan foto/URI pada saved state.

### Kendala direktori temporary Java di Windows

Pada environment Windows saat pembuatan scaffold, Gradle mengalami `Unable to establish loopback connection` sebelum membaca proyek. Build dan lint berhasil setelah direktori temporary Java ditentukan secara eksplisit. Jika mengalami error yang sama, jalankan konfigurasi berikut di sesi PowerShell yang digunakan untuk build, lalu ulangi perintah Gradle:

```powershell
$javaTemp = Join-Path $env:USERPROFILE '.gradle\floopel-java-tmp'
New-Item -ItemType Directory -Force -Path $javaTemp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp"
```

Konfigurasi ini berlaku untuk sesi terminal tersebut. Jika `JAVA_TOOL_OPTIONS` sudah berisi opsi lain, pertahankan opsi tersebut saat menambahkan dua properti di atas.

## Menjalankan iOS Traveler

Host iOS belum menerima wiring runtime Android pada perubahan ini. Kompilasi dan pengujian iOS berstatus `NOT_RUN` di Windows. Build iOS membutuhkan **macOS, Xcode yang kompatibel dengan Kotlin 2.4.0 (26.4 pada matriks kompatibilitas), dan JDK**. Simulator menggunakan Mac Apple Silicon; target simulator Intel tidak disediakan.

```sh
open apps/ios/traveler/FloopelTraveler.xcodeproj
```

Pilih scheme **FloopelTraveler** dan simulator iPhone, lalu Run. Untuk perangkat fisik, atur Apple Development Team melalui **Signing & Capabilities**.

Build Phase **Build Kotlin Framework** otomatis menjalankan `:shared:features:traveler:embedAndSignAppleFrameworkForXcode` sebelum kompilasi Swift. `ContentView.swift` memuat UI Compose melalui `MainViewController.kt`. Konfigurasi framework dan bundle ID berada di `Configuration/Config.xcconfig`; CocoaPods tidak diperlukan. Integrasi mengikuti [panduan direct integration Kotlin](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html).

Contoh build simulator dari root repositori di Mac:

```sh
xcodebuild -project apps/ios/traveler/FloopelTraveler.xcodeproj \
  -scheme FloopelTraveler -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

## Pemeriksaan

```powershell
.\gradlew.bat projects
.\gradlew.bat :apps:android:traveler:assembleDevelopmentDebug :apps:android:partner:assembleDevelopmentDebug
.\gradlew.bat :apps:android:traveler:lintDevelopmentDebug :apps:android:partner:lintDevelopmentDebug
```

Suite host memuat test bisnis/kontrak deterministik, sedangkan instrumentation memakai fixture domain dan tidak membuktikan provider/backend privat:

```powershell
.\gradlew.bat :shared:features:traveler:testAndroidHostTest
.\gradlew.bat :apps:android:traveler:connectedDevelopmentDebugAndroidTest
```

Probe publik nyata bersifat opt-in dan hanya melakukan public read:

```powershell
.\gradlew.bat :shared:features:traveler:testAndroidHostTest -PfloopelLiveSmoke=true -PfloopelApiOrigin=https://your-tunnel.ngrok-free.dev
node api-contract/check-examples.mjs
node --test api-contract/check-examples.test.mjs
```

Run probe memakai direktori report Gradle yang sama dengan run host; arsipkan report sebelum run berikutnya. Tidak ada fixture credential yang otomatis membuat akun backend.

Pada macOS/Linux, ganti `.\gradlew.bat` dengan `sh ./gradlew`. Build iOS harus diperiksa melalui Xcode pada Mac.
