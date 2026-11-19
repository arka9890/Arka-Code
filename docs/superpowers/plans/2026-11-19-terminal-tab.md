# Tab Terminal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tab terminal di dalam app yang menjalankan shell interaktif ber-TTY di distro proot aktif, dirender xterm.js di WebView.

**Architecture:** Satu file C kecil mengalokasikan pty dan mengeksekusi proot; Kotlin membungkus fd master sebagai InputStream/OutputStream, mem-batch output, dan mengirimnya ke WebView lewat `addJavascriptInterface` + `evaluateJavascript` dengan backpressure berbasis ack. Sesi dipegang singleton proses (`TerminalHost`) supaya bertahan saat pindah layar dan rotasi; WebView dibuat per pemasangan layar dan mengisi ulang layar dari ring buffer 128 KB.

**Tech Stack:** Kotlin 2.1.20, Compose, AGP 8.9.3, NDK/CMake (C), WebView, xterm.js 5.5.0 (MIT, vendored).

**Spec:** `docs/superpowers/specs/2026-11-19-terminal-tab-design.md`

## Global Constraints

- Runtime: hanya `RuntimeMode.PROOT`. Terminal tidak dibuat untuk `RuntimeMode.TERMUX`.
- ABI native: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (sama dengan `ProotBinaries.ABI_TO_ARCH`).
- `minSdk = 26`, `compileSdk = 35`, `jvmTarget = "1.8"`.
- `packaging.jniLibs.useLegacyPackaging = true` tidak boleh diubah: binary proot butuh diekstrak ke `nativeLibraryDir`.
- Env guest wajib memuat `TERM=xterm-256color`, `PROOT_LOADER`, `PROOT_TMP_DIR`, `PROOT_NO_SECCOMP=1`, `HOME`, `PATH` dengan `/opt/containers/bin` di depan.
- Semua string UI lewat `I18n` (ID + EN), tanpa teks hardcoded.
- Unit test: JUnit 4.13.2, nama test backtick berbahasa Indonesia, perintah `./gradlew :app:testDebugUnitTest`.
- Tidak menambah dependency Gradle baru; WebView memakai bawaan Android.
- Aset xterm.js dipin versi 5.5.0 dan disertai berkas lisensi MIT.

## Review Focus

1. Keybar mengirim control byte: Ctrl-C harus `0x03`, Ctrl-D `0x04`, Ctrl-Z `0x1a` — bukan teks "Ctrl-C".
2. Chunk output memotong escape sequence di tengah → xterm.js harus menerima byte mentah (base64 → Uint8Array), bukan String yang sudah di-decode Kotlin.
3. Rotasi layar saat `vim`/`htop` terbuka → `resize` sampai ke pty (`TIOCSWINSZ`), layar tidak terpotong.
4. Distro diganti atau rootfs dihapus saat sesi hidup → shell lama dimatikan, tidak ada proot yatim.
5. Prompt interaktif `apk add` (`(y/N)`) → jawaban masuk ke pty, bukan tertelan UI.
6. Output deras (`yes`, `find /`) → flush dibatasi, scrollback dibatasi, UI tidak hang.

---

### Task 1: Builder argv terminal (fungsi murni)

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/TerminalCommand.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/TerminalCommandTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class TerminalTarget(
      val proot: String,
      val loader: String,
      val rootfs: String,
      val prootTmp: String,
      val envPath: String,
      val shellPath: String,
      val home: String,
      val path: String,
      val termuxLayout: Boolean,
      val workspaceDir: String? = null,
      val containersDir: String? = null,
      val shmDir: String? = null,
      val extraBinds: List<String> = emptyList(),
      val extraEnv: Map<String, String> = emptyMap(),
  )
  object TerminalCommand {
      fun build(target: TerminalTarget): List<String>
      fun forDistro(distro: DistroManager, workspaceDir: File?, containersDir: File): List<String>?
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandTest {

    private fun target(
        termuxLayout: Boolean = false,
        workspaceDir: String? = "/data/app/workspace",
    ) = TerminalTarget(
        proot = "/lib/libproot.so",
        loader = "/lib/libproot-loader.so",
        rootfs = "/data/app/distro/alpine",
        prootTmp = "/data/app/proot-tmp",
        envPath = "/bin/busybox",
        shellPath = "/bin/sh",
        home = "/root",
        path = "/opt/containers/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        termuxLayout = termuxLayout,
        workspaceDir = workspaceDir,
        containersDir = "/data/app/containers",
        shmDir = "/data/app/distro/alpine/tmp",
        extraBinds = listOf("/system", "/apex"),
    )

    @Test
    fun `argv dimulai proot dan memakai rootfs distro`() {
        val argv = TerminalCommand.build(target())
        assertEquals("/lib/libproot.so", argv[0])
        val i = argv.indexOf("-r")
        assertEquals("/data/app/distro/alpine", argv[i + 1])
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("--link2symlink"))
    }

    @Test
    fun `layout termux membuang -0`() {
        assertTrue(TerminalCommand.build(target()).contains("-0"))
        assertFalse(TerminalCommand.build(target(termuxLayout = true)).contains("-0"))
    }

    @Test
    fun `bind workspace containers dan shm`() {
        val argv = TerminalCommand.build(target())
        assertTrue(argv.contains("/data/app/workspace:/root/workspace"))
        assertTrue(argv.contains("/data/app/containers:/opt/containers"))
        assertTrue(argv.contains("/data/app/distro/alpine/tmp:/dev/shm"))
        assertTrue(argv.contains("/system"))
    }

    @Test
    fun `env tail lengkap sebelum shell`() {
        val argv = TerminalCommand.build(target())
        val env = argv.indexOf("/bin/busybox")
        assertTrue(env > 0)
        assertEquals("-i", argv[env + 1])
        assertTrue(argv.contains("HOME=/root"))
        assertTrue(argv.contains("TERM=xterm-256color"))
        assertTrue(argv.contains("LANG=C.UTF-8"))
        assertTrue(argv.contains("PROOT_LOADER=/lib/libproot-loader.so"))
        assertTrue(argv.contains("PROOT_TMP_DIR=/data/app/proot-tmp"))
        assertTrue(argv.contains("PROOT_NO_SECCOMP=1"))
        assertTrue(argv.contains(
            "PATH=/opt/containers/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ))
    }

    @Test
    fun `argv diakhiri shell login`() {
        val argv = TerminalCommand.build(target())
        assertEquals(listOf("/bin/sh", "-l"), argv.takeLast(2))
    }

    @Test
    fun `tanpa workspace tidak ada bind workspace`() {
        val argv = TerminalCommand.build(target(workspaceDir = null))
        assertFalse(argv.any { it.endsWith(":/root/workspace") })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TerminalCommandTest"`
Expected: FAIL — `Unresolved reference: TerminalTarget`.

- [ ] **Step 3: Implement `TerminalCommand`**

Urutan argv persis: proot, `--kill-on-exit`, `--link2symlink`, `-0` (kecuali `termuxLayout`), `-r rootfs`, `-w home`, `-b /dev`, `-b /proc`, `-b /sys`, `-b shmDir:/dev/shm` (bila `shmDir` tidak null), bind workspace bila ada, bind containers bila ada, `extraBinds` masing-masing didahului `-b`, lalu `envPath`, `-i`, `HOME=`, `XPATH` — tulis `PATH=`, `TERM=xterm-256color`, `LANG=C.UTF-8`, `PROOT_LOADER=`, `PROOT_TMP_DIR=`, `PROOT_NO_SECCOMP=1`, lalu pasangan `extraEnv`, lalu `shellPath`, `-l`.

`forDistro` mengumpulkan nilai dari `distro` (`prootBinary()`, `loaderBinary()`, `rootfsDir`, `guestEnvPath()`, `guestShellPath()`, `guestHomePath()`, `guestPathValueWithContainers()`, `spec.layout == RootfsLayout.TERMUX_PREFIX`, `extraBindArgs()`, `guestExtraEnv()`) dan mengembalikan `null` bila proot/loader null atau `!distro.isInstalled()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TerminalCommandTest"`
Expected: PASS (6 test).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/TerminalCommand.kt \
        android/app/src/test/java/com/arkacode/app/core/TerminalCommandTest.kt
git commit -m "feat(terminal): builder argv proot untuk shell interaktif"
```

---

### Task 2: Modul native pty

**Files:**
- Create: `android/app/src/main/cpp/CMakeLists.txt`
- Create: `android/app/src/main/cpp/terminal_pty.c`
- Create: `android/app/src/main/java/com/arkacode/app/core/TerminalPty.kt`
- Modify: `android/app/build.gradle.kts` (blok `android { defaultConfig { ... } }`, `buildFeatures`/`externalNativeBuild`)

**Interfaces:**
- Produces:
  ```kotlin
  object TerminalPty {
      class Handle(val fd: Int, val pid: Int)
      fun spawn(argv: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): Handle?
      fun resize(fd: Int, rows: Int, cols: Int)
      fun signal(pid: Int, sig: Int)
      fun waitFor(pid: Int): Int
  }
  ```
  Semua method `native`: `nativeSpawn(String[] argv, String[] env, String cwd, int rows, int cols, int[] out)` → boolean, `nativeResize(int fd, int rows, int cols)`, `nativeSignal(int pid, int sig)`, `nativeWait(int pid)` → `int`.

- [ ] **Step 1: Tulis `terminal_pty.c`**

Isi wajib: `posix_openpt(O_RDWR|O_NOCTTY)`, `grantpt`, `unlockpt`, `ptsname`, `open(slave, O_RDWR)`, `fork`; di anak `setsid()`, `ioctl(slave, TIOCSCTTY, 0)`, `dup2` slave ke 0,1,2, `chdir(cwd)`, `execve(argv[0], argv, env)`, lalu `_exit(127)`; di induk set `TIOCSWINSZ` awal dan `out[0]=master`, `out[1]=pid`, return 1. Gagal di tahap mana pun → return 0 dan tutup fd. Jangan pakai `system()`/`sh -c`.

- [ ] **Step 2: Tulis `CMakeLists.txt` dan sambungkan Gradle**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(terminalpty C)
add_library(terminalpty SHARED terminal_pty.c)
target_link_libraries(terminalpty log)
```

Di `app/build.gradle.kts` `android { }` tambah `ndkVersion = "27.0.12077973"`, di `defaultConfig { }` tambah `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }`, dan `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }`.

- [ ] **Step 3: Tulis binding `TerminalPty.kt`**

`System.loadLibrary("terminalpty")` di `init`; `spawn` membuat `argv.toTypedArray()`/`env.toTypedArray()`, `IntArray(2)`, memanggil `nativeSpawn`, dan mengembalikan `Handle(out[0], out[1])` bila true, `null` bila false.

- [ ] **Step 4: Verifikasi kompilasi native 4 ABI**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; `android/app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/` berisi `libterminalpty.so` di keempat folder ABI.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/cpp android/app/src/main/java/com/arkacode/app/core/TerminalPty.kt \
        android/app/build.gradle.kts
git commit -m "feat(terminal): modul native pty untuk shell di proot"
```

---

### Task 3: Buffer output + sesi terminal

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/TerminalOutput.kt` (`TerminalOutputBuffer` + `TerminalReplayBuffer`)
- Test: `android/app/src/test/java/com/arkacode/app/core/TerminalOutputTest.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/TerminalReplayBufferTest.kt`
- Create: `android/app/src/main/java/com/arkacode/app/core/TerminalSession.kt`

**Interfaces:**
- Consumes: `TerminalCommand.forDistro(...)`, `TerminalPty`.
- Produces:
  ```kotlin
  class TerminalOutputBuffer(private val maxBytes: Int = 64 * 1024) {
      fun append(bytes: ByteArray, length: Int): ByteArray?  // non-null saat buffer penuh
      fun drain(): ByteArray                                  // isi buffer, lalu kosong
  }
  class TerminalReplayBuffer(private val maxBytes: Int = 128 * 1024) {
      fun record(bytes: ByteArray)      // simpan, buang byte tertua bila melebihi maxBytes
      fun snapshot(): ByteArray
  }
  class TerminalSession(
      private val scope: CoroutineScope,
      private val onOutput: (ByteArray) -> Unit,
      private val onExit: (Int) -> Unit,
  ) {
      fun start(argv: List<String>, env: List<String>, cwd: String, rows: Int, cols: Int): Boolean
      fun write(bytes: ByteArray)
      fun resize(rows: Int, cols: Int)
      fun ack(bytes: Int)               // melepas jatah backpressure
      fun replay(): ByteArray           // isi ring buffer untuk WebView baru
      fun close()
  }
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.arkacode.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalOutputTest {

    @Test
    fun `potongan kecil tidak langsung di-flush`() {
        val buf = TerminalOutputBuffer(maxBytes = 8)
        assertNull(buf.append("abc".toByteArray(), 3))
    }

    @Test
    fun `melewati batas mengembalikan potongan`() {
        val buf = TerminalOutputBuffer(maxBytes = 4)
        assertArrayEquals("abcd".toByteArray(), buf.append("abcd".toByteArray(), 4))
    }

    @Test
    fun `drain mengembalikan sisa lalu kosong`() {
        val buf = TerminalOutputBuffer(maxBytes = 64)
        buf.append("halo".toByteArray(), 4)
        assertArrayEquals("halo".toByteArray(), buf.drain())
        assertEquals(0, buf.drain().size)
    }

    @Test
    fun `length menghormati isi buffer yang lebih pendek`() {
        val buf = TerminalOutputBuffer(maxBytes = 64)
        buf.append("abcdXXXX".toByteArray(), 4)
        assertArrayEquals("abcd".toByteArray(), buf.drain())
    }
}
```

```kotlin
package com.arkacode.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalReplayBufferTest {

    @Test
    fun `snapshot mengembalikan byte dengan urutan sama`() {
        val buf = TerminalReplayBuffer(maxBytes = 64)
        buf.record("abc".toByteArray())
        buf.record("def".toByteArray())
        assertArrayEquals("abcdef".toByteArray(), buf.snapshot())
    }

    @Test
    fun `melebihi batas membuang byte tertua`() {
        val buf = TerminalReplayBuffer(maxBytes = 4)
        buf.record("abcdef".toByteArray())
        assertArrayEquals("cdef".toByteArray(), buf.snapshot())
    }

    @Test
    fun `potongan lebih besar dari batas menyisakan ekor`() {
        val buf = TerminalReplayBuffer(maxBytes = 3)
        buf.record("abcdefgh".toByteArray())
        assertArrayEquals("fgh".toByteArray(), buf.snapshot())
    }

    @Test
    fun `snapshot tidak mengosongkan buffer`() {
        val buf = TerminalReplayBuffer(maxBytes = 16)
        buf.record("x".toByteArray())
        assertEquals(1, buf.snapshot().size)
        assertEquals(1, buf.snapshot().size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TerminalOutputTest" --tests "com.arkacode.app.core.TerminalReplayBufferTest"`
Expected: FAIL — `Unresolved reference: TerminalOutputBuffer` / `TerminalReplayBuffer`.

- [ ] **Step 3: Implement kedua buffer**

`TerminalOutputBuffer`: simpan `ByteArrayOutputStream`; `append` menulis `length` byte, mengembalikan `toByteArray()` + reset bila ukuran ≥ `maxBytes`, selain itu `null`; `drain` mengembalikan isi + reset.

`TerminalReplayBuffer`: simpan `ArrayDeque<ByteArray>` atau `ByteArray` sirkular; `record` menambah, lalu membuang byte tertua sampai total ≤ `maxBytes`; `snapshot` mengembalikan gabungan tanpa mengubah isi. Dipakai untuk memutar ulang layar setelah rotasi.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TerminalOutputTest" --tests "com.arkacode.app.core.TerminalReplayBufferTest"`
Expected: PASS (8 test).

- [ ] **Step 5: Implement `TerminalSession`**

`start` memanggil `TerminalPty.spawn`, lalu `scope.launch(Dispatchers.IO)` membaca `FileInputStream(handle.fd)` ke buffer 8 KB dan mengirim tiap potongan lewat `onOutput` (tanpa decode UTF-8; byte mentah). Setiap potongan juga masuk `TerminalReplayBuffer`.

Backpressure: `Semaphore(8 * 1024 * 1024)` sebagai jatah byte belum di-ack. Pembaca memanggil `acquire(chunk.size)` sebelum `onOutput`; `ack(bytes)` memanggil `release(bytes)` (dibatasi sisa jatah agar tidak melebihi semaphore awal). Bila jatah habis, pembaca berhenti membaca fd sehingga proot terblokir — bukan antre di memori. `close` melepas seluruh jatah supaya pembaca tidak menggantung.

Setelah stream habis, `TerminalPty.waitFor(pid)` → `onExit`. `write` memakai `FileOutputStream(fd)` lalu `flush()`. `resize` meneruskan ke `TerminalPty.resize`. `replay()` mengembalikan `TerminalReplayBuffer.snapshot()`. `close` mengirim `SIGHUP`, menunggu 300 ms, lalu `SIGKILL`, menutup fd sekali saja (idempoten).

- [ ] **Step 6: Verifikasi kompilasi**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/TerminalOutput.kt \
        android/app/src/test/java/com/arkacode/app/core/TerminalOutputTest.kt \
        android/app/src/test/java/com/arkacode/app/core/TerminalReplayBufferTest.kt \
        android/app/src/main/java/com/arkacode/app/core/TerminalSession.kt
git commit -m "feat(terminal): sesi pty, buffer output, dan ring buffer replay"
```

---

### Task 4: Aset xterm.js + jembatan JS

**Files:**
- Create: `android/app/src/main/assets/terminal/index.html`
- Create: `android/app/src/main/assets/terminal/xterm.js` (xterm 5.5.0, MIT)
- Create: `android/app/src/main/assets/terminal/xterm.css`
- Create: `android/app/src/main/assets/terminal/LICENSE-xterm`
- Create: `android/app/src/main/java/com/arkacode/app/ui/TerminalBridge.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class TerminalBridge(
      private val onInput: (ByteArray) -> Unit,
      private val onResize: (Int, Int) -> Unit,
      private val onAck: (Int) -> Unit,
  ) {
      @JavascriptInterface fun input(base64: String)
      @JavascriptInterface fun resize(rows: Int, cols: Int)
      @JavascriptInterface fun ack(bytes: Int)
  }
  ```
  Kontrak JS:
  - `window.termWrite(base64)` menulis byte ke terminal dan memanggil `Bridge.ack(byteLength)` di callback `term.write`.
  - `window.termGetSelection()` mengembalikan teks seleksi (`term.getSelection()`), dipanggil Kotlin lewat `evaluateJavascript`.
  - Halaman memanggil `Bridge.input(base64)` untuk ketikan dan `Bridge.resize(rows, cols)` saat ukuran berubah.

- [ ] **Step 1: Unduh aset xterm.js versi 5.5.0 dan simpan di `assets/terminal/`**

Sumber: `https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.js`, `.../css/xterm.css`, `.../LICENSE`. Simpan apa adanya; jangan menambal isi berkas.

- [ ] **Step 2: Tulis `index.html`**

Isi: `<div id="term"></div>`, `<script src="xterm.js">`, lalu skrip inline yang membuat `Terminal({ fontSize: 13, fontFamily: "monospace", scrollback: 5000, cursorBlink: true, theme: { background: "#111111" } })`, `term.open(document.getElementById("term"))`, `term.onData(d => Bridge.input(toBase64(d)))`, `term.onResize(({rows, cols}) => Bridge.resize(rows, cols))`.

```js
window.termWrite = b64 => {
  const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  term.write(bytes, () => Bridge.ack(bytes.length));
};
window.termGetSelection = () => term.getSelection();
```

`toBase64` mengubah string JS ke byte UTF-8 lalu base64 (`TextEncoder` + `btoa`, potong per 32 KB agar tidak kena batas argumen). `term.focus()` setelah load.

- [ ] **Step 3: Tulis `TerminalBridge.kt`**

`input` men-`Base64.decode(base64, Base64.DEFAULT)` lalu `onInput`; `resize` meneruskan `onResize(rows, cols)`; `ack` meneruskan `onAck(bytes)`. Ketiganya `@JavascriptInterface`.

- [ ] **Step 4: Verifikasi aset ikut terpaket**

Run: `cd android && ./gradlew :app:assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/terminal`
Expected: empat berkas `assets/terminal/...` tercantum.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/assets/terminal android/app/src/main/java/com/arkacode/app/ui/TerminalBridge.kt
git commit -m "feat(terminal): aset xterm.js dan jembatan WebView"
```

---

### Task 5: Layar terminal + baris tombol

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/ui/TerminalScreen.kt`
- Modify: `android/app/src/main/java/com/arkacode/app/ui/I18n.kt` (deklarasi + blok ID + blok EN)

**Interfaces:**
- Consumes: `TerminalHost` (Task 6), `TerminalBridge`.
- Produces:
  ```kotlin
  sealed interface TerminalState {
      data object Starting : TerminalState
      data object Running : TerminalState
      data class Exited(val code: Int) : TerminalState
  }

  @Composable
  fun TerminalScreen(
      state: TerminalState,
      distroLabel: String,
      replay: ByteArray,
      output: Flow<ByteArray>,
      onInput: (ByteArray) -> Unit,
      onResize: (Int, Int) -> Unit,
      onAck: (Int) -> Unit,
      onRestart: () -> Unit,
      onClose: () -> Unit,
  )
  ```
  `replay` diputar sekali setelah halaman selesai dimuat; `output` adalah aliran potongan baru.

- [ ] **Step 1: Tambah key i18n (ID + EN)**

ID: `terminalTitle` = "Terminal", `terminalStarting` = "Menyiapkan shell…", `terminalExited` = lambda `(Int) -> String` "Shell berhenti (kode $it)", `terminalRestart` = "Mulai ulang", `terminalClose` = "Tutup", `terminalOnlyProot` = "Terminal hanya tersedia untuk runtime proot", `terminalNotReady` = "Distro belum siap dipakai terminal", `terminalPaste` = "Tempel", `terminalCopy` = "Salin", `terminalDistroBusy` = "Tutup terminal dulu sebelum menghapus atau memasang ulang distro ini".

EN: "Terminal", "Starting shell…", "Shell exited (code $it)", "Restart", "Close", "Terminal is only available in proot runtime", "Distro is not ready for the terminal", "Paste", "Copy", "Close the terminal before removing or reinstalling this distro".

- [ ] **Step 2: Implement `TerminalScreen`**

`AndroidView(factory = { WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; addJavascriptInterface(bridge, "Bridge"); loadUrl("file:///android_asset/terminal/index.html") } })`. `bridge = TerminalBridge(onInput, onResize, onAck)`.

- Pada `onPageFinished` (WebViewClient): kirim `replay` lewat `evaluateJavascript("termWrite(\"$b64\")")`, lalu kumpulkan `output` dengan `LaunchedEffect` dan kirim tiap potongan dengan cara yang sama.
- Bila `state` bukan `Running`, tampilkan overlay pesan + tombol Mulai ulang/Tutup. Ukuran awal `rows = 24`, `cols = 80`; `onResize` pertama menyesuaikan pty.
- WebView dibuat per pemasangan layar; isi lama datang dari `replay`, jadi tidak ada WebView yang dipegang lintas Activity (tidak ada kebocoran Context).

- [ ] **Step 3: Baris tombol**

Row hemat tinggi di bawah WebView, tiap tombol mengirim byte ke `onInput`: `Esc` = `0x1b`, `Tab` = `0x09`, panah = `\u001b[A/B/C/D`, `PgUp/PgDn` = `\u001b[5~`/`\u001b[6~`, `Ctrl-C` = `0x03`, `Ctrl-D` = `0x04`, `Ctrl-Z` = `0x1a`. Tombol `Ctrl` dan `Alt` bersifat sticky: menekan `Ctrl` lalu huruf `c` mengirim `0x03`, `Alt` lalu huruf mengirim `ESC` + huruf; sticky lepas setelah satu karakter.

`Tempel` membaca `LocalClipboardManager.current.getText()` lalu mengirim byte UTF-8-nya lewat `onInput`. `Salin` memanggil `webView.evaluateJavascript("termGetSelection()")`, hasilnya berupa string JSON-escape; bila isinya tidak kosong, taruh di clipboard Android lewat `ClipboardManager`. Seleksi native WebView tidak dipakai karena renderer canvas tidak mengeksposnya.

- [ ] **Step 4: Verifikasi kompilasi**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/ui/TerminalScreen.kt \
        android/app/src/main/java/com/arkacode/app/ui/I18n.kt
git commit -m "feat(terminal): layar terminal, baris tombol, salin dan tempel"
```

---

### Task 6: `TerminalHost` + titik masuk + guard distro

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/TerminalHost.kt`
- Modify: `android/app/src/main/java/com/arkacode/app/ui/EngineScreen.kt`
- Modify: `android/app/src/main/java/com/arkacode/app/ui/DistroScreen.kt`

**Interfaces:**
- Consumes: `TerminalCommand.forDistro`, `TerminalSession`, `TerminalScreen`, `TerminalState`.
- Produces:
  ```kotlin
  object TerminalHost {
      val state: StateFlow<TerminalState>
      val output: Flow<ByteArray>            // potongan baru; buffer terbatas lewat semaphore sesi
      fun open(distro: DistroManager, workspaceDir: File?, containersDir: File): Boolean
      fun replay(): ByteArray
      fun write(bytes: ByteArray)
      fun resize(rows: Int, cols: Int)
      fun ack(bytes: Int)
      fun restart(distro: DistroManager, workspaceDir: File?, containersDir: File): Boolean
      fun stop()
      fun aliveFor(rootfs: String): Boolean
  }
  ```
  `open` mengembalikan `false` bila `forDistro` menghasilkan `null` (distro belum terpasang / runtime bukan proot).

- [ ] **Step 1: Tulis `TerminalHost`**

`object` tingkat proses, bukan `remember`: satu `TerminalSession?`, satu `TerminalReplayBuffer(128 * 1024)`, `MutableStateFlow<TerminalState>(Starting)`, dan `Channel<ByteArray>(capacity = 64)` untuk output.

- `open`: bila sudah ada sesi hidup untuk rootfs yang sama, kembalikan `true` tanpa memulai ulang. Bila ada sesi lain, `stop()` dulu. Lalu `TerminalCommand.forDistro(...)`; `null` → `false`. `scope.launch(Dispatchers.IO)` memanggil `session.start(argv, env, cwd, 24, 80)`; `onExit` mengisi `state = Exited(code)`.
- `write`/`resize`/`ack` meneruskan ke sesi; aman dipanggil saat sesi null (abaikan).
- `replay` mengembalikan `TerminalReplayBuffer.snapshot()`.
- `stop`: `session.close()`, `state = Starting`, kosongkan buffer replay, `session = null`. `aliveFor` menjadi `false` setelahnya.
- `aliveFor(rootfs)`: `session != null && state !is Exited && rootfs == activeRootfs`. `activeRootfs` (path absolut) disimpan host saat `open`.
- Catat di komentar kelas: sesi mati bersama proses; tidak ada foreground service (lihat non-goal design).
- Catat di komentar kelas: sesi mati bersama proses; tidak ada foreground service (lihat non-goal design).

- [ ] **Step 2: Tombol di `ReadyScreen` dan layar gagal Boot**

`ReadyScreen` menerima `onOpenTerminal: () -> Unit`; tambahkan tombol ikon di deretan aksi yang sama dengan `onOpenBuild`. Layar gagal Boot (`Screen.Boot` dengan `failMsg`) juga menerima `onOpenTerminal` dan menampilkan tombol yang sama. Keduanya hanya menyalakan `showTerminal = true`; sesi dibuka di satu tempat:

```kotlin
val workspace = File(context.filesDir, "workspace")   // EngineManager.workspaceDir private
val containers = File(context.filesDir, "containers")
if (!TerminalHost.open(engine.distro, workspace, containers)) notReady = true
```

- [ ] **Step 3: Tampilkan `TerminalScreen`**

Di `EngineScreen`, setelah `showTerminal` true: `TerminalScreen(state = TerminalHost.state.collectAsState().value, distroLabel = engine.distro.label, replay = TerminalHost.replay(), output = TerminalHost.output, onInput = TerminalHost::write, onResize = TerminalHost::resize, onAck = TerminalHost::ack, onRestart = { TerminalHost.restart(...) }, onClose = { TerminalHost.stop(); showTerminal = false })`.

`TerminalScreen` mengambil `replay` sekali saat komposisi; karena itu parameter dibaca dalam `remember { TerminalHost.replay() }` di dalam layar.

- [ ] **Step 4: Distro berganti → sesi lama berhenti**

`DistroScreen.selectDistro` menerima callback `onDistroChanged: () -> Unit` yang dipanggil setelah `engine.setDistro(spec)`; `EngineScreen` mengirimnya sebagai `TerminalHost.stop()`. Sesi berikutnya memakai distro baru saat terminal dibuka lagi.

- [ ] **Step 5: Guard hapus **dan** pasang ulang**

`DistroScreen` menerima `terminalRunning: () -> Boolean`; `EngineScreen` mengirim `{ TerminalHost.aliveFor(engine.distro.rootfsDir.absolutePath) }`. Tombol Hapus dan Pasang ulang memakai `enabled = ... && !engine.isRunning() && !terminalRunning()`; saat nonaktif karena terminal, tampilkan `terminalDistroBusy`. Terminal punya tombol `Tutup` untuk melepas kunci.

- [ ] **Step 6: Verifikasi kompilasi**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/TerminalHost.kt \
        android/app/src/main/java/com/arkacode/app/ui/EngineScreen.kt \
        android/app/src/main/java/com/arkacode/app/ui/DistroScreen.kt
git commit -m "feat(terminal): host sesi proses, titik masuk ready dan boot gagal, guard distro"
```

---

### Task 7: Verifikasi CI + bukti perangkat

**Files:**
- Modify: `.github/workflows/build-android-apk.yml` hanya bila NDK/CMake perlu dipasang eksplisit.

**Interfaces:**
- Consumes: seluruh task sebelumnya.

- [ ] **Step 1: Jalankan build + unit test penuh**

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, tidak ada test gagal.

- [ ] **Step 2: Push branch dan jalankan workflow**

Push ke branch fitur, lalu `workflow_dispatch` pada `build-android-apk.yml` untuk ref tersebut; tunggu sampai selesai.
Expected: job `assembleDebug` juga `Unit test` `success`; bila NDK runner tidak punya `27.0.12077973`, sesuaikan `ndkVersion` ke versi yang terpasang dan ulangi.

- [ ] **Step 3: Checklist perangkat (tempel hasil di body PR)**

- `apk add htop` lalu `htop` → penuh, ikut lebar layar (**Review Focus 3**).
- `vim` buka/tutup → tidak ada sisa layar.
- `sleep 100` + tombol Ctrl-C → berhenti (**Review Focus 1**).
- `read -p "nama: " x` → ketikan masuk ke prompt (**Review Focus 5**).
- `yes` 3 detik lalu Ctrl-C → UI responsif, memori tidak membengkak (**Review Focus 6**).
- Salin teks dari terminal → tempel di chat; tempel teks ke terminal.
- Server tidak jalan (layar gagal Boot) → tombol Terminal ada dan membuka shell yang bisa dipakai.
- Tutup terminal lalu buka lagi → isi layar lama kembali (replay).
- Rotasi saat `top` → tampilan menyesuaikan dan isi diputar ulang.
- Ganti distro lalu `cat /etc/os-release` → distro baru (**Review Focus 4**).
- Coba hapus/pasang ulang distro saat sesi hidup → ditolak dengan `terminalDistroBusy`; setelah `Tutup`, tombol aktif lagi.
- Runtime Termux → pesan `terminalOnlyProot`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/build-android-apk.yml
git commit -m "ci(terminal): pastikan native pty terbangun di runner"
```
