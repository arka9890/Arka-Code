# Design: Tool Container (isolasi tool lintas distro)

Tanggal: 2026-11-17
Status: draft v2 — menunggu approval
Riwayat: v1 → builder check (7 temuan) → v2 (semua temuan sudah diputuskan, lihat "Keputusan setelah builder check").

## Intent / Why

Masalah yang dihadapi user: tool yang diinstall ke dalam rootfs distro proot
(mis. `apk add python3`, `npm -g`, build tool) **hilang saat ganti distro**.
Rootfs tiap distro adalah folder terpisah (`<filesDir>/distro/<id>`), jadi
Alpine → Ubuntu = rootfs baru yang bersih. User meminta pendekatan "Docker":
install tool sekali, tool tetap ada meski distro diganti.

Docker daemon sungguhan **tidak bisa** jalan di Android non-root di dalam
proot (butuh namespace/cgroup/overlayfs nyata yang diblokir kernel Android
untuk aplikasi biasa). Karena itu solusinya adalah *meniru pola Docker dengan
proot yang sudah ada*: setiap tool punya rootfs sendiri yang persisten,
di luar folder distro, di-bind ke semua distro.

## Goals

1. Tool (python, node, dll.) diinstall sekali ke container persistent dan
   tetap berfungsi setelah user ganti distro.
2. Command AI (bash tool opencode) dan terminal user tetap berjalan di
   **distro aktif** (Opsi 1) — tidak mengubah model eksekusi yang ada.
3. Tidak butuh root; memakai binary proot yang sudah dibundel di APK.
4. Migrasi nol untuk engine opencode (sudah persisten lihat Constraints).
5. Tool yang dibuat saat runtime (`pip install foo`, `npm i -g pkg`)
   juga tetap berfungsi — bukan hanya yang ada saat instal container.

## Non-goals

- Docker daemon / `dockerd` / image registry sungguhan.
- Isolasi keamanan (proot = persistensi & isolasi ABI, BUKAN sandbox
  keamanan; container di sini bukan batas kepercayaan).
- Chrome/Chromium — kemungkinan besar tidak jalan di proot (sandbox,
  dependensi besar); ditunda, bukan bagian rilis ini.
- Runtime mode TERMUX — mode itu tidak lewat proot, container tidak berlaku.
- Import offline (SAF/berkas lokal) untuk container — rilis 1 hanya URL;
  pola `installFromStream` distro bisa menyusul bila ada permintaan.

## Anti-goals

- Tidak memindahkan/mengubah lokasi binary engine opencode yang sekarang.
- Tidak menghapus atau memperlakukan distro sebagai "tidak berguna" —
  distro tetap environment utama command.
- Tidak menambah unduhan wajib di boot (container bersifat opt-in dari UI).

## Constraints (dari kode yang ada)

- Binary proot hanya bisa dieksekusi dari `nativeLibraryDir`
  (`libproot.so`, `libproot-loader.so`) — lihat `ProotBinaries.kt`.
- Engine opencode sudah persisten: `<filesDir>/engine/` di-bind ke
  `/opt/arkacode`, plus `MuslRuntime` memasang loader musl ke `/lib` **setiap
  distro** sehingga binary musl jalan di distro glibc. **Mekanisme inilah
  yang akan dipakai ulang container** (lihat Mekanisme eksekusi).
- Semua eksekusi lewat tiga tempat pembangunan argv proot:
  - `DistroManager.buildProotCommand()` — bash command AI/exec biasa.
  - `EngineManager.baseArgv()` — one-shot (cek versi, dll).
  - `EngineManager.serveArgv()` — server `opencode serve`.
  Bind container harus ditambahkan di ketiganya.
  **Catatan:** bind + PATH di `serveArgv()` hanya berlaku saat server mulai —
  lihat Keputusan #3 (restart otomatis).
- Ekstraksi rootfs harus pakai pola staging + penanda (`releaseMarkers`)
  seperti `DistroManager.tryInstall` supaya unduhan gagal tidak merusak
  yang sudah ada.
- SELinux Android: tanpa `--link2symlink`, dpkg/apt gagal — argumen proot
  yang ada harus dipertahankan.

## Approaches yang dipertimbangkan

### Pendekatan isolasi (dipilih: A)

- **A. Tool container per tool** *(dipilih)* — mini-rootfs Alpine sendiri
  per tool di `<filesDir>/containers/<id>`, di-bind ke `/opt/containers` di
  semua distro. Cocok untuk masalah; tiap tool bebas dari libc distro aktif.
- **B. Folder bersama di-bind** — tool diinstall sekali di prefix bersama.
  Ditolak: binary glibc vs musl tetap bentrok antar distro (masalah lama
  berulang).
- **C. Docker asli di proot** — ditolak: butuh namespace/cgroup yang diblokir
  kernel Android non-root.

### Model eksekusi (dipilih: Opsi 1)

- **Opsi 1** *(dipilih)*: command tetap berjalan di distro aktif; tool
  container di-expose ke PATH distro lewat shim. Perubahan paling kecil.
- Opsi 2: semua command di satu container "dev" tetap — membuat pilihan
  distro nyaris tak berguna.
- Opsi 3: routing per tool (proot berlapis) — nested proot tidak andal
  (satu proses hanya bisa di-trace satu tracer; proot lama sudah
  me-seize semua child-nya).

## Desain

### Layout filesystem

```
<filesDir>/
├── distro/<id>/              ← distro aktif (seperti sekarang)
├── engine/                   ← binary opencode (tidak diubah)
├── containers/
│   ├── bin/                  ← shim per tool, di-bind ke /opt/containers/bin
│   ├── python/
│   │   ├── rootfs/           ← Alpine minirootfs 3.20 (±4 MB) + apk add python3
│   │   ├── home/             ← HOME persisten tool ini (lihat Keputusan #5)
│   │   └── .installed        ← penanda selesai (mirror pola releaseMarkers)
│   └── node/
│       ├── rootfs/
│       ├── home/
│       └── .installed
```

### Komponen baru

1. **`ContainerSpec` + `ContainerCatalog`** (`core/ContainerSpec.kt`)
   - Id, label, ukuran perkiraan, URL rootfs per ABI (Alpine minirootfs
     v3.20 — sama dengan `DistroCatalog.ALPINE_320`, termasuk mirror
     cadangan; **dipin ke cabang yang sama dengan default distro** agar
     versi library identik, lihat Risiko), skrip instal idempoten
     (mis. `apk add --no-cache python3 py3-pip ca-certificates`),
     daftar binary yang di-shim (mis. `python3`, `pip3`), marker path.
   - **4 ABI** (arm64-v8a, armeabi-v7a, x86_64, x86) dengan tabel arch
     sendiri — `aarch64`, `armv7`, `x86_64`, `x86`, dan nama loader
     `ld-musl-<arch>.so.1` (armv7 → `ld-musl-armhf.so.1`,
     x86 → `ld-musl-i386.so.1`). **Tidak** memakai `MuslRuntime.archFor`
     (itu khusus ABI yang didukung binary opencode) — lihat Keputusan #4.
   - Katalog rilis 1: **python**, **node**. (chrome = eksperimental/tunda.)

2. **`ContainerManager`** (`core/ContainerManager.kt`)
   - `install(spec, onProgress)` — unduh rootfs → ekstrak ke staging →
     tukar ke `containers/<id>/rootfs` → tulis `etc/ld-musl` path? (tidak —
     lihat Mekanisme) → jalankan skrip instal via proot `-r <rootfs>` →
     tulis shim → tulis `.installed` → **restart engine bila sedang jalan**
     (Keputusan #3). Pola meniru `DistroManager.tryInstall` (staging,
     kandidat URL, penanda).
   - `status(spec)` — terpasang? ukuran? binary ter-shim terbaca?
     Regenerasi shim bila hilang/kadaluarsa (dipanggil dari status).
   - `remove(spec)` — **ditolak bila engine sedang jalan** (Keputusan #6);
     hapus folder container + shim-nya; bila berhasil, restart engine
     bila jalan.
   - `shimScript(spec, binary)` — **fungsi murni** (mudah dites): menulis
     skrip `#!/bin/sh` yang mengekspor env lalu mengeksekusi binary tool
     di dalam rootfs container (lihat Mekanisme eksekusi).

3. **Expose ke distro** — perubahan kecil di tempat yang sudah ada:
   - Tambah bind `-b <filesDir>/containers:/opt/containers` di
     `buildProotCommand()`, `baseArgv()`, `serveArgv()`.
   - Tambah `/opt/containers/bin` di depan PATH guest
     (`guestPathValue()`, env tail serve — **dua-duanya**, karena
     `guestEnvTail()` meng-hardcode PATH terpisah).

### Mekanisme eksekusi (inti teknis)

Container memakai Alpine (musl); distro aktif bisa glibc (Ubuntu/Debian).
Binary tool **tidak bisa** langsung exec di distro glibc tanpa loader musl —
tapi mekanisme itu **sudah terbukti dipakai engine opencode hari ini**:
`MuslRuntime` menyalin `ld-musl-<arch>.so.1` + `libc.musl-<arch>.so.1` ke
`<rootfs>/lib/` setiap distro, dan proot menerjemahkan path PT_INTERP
tersebut ke rootfs tamu saat execve. Container memakai jalur yang sama:

1. **Eksekusi langsung** (mekanisme utama): shim di
   `<filesDir>/containers/bin/<tool>` (terlihat sebagai
   `/opt/containers/bin/<tool>` di guest, ada di PATH):

   ```sh
   #!/bin/sh
   export HOME=/opt/containers/python/home
   export SSL_CERT_FILE=/opt/containers/python/rootfs/etc/ssl/certs/ca-certificates.crt
   exec /opt/containers/python/rootfs/usr/bin/python3 "$@"
   ```

   - `exec` binary tool **langsung** (bukan lewat loader eksplisit):
     PT_INTERP `/lib/ld-musl-<arch>.so.1` resolving ke loader yang sudah
     `MuslRuntime` pasang di distro manapun — persis jalur yang hari ini
     membuat binary opencode jalan di Ubuntu.
   - **Kenapa ini penting:** dengan eksekusi langsung,
     `/proc/self/exe` = binary tool yang asli, sehingga deteksi prefix
     Python (`sys.prefix`, stdlib), `process.execPath` Node, dan
     `sys.executable`/`sys.executable` pip **semuanya benar otomatis** —
     tanpa `PYTHONHOME`/`NODE_PATH`/trik argv0.
   - Pencarian library container: `MuslRuntime` sudah menulis
     `/etc/ld-musl-<arch>.path` (isi sekarang `/opt/arkacode/lib`).
     File itu **di-append** dengan `:/opt/containers/python/rootfs/lib:/opt/containers/python/rootfs/usr/lib`
     (format & urutan pasti = tugas builder verifikasi di perangkat —
     lihat Risiko). Karena kedua distro default dan container memakai
     cabang Alpine yang sama, versi library identik bila terjadi shadowing.

2. **Shebang script yang dibuat saat runtime** (`pip install foo`,
   `npm i -g pkg`) — tidak perlu rewrite apa pun:
   - `pip` menulis shebang dari `sys.executable` → dengan eksekusi
     langsung, itu = path binary python di dalam container
     (`/opt/containers/python/rootfs/usr/bin/python3`). Script tsb dieksekusi
     langsung dari distro → jalur yang sama seperti butir 1 → jalan.
   - `npm` menulis shebang dari `process.execPath` → = path binary node di
     container → sama, jalan.
   - `#!/usr/bin/env python3` / `#!.../env node` → `env` mencari dari PATH →
     ketemu shim. Beres.
   - `#!/bin/sh` skrip umum → dibiarkan; `sh` distro aktif cukup POSIX.
   - (Kalau uji perangkat membuktikan ada tool yang butuh bentuk lain,
     fallback-nya: loader eksplisit `ld-musl --library-path … --argv0 <shim>`
     — tersimpan sebagai cadangan, bukan desain utama.)

3. **Env yang diekspor shim** (kontrak shim):
   - `HOME` → `containers/<id>/home/` — supaya `pip --user`, `~/.npm`,
     `~/.cache` ikut persisten, bukan jatuh ke rootfs distro yang bisa
     diganti (Keputusan #5).
   - `SSL_CERT_FILE` → file sertifikat di dalam container (diinstal oleh
     skrip container via `ca-certificates`) — karena jalur bawaan OpenSSL
     (`/etc/ssl/certs`) me-resolve ke rootfs **distro aktif**, yang mini
     rootfs-nya bisa tidak punya sertifikat.
   - Tool non-Python/Node (mis. nanti ada) menambah env sesuai kebutuhan.

### Alur instal (user perspective)

1. Settings → Containers (seksi baru di `DistroScreen` atau layar sendiri):
   daftar container + status + tombol Pasang/Hapus + progres unduhan.
2. Setelah terpasang, `python3`/`node` langsung bisa dipanggil AI maupun
   user di distro mana pun. **Bila engine sedang jalan, app me-restart
   server otomatis** supaya bind & PATH baru terpakai (Keputusan #3) —
   UI menampilkan catatan singkat saat restart.
3. Ganti distro → container tetap; shim tetap sama.

### UI & I18n

- `DistroScreen`: seksi "Tool containers" berisi kartu per container
  (nama, ukuran, status, aksi). String baru di `I18n.kt` (id + en).
- Progress memakai pola kartu instal distro yang sudah ada.
- Tombol Hapus disabled saat engine jalan (pesan alasan).

### State & persistensi

- Penanda `.installed` di dalam folder container (bukan prefs) — pola sama
  dengan `releaseMarkers`, otomatis konsisten dengan isi disk.
- Tidak ada perubahan skema prefs/DB.

### Testing

- Unit test (pola `app/src/test` yang ada):
  - `ContainerShimTest` — `shimScript()` menghasilkan skrip benar per ABI
    (path binary target, export HOME + SSL_CERT_FILE, shebang `#!/bin/sh`).
  - `ContainerCatalogTest` — URL rootfs per ABI (4 ABI) terbentuk benar,
    nama loader per ABI benar, marker path konsisten, skrip instal
    tidak kosong dan memuat `ca-certificates`.
  - Append ld-musl path: fungsi murni menambah path container ke isi lama
    tanpa menghapus `/opt/arkacode/lib` (idempoten bila dipanggil ulang).
- Integrasi (manual/CI): pasang container python → jalankan one-shot
  `python3 --version` dan `pip3 --version` lewat `runOneShot` di distro
  glibc (ubuntu) dan musl (alpine) → versi sama; `python3 -c "import sys;
  print(sys.prefix)"` menunjuk path container.

### Fase

1. **Fase 1 — infrastruktur**: `ContainerSpec`/`ContainerCatalog`/
   `ContainerManager`, bind + PATH di tiga argv builder (+ append
   ld-musl path), shim & env, restart-otomatis, UI daftar container,
   container **python**. (Inti masalah selesai.)
2. **Fase 2 — container node** + uji `npm i -g` runtime.
3. **Fase 3 (tunda)** — chrome/eksperimen lain, container toolchain
   Android (skrip `ToolchainSetup` saat ini jalan di distro; pindah ke
   container = keputus terpisah), import offline SAF.

### Risiko yang diketahui

- **Nested proot dihindari** — desain sengaja tidak menjalankan proot
  di dalam proot (aturan satu tracer).
- **Versi musl loader distro vs container** — interpreter yang dipakai
  adalah salinan milik distro (hasil `MuslRuntime`); keduanya di-pin ke
  Alpine 3.20 untuk meminimalkan selisih. ABI musl stabil; risiko diterima
  dengan catatan uji di dua distro (fase 1).
- **Shadowing library** — `ld-musl` path file bisa membuat library
  container terjadi sebelum library distro untuk binary musl distro.
  Diterima karena cabang Alpine sama (versi identik); diverifikasi saat uji.
- **Format `ld-musl-<arch>.path`** (pemisah beberapa path) — builder
  memverifikasi di perangkat; bila formatnya tidak mendukung banyak path,
  fallback: shim memakai loader eksplisit (cadangan di Mekanisme #2).
- Distro layout Termux (`TERMUX_PREFIX`) tidak punya `/bin/sh` di root
  rootfs — shim ber-shebang `#!/bin/sh` perlu `sh` yang ada. Builder
  memastikan: buat `/bin/sh` di rootfs Termux saat post-install ATAU
  pakai interpreter yang sudah pasti ada di semua layout.
- APK/ukuran tidak berubah (container diunduh saat user memasang).

## Keputusan setelah builder check

Semua temuan reviewer diselesaikan sebagai keputusan desain (tidak ada
yang menunggu jawaban user lagi):

1. **Script entry-point yang dibuat saat runtime** — dijamin oleh eksekusi
   langsung (Mekanisme #1): `sys.executable`/`process.execPath` menunjuk
   path binary asli di container, jadi shebang hasil `pip`/`npm` selalu
   menunjuk ke path yang terekskusi langsung dari distro. Tidak ada hook
   rewrite runtime.
2. **Kontrak env shim** — shim mengekspor `HOME` (persisten per container)
   dan `SSL_CERT_FILE` (ca-certificates dari dalam container); prefix
   Python/Node tidak butuh env karena eksekusi langsung.
3. **Restart engine** — bind & PATH `serveArgv()` hanya berlaku saat
   server mulai; UI menampilkan hint restart dan memakai tombol Stop→Start
   yang sudah ada (tidak ada auto-restart yang mengejutkan). Bind berlaku
   untuk perintah berikutnya, PATH serve berlaku setelah restart manual.
   (Menyimpang sadar dari draf v1 — dicatat di Task 6 rencana.)
4. **ABI** — rilis 1 mendukung 4 ABI dengan tabel arch/loader sendiri
   (bukan `MuslRuntime.archFor`); feature tersedia di semua perangkat yang
   distro-nya didukung.
5. **State writable tool** — `HOME` tool diarahkan ke
   `containers/<id>/home/` lewat shim, supaya `pip --user`/`~/.npm`
   persisten lintas distro.
6. **Lifecycle** — pasang: boleh kapan pun; hapus: ditolak selama engine
   jalan (pola sama dengan update engine yang menolak saat `isRunning()`).
   `status()` mendeteksi shim hilang dan menulisnya ulang.
7. **Import offline** — di luar lingkup rilis 1 (catatan di Non-goals).

## What's left to the builder

- Penulisan skrip instal per container dan urutan langkah pasti
  `install()` (timeout, progress steps, urutan restart engine).
- Verifikasi format `ld-musl-<arch>.path` multi-path di perangkat dan
  fallbacknya bila perlu.
- Penentuan penanganan `/bin/sh` untuk layout Termux (opsi di Risiko).
- Detail UI (komponen Compose, layout kartu).
- Pengetesan nyata di perangkat (arm64) untuk python & node, termasuk
  `pip install` runtime dan `npm i -g`.
