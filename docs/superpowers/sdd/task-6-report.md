# Task 6 Report — UI "Tool containers" + I18n

**Status:** DONE
**Commits:**
- `25ac5ff` feat(ui): tool container section in distro screen + i18n strings
- (report) docs(sdd): task 6 report

Build/test NOT run locally (build offloaded to GitHub Actions CI).

## Files changed

| File | Change |
|---|---|
| `android/app/src/main/java/com/arkacode/app/ui/I18n.kt` | +8 field di `Strings`, diisi di blok `id` dan `en` (total 3 tempat) |
| `android/app/src/main/java/com/arkacode/app/ui/DistroScreen.kt` | state container, `ContainerManager` instance, `refresh()` diperluas, seksi UI "Tool containers", `installContainer()` + `removeContainer()` |

Hanya dua berkas itu yang di-stage. `docs/superpowers/sdd/*brief.md`, `progress.md`, dan
`ContainerManager.kt` / `ContainerManagerTest.kt` (fix `rootfs/` milik sesi lain) tidak
disentuh / tidak di-commit di sini.

## I18n

8 field baru, sesuai tabel brief, di tiga tempat: `containersTitle`, `containerInstall`,
`containerRemove`, `containerInstalled`, `containerNotInstalled`, `containerRestartHint`,
`containerRemoveBusy`, `containerInstallBusy`. Tidak ada string hardcoded baru di Composable —
teks error/label tambahan memakai field yang sudah ada (`distroProotMissing`,
`distroNotAvailable`, `distroDownloading`, `failedPrefix`).

## DistroScreen

1. **State** (setelah `installToken`): `installedContainerIds`, `containerSizes`,
   `installingContainerId`, `containerProgress`, `containerProgressLabel`, `containerErrors`,
   `removingContainerId`.
2. **Instance manager**: `val containerManager = remember(deviceAbi) { ContainerManager(context, deviceAbi) }`,
   plus `val containersSupported = remember(deviceAbi) { ContainerCatalog.archFor(deviceAbi) != null }`
   (`ContainerCatalog.archFor` mengembalikan null untuk ABI tak didukung, jadi tombol Pasang
   dimatikan dan kartu menampilkan `distroNotAvailable` — tidak ada unduhan yang pasti gagal).
3. **`refresh()`**: pekerjaan IO mengembalikan `(distro) to (container)` — dua `Pair`, jadi satu
   `withContext(Dispatchers.IO)` tanpa menyentuh state Compose dari thread IO. Setelah kembali ke
   main thread baru `installedIds` / `sizes` / `installedContainerIds` / `containerSizes` ditulis.
   Perilaku distro tidak berubah (kunci `ready`/`sizes` tetap, hanya ditambah hasil container).
4. **`installContainer(spec)`**: pola `install(spec)` — guard `installingContainerId != null`
   (→ `containerInstallBusy`), guard ABI, guard `prootReady` (→ `distroProotMissing` sebagai error
   per kartu), lalu `scope.launch { withContext(IO) { containerManager.install(spec) { p, l -> scope.launch { ... } } } }`.
   Callback progress hanya menulis state lewat `scope.launch` (main), sukses → `containerRestartHint`,
   gagal → error per kartu, `finally` mereset installing/progress dan memanggil `refresh()`.
5. **`removeContainer(spec)`**: helper kecil (tidak diminta brief, tapi menghindari lambda
   `onClick` bertingkat di dalam `forEach`) — `withContext(IO) { containerManager.remove(spec) }`,
   error per kartu, `finally` reset `removingContainerId` + `refresh()`.
6. **UI**: seksi `// --- Tool containers ---` tepat sebelum `// --- Distro ---`, dibungkus
   `if (runtime == RuntimeMode.PROOT)`. Per spec: judul + label status (`containerInstalled` /
   `containerNotInstalled`), `summary`, `~N MB` + ukuran nyata dari `containerSizes`, error per
   container, progress inline (`CircularProgressIndicator` 16.dp + label +
   `LinearProgressIndicator(containerProgress / 100f)`) yang meniru kartu distro.
   Tombol: **Pasang** hanya bila belum terpasang (`enabled = prootReady && containersSupported && !isInstalling && installingContainerId == null`);
   **Hapus** hanya bila terpasang, `enabled = !engine.isRunning() && !isRemoving && installingContainerId == null`,
   dan bila engine hidup muncul keterangan `containerRemoveBusy` (teks kecil, bukan toast)
   supaya user tahu kenapa tombol mati. Tidak ada tombol Batal khusus container, sesuai brief.

## Self-review (kompilasi)

- Semua 8 nama field I18n yang dipakai di `DistroScreen.kt` ada di `Strings` (dicek dengan grep:
  8 nama unik, 1:1 dengan 8 deklarasi).
- Brace seimbang di kedua berkas (dicek skrip: kedalaman akhir 0, tidak pernah negatif).
- Import ditambah: `ContainerCatalog`, `ContainerManager`, `ContainerSpec` (alfabetis sebelum
  `DistroCatalog`). Tidak ada import baru dari dependency luar; tidak ada dependency ditambah.
- Tipe state cocok: `installedContainerIds: Set<String>` (sumber `MutableSet` → aman),
  `containerSizes: Map<String,String>`, `installingContainerId`/`removingContainerId`: `String?`,
  `containerProgress`: `Int` (`/ 100f` → `Float` untuk `LinearProgressIndicator`).
- Pemanggilan API dicek ke sumbernya: `isInstalled(spec)`, `sizeLabel(spec)`, `remove(spec)`
  (non-suspend, dipanggil di dalam `withContext(IO)`), `install(spec) { p, l -> }` (suspend,
  `Result<Unit>`, trailing lambda `(Int, String) -> Unit` — lambda mengembalikan `Job` tapi
  di-coerce ke `Unit` seperti pola `install()` distro). `withContext { ... -> Result }` sudah
  dipakai di `ProvidersScreen.kt` (pola sama, jadi aman untuk value class `Result`).
  `EngineManager.isRunning()` ada (baris 718) dan sudah dipakai untuk gating.
- Local function ordering Kotlin: `refresh()` dideklarasikan sebelum `installContainer` /
  `removeContainer`, jadi pemanggilannya valid (aturan sama yang dicatat di KDoc `install()`).
- Alur distro yang lama (install / cancel / remove / select / runtime) tidak diubah.

## Concerns

1. `engine.isRunning()` bukan state Compose yang observable — nilainya dibaca saat recompose.
   Kartu container akan menyegarkan status tombol Hapus setiap kali layar recompose atau
   `refresh()` selesai (mis. setelah kembali dari Engine screen), tapi tidak seketika saat engine
   start/stop dari layar lain. Ini sama dengan pola gating yang sudah ada di layar ini.
2. Container install sengaja **tidak** memanggil `onRuntimeChanged()` (brief hanya minta
   `message = containerRestartHint` + `refresh()`); user diberi tahu lewat pesan bahwa engine
   perlu di-restart.
3. `installContainer` belum punya tombol Batal (sesuai brief) — pembatalan hanya terjadi kalau
   layar ditinggal (scope composable dibatalkan).
4. Belum dijalankan: `assembleDebug` / unit test. Verifikasi hanya statis (baca diff + skrip
   keseimbangan kurung + grep nama field). Build sepenuhnya di CI.