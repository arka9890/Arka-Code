# Build Tab APK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overlay "Build" baru: pilih folder proyek dari workspace → deteksi/siapkan toolchain → jalankan `gradlew assembleDebug` dengan log live → salin otomatis ke folder SAF → pasang via ADB wireless.

**Architecture:** Satu overlay Compose (`BuildScreen`) mengikuti pola `showSettings` di EngineScreen. Eksekusi lewat `ExecRunner` (proot `sh -c`, stdout di-redirect ke file log di `/root/workspace/build-logs/`), UI me-tail file itu — sehingga log terbaca juga oleh sesi AI. Logika murni (pembaca log, pindai proyek, parse probe, nama file) dipisah di `core/` dan diuji JUnit; bagian Android murni (SAF, DataStore, Compose) mengikuti pola yang sudah ada.

**Tech Stack:** Kotlin + Jetpack Compose (material3), DataStore (`uiPrefsStore`), JUnit4, `ActivityResultContracts.OpenDocumentTree`, eksekusi proot via `ExecRunner`.

**Spec:** `docs/superpowers/plans/2026-10-22-build-tab-apk-design.md` (plan ini berargumen dari spec itu; baca keduanya).

## Global Constraints

- Semua label UI lewat `ui/I18n.kt` — setiap string = properti data class + entri blok `id` + entri blok `en` (kompilasi memaksa lengkap). Teks Indonesia persis seperti nilai di task masing-masing.
- Build hanya `assembleDebug`, satu proyek berjalan pada satu waktu.
- Folder proyek = subfolder `VirtualFs.prootWorkspaceDir()` (`filesDir/workspace` ↔ `/root/workspace`) yang mengandung `gradlew` atau `settings.gradle`/`settings.gradle.kts`. Import SAF = non-goal.
- Log SELALU ditulis ke `<workspace>/build-logs/<slug>-<ts>.log` (path tetap, dibaca AI) dan ringkasan hasil (exit code) ikut ditulis ke file yang sama.
- Toolchain ≈2 GB hanya diunduh saat tombol "Siapkan toolchain" ditekan (opsi A).
- APK di-**copy** ke folder SAF dengan nama unik `<proyek>-debug-<yyyyMMdd-HHmmss>.apk`; file asli tetap di `build/outputs` (dibutuhkan gradle + install ADB).
- Tombol "Pasang APK" wajib menampilkan peringatan `adbInstallWarn`: app akan dimatikan Android → sesi berjalan terputus (terbukti di spike).
- Jangan ubah `android/gradle.properties` atau versi AGP; override `android.aapt2FromMavenOverride` ditulis setup script ke `~/.gradle/gradle.properties` di dalam distro.
- Tanpa dependensi baru. Commit memakai `-c user.name=arkacode -c user.email=arkacode@local`.
- Batas timeout eksekusi (ms): build/setup `1_800_000`, install `300_000`, pair/connect `20_000`, probe `15_000`.

## Review Focus

1. **Nama folder berisi spasi/kutipan → command shell pecah atau injection.** Diblokir oleh `BuildSupport.shQuote` yang meng-escape semua nama; test: `BuildSupportTest` kasus `it's a "test"`.
2. **Log build ratusan ribu baris → UI freeze/kehabisan memori.** `LogTailer` baca offset (hanya baris baru) dan state UI dibatasi1000 baris terakhir; test offset-resume + shrink-reset di `BuildSupportTest`.
3. **Setup toolchain dijalankan dua kali / setengah jadi.** Setiap blok skrip punya guard (`command -v`/`[ -x`/`[ -f`) dan skrip ditulis ulang bila konten berubah; test marker+guard ada di `ToolchainSetupTest`.
4. **Grant SAF revoked/expired → copy gagal diam-diam setelah build sukses.** `copyToTree` mengembalikan `Result`, kegagalan WAJIB jadi baris di log pane + tombol pilih folder tetap tampil (langkah verifikasi manual di T6).
5. **Build jalan saat overlay ditutup / tombol Build ditekan dua kali.** Eksekusi memakai scope dari EngineScreen (di atas overlay) sehingga job selamat; guard `BuildScreen.running` (companion `@Volatile`) menolak build kedua; langkah verifikasi di T4.

---

### Task 1: Core helpers murni (pindai proyek, tail log, quote, parse probe, nama APK)

**Files:**
- Create: `app/src/main/java/com/arkacode/app/core/BuildSupport.kt`
- Test: `app/src/test/java/com/arkacode/app/core/BuildSupportTest.kt`

**Interfaces:**
- Produces (dipakai T4–T7):
  - `object BuildSupport { fun findProjects(root: File): List<String>` — nama direktori berisi `gradlew` ATAU `settings.gradle` ATAU `settings.gradle.kts`, urut alfabet, abaikan file biasa.
  - `fun shQuote(s: String): String` — bungkus `'...'` dengan escape `'` → `'\''`.
  - `class LogTailer(private val file: File)` — `fun readNew(): List<String>`: baca byte baru sejak offset; potong baris belum-lengkap (tanpa `\n`) untuk bacaan berikutnya; kalau file menyusut/berubah, reset offset ke0.
  - `data class ToolchainStatus(val java: Boolean, val sdk: Boolean, val aapt2: Boolean, val adb: Boolean) { val ready: Boolean }` + `fun parseToolchain(output: String): ToolchainStatus` — deteksi flag `JAVA_OK`/`SDK_OK`/`AAPT2_OK`/`ADB_OK`.
  - `fun apkOutName(project: String, now: Date): String` — `"<slug>-debug-<yyyyMMdd-HHmmss>.apk"`, slug = proyek dengan `[^A-Za-z0-9._-]` diganti `-`.

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
class BuildSupportTest {
    @Test fun `pindai folder yang mengandung gradlew`() {
        val root = createTempDir(); File(root, "A/gradlew").apply { parentFile.mkdirs(); writeText("") }
        File(root, "B").mkdirs(); File(root, "B/settings.gradle.kts").writeText("")
        File(root, "C").mkdirs(); File(root, "loose.txt").writeText("")
        assertEquals(listOf("A","B"), BuildSupport.findProjects(root))
    }
    @Test fun `quote aman untuk kutip dan spasi`() {
        assertEquals("'it'\\''s a \"test\"'", BuildSupport.shQuote("it's a \"test\""))
        assertEquals("'plain'", BuildSupport.shQuote("plain"))
    }
    @Test fun `tailer baca baris baru dan tahan potongan`() {
        val f = File(createTempDir(), "x.log"); val t = BuildSupport.LogTailer(f)
        f.writeText("a\nb\n"); assertEquals(listOf("a","b"), t.readNew())
        f.appendText("c"); assertEquals(emptyList<String>(), t.readNew())
        f.appendText("d\n"); assertEquals(listOf("cd"), t.readNew().map{ it }) // lihat catatan: potongan menyatu
    }
    @Test fun `tailer reset saat file menyusut`() { /* tulis "z\n", readNew; tulis ulang pendek, readNew = isi awal */ }
    @Test fun `parse probe semua siap`() {
        assertTrue(BuildSupport.parseToolchain("JAVA_OK\nSDK_OK\nAAPT2_OK\nADB_OK\n").ready)
        assertFalse(BuildSupport.parseToolchain("JAVA_OK\nSDK_OK\n").ready)
    }
    @Test fun `nama apk unik dan bersih`() {
        assertEquals("My-App-debug-20261023-101500.apk",
            BuildSupport.apkOutName("My App!", SimpleDateFormat("yyyyMMdd-HHmmss").parse("20261023-101500")))
    }
}
```

Catatan untuk implementer: perilaku potongan baris yang benar = fragmen tanpa `\n` **tidak** dikonsumsi dari offset (jadi `readNew` berikutnya menghasilkan `"cd"` dari `"c"+"d\n"` — koreksi assert jadi `listOf("cd")`).

- [ ] **Step 2: Jalankan test — expect FAIL (BuildSupport tidak ada)**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*BuildSupportTest*' --console=plain`
Expected: FAIL, kompilasi `BuildSupport` tidak ditemukan.

- [ ] **Step 3: Implement `BuildSupport.kt`** sesuai signature di atas (baca byte via `FileInputStream` + `skip(pos)`; UTF-8).

- [ ] **Step 4: Jalankan test — expect PASS**

Run: sama. Expected: `BUILD SUCCESSFUL`, semua 6 test hijau.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/BuildSupport.kt android/app/src/test/java/com/arkacode/app/core/BuildSupportTest.kt
git -c user.name=arkacode -c user.email=arkacode@local commit -m "feat: BuildSupport — pindai proyek, tail log, quote shell, parse probe"
```

---

### Task 2: ToolchainSetup (skrip setup embed + probe)

**Files:**
- Create: `app/src/main/java/com/arkacode/app/core/ToolchainSetup.kt`
- Test: `app/src/test/java/com/arkacode/app/core/ToolchainSetupTest.kt`

**Interfaces:**
- Produces (dipakai T5):
  - `const val PROBE_CMD: String` — satu baris sh:
    `{ command -v java >/dev/null && echo JAVA_OK; [ -f /opt/android-sdk/platforms/android-35/android.jar ] && echo SDK_OK; [ -x /opt/android-sdk/aapt2/aapt2 ] && echo AAPT2_OK; [ -x /usr/local/bin/adb ] && echo ADB_OK; }`
  - `fun scriptContent(): String` — skrip bash (di bawah).
  - `fun ensureScript(workspaceRoot: File): File` — tulis `<workspace>/.arka/setup-toolchain.sh` (mkdirs), timpa bila konten berbeda, chmod +x, return File.

Isi skrip — **keputusan yang tidak boleh diubah implementer** (urutan, URL, pin versi = hasil spike terverifikasi; guard = idempoten):

```bash
#!/bin/sh
# arka-toolchain-setup
set -e
# shortcut: versi aapt2 mengikuti agp (gradle/libs.versions.toml); kalau AGP naik, baris aapt2 di bawah ikut.
AAPT2_VER=8.9.3-12782657
SDK=/opt/android-sdk
DEBIAN_FRONTEND=noninteractive

command -v java >/dev/null 2>&1 || { apt-get update -qq &&
  apt-get install -y -qq openjdk-17-jdk-headless iproute2 qemu-user-static \
    libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross unzip curl ca-certificates; }

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools" && cd /tmp
  curl -sSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o clt.zip -d clt && rm -rf "$SDK/cmdline-tools/latest" && mv clt/cmdline-tools "$SDK/cmdline-tools/latest"
fi

if [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root=$SDK --licenses >/dev/null 2>&1 || true
  i=1; while [ $i -le 3 ]; do
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root=$SDK "platforms;android-35" "build-tools;35.0.0" </dev/null && break
    i=$((i+1)); sleep 5
  done
fi

if [ ! -x "$SDK/aapt2/aapt2" ]; then
  mkdir -p "$SDK/aapt2" && cd "$SDK/aapt2"
  curl -sSL -o a.jar "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/$AAPT2_VER/aapt2-$AAPT2_VER-linux.jar"
  unzip -q -o a.jar aapt2 && mv -f aapt2 aapt2.bin
  printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/android-sdk/aapt2/aapt2.bin "$@"\n' > aapt2
  chmod +x aapt2
fi
grep -q aapt2FromMavenOverride /root/.gradle/gradle.properties 2>/dev/null ||
  { mkdir -p /root/.gradle && echo "android.aapt2FromMavenOverride=$SDK/aapt2/aapt2" >> /root/.gradle/gradle.properties; }

if [ ! -x /usr/local/bin/adb ]; then
  cd /tmp && curl -sSL -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
  unzip -q -o pt.zip -d /tmp/ptx && mv -f /tmp/ptx/platform-tools "$SDK/platform-tools"
  mv -f "$SDK/platform-tools/adb" "$SDK/platform-tools/adb.bin"
  printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/android-sdk/platform-tools/adb.bin "$@"\n' > "$SDK/platform-tools/adb"
  chmod +x "$SDK/platform-tools/adb" && ln -sf "$SDK/platform-tools/adb" /usr/local/bin/adb
fi
echo "TOOLCHAIN_OK"
```

Catatan desain yang terpasang di sini (jangan dihilangkan): `adb` Debian tidak punya perintah `pair` → pakai platform-tools Google + wrapper qemu; symlink `/usr/local/bin/adb` supaya `PROBE_CMD` dan task ADB cukup memanggil `adb`.

**Interfaces (dipakai T5):** `ToolchainSetup.ensureScript(...)`, `ToolchainSetup.PROBE_CMD`.

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
class ToolchainSetupTest {
    @Test fun `ensureScript menulis skrip dengan marker dan guard`() {
        val ws = createTempDir()
        val f = ToolchainSetup.ensureScript(ws)
        val isi = f.readText()
        assertTrue(isi.startsWith("#!/bin/sh"))
        assertTrue(isi.contains("# arka-toolchain-setup"))
        assertTrue(isi.contains("TOOLCHAIN_OK"))
        assertTrue(isi.count { isi.contains("|| {") || isi.contains("if [ !") } >= 5) // guard idempoten
        assertTrue(f.canExecute())
    }
    @Test fun `ensureScript idempoten — konten sama, tidak ada perubahan`() {
        val ws = createTempDir(); val f1 = ToolchainSetup.ensureScript(ws); val t1 = f1.lastModified()
        val f2 = ToolchainSetup.ensureScript(ws); assertEquals(t1, f2.lastModified())
    }
    @Test fun `probe memakai semua flag`() {
        listOf("JAVA_OK","SDK_OK","AAPT2_OK","ADB_OK").forEach { assertTrue(ToolchainSetup.PROBE_CMD.contains(it)) }
    }
}
```

- [ ] **Step 2: Jalankan test — expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests '*ToolchainSetupTest*' --console=plain`

- [ ] **Step 3: Implement `ToolchainSetup.kt`** — `scriptContent()` mengembalikan skrip di atas persis; `ensureScript` tulis bila beda (banding konten), `setExecutable(true)`, `PROBE_CMD` persis seperti di Interfaces.

- [ ] **Step 4: Verifikasi URL aapt2 hidup** (spike memakai cache gradle, URL Maven langsung belum pernah diuji)

Run: `curl -sI "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.9.3-12782657/aapt2-8.9.3-12782657-linux.jar" | head -1`
Expected: `HTTP/2 200` (kalau404: perbaiki path URL di `scriptContent()` sampai test konten + HEAD cocok).

- [ ] **Step 5: Jalankan test — expect PASS**, lalu commit (`feat: ToolchainSetup — skrip setup toolchain build + probe`).

---

### Task 3: BuildPrefs (persistensi SAF + ADB)

**Files:**
- Create: `app/src/main/java/com/arkacode/app/core/BuildPrefs.kt`

**Interfaces:**
- Consumes: `context.uiPrefsStore` (DataStore yang sama dengan `SessionUiPrefs.kt:21`).
- Produces (dipakai T6–T7):
  - `data class AdbFields(val ip: String = "", val pairPort: String = "", val connectPort: String = "")`
  - `class BuildPrefs(private val context: Context)`:
    - `suspend fun safTree(): String?` / `suspend fun setSafTree(uri: String?)`
    - `suspend fun adbFields(): AdbFields` / `suspend fun setAdbFields(f: AdbFields)`
  - Key persis: `stringPreferencesKey("build:safTree")`, `"adb:ip"`, `"adb:pairPort"`, `"adb:connectPort"`.

- [ ] **Step 1: Implement** — ikuti gaya `SessionUiPrefs` (satu file kecil, `uiPrefsStore.data.map{}.first()` + `uiPrefsStore.edit{}`).
- [ ] **Step 2: Gate** — Run: `./gradlew :app:testDebugUnitTest --console=plain` → Expected: `BUILD SUCCESSFUL` (seluruh suite lama tetap hijau; tidak ada test baru — pola prefs di repo ini tidak punya test, konsisten dengan `SessionUiPrefs`).
- [ ] **Step 3: Commit** `feat: BuildPrefs — simpan tujuan SAF dan field ADB` (author flags).

---

### Task 4: I18n + overlay BuildScreen + eksekusi build + log live

Task terbesar — deliverable: pilih proyek → tap Build → log mengalir → hasil/exit tercatat di file.

**Files:**
- Modify: `app/src/main/java/com/arkacode/app/ui/I18n.kt` (3 blok: properti ~baris187, `id` ~416, `en` ~642)
- Modify: `app/src/main/java/com/arkacode/app/ui/EngineScreen.kt` (5 titik: flag ~121, BackHandler ~317, rantai overlay ~328–381, callback ~412, param `ReadyScreen` ~584 + `ClayBottomBar` ~710)
- Modify: `app/src/main/java/com/arkacode/app/core/ExecRunner.kt` (tambah parameter `onProcess`)
- Create: `app/src/main/java/com/arkacode/app/ui/BuildScreen.kt`
- Test: `app/src/test/java/com/arkacode/app/ui/BuildLogFormatTest.kt`

**Interfaces:**
- Consumes (T1): `BuildSupport.findProjects/shQuote/LogTailer/ToolchainStatus/apkOutName`; (T2) `ToolchainSetup.ensureScript/PROBE_CMD` — T5 yang memakai PROBE, task ini hanya `ensureScript` belum.
- Modifikasi `ExecRunner.run` (tidak ada pemanggil lain di luar fitur ini — aman):
  `suspend fun run(command: String, sessionId: String? = null, settings: ExecSettings = ExecSettings(), onProcess: ((Process) -> Unit)? = null): ExecResult` — panggil `onProcess?.invoke(process)` tepat setelah `builder.start()` di `execute()`; default `null` = tanpa perubahan perilaku.
- Produces:
  - `fun BuildScreen(engine: EngineManager, execScope: CoroutineScope, onBack: () -> Unit)` — `execScope` = scope milik EngineScreen (di atas overlay ⇒ job selamat saat overlay ditutup).
  - Companion: `@Volatile var running: Boolean` + `@Volatile var process: Process?` (guard single-flight + tombol Batal).

**Nilai I18n (teks persis — ketiganya: properti, `id`, `en`):**

| key | id | en |
|---|---|---|
| navBuild | `Build` | `Build` |
| buildTitle | `Build APK` | `Build APK` |
| buildProject | `Proyek` | `Project` |
| buildRefresh | `Muat ulang` | `Refresh` |
| buildToolchain | `Toolchain` | `Toolchain` |
| buildToolchainOk | `siap` | `ready` |
| buildToolchainMissing | `belum siap` | `not ready` |
| buildSetup | `Siapkan toolchain (≈2 GB)` | `Set up toolchain (≈2 GB)` |
| buildRun | `Build assembleDebug` | `Build assembleDebug` |
| buildCancel | `Batal` | `Cancel` |
| buildRunning | `Build berjalan…` | `Build running…` |
| buildLog | `Log build` | `Build log` |
| buildResult | `Hasil` | `Result` |
| buildSuccess | `Build sukses` | `Build succeeded` |
| buildFailed | `Build gagal` | `Build failed` |
| buildNoApk | `Belum ada APK` | `No APK yet` |
| buildPickFolder | `Pilih folder tujuan` | `Choose destination folder` |
| adbTitle | `ADB wireless` | `Wireless ADB` |

(T6/T7 menambah key sendiri — lihat task masing-masing; pola yang sama.)

- [ ] **Step 1: Tambah I18n** —18 key di atas, ketiga blok (properti, `id`, `en`). Jalankan `./gradlew :app:compileDebugKotlin --console=plain` → Expected: `BUILD SUCCESSFUL` (data class lengkap; key boleh belum dipakai).

- [ ] **Step 2: Tulis test format ringkasan log** (satu fungsi kecil murni, taruh di `BuildSupport`):

```kotlin
@Test fun `ringkasan exit ditulis dengan format tetap`() {
    assertEquals("=== exit 0 === BUILD SUKSES\n", BuildSupport.summaryLine(0))
    assertEquals("=== exit 1 === BUILD GAGAL\n", BuildSupport.summaryLine(1))
}
```
Jalankan → FAIL (`summaryLine` belum ada) → implement satu baris (`if (exitCode ==0) ... else ...`) → PASS.

- [ ] **Step 3: `ExecRunner.onProcess`** — tambah parameter default-null (signature di Interfaces), lewati null saat tidak dipakai. Gate: `:app:testDebugUnitTest` hijau.

- [ ] **Step 4: `BuildScreen.kt` — struktur & state** (ikuti layout/knob visual `SettingsScreen.kt` — Surface + header + tombol back):

```kotlin
@Composable
fun BuildScreen(engine: EngineManager, execScope: CoroutineScope, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()           // untuk kerja pendek UI
    val fs = remember { com.arkacode.app.core.VirtualFs(context) }
    val runner = remember { ExecRunner(context, DistroManager(context), engine.runtimeMode, TermuxBridge(context)) }
    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var runningBuild by remember { mutableStateOf(false) }
    val logLines = remember { mutableStateListOf<String>() }
    ...
}
```

Logika per titik (keputusan yang sudah dikunci):
- **Scan proyek**: `scope.launch { withContext(IO) { BuildSupport.findProjects(fs.prootWorkspaceDir()) } }` + muat ulang (`buildRefresh`).
- **Jalankan build** (tombol `buildRun`, disabled kalau `BuildScreen.running`): bangun command — semua path proyek/log di-quote `BuildSupport.shQuote`:

```
mkdir -p '<workspace>/build-logs' && cd '<workspace>/<proyek>' && chmod +x gradlew ; \
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug --console=plain > '<workspace>/build-logs/<slug>-<ts>.log' 2>&1
```

  jalankan via `execScope.launch { ... runner.run(cmd, settings = ExecSettings(timeoutMs = 1_800_000), onProcess = { BuildScreen.process = it }) ... }`; set `BuildScreen.running` true/false; hasil: `exitCode`/`timedOut` → **tulis `BuildSupport.summaryLine(...)` ke file log via appendText (bukan hanya UI)** → tampilkan juga di pane → refresh daftar APK (walk `projectDir`, filter file `.apk` yang path-nya mengandung `build/outputs/apk`).
- **Tail**: `LaunchedEffect(runningBuild, logPath)` loop `{ logLines += tailer.readNew(); if (logLines.size > 1000) removeRange(0, logLines.size - 1000); delay(500) }` — baca penuh saat pertama buka (resume tampilan).
- **Batal** (`buildCancel`): `BuildScreen.process?.destroy()` + baris "dibatalkan" di pane.
- **Tangkap kegagalan** `runner.run` (throw `IllegalStateException` dari `notReadyReason`/allowlist) → baris pesan exception ke pane.
- **Chips toolchain**: tampilkan status dari `parseToolchain` — T5 yang mengisi probing; task ini cukup placeholder status `buildToolchainMissing` yang diperbarui bila hasil probe tersedia (biarkan field `status: ToolchainStatus? = null`).
- **Hasil** (`buildResult`): daftar APK (nama + path relatif) + tombol `buildPickFolder` (T6 mengaktifkan) + placeholder ADB (T7).

- [ ] **Step 5: Wiring EngineScreen (5 titik persis)**
  1. `var showBuild by remember { mutableStateOf(false) }` setelah `showDistro` (baris ~127).
  2. `BackHandler(enabled = ... || showBuild ...)` + `showBuild -> showBuild = false` di `when` (~317–323).
  3. Cabang `else if (showBuild) { BuildScreen(engine = engine, execScope = scope, onBack = { showBuild = false }) }` dalam rantai overlay (~328–381) — **sebelum** cabang Buddy terakhir.
  4. Param `onOpenBuild: () -> Unit` di `private fun ReadyScreen(` (~584) + `onOpenBuild = { showBuild = true }` di pemanggilan (~414).
  5. Item nav di `ClayBottomBar` (setelah `navCaps`): `ClayNavItem(Icons.Default.Android, I18n.s.navBuild, selected = false, onClick = onOpenBuild)` — **jangan** pakai `Icons.Default.Build` (sudah dipakai `navCaps`).

- [ ] **Step 6: Gate** — Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --console=plain` → Expected: `BUILD SUCCESSFUL`, semua test hijau.

- [ ] **Step 7: Verifikasi manual Review Focus #5** — dokumentasikan di pesan commit: `running` menolak build kedua (tombol disabled saat `runningBuild`), job memakai `execScope` sehingga menutup overlay tidak membatalkan. (Executor tidak punya perangkat — validasi UI final oleh user via APK CI di T8.)

- [ ] **Step 8: Commit** `feat: tab Build — pilih proyek, jalankan assembleDebug, log live`.

---

### Task 5: Probe toolchain + tombol "Siapkan toolchain"

**Files:**
- Modify: `app/src/main/java/com/arkacode/app/ui/BuildScreen.kt`

**Interfaces:**
- Consumes (T2): `ToolchainSetup.PROBE_CMD`, `ensureScript`; (T1): `parseToolchain`; (T4): `runner`, pane log, companion.

- [ ] **Step 1: Probe saat overlay dibuka & setelah setup** — `LaunchedEffect(Unit) { val r = runCatching { runner.run(ToolchainSetup.PROBE_CMD, settings = ExecSettings(timeoutMs = 15_000)) }; status = r.map { BuildSupport.parseToolchain(it.stdout) }.getOrNull() }`.
- [ ] **Step 2: Tombol `buildSetup` tampil hanya bila `status != null && !status.ready`** — aksi: `scope.launch { withContext(IO) { ToolchainSetup.ensureScript(fs.prootWorkspaceDir()) } }` lalu jalankan `execScope.launch { runner.run("bash '<workspace>/.arka/setup-toolchain.sh' > '<workspace>/build-logs/setup-<ts>.log' 2>&1", settings = ExecSettings(timeoutMs = 1_800_000), onProcess = ...) }` — tail pane yang sama (ganti `logPath` aktif ke file setup), tulis ringkasan ke file, re-probe selesai. Semua path lewat `shQuote`.
- [ ] **Step 3: Gate** — `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin` → hijau. Commit: `feat: tab Build — deteksi dan siapkan toolchain`.

---

### Task 6: Salin otomatis APK ke folder SAF

**Files:**
- Modify: `app/src/main/java/com/arkacode/app/core/WorkspaceExport.kt` (+ fungsi `copyToTree`)
- Modify: `app/src/main/java/com/arkacode/app/ui/BuildScreen.kt` (launcher + hook sukses + tombol pilih)
- Modify: `app/src/main/java/com/arkacode/app/ui/I18n.kt` (+key `buildCopied`, `buildCopyFailed`)

**Interfaces:**
- Produces: `suspend fun WorkspaceExport.copyToTree(context: Context, treeUri: Uri, src: File, newName: String): Result<String>` — `DocumentsContract.createDocument(resolver, treeUri, "application/vnd.android.package-archive", newName)` → `openOutputStream(uri, "w")` → `src.inputStream().copyTo(out)`; semua dalam `runCatching` → `Result`.
- Consumes (T3): `BuildPrefs.safTree/setSafTree`; (T1): `apkOutName`.

- [ ] **Step 1: Test untuk `copyToTree` hanya bagian murni** — tidak ada (ContentResolver); ganti dengan test `BuildSupport.apkOutName` dipakai persis oleh pemanggil (sudah hijau di T1) **dan** gate kompilasi.
- [ ] **Step 2: Implement `copyToTree`** di `WorkspaceExport` (pola `exportFolder` yang sudah ada untuk urutan resolver/IO).
- [ ] **Step 3: Launcher + hook di BuildScreen**
  - `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> ... }` — bila non-null: `takePersistableUriPermission(uri, FLAG_READ or FLAG_WRITE)` → `prefs.setSafTree(uri.toString())` → kalau ada APK hasil build terakhir, langsung salin.
  - Hook setelah build sukses: bila `prefs.safTree() != null` → `withContext(IO) { copyToTree(...) }` → sukses: baris `I18n.s.buildCopied + nama` di pane; gagal: `buildCopyFailed + e.message` **di pane** (Review Focus #4) + tombol `buildPickFolder` ikut ditampilkan.
  - Belum pernah pilih → tombol `buildPickFolder` di seksi `buildResult`.
- [ ] **Step 4: Tambah I18n** `buildCopied = "Tersalin: "/"Copied: "`, `buildCopyFailed = "Gagal menyalin: "/"Copy failed: "` (3 blok).
- [ ] **Step 5: Gate** — test suite + compile hijau. **Verifikasi manual (catat di commit)**: grant di-revoke → pesan tampil di pane, tombol pilih tetap ada. Commit: `feat: tab Build — salin otomatis APK ke folder SAF`.

---

### Task 7: Seksi ADB wireless (pair / connect / install)

**Files:**
- Modify: `app/src/main/java/com/arkacode/app/ui/BuildScreen.kt`
- Modify: `app/src/main/java/com/arkacode/app/ui/I18n.kt`

**Interfaces:**
- Consumes: (T3) `BuildPrefs.adbFields/setAdbFields`; (T4) `runner` + pane + `execScope`; (T1) `shQuote`.

**Nilai I18n tambahan:**

| key | id | en |
|---|---|---|
| adbIp | `IP perangkat` | `Device IP` |
| adbPairPort | `Port pairing` | `Pairing port` |
| adbCode | `Kode pairing` | `Pairing code` |
| adbConnectPort | `Port connect` | `Connect port` |
| adbPair | `Pair` | `Pair` |
| adbConnect | `Connect` | `Connect` |
| adbInstall | `Pasang APK` | `Install APK` |
| adbInstallWarn | `App akan dimatikan Android — sesi berjalan terputus.` | `The app will be killed by Android — the running session ends.` |

- [ ] **Step 1: Field + persistensi** — empat input. **Auto-probe IP** (spec: bisa diedit): saat seksi dibuka dan `adbFields().ip` kosong → `runner.run("ip -4 addr show wlan0 | awk '/inet /{print $2}' | cut -d/ -f1", settings = ExecSettings(timeoutMs = 15_000))` → isi field dengan hasil stdout trim (gagal = biarkan kosong, user ketik manual; `iproute2` dipasang oleh setup). onChange → `setAdbFields` (simpan saat blur/submit cukup — keputusan implementer, yang penting persist). Kode pairing TIDAK pernah disimpan.
- [ ] **Step 2: Aksi** — masing-masing via `scope.launch` + `runner.run`, output stdout+stderr dijadikan baris di pane:
  - `adb pair <ip>:<pairPort> <code>` — `ExecSettings(timeoutMs = 20_000)`; kode TIDAK disimpan ke prefs.
  - `adb connect <ip>:<connectPort>` — timeout20_000.
  - `adb install -r -d '<path-apk-guest>'` — timeout `300_000`; tombol disabled saat jalan; **peringatan `adbInstallWarn` dirender sebagai teks di atas tombol** (Global Constraints).
- [ ] **Step 3: Gate** — test suite + compile hijau. Commit: `feat: tab Build — ADB wireless pair/connect/install`.

---

### Task 8: Verifikasi akhir, push, dispatch CI

**Files:** tidak baru — verifikasi + distribusi.

- [ ] **Step 1: Cakupan spec** — baca ulang `2026-10-22-build-tab-apk-design.md` per bagian (Cakupan/Komponen/Alur/Risiko/Non-goal); tiap butir menunjuk task yang mengerjakannya. Catat selisihnya di pesan commit/akhir bila ada.
- [ ] **Step 2: Full gate lokal** — Run: `cd android && ./gradlew test assembleDebug --console=plain` → Expected: `BUILD SUCCESSFUL`, semua test hijau, `app/build/outputs/apk/debug/app-debug.apk` ada (zip OK: `unzip -t ... | tail -1`).
- [ ] **Step 3: Push**

```bash
cd /root/workspace/Arka-Code
export GIT_EXEC_PATH=/opt/gitroot/usr/lib/git-core LD_LIBRARY_PATH="/opt/gitlibs/usr/lib/aarch64-linux-gnu:/opt/gitlibs/lib/aarch64-linux-gnu:$LD_LIBRARY_PATH"
git -c user.name=arkacode -c user.email=arkacode@local push origin main
```

- [ ] **Step 4: Dispatch CI** (workflow id `392020412`, pola yang sudah dipakai):

```bash
curl -s -X POST -H "Authorization: token $(sed -n 's|https://x-access-token:\([^@]*\)@.*|\1|p' /root/.git-credentials)" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/arka9890/Arka-Code/actions/workflows/392020412/dispatches \
  -d '{"ref":"main"}'
```
Poll `/actions/runs/<id>` sampai `success` → lapor user: APK artifact siap + uji manual: (a) buka tab Build, (b) Siapkan toolchain di distro segar, (c) build Arka-Code sendiri, (d) SAF copy, (e) pair+install (siapkan kode pairing, ingat app akan restart).

- [ ] **Step 5: Commit sisa bila ada** (biasanya tidak — tiap task sudah ber-commit).
