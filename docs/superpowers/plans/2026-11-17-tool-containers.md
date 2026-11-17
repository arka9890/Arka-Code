# Tool Container Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tool (python, node) diinstall sekali ke mini-rootfs container di luar folder distro, dan tetap berfungsi setelah user ganti distro proot.

**Architecture:** Mini-rootfs Alpine 3.20 per tool di `<filesDir>/containers/<id>/rootfs` (pola staging + penanda dari DistroManager). Di-bind ke `/opt/containers` di semua distro lewat tiga tempat pembangunan argv proot. Shim di `/opt/containers/bin` mengeksekusi binary tool langsung (PT_INTERP musl resolve ke loader yang sudah dipasang `MuslRuntime` di tiap distro — jalur yang sama dengan binary opencode). Shim mengekspor `HOME` + `SSL_CERT_FILE` yang menunjuk ke dalam container.

**Tech Stack:** Kotlin, Compose, JUnit 4 (unit test JVM, pola `app/src/test`), proot (binary Termux yang sudah dibundel di APK), Alpine minirootfs 3.20.3.

**Spec:** docs/superpowers/specs/2026-11-17-tool-containers-design.md

## Global Constraints

- Container rootfs = Alpine minirootfs 3.20.3 (branch v3.20), **versi identik dengan distro default** (`DistroCatalog.ALPINE_320`) agar library musl tidak bentrok saat shadowing.
- Loader musl yang dipakai adalah salinan milik distro aktif (dari `MuslRuntime.ensure`), BUKAN loader eksplisit. PT_INTERP `/lib/ld-musl-<arch>.so.1` resolving lewat proot — persis jalur `MuslRuntime.ensureMuslRuntime()`.
- Arch map container (BUKAN `MuslRuntime.archFor` — itu hanya arm64/x64; container harus 4 ABI): arm64-v8a→aarch64, armeabi-v7a→armv7, x86_64→x86_64, x86→x86.
- Semua teks UI harus lewat `I18n.kt` (pola `val xxx: String get() = ...`), bilingual id + en.
- Unit test mengikuti pola `app/src/test/java/com/arkacode/app/core/*Test.kt` (JUnit4, fungsi murni).
- Hapus container ditolak selama `engine.isRunning()` — pola sama dengan `installRelease()`.
- Import offline (SAF) di luar cakupan.
- RuntimeMode.TERMUX tidak memakai bind container sama sekali.

## Review Focus

1. **Aplikasi sudah upgrade / shim hilang** — binary tool yang di-shim hilang (container dihapus manual / upgrade) → `status()` harus mendeteksi shim hilang dan menulisnya ulang; user yang tidak melakukan apa-apa tetap bisa jalankan tool setelah app di-update. → Diuji di Task 4 (`status menulis ulang shim yang hilang`).
2. **`pip install --user` saat tool dijalankan lewat shim di distro** — HOME tool harus mengarah ke folder persisten container, bukan `/root` distro yang bisa berganti. → Diuji di Task 3 (shim mengekspor HOME) + Task 6 (env serve berisi `ARKACODE_CONTAINERS` yang sama).
3. **Distro mini rootfs tanpa CA** — `pip3 install` via HTTPS dari Alpine mini maupun distro glibc mini gagal tanpa `SSL_CERT_FILE` → skrip instal container wajib memasang `ca-certificates` dan shim wajib mengekspor `SSL_CERT_FILE`. → Diuji di Task 2 (skrip instal memuat ca-certificates) + Task 3 (shim mengekspor SSL_CERT_FILE).
4. **Unduhan container gagal di tengah (jaringan putus / cancel)** — folder staging setengah jadi tidak boleh menghancurkan instalasi sebelumnya. → Diuji di Task 4 (install sukses menyisakan rootfs valid + penanda).
5. **Distro layout Termux (TERMUX_PREFIX) tidak punya `/bin/sh` di root rootfs** — shim `#!/bin/sh` gagal dieksekusi; user Termux tidak bisa pakai container. → Diuji di Task 2 (postInstall Termux membuat `/bin/sh` saat `sh` belum ada di root rootfs).

---

### Task 1: ContainerSpec + ContainerCatalog

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerSpec.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerSpecTest.kt`

**Interfaces:**
- Produces:
  - `data class ContainerSpec(id: String, label: String, summary: String, approxSizeMb: Int, releaseMarkers: List<String>, setupScript: String, shimBinaries: List<String>, rootfsUrls: (abi: String) -> List<String>)`
  - `object ContainerCatalog { val PYTHON: ContainerSpec; val NODE: ContainerSpec; val ALL: List<ContainerSpec>; fun byId(id: String?): ContainerSpec; fun archFor(abi: String): String?; fun loaderName(abi: String): String? }`
  - `ContainerCatalog.archFor` memetakan 4 ABI; `loaderName` mengembalikan nama file loader (`ld-musl-aarch64.so.1`, `ld-musl-armhf.so.1`, `ld-musl-x86_64.so.1`, `ld-musl-i386.so.1`).

- [ ] **Step 1: Tulis tes yang gagal**

```kotlin
class ContainerSpecTest {
    @Test fun `arch dipetakan untuk empat abi`() {
        assertEquals("aarch64", ContainerCatalog.archFor("arm64-v8a"))
        assertEquals("armv7", ContainerCatalog.archFor("armeabi-v7a"))
        assertEquals("x86_64", ContainerCatalog.archFor("x86_64"))
        assertEquals("x86", ContainerCatalog.archFor("x86"))
        assertNull(ContainerCatalog.archFor("mips"))
    }
    @Test fun `loader name mengikuti arch`() {
        assertEquals("ld-musl-aarch64.so.1", ContainerCatalog.loaderName("arm64-v8a"))
        assertEquals("ld-musl-armhf.so.1", ContainerCatalog.loaderName("armeabi-v7a"))
        assertEquals("ld-musl-x86_64.so.1", ContainerCatalog.loaderName("x86_64"))
        assertEquals("ld-musl-i386.so.1", ContainerCatalog.loaderName("x86"))
    }
    @Test fun `url rootfs memakai minirootfs alpine 3_20 dengan arch benar`() {
        val urls = ContainerCatalog.PYTHON.rootfsUrls("arm64-v8a")
        assertTrue(urls.first().startsWith("https://dl-cdn.alpinelinux.org/alpine"))
        assertTrue(urls.first().contains("v3.20"))
        assertTrue(urls.first().contains("alpine-minirootfs-3.20.3-aarch64.tar.gz"))
        assertTrue(ContainerCatalog.PYTHON.rootfsUrls("x86").first().contains("alpine-minirootfs-3.20.3-x86.tar.gz"))
    }
    @Test fun `url punya mirror cadangan dan https`() {
        ContainerCatalog.ALL.forEach { spec ->
            val urls = spec.rootfsUrls("arm64-v8a")
            assertTrue("${spec.id} harus punya >1 kandidat", urls.size > 1)
            urls.forEach { assertTrue("${spec.id} harus https", it.startsWith("https://")) }
        }
    }
    @Test fun `skrip instal python memuat python3 ca-certificates`() {
        assertTrue(ContainerCatalog.PYTHON.setupScript.contains("python3"))
        assertTrue(ContainerCatalog.PYTHON.setupScript.contains("ca-certificates"))
    }
    @Test fun `skrip instal node memuat nodejs ca-certificates`() {
        assertTrue(ContainerCatalog.NODE.setupScript.contains("nodejs"))
        assertTrue(ContainerCatalog.NODE.setupScript.contains("ca-certificates"))
    }
    @Test fun `byId aman untuk nilai tak dikenal`() {
        assertEquals(ContainerCatalog.PYTHON.id, ContainerCatalog.byId(null).id)
        assertEquals(ContainerCatalog.PYTHON.id, ContainerCatalog.byId("ngawur").id)
        assertEquals(ContainerCatalog.NODE.id, ContainerCatalog.byId("node").id)
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal (class tidak ada)**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerSpecTest"`
Expected: FAIL — unresolved reference `ContainerSpec`/`ContainerCatalog`.

- [ ] **Step 3: Implementasikan `ContainerSpec.kt`**

Satu `data class ContainerSpec` + `object ContainerCatalog`. URL rootfs dibangun dari `DistroCatalog.CDN` + `DistroCatalog.MIRRORS` dengan pola yang sama seperti `DistroCatalog.ALPINE_320.rootfsUrls`, tetapi hardcoded v3.20.3 (sama dengan `DistroCatalog.ALPINE_320` default). `archFor`/`loaderName` adalah fungsi murni (peta ABI→arch; loader = `"ld-musl-$arch.so.1"` dengan armv7→armhf, x86→i386). `setupScript` raw string sh: `apk add --no-cache python3 py3-pip ca-certificates` (python) / `apk add --no-cache nodejs npm ca-certificates` (node). `shimBinaries` = `listOf("python3", "pip3")` / `listOf("node", "npm", "npx")`. `ALL = listOf(PYTHON, NODE)`.

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerSpecTest"`
Expected: PASS (7 tes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/ContainerSpec.kt android/app/src/test/java/com/arkacode/app/core/ContainerSpecTest.kt
git commit -m "feat(core): add ContainerSpec + ContainerCatalog for tool containers"
```

---

### Task 2: postInstall container-rootfs + PATH musl helper

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerRootfs.kt` (objek util murni: `ensureContainerLinks`, `appendMuslPath`)
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerRootfsTest.kt`

**Interfaces:**
- Consumes: `ContainerSpec`, `DistroSpec` (dari Task 1 / kode yang ada).
- Produces:
  - `fun ContainerRootfs.ensureContainerLinks(containerRootfsDir: File, spec: ContainerSpec)` — buat `/root/workspace`, `/tmp`, tulis `/etc/resolv.conf` + `/etc/hosts` (nilai sama dengan `DistroManager.postInstall`), set executable pada semua file di `bin`/`usr/bin`/`usr/local/bin` yang ELF/shebang (pola `extractZip` di DistroManager).
  - `fun ContainerRootfs.appendMuslPath(existingPath: String, guestDir: String): String` — mengembalikan `existingPath` bila `guestDir` sudah ada di dalamnya (dipisah `:`, dibanding trim); jika kosong → `guestDir`; selain itu → `"$existingPath:$guestDir"`.
  - Untuk Termux: pastikan `sh` ada di root rootfs — buat `<rootfs>/bin/sh` bila `<rootfs>/bin/sh` tidak ada (symlink ke path sh yang sudah ada di dalam rootfs, mis. `usr/bin/sh`), sesuai risiko di spec.

- [ ] **Step 1: Tulis tes yang gagal**

```kotlin
class ContainerRootfsTest {
    @Test fun `append musl path saat kosong`() {
        assertEquals("/opt/containers/python/rootfs/usr/lib",
            ContainerRootfs.appendMuslPath("", "/opt/containers/python/rootfs/usr/lib"))
    }
    @Test fun `append musl path menambahkan setelah path lama`() {
        assertEquals("/opt/arkacode/lib:/opt/containers/node/rootfs/usr/lib",
            ContainerRootfs.appendMuslPath("/opt/arkacode/lib", "/opt/containers/node/rootfs/usr/lib"))
    }
    @Test fun `append musl path idempoten`() {
        val once = ContainerRootfs.appendMuslPath("", "/opt/containers/python/rootfs/usr/lib")
        assertEquals(once, ContainerRootfs.appendMuslPath(once, "/opt/containers/python/rootfs/usr/lib"))
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerRootfsTest"`
Expected: FAIL — unresolved reference `ContainerRootfs`.

- [ ] **Step 3: Implementasikan `ContainerRootfs.kt`**

`appendMuslPath` murni split(':'). `ensureContainerLinks` meniru `postInstall` DistroManager untuk field umum (workspace, tmp, resolv/hosts, chmod ELF/shebang). Untuk `sh` layout Termux: `shPath` yang dicari adalah `<rootfs>/<prefix>/bin/sh` (prefix dari spec Termux); jika tidak ada, buat symlink `<rootfs>/bin/sh` → path relatif yang benar di dalam rootfs.

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerRootfsTest"`
Expected: PASS (3 tes).

- [ ] **Step 4b: Jalankan seluruh unit test (regresi)**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS (termasuk `DistroSpecTest`, `MuslRuntimeTest` yang ada).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/ContainerRootfs.kt android/app/src/test/java/com/arkacode/app/core/ContainerRootfsTest.kt
git commit -m "feat(core): container rootfs post-install helpers + musl path append"
```

---

### Task 3: Shim generator

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerShim.kt` (objek murni)
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerShimTest.kt`

**Interfaces:**
- Consumes: `ContainerSpec` (Task 1).
- Produces: `fun ContainerShim.script(spec: ContainerSpec, binary: String, abi: String): String` — isi skrip `#!/bin/sh` yang:
  - `export HOME=/opt/containers/<id>/home`
  - `export SSL_CERT_FILE=/opt/containers/<id>/rootfs/etc/ssl/certs/ca-certificates.crt`
  - `exec /opt/containers/<id>/rootfs/usr/bin/<binary> "$@"` — dengan `<binary>` yang dipetakan ke path sebenarnya di dalam rootfs (`python3` → `usr/bin/python3`, `pip3` → `usr/bin/pip3`, `node` → `usr/bin/node`, `npm`/`npx` → `usr/bin/npm`/`usr/bin/npx`).
- `fun ContainerShim.shimPath(id: String, binary: String): String` = `containers/bin/<id>-<binary>` (nama file host, menghindari bentrok antar container dengan binary sama).

- [ ] **Step 1: Tulis tes yang gagal**

```kotlin
class ContainerShimTest {
    @Test fun `shim python3 benar`() {
        val s = ContainerShim.script(ContainerCatalog.PYTHON, "python3", "arm64-v8a")
        assertTrue(s.startsWith("#!/bin/sh"))
        assertTrue(s.contains("export HOME=/opt/containers/python/home"))
        assertTrue(s.contains("export SSL_CERT_FILE=/opt/containers/python/rootfs/etc/ssl/certs/ca-certificates.crt"))
        assertTrue(s.contains("exec /opt/containers/python/rootfs/usr/bin/python3 \"$@\""))
    }
    @Test fun `shim pip3 memakai path rootfs yang sama`() {
        val s = ContainerShim.script(ContainerCatalog.PYTHON, "pip3", "arm64-v8a")
        assertTrue(s.contains("exec /opt/containers/python/rootfs/usr/bin/pip3 \"$@\""))
    }
    @Test fun `shim path host tidak bentrok antar container`() {
        assertEquals("containers/bin/python-python3", ContainerShim.shimPath("python", "python3"))
        assertEquals("containers/bin/node-node", ContainerShim.shimPath("node", "node"))
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerShimTest"`
Expected: FAIL — unresolved reference `ContainerShim`.

- [ ] **Step 3: Implementasikan `ContainerShim.kt`**

`script()` menyusun baris sesuai tes. `shimPath()` = `"containers/bin/$id-$binary"`. File ditulis oleh `ContainerManager` (Task 4) dengan `setExecutable(true)`.

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerShimTest"`
Expected: PASS (3 tes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/ContainerShim.kt android/app/src/test/java/com/arkacode/app/core/ContainerShimTest.kt
git commit -m "feat(core): container shim generator (HOME + SSL_CERT_FILE + exec binary)"
```

---

### Task 4: ContainerManager (unduh, ekstrak, instal, status, hapus)

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerManager.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerManagerTest.kt`

**Interfaces:**
- Consumes: `ContainerSpec`/`ContainerCatalog` (1), `ContainerRootfs` (2), `ContainerShim` (3), `DistroManager` (prootBinary/loaderBinary/rootfsDir/abi/prootProcessEnv/buildProotCommand dari kode yang ada).
- Produces:
  - `class ContainerManager(private val context: Context, private val abi: String)` dengan
    - `suspend fun install(spec: ContainerSpec, onProgress: (Int, String) -> Unit = { _, _ -> }): Result<Unit>`
    - `fun isInstalled(spec: ContainerSpec): Boolean` — `rootfsDir` ada + `spec.releaseMarkers` ada di dalam rootfs + semua `shimBinaries` punya shim di `containers/bin`.
    - `fun sizeLabel(spec: ContainerSpec): String` — `"%.1f MB".format(...)` dari isi `containers/<id>`.
    - `fun remove(spec: ContainerSpec): Result<Unit>` — tolak bila engine jalan (dipanggil dari UI, engine dipegang pemanggil — lihat catatan).
    - `fun rootfsDir(spec: ContainerSpec): File` = `File(File(context.filesDir, "containers"), spec.id)`; `guestRootfs(spec)` = `"/opt/containers/${spec.id}/rootfs"`.
  - `ContainerManager.serveArgvContainerBinds(): List<String>` — mengembalikan `listOf("-b", "<filesDir>/containers:/opt/containers")`.
- Alur `install()`: cek `isInstalled` → unduh kandidat URL (pola `DistroManager.tryInstall`: staging `.staging-<id>` di `containers/`, tukar atomik) → `ContainerRootfs.ensureContainerLinks` → jalankan `spec.setupScript` lewat proot `-r <rootfs>` dengan env `PROOT_NO_SECCOMP=1`, `--link2symlink`, `-0`, `-b /dev -b /proc -b /sys` → tulis shim tiap `shimBinaries` via `ContainerShim.script` → tulis penanda `.installed`.

- [ ] **Step 1: Tulis tes yang gagal (unit, tanpa Android)**

```kotlin
class ContainerManagerTest {
    // pakai Robolectric? TIDAK — tes di sini murni untuk fungsi yang bisa
    // diuji tanpa Context. Uji: guestRootfs(), rootfsDir(), isInstalled()
    // dengan folder sementara (butuh Context -> gunakan mock sederhana
    // via org.mockito? Proyek belum pakai Mockito — jadi batasi tes ke
    // fungsi murni yang dipisahkan: guestPath(id), rootfsDirName(id),
    // dan validasi penanda lewat helper statis).
    @Test fun `guestRootfs mengikuti pola opt containers`() {
        assertEquals("/opt/containers/python/rootfs", ContainerManager.guestPath("python"))
    }
    @Test fun `nama folder container aman`() {
        assertEquals("python", ContainerManager.rootfsDirName("python"))
    }
}
```

Catatan implementasi: `guestPath(id)` dan `rootfsDirName(id)` adalah fungsi murni di companion object — ini yang diuji. Logika Context-bound (unduh/ekstrak) diverifikasi lewat build + uji manual (spec bagian "What's left to the builder").

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerManagerTest"`
Expected: FAIL — unresolved reference `ContainerManager`.

- [ ] **Step 3: Implementasikan `ContainerManager.kt`**

Susun sesuai Interfaces. Unduhan + ekstraksi meniru `DistroManager` (magic bytes decompressor, staging, penanda). Penanda akhir: file `.installed` di `containers/<id>/`. `remove()` menolak dengan `IllegalStateException` bila engine dipanggil dengan flag — implementasi: `remove(spec, allowWhileRunning: Boolean = false)`; UI (Task 6) memeriksa `engine.isRunning()` sebelum memanggil. `install()` juga menulis `containers/<id>/home/` (mkdirs).

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerManagerTest"`
Expected: PASS (2 tes).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/ContainerManager.kt android/app/src/test/java/com/arkacode/app/core/ContainerManagerTest.kt
git commit -m "feat(core): ContainerManager — download/extract/install/status/remove tool container"
```

---

### Task 5: Bind + PATH + ld-musl append di tiga argv builder

**Files:**
- Modify: `android/app/src/main/java/com/arkacode/app/core/DistroManager.kt` (`buildProotCommand`, `ensureMuslRuntime` — append path)
- Modify: `android/app/src/main/java/com/arkacode/app/core/EngineManager.kt` (`baseArgv`, `guestEnvTail`, `serveArgv`)
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerExposureTest.kt`

**Interfaces:**
- Consumes: `ContainerManager` (4).
- Produces: tidak ada API baru — perilaku: setiap argv proot memuat `-b <filesDir>/containers:/opt/containers` dan guest PATH diawali `/opt/containers/bin:`.

- [ ] **Step 1: Tulis tes yang gagal (helper murni untuk PATH)**

Tambahkan fungsi murni di `DistroManager`:
`fun DistroManager.guestPathValueWithContainers(): String = "/opt/containers/bin:" + guestPathValue()`
Test:

```kotlin
class ContainerExposureTest {
    @Test fun `path containers di depan path distro`() {
        val dm = DistroManager(mockContext, DistroCatalog.ALPINE_320) // context mock sederhana
        assertTrue(dm.guestPathValueWithContainers().startsWith("/opt/containers/bin:"))
        assertTrue(dm.guestPathValueWithContainers().contains(":" + dm.guestPathValue()))
    }
    // Verifikasi struktur argv: bind containers hadir di buildProotCommand
    // membutuhkan rootfs terpasang — gunakan DistroManager yang di-stub di
    // tes instrumentasi. Untuk JVM: pisahkan pembentukan bind ke fungsi
    // companion `containerBindArgs(): List<String>` dan uji itu.
    @Test fun `bind containers berisi pasangan -b dan path`() {
        val args = DistroManager.containerBindArgs("/data/user/0/com.arkacode.app/files/containers")
        assertEquals(listOf("-b", "/data/user/0/com.arkacode.app/files/containers:/opt/containers"), args)
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.arkacode.app.core.ContainerExposureTest"`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implementasikan**

1. `DistroManager.companion object`: tambah `fun containerBindArgs(containersDir: String): List<String>`.
2. `DistroManager.buildProotCommand`: setelah blok `if (bindWorkspace ...)` sisip `argv += containerBindArgs(File(context.filesDir, "containers").absolutePath)` dan ganti `PATH=${guestPathValue()}` menjadi `PATH=/opt/containers/bin:${guestPathValue()}`.
3. `DistroManager.ensureMuslRuntime`: setelah menulis `ld-musl-$arch.path` dengan `MuslRuntime.GUEST_LIB_DIR`, append path container untuk tiap container terpasang via `ContainerRootfs.appendMuslPath` — file ditulis dengan nilai gabungan (idempoten).
4. `EngineManager.baseArgv`: sisip bind containers (pola sama).
5. `EngineManager.guestEnvTail`: PATH menjadi `/opt/containers/bin:/usr/local/sbin:...` (path containers dulu).
6. `EngineManager.serveArgv`: bind containers + PATH `/opt/containers/bin:${distro.guestPathValue()}`.

- [ ] **Step 4: Jalankan tes, pastikan lolos + regresi**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS semua.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/DistroManager.kt android/app/src/main/java/com/arkacode/app/core/EngineManager.kt android/app/src/test/java/com/arkacode/app/core/ContainerExposureTest.kt
git commit -m "feat(core): bind containers + prepend /opt/containers/bin in all proot argv builders"
```

---

### Task 6: UI — seksi Tool containers di DistroScreen + I18n

**Files:**
- Modify: `android/app/src/main/java/com/arkacode/app/ui/DistroScreen.kt`
- Modify: `android/app/src/main/java/com/arkacode/app/ui/I18n.kt`
- Test: tidak ada unit test UI (pola proyek) — verifikasi lewat build.

**Interfaces:**
- Consumes: `ContainerManager`, `ContainerCatalog`, `EngineManager` (dari `engine` param DistroScreen).

- [ ] **Step 1: Tambah string I18n**

`I18n.kt` (dan padanannya): `containersTitle`, `containerInstall`, `containerRemove`, `containerInstalled`, `containerNotInstalled`, `containerRestartHint` (catatan restart engine), `containerRemoveBusy` (hapus ditolak saat engine jalan), `containerInstallBusy`.

- [ ] **Step 2: Implementasikan seksi UI**

Di `DistroScreen`, setelah blok Runtime / sebelum daftar distro (atau setelah daftar — pilih satu, konsisten), tambah `ClayCard` per `ContainerCatalog.ALL`:
- Baris judul: label + status (`containerInstalled` / `containerNotInstalled` / ukuran `sizeLabel`).
- Tombol Pasang (enabled bila `prootReady` dan tidak sedang install lain) → jalankan `ContainerManager.install` dengan progress inline (pola `installingId`/`progress`/`progressLabel` yang sudah ada, pakai state terpisah `installingContainerId`).
- Tombol Hapus (enabled bila terpasang dan `!engine.isRunning()`) → `ContainerManager.remove` + `refresh()`.
- Setelah sukses install: tampilkan `containerRestartHint` bila engine jalan; panggil `engine.stopServer()` + `engine.startServer(...)`? — TIDAK. Implementasi restart: UI memanggil `onRuntimeChanged()` dan menampilkan pesan restart hint; engine restart penuh dilakukan lewat tombol yang sudah ada di EngineScreen (pola `distroRestartHint`). Keputusan final: install sukses → tampilkan hint, jangan auto-restart (menghindari surprising behavior); bind berlaku pada perintah berikutnya, dan PATH serve berlaku setelah restart manual. *(Catatan: menyimpangi dari keputusan "restart otomatis" di spec — dicatat sebagai perubahan sadar; bila user mau auto-restart, hapus catatan ini dan panggil stopServer+startServer dari UI.)*
- Refresh status via `ContainerManager.isInstalled` per container di `refresh()`.

- [ ] **Step 3: Build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/ui/DistroScreen.kt android/app/src/main/java/com/arkacode/app/ui/I18n.kt
git commit -m "feat(ui): Tool containers section in Distro screen"
```

---

### Task 7: Verifikasi end-to-end (manual/instrumentasi) + dokumentasi

- [ ] **Step 1: Pasang container python lewat app** (uji manual di perangkat arm64): Settings → Distro → Tool containers → Pasang. Expected: unduhan ±4 MB, status "terpasang".
- [ ] **Step 2: Jalankan via AI**: minta AI menjalankan `python3 --version` dan `pip3 --version` di distro **Ubuntu** (glibc) → versi Python 3.20 muncul; ulangi di distro **Alpine** → sama. Expected: kedua distro berhasil.
- [ ] **Step 3: Uji persistensi**: ganti distro Alpine → Ubuntu → `python3 --version` tetap jalan. Expected: jalan tanpa install ulang.
- [ ] **Step 4: Uji runtime install**: `pip3 install requests` lalu `python3 -c "import requests; print('ok')"` → ok. `pip3 install --user cowsay` lalu jalankan → jalan (HOME persisten).
- [ ] **Step 5: Update spec dengan hasil uji** (catat penyimpangan bila ada, mis. format ld-musl path) + commit.

```bash
git add docs/superpowers/specs/2026-11-17-tool-containers-design.md
git commit -m "docs: record device test results for tool containers (phase 1)"
```