# 168Railway World

Fork publik dari circularsquare/trainsworld commit 405ecf55b1816994d7ec19a3a1834046db394940. Perubahan oleh 168Railway pada 18 November 2026. Repo ini berisi frontend Preact/TypeScript dan engine Rust/WASM dari fork. Kode server 168Railway berada di proyek terpisah.

## Menjalankan fork

Pasang Node, Rust, target wasm32-unknown-unknown dan wasm-pack. Dalam trainworld/app jalankan npm ci dan npm run build. Sajikan dist di /studio-fork/. index.html mematok tw-data pada /studio-data/2026-11-18/ di domain 168railway.com. Berkas data terbit dari pipeline distribusi terpisah. Lihat NOTICE.md untuk lisensi masing-masing sumber. Peta dasar dan sebagian imagery publik tetap memakai penyedia petak daring. Lapisan EOX tidak digunakan pada fork ini.

Dalam game, buka Simpanan akun 168Railway. Sesi masuk dan CSRF berasal dari /api/web/studio/session pada origin yang sama. Simpan sebagai dunia baru mengekspor berkas TWG1 gzip engine asli. Perbarui memerlukan persetujuan dan versi yang cocok. Buka salinan lokal memakai pengimpor game dan slot lokal baru. Simpanan Studio mandiri JSON mempunyai format lain. Akun di browser tempat link dibuka menentukan akun penyimpanan. Permainan lokal tetap tersedia tanpa akun.

## Atribusi

Kode upstream © 2026 Anita Chen, AGPL-3.0. Lihat LICENSE serta NOTICE.md. Nama layanan, ikon, halaman privasi/kredit dan integrasi akun dikelola 168Railway. Kode sumber tersedia di https://github.com/manyunyu7/168railway-world . Kode dan perubahan dapat diperoleh dengan mengkloning repo ini. Tampilan awal dan pengaturan mencantumkan tautan tersebut.

Empat bunyi konstruksi dibuat secara sintetis dengan trainworld/tools/fork/build-sounds.py, dengan lisensi kode fork. Berkas rekaman Pixabay milik versi asli tidak digunakan.
