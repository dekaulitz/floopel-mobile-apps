# ADR 0001: runtime Traveler dan UI bersama

7 November 2026 · diterapkan pada implementasi Traveler Android.

Repo memiliki modul KMP Traveler dan dua host Android, tetapi belum memiliki network, state holder atau persistence. Paket handover menunjuk implementasi Traveler dalam repo sumber lain. Menyalin seluruh aplikasi Android lama akan mengikat UI ke Android dan membawa struktur yang berbeda.

Layer domain/data/session Traveler yang relevan diadaptasi ke modul Traveler yang ada; hash source tersimpan pada provenance. UI baru menggunakan commonMain, repository domain, state immutable dan ViewModel multiplatform. Host Android menyediakan metadata build, penyimpanan credential/recovery terenkripsi, Back dan integrasi native. Partner tidak masuk graf Traveler.

Ktor 3.4.0, serialization 1.9.0 dan coroutines 1.10.2 mempertahankan versi baseline adapter. Lifecycle multiplatform 2.10.0 memberi ownership ViewModel dan collection lifecycle; Compose Resources melokalkan teks; Coil3 memuat media dinamis, bukan fixture fallback. Semua versi ada pada katalog. Tidak ditambahkan framework DI/database/navigation; komposisi memakai constructor dan back stack ID kecil.

Konsekuensi: port source perlu diverifikasi ulang terhadap snapshot API, termasuk error/envelope, credential purpose, idempotency/recovery, null vs absent, dan session isolation. Test deterministik serta emulator harus melengkapi build/lint. Tidak ada klaim backend, provider, iOS atau rilis dari keberhasilan Android. Rollback dapat mengembalikan entry host ke scaffold tanpa mengubah backend atau kontrak ekspor.

Referensi: [ViewModel KMP](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html), [Ktor KMP](https://ktor.io/docs/client-create-multiplatform-application.html), [Resources](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html), dan aturan A1–A8/R1–R10 repository. Versi yang dipilih tetap baseline proyek, bukan klaim versi terbaru.
