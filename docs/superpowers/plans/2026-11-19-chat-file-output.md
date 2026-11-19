# Berkas dari Model di Chat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Berkas yang dihasilkan model selama satu giliran muncul di bubble jawaban giliran itu — gambar sebagai pratinjau, video bisa diputar, semua jenis punya tombol Unduh dan Bagikan.

**Architecture:** Catat isi folder sesi (`workspace/sessions/<id>`) sebelum prompt dikirim, diff saat giliran selesai, simpan hasilnya per id pesan asisten. Saat riwayat dimuat, daftar itu ditempelkan ke `ChatItem.Assistant.files` dan dirender di `ChatScreen`.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, `VideoView`/`BitmapFactory` bawaan Android, JUnit 4 (unit test JVM).

**Spec:** `docs/superpowers/specs/2026-11-19-chat-file-output-design.md`

## Global Constraints

- Jangan tambah dependency Gradle apa pun. Pratinjau gambar/video memakai API platform.
- Komentar kode dan teks commit dalam bahasa Indonesia, mengikuti isi repo.
- Semua teks UI lewat `I18n.kt`; paritas `id`/`en` wajib (sekarang 282 kunci per bahasa, menjadi 288 setelah task 6).
- Tidak ada build native lokal di mesin ini (NDK x86_64 di host arm64). Verifikasi kompilasi lewat CI: `workflow_dispatch` `.github/workflows/build-android-apk.yml`. Yang boleh dijalankan lokal: `./gradlew :app:testDebugUnitTest` dan `:app:compileDebugKotlin` (JVM).
- Path berkas di `ChatFile.path` selalu **relatif terhadap folder sesi** (`VirtualFs.workspaceDir(sessionId)`), tidak pernah absolut.
- Folder yang dilewati saat pemindaian: `uploads`, `node_modules`, `.git`, `.venv`, `__pycache__`, `build`, `.gradle`.

## Review Focus

- Berkas yang dibuat lalu dihapus dalam giliran yang sama → tidak boleh muncul (diff membandingkan kondisi akhir). Diuji di Task 2.
- Giliran yang meng-install dependency (ribuan berkas) → tidak boleh membanjiri chat. Diuji di Task 2 (batas entri + batas hasil).
- Nama berkas ber-spasi / non-ASCII / bersubfolder dalam → harus tetap tampil dan bisa diunduh. Diuji di Task 2 (nama) dan Task 5 (path relatif).
- Video berukuran sangat besar atau gambar > 32 MB → tidak boleh membuat app kehabisan memori/OOM. Diuji di Task 6 lewat batas ukuran sebelum decode/putar.
- Riwayat sesi yang dimuat ulang setelah app ditutup → kartu berkas harus tetap muncul, dan hilang bersih kalau berkasnya sudah dihapus. Diuji di Task 3.

---

### Task 1: `ChatFile` dan pembacaan part `type: "file"`

**Files:**
- Modify: `android/app/src/main/java/com/arkacode/app/net/ServerModels.kt` (tambah `ChatFile`, field `files` di `ChatItem.Assistant`)
- Modify: `android/app/src/main/java/com/arkacode/app/net/OpenCodeClient.kt:388-440` (`parseMessage`)
- Test: `android/app/src/test/java/com/arkacode/app/net/MessagePartsTest.kt` (baru)

**Interfaces:**
- Produces:
  - `data class ChatFile(val path: String, val name: String, val size: Long, val mime: String)` dengan `val kind: Kind` (`Kind.IMAGE`, `Kind.VIDEO`, `Kind.OTHER`) dan `companion object { fun mimeOf(name: String): String }`.
  - `ChatItem.Assistant(..., val files: List<ChatFile> = emptyList())`.
  - `internal fun parseMessage(item: JsonElement): ChatItem?` di `OpenCodeClient` (sebelumnya `private`), supaya bisa diuji.

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
package com.arkacode.app.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePartsTest {

    private fun parse(json: String) = OpenCodeClient.parseMessage(Json.parseToJsonElement(json))

    @Test
    fun `part file jadi ChatFile`() {
        val item = parse(
            """
            {"info":{"id":"m1","role":"assistant","finish":"stop"},
             "parts":[{"type":"file","mime":"image/png","filename":"chart.png","url":"file:///root/workspace/sessions/s1/chart.png"}]}
            """.trimIndent(),
        ) as ChatItem.Assistant
        assertEquals(1, item.files.size)
        assertEquals("chart.png", item.files[0].name)
        assertEquals(ChatFile.Kind.IMAGE, item.files[0].kind)
    }

    @Test
    fun `part file tanpa nama atau url diabaikan`() {
        val item = parse(
            """
            {"info":{"id":"m2","role":"assistant","finish":"stop"},
             "parts":[{"type":"file","mime":"image/png"},{"type":"text","text":"halo"}]}
            """.trimIndent(),
        ) as ChatItem.Assistant
        assertTrue(item.files.isEmpty())
        assertEquals(listOf("halo"), item.texts)
    }

    @Test
    fun `mime dari ekstensi`() {
        assertEquals("image/webp", ChatFile.mimeOf("a.webp"))
        assertEquals("video/mp4", ChatFile.mimeOf("a.mp4"))
        assertEquals("application/octet-stream", ChatFile.mimeOf("tanpa-ekstensi"))
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.net.MessagePartsTest"`
Expected: FAIL — `ChatFile` belum ada.

- [ ] **Step 3: Implementasi**

Di `net/ServerModels.kt`: tambah `ChatFile` (kind diturunkan dari mime: prefix `image/` → `IMAGE`, `video/` → `VIDEO`, selain itu `OTHER`; MIME sendiri diambil dari field `mime` bila ada, kalau kosong dari `mimeOf(name)`), konstanta MIME lewat peta ekstensi yang sama dengan `WorkspaceExport.mimeOf` (png, jpg/jpeg, webp, gif, mp4, webm, pdf, zip, txt/md/log/kt/java/sh, json; default `application/octet-stream`), dan field `files` di `ChatItem.Assistant`.

Di `OpenCodeClient.parseMessage`: cabang `"file"` di dalam loop part asisten. Ambil `filename` (fallback `name`), `mime`, dan `url`; `path` = bagian setelah `"/sessions/<sid>/"` bila URL mengandung itu, selain itu `url` apa adanya. Part tanpa `filename` atau tanpa `url` diabaikan.

**Penyimpangan dari spec §4:** spec menyebut `url` berbentuk `data:` didecode ke `cacheDir` lalu dipakai seperti berkas lain. Rencana ini sengaja **tidak** melakukannya — part `data:` diabaikan. Alasan: parser tidak punya `Context`, dan di praktiknya model yang dipakai di app ini menghasilkan berkas lewat tool di workspace, bukan lewat part `data:`. Kalau nanti terbukti provider mengirim part `data:`, decoder-nya ditambahkan di `ChatFileCards` (butuh `Context`, sudah tersedia di sana).

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.net.MessagePartsTest"`
Expected: PASS (3 test).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/net/ServerModels.kt \
        android/app/src/main/java/com/arkacode/app/net/OpenCodeClient.kt \
        android/app/src/test/java/com/arkacode/app/net/MessagePartsTest.kt
git commit -m "feat(chat): model ChatFile dan baca part file dari server"
```

---

### Task 2: Pemindaian dan diff folder sesi

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/TurnFiles.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/TurnFilesTest.kt` (baru)

**Interfaces:**
- Consumes: `ChatFile` (Task 1).
- Produces:
  - `data class TurnStamp(val size: Long, val mtime: Long)`
  - `object TurnFiles`:
    - `internal fun scan(root: File, maxEntries: Int = 10_000): Map<String, TurnStamp>?` — `null` bila melebihi `maxEntries`.
    - `internal fun diff(before: Map<String, TurnStamp>, root: File, limit: Int = 50): List<ChatFile>`
    - `fun snapshot(context: Context, sessionId: String): Map<String, TurnStamp>?`
    - `fun changed(context: Context, sessionId: String, before: Map<String, TurnStamp>?): List<ChatFile>`
  - `private val SKIP_DIRS = setOf("uploads","node_modules",".git",".venv","__pycache__","build",".gradle","target","dist","vendor",".cache",".next")`
  - Keempat fungsi publik di `TurnFiles` **bloking** (bukan `suspend`); pemanggil yang menaruhnya di `Dispatchers.IO`.

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TurnFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(rel: String, text: String, mtime: Long? = null): File {
        val f = File(tmp.root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        if (mtime != null) f.setLastModified(mtime)
        return f
    }

    @Test
    fun `scan melewati folder yang diabaikan`() {
        write("out/chart.png", "x")
        write("node_modules/a/index.js", "x")
        write("uploads/user.png", "x")
        write(".git/HEAD", "x")
        val map = TurnFiles.scan(tmp.root)!!
        assertEquals(setOf("out/chart.png"), map.keys)
    }

    @Test
    fun `diff hanya berkas baru atau berubah`() {
        val lama = write("tetap.txt", "sama", mtime = 1000)
        val diubah = write("ubah.txt", "v1", mtime = 1000)
        write("buang.txt", "akan dihapus", mtime = 1000)
        val before = TurnFiles.scan(tmp.root)!!

        write("baru.png", "x", mtime = 2000)
        diubah.writeText("v2 lebih panjang")
        diubah.setLastModified(2000)
        File(tmp.root, "buang.txt").delete()
        lama.setLastModified(1000)

        val changed = TurnFiles.diff(before, tmp.root).map { it.path }
        assertEquals(listOf("baru.png", "ubah.txt"), changed)
    }

    @Test
    fun `diff mengabaikan berkas yang hanya dibaca`() {
        write("masukan.txt", "isi")
        val before = TurnFiles.scan(tmp.root)!!
        assertTrue(TurnFiles.diff(before, tmp.root).isEmpty())
    }

    @Test
    fun `scan mengembalikan null bila terlalu banyak berkas`() {
        repeat(5) { write("f$it.txt", "x") }
        assertNull(TurnFiles.scan(tmp.root, maxEntries = 3))
    }

    @Test
    fun `diff dipotong sesuai limit`() {
        val before = TurnFiles.scan(tmp.root)!!
        repeat(10) { i -> write("b$i.png", "x", mtime = 1000L + i) }
        assertEquals(4, TurnFiles.diff(before, tmp.root, limit = 4).size)
    }

    @Test
    fun `nama dan mime dibawa ke ChatFile`() {
        val before = TurnFiles.scan(tmp.root)!!
        write("hasil video.mp4", "x")
        val f = TurnFiles.diff(before, tmp.root).single()
        assertEquals("hasil video.mp4", f.name)
        assertEquals("video/mp4", f.mime)
        assertEquals(ChatFile.Kind.VIDEO, f.kind)
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TurnFilesTest"`
Expected: FAIL — `TurnFiles` belum ada.

- [ ] **Step 3: Implementasi `core/TurnFiles.kt`**

`scan`: rekursif (`File.walkTopDown()`), lewati direktori yang namanya ada di `SKIP_DIRS` (`onEnter`/filter), hanya `isFile`, kunci = path relatif dengan pemisah `/`, nilai `TurnStamp(length(), lastModified())`. Berhenti dan return `null` bila jumlah entri > `maxEntries`. `diff`: pindai ulang, ambil entri yang tidak ada di `before` atau `size`/`mtime` berbeda, urutkan `mtime` menurun, ambil `limit`, petakan ke `ChatFile(path, name = nama berkas, size, mime = ChatFile.mimeOf(name))`. `snapshot`/`changed` hanya membungkus dengan `VirtualFs(context).workspaceDir(sessionId)`.

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TurnFilesTest"`
Expected: PASS (6 test).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/TurnFiles.kt \
        android/app/src/test/java/com/arkacode/app/core/TurnFilesTest.kt
git commit -m "feat(chat): pindai dan diff berkas sesi per giliran"
```

---

### Task 3: Penyimpanan daftar berkas per pesan

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/core/TurnFileStore.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/TurnFileStoreTest.kt` (baru)

**Interfaces:**
- Consumes: `ChatFile` (Task 1), `TurnFiles` (Task 2).
- Produces:
  - `class TurnFileStore(private val dir: File, private val maxMessages: Int = 200)`
    - `fun save(messageId: String, paths: List<String>)`
    - `fun paths(messageId: String): List<String>`
    - `fun files(messageId: String, root: File): List<ChatFile>` — hanya berkas yang masih ada.
  - `companion object { fun of(context: Context): TurnFileStore }` → `dir = context.filesDir`, berkas `chat_turn_files.json`.
  - Format JSON: `{"messages":[{"id":"...","paths":["a/b.png"]}]}` (urutan penyimpanan dipertahankan).

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TurnFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `simpan dan baca ulang`() {
        val root = tmp.newFolder("sesi")
        File(root, "a.png").writeText("x")
        val store = TurnFileStore(tmp.newFolder("data"))
        store.save("m1", listOf("a.png"))
        assertEquals(listOf("a.png"), store.paths("m1"))
        assertEquals(listOf("a.png"), TurnFileStore(tmp.root.let { File(it, "data") }).files("m1", root).map { it.path })
    }

    @Test
    fun `berkas yang sudah dihapus tidak dikembalikan`() {
        val root = tmp.newFolder("sesi")
        File(root, "ada.png").writeText("x")
        val store = TurnFileStore(tmp.newFolder("data2"))
        store.save("m1", listOf("ada.png", "hilang.png"))
        assertEquals(listOf("ada.png"), store.files("m1", root).map { it.path })
    }

    @Test
    fun `dipangkas ke jumlah pesan maksimum`() {
        val dir = tmp.newFolder("data3")
        val store = TurnFileStore(dir, maxMessages = 2)
        store.save("m1", listOf("a"))
        store.save("m2", listOf("b"))
        store.save("m3", listOf("c"))
        assertTrue(TurnFileStore(dir, maxMessages = 2).paths("m1").isEmpty())
        assertEquals(listOf("b"), TurnFileStore(dir, maxMessages = 2).paths("m2"))
        assertEquals(listOf("c"), TurnFileStore(dir, maxMessages = 2).paths("m3"))
    }

    @Test
    fun `id tak dikenal mengembalikan daftar kosong`() {
        assertTrue(TurnFileStore(tmp.newFolder("data4")).paths("nope").isEmpty())
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TurnFileStoreTest"`
Expected: FAIL — `TurnFileStore` belum ada.

- [ ] **Step 3: Implementasi**

`@Serializable` record `TurnFileRecord(val id: String, val paths: List<String>)` dan `TurnFileDoc(val messages: List<TurnFileRecord>)`. Baca seluruh dokumen di setiap panggilan (`runCatching`, file rusak/hilang → dokumen kosong), tulis ulang utuh dengan `Json` (encodeDefaults). `save` menimpa record dengan id sama lalu memangkas dari depan sampai `maxMessages`. `files` memetakan path yang lolos `File(root, path).isFile` ke `ChatFile(path, name, size, ChatFile.mimeOf(name))`.

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TurnFileStoreTest"`
Expected: PASS (4 test).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/TurnFileStore.kt \
        android/app/src/test/java/com/arkacode/app/core/TurnFileStoreTest.kt
git commit -m "feat(chat): simpan daftar berkas per pesan asisten"
```

---

### Task 4: Sambungkan ke `SessionRunRegistry`

**Files:**
- Modify: `android/app/src/main/java/com/arkacode/app/core/SessionRunRegistry.kt` (kelas `SessionRun` ~baris 26-47, `send` ~185-217, `startPolling` ~229, `pollUntilDone` ~244-296, `loadSession`/`refresh` ~70-92)
- Test: `android/app/src/test/java/com/arkacode/app/core/TurnFilesMergeTest.kt` (baru)

**Interfaces:**
- Consumes: `TurnFiles` (Task 2), `TurnFileStore` (Task 3), `ChatItem.Assistant.files` (Task 1).
- Produces:
  - `internal fun mergeTurnFiles(items: List<ChatItem>, extra: (String) -> List<ChatFile>): List<ChatItem>` (fungsi top-level di `SessionRunRegistry.kt`, dipakai juga oleh test).
  - `SessionRun`: field baru `internal var preFiles: Map<String, TurnStamp>? = null`.

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
package com.arkacode.app.core

import com.arkacode.app.net.ChatFile
import com.arkacode.app.net.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnFilesMergeTest {
    @Test
    fun `berkas hasil giliran ditempelkan ke asisten dan digabung tanpa duplikat`() {
        val asisten = ChatItem.Assistant("a1", listOf("ok"), emptyList(), emptyList(), files = listOf(
            ChatFile("chart.png", "chart.png", 10, "image/png"),
        ))
        val hasil = mergeTurnFiles(listOf(ChatItem.User("u1", "halo", 0), asisten)) { id ->
            if (id == "a1") listOf(
                ChatFile("chart.png", "chart.png", 10, "image/png"),
                ChatFile("hasil.mp4", "hasil.mp4", 20, "video/mp4"),
            ) else emptyList()
        }
        val filed = hasil.filterIsInstance<ChatItem.Assistant>().single().files.map { it.path }
        assertEquals(listOf("chart.png", "hasil.mp4"), filed)
    }

    @Test
    fun `pesan tanpa berkas tidak berubah`() {
        val asisten = ChatItem.Assistant("a1", listOf("ok"), emptyList(), emptyList())
        val hasil = mergeTurnFiles(listOf(asisten)) { emptyList() }
        assertEquals(emptyList<ChatFile>(), hasil.filterIsInstance<ChatItem.Assistant>().single().files)
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TurnFilesMergeTest"`
Expected: FAIL — `mergeTurnFiles` belum ada.

- [ ] **Step 3: Implementasi**

Tambah `mergeTurnFiles` (map `ChatItem.Assistant` → `copy(files = (files + extra(id)).distinctBy { it.path })`, item lain apa adanya). Tambah `preFiles` di `SessionRun`.

Di `send`: tepat sebelum `client.sendPrompt(...)`, `r.preFiles = TurnFiles.snapshot(context, sessionId)`.
Di `startPolling`: `r.preFiles = TurnFiles.snapshot(context, r.correspondingSessionId)` — pakai `sessionId` parameter yang sudah ada.
Di `pollUntilDone` blok `finally`, sebelum fetch terakhir: hitung diff di bawah `withContext(NonCancellable + Dispatchers.IO) { … }` (agar tombol Stop tidak membatalkannya), tentukan id asisten giliran ini (`r.messages.value.filterIsInstance<ChatItem.Assistant>().lastOrNull { it.id !in r.preIds } ?: .lastOrNull()`), lalu bila hasilnya tidak kosong: `TurnFileStore.of(context).save(id, changed.map { it.path })`. Set `r.preFiles = null` setelahnya.
Ganti semua penugasan `r.messages.value = ...` dari server (di `loadSession`, `refresh`, `pollUntilDone`) supaya lewat satu helper `private suspend fun setMessages(r: SessionRun, sessionId: String, list: List<ChatItem>)` yang menjalankan `withContext(Dispatchers.IO) { mergeTurnFiles(list) { TurnFileStore.of(context).files(it, VirtualFs(context).workspaceDir(sessionId)) } }` sebelum menulis ke state.

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.TurnFilesMergeTest"`
Expected: PASS (2 test). Lanjut `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/SessionRunRegistry.kt \
        android/app/src/test/java/com/arkacode/app/core/TurnFilesMergeTest.kt
git commit -m "feat(chat): catat berkas hasil giliran dan tempelkan ke pesan"
```

---

### Task 5: Unduh dan bagikan berkas sesi

**Files:**
- Modify: `android/app/src/main/java/com/arkacode/app/core/WorkspaceExport.kt` (`writeToDownloads` ~208-260, tambah overload)
- Modify: `android/app/src/main/res/xml/file_paths.xml`
- Create: `android/app/src/main/java/com/arkacode/app/core/ChatFileActions.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ChatFileActionsTest.kt` (baru)

**Interfaces:**
- Consumes: `ChatFile` (Task 1).
- Produces:
  - `WorkspaceExport.saveFileToDownloads(context: Context, file: File, mime: String): Long` (overload baru; `writeToDownloads` diubah dari `private` menjadi `internal`).
  - `object ChatFileActions`:
    - `internal fun resolve(root: File, path: String): File?` — tolak path absolut dan `..`; `null` bila keluar dari `root`.
    - `fun save(context: Context, root: File, file: ChatFile): Result<Long>` (suspend, `Dispatchers.IO`).
    - `fun share(context: Context, root: File, file: ChatFile)` — `ACTION_SEND` + `EXTRA_STREAM` dari FileProvider `${packageName}.fileprovider`, `type = file.mime`, `FLAG_GRANT_READ_URI_PERMISSION`, `clipData = ClipData.newRawUri("", uri)`.

- [ ] **Step 1: Tulis test yang gagal**

```kotlin
package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatFileActionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `resolve menerima path relatif bersubfolder`() {
        val root = tmp.newFolder("sesi")
        val f = File(root, "out/hasil video.mp4").apply { parentFile?.mkdirs(); writeText("x") }
        assertEquals(f.absolutePath, ChatFileActions.resolve(root, "out/hasil video.mp4")?.absolutePath)
    }

    @Test
    fun `resolve menolak traversal dan absolut`() {
        val root = tmp.newFolder("sesi2")
        assertNull(ChatFileActions.resolve(root, "../rahasia.txt"))
        assertTrue(ChatFileActions.resolve(root, "/etc/passwd") == null)
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.ChatFileActionsTest"`
Expected: FAIL — `ChatFileActions` belum ada.

- [ ] **Step 3: Implementasi**

`resolve`: pakai aturan yang sama dengan `VirtualFs.resolveUnder` (trim `\`→`/`, tolak komponen `..`, cek `canonicalPath` tetap di dalam `root`).
`save`: `resolve` → `WorkspaceExport.saveFileToDownloads(context, file, mime)`; berkas tak ada → `Result.failure(IOException("berkas tidak ada"))`.
`share`: `FileProvider.getUriForFile` → Intent chooser.
`file_paths.xml`: tambah `<files-path name="workspace" path="workspace/" />`.
`WorkspaceExport`: `writeToDownloads` → `internal`; tambah `suspend fun saveFileToDownloads(context: Context, file: File, mime: String): Long = withContext(Dispatchers.IO) { writeToDownloads(context, file.name, mime) { out -> file.inputStream().buffered(64 * 1024).use { it.copyTo(out) } }; file.length() }` — **streaming**, jangan `readBytes()` (video ratusan MB akan OOM).

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.arkacode.app.core.ChatFileActionsTest"`
Expected: PASS (2 test).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/arkacode/app/core/WorkspaceExport.kt \
        android/app/src/main/java/com/arkacode/app/core/ChatFileActions.kt \
        android/app/src/main/res/xml/file_paths.xml \
        android/app/src/test/java/com/arkacode/app/core/ChatFileActionsTest.kt
git commit -m "feat(chat): unduh dan bagikan berkas hasil model"
```

---

### Task 6: Kartu berkas di bubble chat

**Files:**
- Create: `android/app/src/main/java/com/arkacode/app/ui/ImagePreview.kt`
- Create: `android/app/src/main/java/com/arkacode/app/ui/ChatFileCards.kt`
- Modify: `android/app/src/main/java/com/arkacode/app/ui/FilesScreen.kt:522-529` (hapus `decodePreview` lokal, impor dari `ImagePreview.kt`)
- Modify: `android/app/src/main/java/com/arkacode/app/ui/ChatScreen.kt:874` (`AssistantBubble` — butuh `sessionRoot`, sisipkan kartu sebelum baris tombol Salin) dan pemanggilnya ~472
- Modify: `android/app/src/main/java/com/arkacode/app/ui/I18n.kt` (6 kunci baru, dua bahasa)

**Interfaces:**
- Consumes: `ChatFile`, `ChatFileActions` (Task 5), `ChatItem.Assistant.files` (Task 1).
- Produces:
  - `internal fun decodePreview(bytes: ByteArray, maxPx: Int = 2048): ImageBitmap?` di `ui/ImagePreview.kt`.
  - `@Composable fun ChatFileCards(files: List<ChatFile>, sessionRoot: File)` di `ui/ChatFileCards.kt`.
  - `AssistantBubble(item: ChatItem.Assistant, sessionRoot: File)`.

- [ ] **Step 1: Tambah kunci i18n**

Di `class Strings`: `var fileSave: String = ""`, `var fileShare`, `var fileOpen`, `var fileSaved`, `var fileSaveFailed`, `var filesMore: (Int) -> String = { "" }`. Isi `id`: "Unduh", "Bagikan", "Buka", "Tersimpan di Download", "Gagal menyimpan", "+%d berkas lagi". `en`: "Download", "Share", "Open", "Saved to Downloads", "Failed to save", "+%d more files". Verifikasi paritas 288/288 dengan skrip regex yang sama seperti sebelumnya.

- [ ] **Step 2: Pindahkan `decodePreview`**

Buat `ui/ImagePreview.kt` berisi fungsi yang sama (dipindah apa adanya dari `FilesScreen.kt`) sebagai `internal fun decodePreview(bytes: ByteArray, maxPx: Int = 2048): ImageBitmap?`, plus `@Composable fun ImagePreviewDialog(bytes: ByteArray, onDismiss: () -> Unit)` (Dialog layar penuh, latar hitam, gambar `ContentScale.Fit`, tap untuk menutup). Hapus versi lokal di `FilesScreen.kt` dan impor yang tidak terpakai.

- [ ] **Step 3: Buat `ui/ChatFileCards.kt`**

`@Composable fun ChatFileCards(files: List<ChatFile>, sessionRoot: File)`: kalau `files.isEmpty()` tidak merender apa pun. Ambil `files.take(12)`; render per `kind`:
- `IMAGE` & `size <= 32 MB` → baca bytes di `LaunchedEffect` (`Dispatchers.IO`), `decodePreview`, tampilkan `Image` selebar bubble (`ContentScale.FillWidth`, sudut 12dp) yang tap-nya membuka `ImagePreviewDialog`; gagal decode → jatuh ke baris berkas dengan teks `I18n.s.previewFail`.
- `VIDEO` & `size <= 512 MB` → kartu `AndroidView(VideoView)` rasio 16:9, `setVideoURI(FileProvider` URI`)`, `setMediaController(MediaController(context).also { it.setAnchorView(view) })`; `DisposableEffect` memanggil `stopPlayback()`.
- selain itu → baris: nama (satu baris, `ellipsis`), ukuran terformat (`SystemSample.formatBytes` bila cocok, kalau tidak helper lokal `formatSize`), lalu tombol `I18n.s.fileSave` dan `I18n.s.fileShare`.
Tombol Unduh/Bagikan **juga dirender untuk IMAGE dan VIDEO** (di bawah pratinjau, bukan overlay). Unduh memanggil `ChatFileActions.save` di `rememberCoroutineScope`, hasilnya Toast `I18n.s.fileSaved` / `I18n.s.fileSaveFailed`. Bagikan memanggil `ChatFileActions.share`. Bila `files.size > 12`, tampilkan baris `I18n.s.filesMore(files.size - 12)`.

- [ ] **Step 4: Sambungkan ke `ChatScreen`**

`AssistantBubble(item, sessionRoot)` — render `ChatFileCards(item.files, sessionRoot)` tepat setelah blok `item.tools` dan sebelum baris tombol Salin, dengan `Spacer(4.dp)`. Di pemanggil (~baris 472): `val sessionRoot = remember(sessionId) { VirtualFs(context).workspaceDir(sessionId) }` lalu teruskan. Bubble sintetis untuk giliran berjalan (dibuat inline di ~496 dan ~523) memakai `files = emptyList()` — kartu muncul setelah giliran selesai, saat pesan tersimpan dimuat.

- [ ] **Step 5: Verifikasi kompilasi**

Run: `cd android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, seluruh test lama + 17 test baru lulus.

- [ ] **Step 6: Commit dan kirim ke CI**

```bash
git add android/app/src/main/java/com/arkacode/app/ui/ImagePreview.kt \
        android/app/src/main/java/com/arkacode/app/ui/ChatFileCards.kt \
        android/app/src/main/java/com/arkacode/app/ui/ChatScreen.kt \
        android/app/src/main/java/com/arkacode/app/ui/FilesScreen.kt \
        android/app/src/main/java/com/arkacode/app/ui/I18n.kt
git commit -m "feat(chat): kartu berkas, pratinjau gambar, dan pemutar video di bubble"
git push origin <branch>
```

Lalu dispatch CI (`workflow_dispatch` pada `build-android-apk.yml`) dan pastikan hijau. Build native hanya bisa diverifikasi di sini.

---

## Verifikasi di perangkat (setelah CI hijau)

1. Minta model membuat gambar lewat perintah (`python`/`ffmpeg` atau `write`) → pratinjau muncul di bubble, tombol Unduh menyimpan ke folder Download, tombol Bagikan membuka share sheet.
2. Minta model membuat mp4 → kartu video muncul, bisa diputar inline.
3. Minta model membuat berkas teks → baris berkas + ukuran, Unduh berhasil.
4. Tutup app, buka sesi itu lagi → kartu berkas masih muncul; hapus berkasnya lewat tab Workspace, buka ulang → kartu hilang.
5. Giliran yang menjalankan `npm install` → tidak ada kartu berkas yang membanjiri chat.
