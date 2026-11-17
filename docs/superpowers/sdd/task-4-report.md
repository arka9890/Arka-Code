# Task 4 Report: ContainerManager

## Status

**COMPLETE** (tests written, NOT executed locally — CI verifies).

## Files

- `android/app/src/main/java/com/arkacode/app/core/ContainerManager.kt` (baru, ±570 baris)
- `android/app/src/test/java/com/arkacode/app/core/ContainerManagerTest.kt` (baru, 3 tes murni)

## Yang dibangun

`class ContainerManager(private val context: Context, private val abi: String, prootBinary: File? = null, loaderBinary: File? = null)`

Companion (fungsi murni, diuji):

- `guestPath(id)` → `/opt/containers/<id>/rootfs`
- `rootfsDirName(id)` → `id`
- `containerBindArgs(containersHostDir)` → `["-b", "<dir>:/opt/containers"]` (dipakai Task 5)
- `SETUP_TIMEOUT_MS = 300_000`

Member:

- `containersDir()` = `<filesDir>/containers` (mkdirs)
- `rootfsDir(spec)` = `containers/<id>/rootfs`
- `shimDir()` = `containers/bin`; `shimFile(spec, binary)` = `filesDir/containers/bin/<id>-<binary>` (lewat `ContainerShim.shimPath`)
- `isInstalled(spec)` = folder rootfs ada + **semua** `releaseMarkers` ada sebagai file + **semua** shim ada
- `sizeLabel(spec)` = ukuran `containers/<id>` → `"%.1f MB"` (Locale.US, `0.0 MB` bila belum ada)
- `status(spec)` = `"terpasang"` / `"belum terpasang"`
- `suspend install(spec, onProgress)` → `Result<Unit>`
- `remove(spec)` → `Result<Unit>`

### Alur `install`

1. Idempoten: `isInstalled` → sukses tanpa jaringan.
2. Kandidat `spec.rootfsUrls(abi)` (distinct) dicoba berurutan; satu kandidat gagal → kandidat berikutnya (pola `DistroManager.tryInstall`).
3. Unduh ke `cacheDir/<id>.tar.gz` (HTTP 20s/60s, progress 0..50).
4. Ekstrak ke staging `containers/.staging-<id>/rootfs` (progress 50..77), format dari **magic bytes** (gzip/xz/zst/tar polos, pola `DistroManager.decompressor`), path traversal ditolak, symlink/hardlink dipulihkan, bit x dari mode entri.
5. Sanity check rootfs Alpine (`etc/alpine-release` / `etc/os-release` / `bin/busybox` / `usr/bin/env`).
6. `ContainerRootfs.ensureContainerLinks(stagingRootfs)` → `/root/workspace`, `/tmp`, `resolv.conf`, `hosts`, bit executable.
7. `spec.setupScript` via proot: `[proot, --kill-on-exit, --link2symlink, -0, -r <stagingRootfs>, -w /root, -b /dev, -b /proc, -b /sys, /usr/bin/env, -i, HOME=/root, PATH=…, TERM, LANG, PROOT_LOADER=…, PROOT_TMP_DIR=…, PROOT_NO_SECCOMP=1, /bin/sh, -c <setupScript>]`; env proses dari `DistroManager(context, ALPINE_320).prootProcessEnv()`; timeout 300s; output dibaca di thread terpisah (cegah deadlock pipe >64 KB); exit ≠ 0 → `IllegalStateException` dengan 800 karakter terakhir output.
8. Validasi `releaseMarkers` (mis. `usr/bin/python3`, `usr/bin/pip3`) ada di rootfs staging setelah setup.
9. Tukar atomik: hapus `containers/<id>` lama → `renameTo`, fallback `copyRecursively`.
10. Tulis shim tiap `shimBinaries` (`ContainerShim.script` + `setReadable` + `setExecutable`), `containers/<id>/home`, penanda `containers/<id>/.installed` = `"ok"`.
11. Cancellation: `CancellationException` diteruskan (download loop + polling proses tiap 200 ms).

### `remove`

Hapus `containers/<id>`, staging, dan semua file shim. Tidak memeriksa engine (gating di UI, Task 6).

## Keputusan / deviasi kecil dari brief

- Arsip unduhan: `cacheDir/<id>.tar.gz` (brief menulis `containers/<id>.tar.gz` di cacheDir — path itu tidak mungkin; dipilih cacheDir).
- Staging: `.staging-<id>/rootfs` supaya `renameTo(containers/<id>)` menghasilkan langsung `containers/<id>/rootfs` (tukar atomik satu langkah).
- Sanity check ekstraksi memakai penanda rootfs generik, **bukan** `spec.releaseMarkers` — marker container (`usr/bin/python3`, dst.) baru ada setelah `setupScript` jalan, jadi tidak bisa dipakai sebelum setup.
- `isInstalled` memeriksa shim (sesuai brief) — tanpa shim tool tidak bisa dipanggil dari distro.
- `prootBinary`/`loaderBinary` opsional di constructor (default `null` → dari `DistroManager(ALPINE_320)`) sesuai brief.

## Tests

`ContainerManagerTest` — 3 tes murni (tanpa Android, tanpa filesystem):

1. `guestPath("python")` = `/opt/containers/python/rootfs` (dan `node`).
2. `rootfsDirName` = id, konsisten dengan komponen terakhir `guestPath`.
3. `containerBindArgs(dir)` = `["-b", "<dir>:/opt/containers"]`.

**Test ditulis tapi BELUM dijalankan** (aturan user: tidak ada gradle build/test lokal — build APK di GitHub Actions CI).

## Self-review

- Hanya `Context` + `File` + `ProcessBuilder`; tidak ada API di atas minSdk 26 (`Process.waitFor(timeout, unit)`, `Process.isAlive`, `destroyForcibly`, `java.nio.file.Files` semuanya API 26).
- Tidak ada dependency baru (commons-compress sudah ada).
- Pola argv/env/extract/download menyalin `DistroManager` & `EngineManager` apa adanya.
- Progress callback dipanggil dari `Dispatchers.IO`; tidak menyentuh main thread.

## Concerns

- `setupScript` butuh jaringan **di dalam guest** (`apk add`) → `resolv.conf` dari `ensureContainerLinks` wajib; sudah ditulis sebelum setup.
- Belum ada pemanggil produksi (UI Task 6, bind Task 5); `containerBindArgs` baru dipakai Task 5.
- `sizeLabel` mengembalikan `"0.0 MB"` untuk container belum terpasang (UI sebaiknya hanya menampilkan bila terpasang).
- Test kompilasi/jalan diverifikasi CI, bukan lokal.