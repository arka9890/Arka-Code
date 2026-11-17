# Task 4 Brief: ContainerManager

## Files
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerManager.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerManagerTest.kt`

## Context

Arka-Code (Android Kotlin). Task ini adalah inti fitur: unduh + ekstrak + instal mini-rootfs container ke `<filesDir>/containers/<id>/`, tulis shim, status, hapus.

Pola yang diikuti (WAJIB dibaca):
- `android/app/src/main/java/com/arkacode/app/core/DistroManager.kt`:
  - `tryInstall()` (staging `.staging-<id>` → tukar atomik, kandidat URL, penanda)
  - `download()` + `extractArchive()`/`decompressor()` (magic bytes, progress callback)
  - `prootProcessEnv()`, `ensureLibLinks()`
- `android/app/src/main/java/com/arkacode/app/core/ContainerRootfs.kt` (Task 2 — `ensureContainerLinks`)
- `android/app/src/main/java/com/arkacode/app/core/ContainerShim.kt` (Task 3 — `script`, `shimPath`)
- `android/app/src/main/java/com/arkacode/app/core/ContainerSpec.kt` (Task 1 — spec, rootfsUrls)

## What to Build

`class ContainerManager(private val context: Context, private val abi: String)`:

Companion fungsi murni (diuji):
- `fun guestPath(id: String): String` = `"/opt/containers/$id/rootfs"`
- `fun rootfsDirName(id: String): String` = `id`
- `fun containerBindArgs(containersHostDir: String): List<String>` = `listOf("-b", "$containersHostDir:/opt/containers")`

Member:
- `fun containersDir(): File` = `File(context.filesDir, "containers")` (mkdirs)
- `fun rootfsDir(spec: ContainerSpec): File` = `File(containersDir(), "${spec.id}/rootfs")`
- `fun shimDir(): File` = `File(containersDir(), "bin")`
- `fun isInstalled(spec: ContainerSpec): Boolean` — rootfsDir ada + semua `spec.releaseMarkers` ada di rootfs + semua `spec.shimBinaries` punya file shim (`shimDir/<id>-<binary>`, nama dari `ContainerShim.shimPath`)
- `fun sizeLabel(spec: ContainerSpec): String` — ukuran `containers/<id>` dalam MB `%.1f MB`
- `suspend fun install(spec: ContainerSpec, onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit>`:
  1. Jika sudah terpasang → success.
  2. Unduh kandidat `spec.rootfsUrls(abi)` (berurutan; pola `DistroManager.install`/`tryInstall`) ke `containers/<id>.tar.gz` di cacheDir.
  3. Ekstrak ke staging `containers/.staging-<id>` (pola extractArchive DistroManager dengan magic bytes).
  4. `ContainerRootfs.ensureContainerLinks(stagingRootfs)` + tulis `etc/resolv.conf` (sudah di helper).
  5. Jalankan `spec.setupScript` di dalam staging via proot: argv = `[proot, --kill-on-exit, --link2symlink, -0, -r, <stagingRootfs>, -w, /root, -b, /dev, -b, /proc, -b, /sys, <env>, /bin/sh, -c, spec.setupScript]` dengan env dari `distro-like` prootProcessEnv (buat DistroManager sementara dengan spec ALPINE_320 untuk binary proot/loader — atau parameter prootBinary/loaderBinary; pilih: constructor kedua `ContainerManager(context, abi, prootBinary: File?, loaderBinary: File?)` dengan default null → ambil dari DistroManager(context, DistroCatalog.ALPINE_320)). Timeout 300s.
  6. Tukar staging → rootfs (hapus lama).
  7. Tulis shim tiap `shimBinaries` via `ContainerShim.script` ke `shimDir/<id>-<binary>` + setExecutable.
  8. mkdir `containers/<id>/home`.
  9. Tulis penanda `containers/<id>/.installed` ("ok").
- `fun remove(spec: ContainerSpec): Result<Unit>` — hapus `containers/<id>` + shim files. TIDAK menolak (gating di UI — lihat catatan).
- `fun status(spec: ContainerSpec): String` — "terpasang" / "belum terpasang" (label untuk UI).

Catatan gating: `remove()` TIDAK memeriksa engine; UI (Task 6) yang menonaktifkan tombol saat `engine.isRunning()`.

## Tests (ContainerManagerTest)

Dua tes murni (lihat plan Task 4): `guestPath("python")` = `/opt/containers/python/rootfs`; `rootfsDirName` aman. Tambah tes `containerBindArgs`.

## Constraints

- Kotlin, JUnit4, tanpa Android untuk fungsi murni.
- Progress callback dipanggil dari thread IO; jangan sentuh Android main thread (pola DistroManager).
- Cancellation: lempar `kotlinx.coroutines.CancellationException` bila job dibatalkan (pola DistroManager.tryInstall).

## Build/Test Rules (PENTING — override instruksi umum)

- **JANGAN menjalankan gradle build/test apa pun di mesin lokal** (tidak ada `./gradlew`, tidak ada `gradle test`). User mem-build APK langsung di GitHub Actions.
- Verifikasi: cukup pastikan kode kompilasi secara logis (self-review diff) + tulis tes yang benar. Laporkan di report bahwa tes ditulis tapi BELUM dijalankan (reason: build offloaded ke CI).
- Jangan install dependency baru.

## Report

Tulis laporan ke `docs/superpowers/sdd/task-4-report.md`.