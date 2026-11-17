# Task 6 Brief: UI "Tool containers" + I18n

## Files
- Modify: `android/app/src/main/java/com/arkacode/app/ui/DistroScreen.kt` (544 baris)
- Modify: `android/app/src/main/java/com/arkacode/app/ui/I18n.kt` (817 baris)

## Context

Arka-Code (Android Kotlin, Jetpack Compose). `DistroScreen` sudah punya kartu runtime
+ daftar distro dengan progress inline. Task ini menambah seksi **Tool containers**
memakai pola yang sudah ada.

Kelas pendukung sudah committed (Task 1–5):
- `ContainerCatalog.ALL` — daftar `ContainerSpec` (python, node)
- `ContainerSpec`: `id`, `label`, `summary`, `approxSizeMb`, `shimBinaries`
- `ContainerManager(context, abi)`:
  - `isInstalled(spec): Boolean`
  - `sizeLabel(spec): String` (mis. `"42.3 MB"`)
  - `suspend install(spec, onProgress: (Int, String) -> Unit): Result<Unit>`
  - `remove(spec): Result<Unit>`
  - `status(spec): String`

## Anchor baris di DistroScreen.kt (WAJIB dicek ulang sebelum edit)

- Baris ~76–103: blok `var ... by remember { mutableStateOf(...) }` (state layar).
  Sudah ada: `installedIds`, `sizes`, `prootReady`, `progress`, `progressLabel`,
  `message`, `installingId`, `installJob`, `installErrors`, `removingId`, `installToken`.
- Baris ~106: `val deviceAbi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a" }`
- Baris ~117–141: `fun refresh()` — scope.launch + `withContext(Dispatchers.IO)`.
- Baris ~334–381: blok `// --- Runtime ---` (ClayCard).
- Baris ~383: `// --- Distro ---` ← **seksi container disisipkan TEPAT SEBELUM baris ini**.

## Perubahan I18n.kt

`Strings` adalah class dengan field `var x: String = ""`, lalu ada dua blok:
`private val id = Strings().apply { ... }` (~baris 289) dan
`private val en = Strings().apply { ... }` (~baris 551).
**Tambahkan ke TIGA tempat** (deklarasi class + blok `id` + blok `en`).
Teks Indonesia di blok `id`, Inggris di blok `en`:

| field | id | en |
|---|---|---|
| `containersTitle` | `Tool containers` | `Tool containers` |
| `containerInstall` | `Pasang` | `Install` |
| `containerRemove` | `Hapus` | `Remove` |
| `containerInstalled` | `terpasang` | `installed` |
| `containerNotInstalled` | `belum terpasang` | `not installed` |
| `containerRestartHint` | `Container terpasang. Restart engine (Stop → Start) agar PATH container aktif di server.` | `Container installed. Restart engine (Stop → Start) for the container PATH to apply to the server.` |
| `containerRemoveBusy` | `Hentikan engine dulu sebelum menghapus container.` | `Stop the engine before removing a container.` |
| `containerInstallBusy` | `Sedang memasang container lain…` | `Another container is installing…` |

## Perubahan DistroScreen.kt

### 1. State baru (setelah baris ~103, dekat `installToken`)

```kotlin
var installedContainerIds by remember { mutableStateOf<Set<String>>(emptySet()) }
var containerSizes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
var installingContainerId by remember { mutableStateOf<String?>(null) }
var containerProgress by remember { mutableStateOf(0) }
var containerProgressLabel by remember { mutableStateOf("") }
var containerErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
var removingContainerId by remember { mutableStateOf<String?>(null) }
```

Satu instance manager di tingkat composable (dekat `deviceAbi`):

```kotlin
val containerManager = remember(deviceAbi) { ContainerManager(context, deviceAbi) }
```

### 2. `refresh()` — tambahkan pengecekan container

Di dalam `withContext(Dispatchers.IO)` yang sudah ada (yang mengisi `ready`/`sizes`),
tambahkan perhitungan container memakai `containerManager`:

```kotlin
val containerReady = mutableSetOf<String>()
val containerSz = mutableMapOf<String, String>()
ContainerCatalog.ALL.forEach { spec ->
    if (containerManager.isInstalled(spec)) {
        containerReady += spec.id
        containerSz[spec.id] = containerManager.sizeLabel(spec)
    }
}
```

Lalu setelah `installedIds = ids` / `sizes = sz`, set `installedContainerIds` dan
`containerSizes`. (Perhatikan: `withContext` mengembalikan satu nilai — ubah menjadi
`Triple(ready, sizes, containerReady to containerSz)` atau kembalikan data class kecil;
jangan menyentuh state Compose dari thread IO.)

### 3. Seksi UI — sisipkan tepat sebelum `// --- Distro ---` (baris ~383)

Bungkus dengan `if (runtime == RuntimeMode.PROOT) { ... }` karena runtime TERMUX
tidak memakai bind container.

```kotlin
// --- Tool containers ---------------------------------------------
if (runtime == RuntimeMode.PROOT) {
    Text(
        I18n.s.containersTitle,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
    ContainerCatalog.ALL.forEach { spec ->
        val isInstalled = spec.id in installedContainerIds
        val isInstalling = spec.id == installingContainerId
        val isRemoving = spec.id == removingContainerId
        val error = containerErrors[spec.id]
        ClayCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Judul + status (pola kartu distro baris 401–421)
                // summary + "~N MB" (pola baris 422–447)
                // error?.let { Text(it, labelSmall, color = error) }
                // if (isInstalling) { Row(CircularProgressIndicator 16.dp + containerProgressLabel) + LinearProgressIndicator(containerProgress / 100f) }
                // TextButton "Pasang" / "Hapus"
            }
        }
    }
}
```

Tombol:
- **Pasang** — `onClick = { installContainer(spec) }`,
  `enabled = prootReady && !isInstalling && installingContainerId == null`
  (sembunyikan bila sudah terpasang).
- **Hapus** — hanya bila `isInstalled`; `enabled = !engine.isRunning() && !isRemoving`;
  bila `engine.isRunning()` tampilkan teks `I18n.s.containerRemoveBusy` sebagai
  keterangan kecil (bukan toast) supaya user tahu kenapa tombol mati.
  `onClick` → `scope.launch { withContext(IO) { containerManager.remove(spec) } ... refresh() }`.

### 4. Fungsi `installContainer(spec)` (lokal di composable, pola `install(spec)` baris ~200)

- Bila `installingContainerId != null` → `message = I18n.s.containerInstallBusy`, return.
- Bila `!prootReady` → `containerErrors = containerErrors + (spec.id to I18n.s.distroProotMissing)`, return.
- Set `installingContainerId = spec.id`, reset `containerProgress = 0`,
  `containerProgressLabel = ""`, hapus error spec ini.
- `scope.launch` → `withContext(Dispatchers.IO) { containerManager.install(spec) { p, l -> ... } }`.
  Callback progress datang dari thread IO: **jangan** langsung set state Compose —
  pakai `scope.launch { containerProgress = p; containerProgressLabel = l }` (pola
  baris ~236–239).
- Sukses → `message = I18n.s.containerRestartHint`, lalu `refresh()`.
- Gagal → `containerErrors = containerErrors + (spec.id to (error.message ?: I18n.s.failedPrefix))`.
- `finally`-style: `installingContainerId = null`, `containerProgress = 0`,
  `containerProgressLabel = ""`.

## Constraints

- **JANGAN** mengubah alur distro yang sudah ada (install/cancel/remove/select).
- Semua teks lewat `I18n` (dua bahasa). Jangan ada string hardcoded di Composable.
- Ikuti gaya existing: `ClayCard`, `TextButton`, `MaterialTheme.typography.labelSmall/bodySmall`,
  `MaterialTheme.colorScheme.onSurfaceVariant` / `.error`.
- Import baru yang mungkin perlu: `com.arkacode.app.core.ContainerCatalog`,
  `com.arkacode.app.core.ContainerManager`. Cek blok import di atas file.
- Perhatikan `remember(deviceAbi)` — `ContainerManager` ringan (pembungkus `File`), aman.
- Tidak perlu tombol Batal khusus container (cukup disable tombol lain saat memasang).

## Build/Test Rules (PENTING — override instruksi umum)

- **JANGAN menjalankan gradle build/test apa pun di mesin lokal** (tidak ada `./gradlew`,
  tidak ada `gradle test`). User mem-build APK langsung di GitHub Actions.
- Verifikasi: baca ulang diff dan pastikan kompilasi logis (nama field I18n cocok,
  kurung kurawal Compose seimbang, tipe state cocok). Laporkan di report bahwa
  build/test BELUM dijalankan (build offloaded ke CI).
- Jangan install dependency baru.

## Commit

- `feat(ui): tool container section in distro screen + i18n strings`
- Lalu report: `docs(sdd): task 6 report`

## Report

Tulis laporan ke `docs/superpowers/sdd/task-6-report.md`.