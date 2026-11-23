# 168Railway World

Fork publik dari circularsquare/trainsworld commit 405ecf55b1816994d7ec19a3a1834046db394940. Perubahan oleh 168Railway pada 18 November 2026. Repo ini berisi frontend Preact/TypeScript dan engine Rust/WASM dari fork. Kode server 168Railway berada di proyek terpisah.

## Menjalankan fork

Pasang Node, Rust, target wasm32-unknown-unknown dan wasm-pack. Dalam trainworld/app jalankan npm ci dan npm run build. Sajikan dist di /studio/ (sampai 19 November 2026 di /studio-fork/, kini dialihkan ke /studio/ oleh web 168Railway). index.html mematok tw-data pada /studio-data/2026-11-18/ di domain 168railway.com. Berkas data terbit dari pipeline distribusi terpisah. Lihat NOTICE.md untuk lisensi masing-masing sumber. Peta dasar dan sebagian imagery publik tetap memakai penyedia petak daring. Lapisan EOX tidak digunakan pada fork ini.

Progres dunia yang dimainkan saat masuk 168Railway disimpan di browser dan disinkronkan ke arsip akun setelah penyimpanan lokal berhasil. Dunia lokal lama ditautkan ketika dimainkan. Menu **Simpanan akun 168Railway** dan tautan `/studio/?world=<uuid>` dapat melanjutkan dunia dari perangkat lain. Perubahan wajib menyertakan identitas akun dan revisi. Saat terjadi konflik, progres peramban dan versi perangkat lain tersedia untuk dipilih. Peramban mempertahankan slot dan cadangan sebelumnya saat memuat revisi akun yang lebih baru. Permainan tetap dapat disimpan secara lokal saat luring. Backend menggunakan berkas TWG1 gzip asli dengan batas 8 MiB per dunia dan 16 dunia per akun.

## Simulasi Studio dalam aplikasi Android

Setelah kedua WASM Studio tersedia, jalankan `npm run build:native-clock` dari `trainworld/app`. Salin seluruh isi `dist-native-clock/` menjadi aset APK `world-native/clock/`. Android membuka `https://studio-clock.invalid/clock/index.html` dengan WebView, menunggu `window.__studioClockReady`, lalu mengirim satu MessagePort. Kotlin mengirim arsip TWG1, perintah jalan atau jeda, dan permintaan snapshot. Worker Studio memakai rumus permintaan, tarif dan biaya yang sama dengan web. Android menggambar setiap gerbong dari posisi kedua bogie di lintasan pada waktu yang dikirim worker. Di zoom menengah, satu badan mengikuti lengkungan rel. Di zoom dekat, gerbong terpisah dan kabin depan terlihat. Isian rangkaian membaca muatan perjalanan dari perhitungan penumpang Studio. Saat dijeda, peta dapat membaca muatan dari salinan dunia tanpa mengubah jam atau kas.

Sediakan `/clock/packs/` dari rilis data yang sama dengan katalog Android. Permintaan mencakup kota tempat stasiun berada, pasar daerah tetangga, batas wilayah, serta data bersama seperti `outlines.json` dan `market-drives.bin`. Permintaan bidang data biner lewat WebView memakai `?studioRange=awal-akhir` dengan akhir inklusif. Gateway mengembalikan byte bidang itu dengan status 200.

Untuk uji dengan Chrome, OpenSSL, Playwright dan salinan lokal data Jakarta, jalankan dari `trainworld/` perintah `node checks/168railway-native-clock.mjs data/packs/real/jak_bogor.save /path/ke/packs`. Variabel `PLAYWRIGHT_MODULE` menunjuk modul Playwright jika dipasang di luar proyek. Uji memainkan satu jam, memeriksa penumpang, tarif, biaya operasional dan perawatan, menjeda simulasi, lalu membuka kembali arsip hasil ekspor.

Pada Android 2.0.14, menu Kelola membuka diagram jaringan, pengaturan, tutorial, dan lapisan penumpang langsung pada peta Android. Pemeriksa stasiun memuat editor bangunan dan lorong. Progres tutorial dan perubahan jaringan disimpan bersama dunia TWG1.

Peta Android memuat jalan, bangunan, taman dan perairan dari OpenFreeMap/OpenStreetMap. APK membundel 78 petak Jabodetabek pada beberapa tingkat zoom, dengan detail jalan di pusat Jakarta. Untuk kota lain, buka dan perbesar daerah yang dibutuhkan sebelum luring. Petak yang selesai dimuat dapat dibaca kembali setelah aplikasi ditutup selama cache masih tersedia. Editor juga membawa garis pantai Natural Earth. Perjalanan dan ekonomi Normal membutuhkan data permintaan kota yang sesuai. Dengan data yang belum lengkap saat dibuka, editor menahan waktu agar pemain dapat mengubah jaringan dan menyimpan perubahan tanpa menghitung pemasukan dari data parsial. Untuk menguji antarmuka editor, tambahkan argumen `editor` pada perintah uji di atas. Uji ini mencakup grup lini, grafik Marey, diagram jaringan dan PNG, pemeriksa potongan rel, serta perjalanan. Argumen `layers` menguji sepuluh pilihan Peta penumpang dari menu Android dan memastikan tombol terkait terpilih pada toolbar Studio. Argumen `tutorial` menguji lanjutan kartu tersembunyi, pilihan untuk mengulang panduan yang selesai, dan panduan Sandbox.

## Atribusi

Kode upstream © 2026 Anita Chen, AGPL-3.0. Lihat LICENSE serta NOTICE.md. Nama layanan, ikon, halaman privasi/kredit dan integrasi akun dikelola 168Railway. Kode sumber tersedia di https://github.com/manyunyu7/168railway-world . Kode dan perubahan dapat diperoleh dengan mengkloning repo ini. Tampilan awal dan pengaturan mencantumkan tautan tersebut.

Empat bunyi konstruksi dibuat secara sintetis dengan trainworld/tools/fork/build-sounds.py, dengan lisensi kode fork. Berkas rekaman Pixabay milik versi asli tidak digunakan.

## Uji simpanan

trainworld/checks/168railway-host-saves.mjs menguji UI Chrome seluler, berkas gzip TWG1 asli dari worker Rust/WASM, konflik pembaruan, pergantian sesi, isolasi akun dan pembukaan salinan lokal. Atur FORK_URL ke URL frontend uji, FORK_DATA ke direktori dataset QA, serta PLAYWRIGHT_MODULE ke instalasi playwright-core. Secara default respons API akun menggunakan fixture HTTP dalam konteks browser. FORK_QA_HOST=1 hanya mengizinkan localhost dan menggunakan login Laravel /login/proc dengan dua akun sementara beralamat test-a@example.invalid dan test-b@example.invalid di database QA terisolasi. Jangan gunakan kredensial pengguna.

Pada 18 November 2026, pengujian dengan SQLite dan penyimpanan lokal Laravel yang terisolasi menghasilkan save New York 10.462 byte. Akun kedua tidak dapat membaca daftar atau menulis simpanan akun pertama. Pengujian juga meliputi CSRF sesi lama dan penulisan dua perangkat dari versi yang sama.

Tambahkan FORK_IMPORT_SAVE=/path/to/nyc.save pada uji host lokal untuk membuka file kompatibel melalui akun sebelum menguji perbaruan versi. Berkas 81.788 byte keluaran modul native NYC telah diuji pada Chrome, melalui login dan penyimpanan akun Laravel.

Ukur interval requestAnimationFrame pada dunia terbuka dengan FORK_RAF=1 dan FORK_HEADED=1 (Chrome terlihat). Pada 18 November 2026 di Mac Apple M6, macOS 27, Chrome 157.0.8103.79, viewport 390×844 DPR2, save NYC 661 kereta SHA256 b6b6b8379e9231563002012b36a75cd82ef5f19b94abb8e1ab2a4366a31b5742, dua sampel singkat per 4,5 detik menghasilkan 56,0 fps saat jalan (p95 31,7 ms) serta 45,7 fps saat roda zoom (p95 50,3 ms). Mode headless menghasilkan 24,7 / 21,4 fps. Sampel ini mencakup pemrosesan permintaan perjalanan awal dan belum memvalidasi 60 fps di ponsel fisik.

The optional Noritetsu regional map overlay is unavailable in the pinned provider release. Its discovery and tile requests are disabled. The mirrored official city packs, rail graphs and terrain remain available.
