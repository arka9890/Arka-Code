# Design: Tool Container (isolasi tool lintas distro)

Tanggal: 2026-11-17
Status: **fase 1 terimplementasi, belum diuji di perangkat** — lihat
"Implementation status (fase 1)".
Riwayat: v1 → builder check (7 temuan) → v2 (semua temuan sudah diputuskan,
lihat "Keputusan setelah builder check") → fase 1 diimplementasikan
(Task 1–6) → status implementasi dicatat (Task 7).

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

## Implementation status (fase 1)

Ditulis di Task 7 dan menggambarkan **keadaan kode**, bukan rencana. Fase 1 =
Task 1–6 rencana (kode) + Task 7 (dokumentasi). Semua klaim di bawah dicek
langsung ke berkas sumber, bukan dari laporan tugas.

### Yang sudah dibangun

| Berkas | Isi |
|---|---|
| `core/ContainerSpec.kt` | `data class ContainerSpec` + `object ContainerCatalog` (PYTHON, NODE). Rootfs = Alpine minirootfs **3.20.3** (branch `v3.20`) per ABI dari `DistroCatalog.CDN` + semua `DistroCatalog.MIRRORS`. `archFor()` memetakan 4 ABI (`aarch64`, `armv7`, `x86_64`, `x86`); `loaderName()` memberi `ld-musl-aarch64.so.1` / `…armhf…` / `…x86_64…` / `…i386…`. `setupScript` = `apk add --no-cache python3 py3-pip ca-certificates` (python) dan `nodejs npm ca-certificates` (node); `releaseMarkers` per tool; `shimBinaries` = `python3,pip3` / `node,npm,npx`. |
| `core/ContainerRootfs.kt` | `ensureContainerLinks()` (root/workspace, tmp, `/etc/resolv.conf` + `/etc/hosts` sama dengan `DistroManager.postInstall`, chmod ELF/shebang di `bin`, `usr/bin`, `usr/local/bin`), `appendMuslPath()` (fungsi murni, idempoten), `ensureShForTermuxLayout()` (symlink relatif `<rootfs>/bin/sh` → `../<prefix>/bin/sh`). |
| `core/ContainerShim.kt` | `script(spec, binary, abi)` = 4 baris (`#!/bin/sh`, `export HOME=…`, `export SSL_CERT_FILE=…`, `exec …/usr/bin/<binary> "$@"`); `shimPath()` = `containers/bin/<id>-<binary>` (nama unik antar container). `abi` hanya untuk konsistensi API — isi skrip tidak berbeda per ABI. |
| `core/ContainerManager.kt` | `install()` (kandidat URL CDN→mirror, unduh + deteksi kompresi dari magic bytes, staging `.staging-<id>` + rename atomik, `ensureContainerLinks`, `setupScript` lewat proot `-r <staging>/rootfs` dengan
`--kill-on-exit --link2symlink -0 -b /dev -b /proc -b /sys`, timeout 300 s,
cancellation diteruskan, tulis shim + `home/` + `.installed`), `isInstalled()` (rootfs + semua `releaseMarkers` + semua shim), `sizeLabel()`, `status()`, `remove()`, `guestPath()`, `containerDirName()`, `ROOTFS_SUBDIR`, `containerBindArgs()`. |
| `core/DistroManager.kt` | bind `containers → /opt/containers` di `buildProotCommand()`; PATH guest memakai `guestPathValueWithContainers()` (`/opt/containers/bin:` di depan), termasuk isi `root/.profile`; `ensureMuslRuntime()` menulis `<rootfs>/etc/ld-musl-<arch>.path` = `MuslRuntime.GUEST_LIB_DIR` + `<container>/usr/lib` untuk tiap container terpasang (idempoten); `postInstall()` memanggil `ensureShForTermuxLayout()` untuk layout Termux; `containerBindArgs()` mendelegasikan ke `ContainerManager` (satu sumber kebenaran). |
| `core/EngineManager.kt` | bind containers di `baseArgv()` dan `serveArgv()`; `guestEnvTail()` PATH = `/opt/containers/bin:…`; `serveArgv()` PATH = `distro.guestPathValueWithContainers()`. Argumen proot lama tidak berubah. |
| `ui/DistroScreen.kt` + `ui/I18n.kt` | seksi "Tool containers" (hanya di `RuntimeMode.PROOT`): status terpasang/belum + ukuran nyata, tombol Pasang/Hapus, progress inline, error per kartu, 8 string baru (id + en). |

Unit test yang ditulis: `ContainerSpecTest` (7), `ContainerRootfsTest` (13),
`ContainerShimTest` (3), `ContainerManagerTest` (4), `ContainerExposureTest`
(4) — total 31 tes. **Belum ada satu pun yang dijalankan** (lihat bagian
"Belum diverifikasi").

### Penyimpangan sadar dari desain

1. **Tidak ada auto-restart engine setelah container dipasang.** Draf v1
   menyebut restart otomatis; Keputusan #3 memilih hint saja. Implementasi:
   `installContainer()` menampilkan `containerRestartHint` ("Restart engine
   (Stop → Start) …") dan **tidak** memanggil `engine.stopServer()` /
   `startServer()`. Bind containers berlaku untuk perintah berikutnya; PATH
   server (`serveArgv`) baru berlaku setelah user me-restart manual. Alasan:
   restart di tengah sesi AI dianggap mengagetkan user, dan tombol Stop→Start
   sudah ada di layar Engine.
2. **Gating hapus hanya di UI.** `ContainerManager.remove()` **tidak**
   memeriksa `engine.isRunning()` (tertulis di KDoc-nya); yang menahan adalah
   tombol Hapus di `DistroScreen` (`enabled = !engine.isRunning()`) plus
   keterangan `containerRemoveBusy`. Konsekuensi: pemanggil lain di masa
   depan tidak otomatis terlindungi dan harus mengulang pemeriksaan itu.
3. **Import offline (SAF) di luar rilis 1** — hanya URL. Belum ada
   `installFromStream` versi container.
4. **`ContainerManager.status()`** masih memakai string Indonesia hardcoded
   (`"terpasang"` / `"belum terpasang"`) dan saat ini tidak dipanggil UI (UI
   memakai `isInstalled()` + string I18n). Sisa kecil: kandidat dihapus atau
   di-I18n-kan.

### Belum diverifikasi (baca sebelum menganggap fitur jalan)

- **Tidak ada pengujian di perangkat nyata.** Belum pernah ada container
  yang benar-benar dipasang/dijalankan di arm64 maupun ABI lain: uji manual
  `python3 --version`, `pip install`, `pip install --user`, `npm i -g`, dan
  persistensi setelah ganti distro (Task 7 Step 1–4 rencana) **belum
  dijalankan**.
- **Tidak ada build/gradle lokal.** Kebijakan user: APK dibangun di GitHub
  Actions CI. Semua kompilasi + unit test fase 1 diverifikasi statis saja
  (baca diff, hitung keseimbangan kurung, grep nama field). Kegagalan
  kompilasi pertama akan muncul di CI.
- **Format `ld-musl-<arch>.path` multi-path** belum diverifikasi di
  perangkat (risiko yang sudah tercatat di atas). Implementasi hanya
  menambahkan `<container>/rootfs/usr/lib` per container — bukan juga
  `<container>/rootfs/lib`. Bila loader tidak membaca format banyak path,
  fallback loader eksplisit (`ld-musl --library-path … --argv0 …`) di
  Mekanisme #2 belum ditulis.
- **`/bin/sh` layout Termux** — `ensureShForTermuxLayout()` sudah dipanggil
  dari `postInstall`, tetapi symlink relatifnya belum pernah dieksekusi di
  device Termux.
- **Shadowing library musl** (distro vs container) belum diukur di perangkat;
  desain hanya mengandalkan Alpine 3.20.3 di kedua sisi.
- **Cancellation/progress UI** belum terlihat berjalan (tidak ada tombol
  Batal khusus container; pembatalan hanya terjadi bila layar ditinggal).

### Risiko yang sudah terselesaikan

- **Bug kritis: rootfs diekstrak satu tingkat terlalu dalam** — ditemukan
  saat review Task 4/5, diperbaiki di commit `b27e5f9`. `install()`
  mengekstrak isi arsip ke `containers/.staging-<id>/<id>` lalu me-rename
  folder itu menjadi `containers/<id>`, sehingga rootfs mendarat di
  `containers/<id>/<id>/rootfs`: `isInstalled()` akan **selalu false** dan
  shim menunjuk path yang tidak ada — fitur tidak akan pernah jalan walau
  unduhan sukses. Perbaikan: ekstrak ke `<staging>/rootfs` (konstanta
  `ROOTFS_SUBDIR`), `rootfsDirName` → `containerDirName` dengan KDoc, dan
  tes regresi di `ContainerManagerTest` yang mengunci kesamaan
  `<id>/rootfs` versi host dengan `guestPath(id)`. Tes lama juga mustahil
  lulus (membandingkan `"python"` dengan `"rootfs"`), jadi bug ini tidak
  mungkin tertangkap tes yang ada saat itu.

### Caveat kualitas tes

- `ContainerExposureTest` **tidak memanggil**
  `DistroManager.guestPathValueWithContainers()` yang asli (instance method
  butuh `Context` nyata); tes memakai salinan murni dari komposisi PATH yang
  sama. Tes mengunci *bentuk* string, bukan perilaku instance — kalau
  implementasi berubah tanpa mengubah salinannya, tes tetap hijau.
- `ContainerManagerTest` hanya menguji fungsi murni companion (`guestPath`,
  `containerDirName`, `ROOTFS_SUBDIR`, `containerBindArgs`). Alur
  `install()` / `remove()` / `isInstalled()` (filesystem, jaringan, proot)
  tidak punya tes otomatis; verifikasinya bergantung sepenuhnya pada uji
  perangkat.

## What's left to the builder

Diperbarui di Task 7 — status per butir:

- ~~Skrip instal per container dan urutan langkah pasti `install()`
  (timeout, progress steps, urutan restart engine)~~ — **selesai** (Task 4;
  lihat Implementation status). Restart engine diputuskan **tidak**
  otomatis (Keputusan #3).
- ~~Penentuan penanganan `/bin/sh` untuk layout Termux~~ — **selesai**
  (`ContainerRootfs.ensureShForTermuxLayout`, dipanggil dari `postInstall`),
  **belum diuji di device Termux**.
- ~~Detail UI (komponen Compose, layout kartu)~~ — **selesai** (Task 6).
- **Masih terbuka:** verifikasi format `ld-musl-<arch>.path` multi-path di
  perangkat + fallback loader eksplisit bila format tidak didukung.
- **Masih terbuka (paling penting):** pengetesan nyata di perangkat (arm64)
  untuk python & node — `python3 --version`, `pip install`, `pip install
  --user`, `npm i -g` — di distro glibc (Ubuntu) dan musl (Alpine), plus
  persistensi setelah ganti distro.
- **Masih terbuka:** build + unit test pertama di GitHub Actions CI (tidak
  pernah dijalankan lokal sesuai kebijakan user).
