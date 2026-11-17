# Task 7 Report — dokumentasi + finalisasi

**Status:** DONE (dokumentasi saja; tidak menyentuh kode produksi)
**Commit:** `docs: record phase-1 implementation status for tool containers`
(SHA: lihat `git log -1 --oneline`; tidak ditulis di sini supaya tidak basi)
**Tidak di-push**, tidak ada `gradle`, tidak ada berkas kode yang diubah.

## Files changed

| File | Change |
|---|---|
| `docs/superpowers/specs/2026-11-17-tool-containers-design.md` | Header status; bagian baru **"Implementation status (fase 1)"**; bagian "What's left to the builder" diperbarui per butir |
| `docs/superpowers/plans/2026-11-17-tool-containers.md` | Tabel **"Task status"** (SHA kode + laporan per task) + baris **Status** di Task 1–7 |
| `docs/superpowers/sdd/progress.md` | Ledger: Task 6 & Task 7 selesai, baris fix `b27e5f9` |
| `docs/superpowers/sdd/task-7-report.md` | Laporan ini |

## Verifikasi ke kode (bukan dari laporan tugas)

Yang dicek langsung sebelum menulis, supaya dokumen tidak memuat klaim karangan:

- `core/ContainerSpec.kt` — `ContainerCatalog.PYTHON/NODE`, `ALPINE_VERSION =
  3.20.3`, `ALPINE_BRANCH = v3.20`, `archFor` 4 ABI, `loaderName`
  (armhf/i386), URL = CDN + `DistroCatalog.MIRRORS`, `setupScript` memuat
  `ca-certificates`, `shimBinaries` python3/pip3 & node/npm/npx.
- `core/ContainerRootfs.kt` — `ensureContainerLinks`, `appendMuslPath`
  (idempoten), `ensureShForTermuxLayout` (symlink relatif).
- `core/ContainerShim.kt` — 4 baris skrip (HOME, SSL_CERT_FILE, exec),
  `shimPath` = `containers/bin/<id>-<binary>`.
- `core/ContainerManager.kt` — staging `.staging-<id>` + `ROOTFS_SUBDIR`,
  rename atomik, proot `--kill-on-exit --link2symlink -0 -b /dev /proc /sys`,
  `SETUP_TIMEOUT_MS = 300_000`, cancellation, `isInstalled` (rootfs +
  releaseMarkers + shim), `remove()` **tanpa** cek engine (KDoc-nya
  menyatakan gating di UI), `status()` masih string Indonesia hardcoded.
- `core/DistroManager.kt:108-116,401,410,478-484,860,948,953` —
  `containerBindArgs` (delegasi), `guestPathValueWithContainers`,
  `root/.profile`, `ensureMuslRuntime` append `<container>/usr/lib`,
  `postInstall` → `ensureShForTermuxLayout`, bind di `buildProotCommand`.
- `core/EngineManager.kt:188-217,760-786` — bind containers di `baseArgv` +
  `serveArgv`, PATH `/opt/containers/bin:…` di `guestEnvTail`, `serveArgv`
  memakai `guestPathValueWithContainers()`.
- `ui/DistroScreen.kt:503-608` + `ui/I18n.kt:288-295,558-565,828-835` —
  seksi Tool containers (di dalam `RuntimeMode.PROOT`), 8 string id + en,
  tombol Hapus `enabled = !engine.isRunning()` + `containerRemoveBusy`.
- Jumlah tes di `app/src/test/.../core/`: ContainerSpecTest 7,
  ContainerRootfsTest 13, ContainerShimTest 3, ContainerManagerTest 4,
  ContainerExposureTest 4 = **31 tes**, tidak satu pun pernah dijalankan.
- `git log --oneline` untuk SHA: `bab99bd`, `5a4f3ac`, `5b85534`, `a0981d6`,
  `b27e5f9` (fix), `d59b804`, `25ac5ff`; laporan `df091c1`, `94caf77`,
  `5e22611`, `44cb92b`, `18aaf2a`, `9f78f9c`.

## Isi bagian "Implementation status (fase 1)"

Tabel komponen per berkas (7 baris) + 31 tes yang belum dijalankan, lalu:

1. **Penyimpangan sadar** — (a) tanpa auto-restart engine, hanya
   `containerRestartHint` + Stop→Start manual; (b) gating hapus hanya di UI,
   `ContainerManager.remove()` tidak memeriksa `engine.isRunning()`;
   (c) import offline SAF di luar rilis 1; (d) `status()` masih hardcoded
   Indonesia dan tidak dipakai UI.
2. **Belum diverifikasi** — nol pengujian di perangkat nyata (arm64 atau ABI
   lain); nol build/gradle lokal (APK dibangun di GitHub Actions CI, jadi
   kompilasi hanya diverifikasi statis); format `ld-musl-<arch>.path`
   multi-path belum diuji dan fallback loader eksplisit belum ditulis;
   symlink `/bin/sh` Termux belum dieksekusi; shadowing library musl belum
   diukur; pembatalan/progress UI belum terlihat berjalan.
3. **Risiko terselesaikan** — bug kritis `b27e5f9`: rootfs diekstrak ke
   `containers/<id>/<id>/rootfs` sehingga `isInstalled()` selalu false dan
   shim menunjuk path tidak ada; diperbaiki via `ROOTFS_SUBDIR` + rename
   atomik, tes regresi ditambahkan (tes lama juga mustahil lulus).
4. **Caveat kualitas tes** — `ContainerExposureTest` memakai salinan murni
   komposisi PATH, bukan instance asli (butuh `Context`);
   `ContainerManagerTest` hanya menguji fungsi murni companion, alur
   install/remove/isInstalled tanpa tes otomatis.

## Plan

Tabel "Task status" (Task 1–7 + baris fix) dengan commit kode & laporan, plus
baris **Status** di tiap heading task. Checkbox `- [ ]` per langkah dibiarkan
apa adanya dengan penjelasan: langkah dijalankan sesi subagent dan build/test
dialihkan ke CI, jadi mencentang per-langkah akan menyesatkan. Task 7 Step 1–4
diberi catatan "belum dijalankan — tidak ada perangkat"; Step 5 ditandai
selesai sebagai catatan status, bukan hasil uji.

## Concerns

1. **Fitur belum pernah dijalankan.** Ini risiko tertinggi yang tersisa dan
   sekarang tertulis eksplisit di spec. Dokumen ini sengaja tidak memakai kata
   "selesai" tanpa kualifikasi: fase 1 selesai *di level kode*, belum di level
   perilaku.
2. **Build pertama belum terjadi.** Kalau CI gagal kompilasi, status di
   dokumen ini perlu dikoreksi. Semua verifikasi Task 1–7 bersifat statis.
3. `progress.md` membawa perubahan yang belum ter-commit dari sesi review
   (baris fix `b27e5f9`) — diikutkan di commit ini karena satu tema
   (pencatatan status), bukan perubahan kode.
4. `ContainerManager.status()` masih hardcoded Indonesia + tidak dipakai;
   kandidat dibersihkan di fase berikutnya (dicatat di spec, tidak diubah di
   sini karena Task 7 dokumentasi saja).