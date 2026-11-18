# New York di Android native

Prototipe ini membuka berkas asli `T-007-real-nyc.save` di Activity Android. Rust `trainworld-sim` di direktori sebelah berjalan sebagai instruksi ARM64 melalui JNI. Kotlin menggambar stasiun, geometri jalur dan posisi perjalanan dari Rust. Tombol Jalankan memajukan waktu permainan delapan kali kecepatan nyata. Jeda dan perpindahan aplikasi menghentikan jam. Cubit untuk memperbesar, seret untuk menggeser, dan ketuk dua kali untuk mengembalikan tampilan awal.

Tombol Simpan menulis `TWG1` gzip menggunakan `TrackApi.save()` dan format header asli. Membuka kembali Activity melanjutkan berkas lokal terakhir. Tombol simpan ini belum tersambung ke akun. Peta memuat rel, nama stasiun dan perairan NYC secara offline. Dalam menu Dunia bangun, ketuk dua lokasi untuk stasiun A dan B serta beri nama layanan. Engine membuat ruas ganda, peron dan perjalanan terjadwal. Simpanan dunia bangun memakai slot tersendiri. Di panel Layanan, cek keberangkatan atau atur jumlah kereta, selang keberangkatan, susunan rangkaian, waktu berhenti, balik arah, dan persentase akhir pekan.

## Build modul

Gunakan Rust stable, target `aarch64-linux-android`, Android SDK 36 dan NDK r28c. Set `ANDROID_NDK_HOME` ke NDK, jalankan `./build-arm64.sh` dari direktori ini. Skrip menyiapkan library JNI, fixture dan atribusi di `build/`. Sertakan `android-lib` sebagai modul Android Library Gradle dengan AGP 8.12 dan Kotlin Android 2.2. Activity yang didaftarkan oleh manifest library bernama `world.nativegame.NativeWorldActivity`. Buka dengan intent eksplisit dari dalam aplikasi yang menyertakan library. Activity tidak diekspor.

Untuk memeriksa mesin di komputer, jalankan `cargo test --locked --release -- --nocapture`. Untuk mengulang pemeriksaan pada perangkat ARM64, tetapkan `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` ke clang NDK untuk API 24, jalankan `cargo test --locked --release --target aarch64-linux-android --no-run`, kemudian salin executable tes ke `/data/local/tmp` menggunakan adb dan jalankan dengan `--nocapture`. Dengan fixture asli, pada waktu 111600 tes menemukan 109 layanan, 1.756 stasiun dan 661 kereta. Tes juga memeriksa dua koordinat kereta terhadap hasil desktop serta posisi seluruh armada setelah simpan dan buka kembali.

## Data dan atribusi

Skenario New York disertakan di repo upstream. Peta perairan dibuat dari mask New York 25 m dalam paket upstream 18 November 2026, yang bersumber dari U.S. Census Bureau, TIGER/Line 2020 AREAWATER dan batas negara bagian. Gambar ringkas memiliki resolusi 125 m per piksel. Skrip `make-water.py` menghasilkan gambar dan metadata yang menyimpan URL sumber, rentang byte dan SHA-256. Untuk mereproduksi dari berkas lokal, berikan JSON header pack NYC dan berkas byte range sebagai argumen. [Sumber perairan Census](https://www2.census.gov/geo/tiger/TIGER2020/AREAWATER/)

Kode Rust upstream dan modifikasi dicakup oleh lisensi proyek, GNU AGPL v3. Lihat LICENSE dan NOTICE.md pada akar repo.

### Jaringan kota pada Android

Menu **Kota** menawarkan 37 kota yang memiliki simpanan jaringan asli pada rilis 2026-11-18. Pada pembukaan pertama, perangkat mengambil jaringan, sel permintaan, pasar, air, dan kelas medan dari mirror 168Railway. Katalog SHA-256 yang tertanam menentukan ukuran dan isi tiap berkas. Cache dan simpanan tiap kota berada di penyimpanan internal aplikasi. Setelah unduhan selesai, dunia itu dapat dibuka tanpa internet. Bengkel menggunakan slot tersendiri. Starter New York yang disertakan tetap dapat dibuka sejak pemasangan tanpa internet.

Untuk membuat ulang katalog, unduh manifest dari `https://168railway.com/studio-data/2026-11-18/manifest.json` lalu jalankan `python3 scripts/city-catalog.py /path/to/manifest.json`. Skrip memeriksa hash manifest publik sebelum menulis katalog aplikasi. Batas wilayah dan kualitas data mengikuti rilis yang dijelaskan dalam DATA-CREDITS.txt dan CHANGES.txt pada mirror.

Tes JVM menggunakan direktori berisi struktur unduhan Tokyo dan London. Jalankan `javac -d /tmp/world-tests android-lib/src/main/java/world/nativegame/CityPackStore.java tests/CityPackStoreTest.java` lalu `java -cp /tmp/world-tests world.nativegame.CityPackStoreTest /path/to/city-release`. Jalankan `NATIVE_CITY_RELEASE=/path/to/city-release cargo test --locked --release` untuk membandingkan lokasi, perjalanan, air, medan, dan pemuatan ulang kedua jaringan asli dengan kernel Rust.
