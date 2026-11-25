# Android and web fork acceptance

Reference: circularsquare/trainsworld at 405ecf55b1816994d7ec19a3a1834046db394940, data 2026-11-18. The public fork web reads these datasets and runs the upstream track, demand and diagram WASM. Android uses Kotlin and original Rust TrackApi over JNI. The table tracks the native implementation. A core test alone does not count as Android UI verification.

| Feature in the reference | Android evidence | Remaining acceptance |
|---|---|---|
| Start menu, city picker, real maps | 4,135 selectable zones; 37 real-network starters plus seven bundled Jakarta/Indonesia scenarios. New empty Bandung and the Jakarta–Bogor scenario reopen on Android and web. | Verify every network on-device, cross-zone play, search, camera state, basemap, imagery, accessibility and attribution. |
| Topography, water, gradients, road network | Native pack loads original terrain and water. Vector basemap draws OpenFreeMap roads, water, buildings and green areas; 78 bundled Jabodetabek tiles. Cold Android startup verified in airplane mode without a tile cache in Gondangdia. Bandung city and street views matched after offline restart using cached tiles. Tokyo/London engine projections and water samples tested. | Compare tunnel, viaduct, ferry and street geometry, prices and overlays at multiple zoom levels. |
| Blueprint, construct and remove track, stations, lines | Native workshop builds connected stations, reuses transfer stops and creates running lines. | Match preview, curvature, track tiers, parallel tracks, demolition refunds, undo, station structures, underground levels and inspections. |
| Rail service | Real NYC, London and Tokyo schedules move trains. Native form edits fleets, cars, headways, weekend, dwell and turnaround. | Compare peak transitions, depot/turnback, loop services, platforms, junction conflict and capacity. |
| Ferry, bus, tram, high-speed | Jakarta creates commuter rail, high-speed rail, dedicated tram track, coastal ferries and road-following buses. Original TrackApi reopens all five. Real Jakarta fixtures cover coastal route water, movement, and a 39-point road route. | On-device touch QA for road and water modes, street-running trams and the web tool’s alternate road snaps. |
| Local residential/commuter and everyday trip assignment | City coordinates, timetable stops/directions, service spans, station upgrades, owned passages, bus stop/catchment geometry and tram/ferry flags feed the original population models. Three crowding passes use summed train loads. A period is calculated on demand. | Parse station upgrades, passages, fares, walk access and bus catchments; add the same interactive station, corridor and day-kind figures as web. |
| Travel between zones and markets | Optional 17.6 MB first-use download composes the complete 4,135-row market with the opened zone using upstream IntercityApi. Its segment loads, boardings and alightings affect each population in all three crowding passes. | Open multiple adjacent zones in a single network, edit cross-zone fares, test onboard UI and memory on a physical phone. |
| Jakarta fare controls | The Flutter host opens the native finance sheet and saves base, distance, transfer, HSR, ferry and bus terms to the current Jakarta TWG1. Mode-only changes preserve nonlinear curves; explicitly editing base or distance sets a straight curve. Legacy price units and country scales survive reopening. Native and original web worker inputs agree for default, legacy and customized Indonesian scales. | Physical-device input QA; interactive curve-node, individual-line and country-scale editors. |
| Cash, fleet purchases, operating costs, upkeep, loans, day/hour ledger | Normal mode runs the bundled Studio clock and demand workers. On Android 15 arm64 the Jakarta–Bogor network earned fares and paid running costs and upkeep, resumed from its checkpoint, and split the ledger at local midnight. Studio reopened both checkpoints with matching cash and service data. Native construction and fleet debits, 12 hourly operating periods and loan terms have regression coverage. | Compare borrowing through the device UI and measure large networks on physical phones. |
| Saves and login | Signed-in Android hosts list web worlds and use the same UUID and optimistic revision. The web codec and WASM reopen TWT4–6 after Android edits, including a New York–Tokyo network, a new Jakarta network and legacy Jakarta workshop saves. The Android JNI reopened an actual worker checkpoint and preserved its settled finances. Account changes, conflicts, offline edits and saving a separate copy have regression coverage. | Production rollout and verification with a release build on a physical device. |
| Lines/stations tabs, ridership overlays, search, diagrams, charts, goals, tutorial, language and settings | The play dock opens services and finances. Each line opens a time–distance chart and a station departure board using TrackApi trips and stop times. Finance reads recorded daily fare revenue, running cost, upkeep and rider count. | Reproduce each panel against reference UI, exercise touch, label placement, map gestures and restored selections. |
| Frame rate and resource use | Original web simulation and native renderer exercised on separate emulators; a fresh NYC game clock advanced in public Chrome. | Compare large worlds on a physical 60 Hz Android phone; include touch, cold city load, memory and frame intervals. |
| Online community/leaderboards | Upstream account service intentionally disconnected. | Product scope for 168Railway accounts, scores, submissions, moderation and visibility. Optional Noritetsu overlays are unavailable in the pinned data distribution. |

## Reproduce the regional comparison

Run the ignored Rust test with WORLD_CITY_DATA (directory containing ID.json and ID.bin), WORLD_CITY, WORLD_SAVE (a populated original TWG1 network), WORLD_NETWORK_EXPORT and WORLD_RESULT_EXPORT set to output files. Use cargo test --release --manifest-path trainworld/native-android/Cargo.toml city_commute -- --ignored --nocapture. Build the web demand module with npm run wasm:demand in trainworld/app. Run node trainworld/native-android/check-ridership.mjs with the generated sim.js, region header, binary, exported service and native result in that order.

NYC morning (06–10), 109 lines and 1,756 stations: with the same local population inputs, three crowding rounds give 2,666,463.027 local rail trips natively versus 2,666,463.013 in WASM. Maximum relative difference for seven measured fields was below 0.000001%. This isolates the population kernel and packed service. Full-game figures can differ until the remaining market, fares, geographic transfers and zone inputs are connected.

For the original world market, also set WORLD_MARKET_INDEX to the gzip generated by build-market.mjs. Place market-drives.bin and the zone.market.json next to the local city pack. Pass the same index, drives and zone files as the final three arguments to check-ridership.mjs. In NYC, nine quantities match the browser WASM within 0.000001% relative difference, including 60,277.979 regional transit trips in the morning. The market all-mode denominator covers the global registry and must not be labeled city ridership.

After enabling service spans, passages, bus geometry and mode flags, NYC morning gives 2,710,240.407 transit trips including 60,149.486 regional trips. The prior results above describe their respective inputs. Shared packed-input WASM replay agrees within 0.000001% for nine fields.

Native service assignment also reads fare nodes and transfer charges from TWG1, applies local income and price-level scaling, and sends line multipliers and segment distances to the population models. Run check-fare-terms.mjs to compare five independent fare contexts with the browser implementation. With fares enabled, the NYC morning fixture yields 2,459,382.021 transit trips, of which 38,607.366 are regional. The nine packed-input replay measures agree within 0.000001% relative. The check covers passenger choice and does not measure fare revenue settlement.

The embedded Jakarta station inspector offers installed platform upgrades through
TrackApi's station_upgrade_cost and set_station_upgrade. It filters port, tram, one-track,
passing-track and station building availability before editing a constructed platform.
Construction is booked in US$M in TWG1's day, hour and default regional ledgers.
Removal has no refund. Shared-complex purchases, passages and building layouts are separate tools.

For a desktop round trip, set STATION_FACILITY_TEST_SAVE to an output save path while running
cargo test --lib paid_upgrade_survives_twg1_and_removal_never_refunds in native-android.
Run node check-station-facility.mjs PATH_TO_WEB_APP/src/wasm/track OUTPUT_SAVE using the
web game's generated module to verify the purchased bit, price, balance and ledger.


## Verifikasi Studio di Android

Acuan untuk pertukaran dunia adalah Studio di 168railway.com/studio/. Jalankan `check-studio-roundtrip.mjs` dengan path ke folder `trainworld/app` yang berisi WASM hasil build Studio. Matriks mencakup TWT4, TWT5 dan TWT6, dunia Jakarta baru serta perpindahan jaringan lintas benua. Tes membandingkan stasiun, urutan pemberhentian, geometri, nama, metadata dunia, jadwal dan pembukuan.

APK memakai akun dari aplikasi 168Railway. Pustaka Gradle menyertakan mesin ARM64, ARMv7 dan x86_64. Dari repo aplikasi, `scripts/build_studio_native.sh` membangun ulang pustaka dengan `RAILWAY_NATIVE_WORLD_DIR` dan `ANDROID_NDK_HOME` yang menunjuk ke checkout dan NDK. Editor menyediakan mulai dari peta kosong atau jaringan contoh, pembangunan, pembongkaran serta undo dan redo. Peta kota tambahan diunduh saat dibutuhkan.

Dunia di HP dan di web memakai revisi akun yang sama. Jika kedua perangkat mengedit revisi yang sama, pemain bisa mempertahankan edit HP sebagai dunia baru. Salinan di perangkat disimpan secara atomik. Android menjadwalkan sinkronisasi ketika tersedia koneksi.


Untuk menguji hasil permainan Normal di perangkat, jalankan `node check-studio-roundtrip.mjs /path/to/trainworld/app /path/to/jak_bogor.save /path/to/android-checkpoint.save` dari folder ini. Mainkan templat Jakarta–Bogor beberapa jam di perangkat lalu jeda dan simpan tanpa mengubah jaringan. Tes memeriksa selisih kas terhadap pendapatan tiket, biaya operasi dan pemeliharaan, jumlah penumpang, posisi stasiun, bentuk jalur serta jadwal. Uji juga bisa memakai dua checkpoint berurutan dari jaringan yang sama. Pada emulator, berkas penumpang yang tidak tersedia menghentikan pembukaan sebelum waktu bergerak dan mempertahankan isi checkpoint sebelumnya.

### Pemeriksaan pintu fitur Studio 168Railway

Acuan panel ada di `app/src/ui`, `app/src/ui/inspector` dan `docs/ui-*.md` pada checkout Studio. Ketersediaan komponen web serta pengujian tombol Android dicatat terpisah. Fitur daring memerlukan akun dan layanan yang diaktifkan oleh pengelola Studio.

| Alur pemain | Acuan Studio | Implementasi atau batas verifikasi Android |
|---|---|---|
| Pilih atau lanjutkan dunia | StartMenu, NewGame, SavesPanel, HostWorlds | Duniaku, mulai kosong, tujuh skenario Jakarta/Indonesia, unduh dan sinkronkan slot akun. Konflik revisi menyediakan pilihan salin. |
| Bangun jaringan | BuildTab, Blueprint, ConstructButton, PieceRebuild | Gambar jalur dan stasiun pada peta, tinjau biaya, bangun, bongkar, urungkan atau ulangi. Persilangan, pembangunan bertingkat dan cetak biru lanjutan perlu dibandingkan dengan editor acuan. |
| Jalankan layanan | LinesTab, LineInspector, SchedulePanel, TrainTable, MareyChart | Cari jalur berdasarkan nama, atur armada dan jadwal. Grafik native dua jam memakai profil kedatangan dan keberangkatan setiap perjalanan. Pilih stasiun untuk melihat papannya. Perbandingan grup dan jalur kembar tetap memerlukan panel Studio lengkap. |
| Periksa stasiun dan penumpang | StationsTab, StationInspector, ComplexMap, TrainInspector, TripInspector, OneRider, TripGraph | Peta dan pemeriksa native menampilkan stasiun, layanan dan keberangkatan. Periksa diagram kompleks, penumpang individual dan distribusi panjang perjalanan di editor lengkap. |
| Kelola kas dan tarif | MoneyTab, MoneyHistory, FareGraph, LineMoney | Kas, utang, angsuran, buku harian, operasi per jam dan perawatan ada di Keuangan. Grafik merekap hingga tujuh hari dari ledger. Editor kurva tarif dan pembukuan per jalur perlu pemeriksaan tersendiri. |
| Jelajahi dan bagikan | Cities, Search, DiagramButton, Achievements, Guide, SettingsTab, Community, Leaderboard | Pencarian wilayah dan kamera tersedia secara native. Diagram seluruh jaringan, ekspor gambar, pencapaian, tutorial, pengaturan tampilan dan layanan akun harus diperiksa terpisah pada editor lengkap. |

### Pengujian editor lokal di Android API 35

- Tutorial pada dunia Jakarta kosong mencatat rel pertama dari dua ketukan dan konfirmasi. Pada preset Jakarta–Bogor, menjalankan kereta mencentang langkah operasi. Pada dunia Bandung kosong, mengetuk indikator pangsa moda mencentang langkah pertama. Progres ketiganya bertahan setelah proses aplikasi ditutup dan slot dibuka kembali.
- Pelajaran *Bangun lintas baru* mengawali keenam langkah dari kondisi jaringan ketika pelajaran dipilih. Menambah layanan baru pada preset mencentang langkah layanan. Sesudah simpan dan buka ulang, layanan beserta centangnya tetap ada. Kembali ke panduan utama mengembalikan tanda centang sebelumnya.
- Ekspor diagram Jakarta melalui tab Kota dan pemilih berkas Android menghasilkan PNG 531 × 2145 piksel. Berkas di Downloads dibuka ulang dan memuat jaringan beserta nama stasiun.
- Pada salinan preset Jakarta–Bogor, pembelian ruang tunggu dan rel penyusulan di Gondangdia mengurangi saldo Rp848 miliar. Lorong 740 m antara Juanda dan Sawah Besar membentuk kompleks dua stasiun. Setelah denah gedung diperkecil lewat empat seretan jari, editor mengizinkan pembangunan gedung seluas 245.979 m². Pembaca Studio memulihkan fasilitas, kedua ujung lorong, keempat sudut gedung dan kedua stasiun anggotanya dari checkpoint Android.

- Peta Bandung pada emulator API 35 mengunduh 13 petak OpenFreeMap tingkat 11–14 lewat pembukaan, perbesaran dan geser biasa. Sesudah proses dihentikan serta Wi-Fi dan data seluler dimatikan, area peta 1040 × 1210 piksel pada tampilan kota dan tampilan jalan pusat Bandung identik dengan tangkapan daring. Petak jalan memuat Jalan Braga, Asia Afrika, Merdeka dan Pajajaran. Saat kamera berpindah ke petak detail yang belum ada, aplikasi menampilkan petak induk tersimpan. Sambungan kembali mengunduh petak detail baru tanpa membuka ulang aplikasi.
- Melalui Kelola → Peta penumpang & lalu lintas → Tujuan perjalanan, editor Jakarta–Bogor terbuka dengan Destinasi terpilih. Mengetuk satu lingkaran menampilkan 218 ribu ruas per hari beserta moda, proporsi, dan stasiun terdekat. Angka tersebut adalah satu area pada simpanan uji.
