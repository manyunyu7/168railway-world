# Jalan dan perairan Jakarta

Panel Buat layanan di host Android memanggil mode 4 untuk feri dan mode 5 untuk bus. Rute dan pergerakan disimpan oleh TrackApi asli dalam TWG1. Pemain dapat membuka, menjalankan, dan menyimpan kembali dunia offline setelah jaringan terbentuk.

Feri mengambil batas darat/air dari paket kota yang sudah diunduh. Dermaga dicari hingga 300 m dari ketukan pemain. Penelusuran A* memakai kisi 25 m, menolak sudut diagonal yang tertutup dan memeriksa sampel di antara dua sel. Kanal sempit bisa tidak muncul dalam raster. Pencarian berhenti setelah 150.000 simpul. Ketuk titik tepi air yang lebih dekat atau tahan untuk menambah titik lintasan.

Bus memakai data jalan dari **© OpenStreetMap contributors**, berlisensi ODbL, melalui OpenFreeMap / skema OpenMapTiles. Perangkat mengunduh tile MVT zoom 14 dari paket planet OpenFreeMap versi 20261115_080022_pt melalui HTTPS saat pertama membuat rute itu. Cache privat menggunakan nama rilis yang sama sehingga versi tile tidak bercampur. Unduhan tidak mencakup seluruh kota. Jika pemasok tidak lagi menyediakan versi itu dan cache lokal kosong, pembangunan rute baru memerlukan pembaruan paket. Atribusi dan lisensi data: https://www.openstreetmap.org/copyright dan https://openfreemap.org/.

Modul mengambil decoder roadTiles, sambungan tile, pencarian terarah RoadGraph, dan penyederhanaan lintasan dari kode web asli. Pembangun mencoba titik jalan terdekat dalam 100 m dengan batas detour web; pencarian kandidat alternatif dan pelacakan putaran balik khusus dari UI web belum tersedia. Data jalan diproses dalam QuickJS lokal (tanpa DOM), pada thread latar. Hanya kode dari APK yang dievaluasi; tile eksternal diteruskan sebagai ArrayBuffer. Per layanan berlaku batas 64 tile, 40 MiB data terurai, 160 MiB memori QuickJS, dan waktu komputasi 30 detik. World menyimpan geometri jalan hasilnya sehingga memutar ulang bus tidak mengambil tile lagi. Peta belum menggambar semua jalan di sekeliling rute.

Untuk memperbarui bundle JavaScript dari sumber terbuka yang sama, jalankan dari root:

```sh
npx --yes esbuild@0.25.12 trainworld/native-android/roads/planner.ts --bundle --target=es2020 --format=iife --global-name=Roads --outfile=trainworld/native-android/roads/planner.js
```

Dari trainworld/native-android, pengujian fixture asli menggunakan NATIVE_CITY_RELEASE berisi jak.json, jak.bin dan terrain/jak.terrain. WORLD_ROADS menunjuk folder dengan tile 14-{x}-{y}.pbf yang sudah didekompresi. Fixture Jakarta pusat memerlukan x=13052–13054, y=8473–8475. Jalankan:

```sh
cargo test --release --locked jakarta_ferry -- --ignored
cargo test --release --locked jakarta_bus -- --ignored
```
