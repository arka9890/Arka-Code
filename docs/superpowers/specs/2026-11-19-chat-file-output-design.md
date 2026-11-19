# Berkas dari Model di Chat — Design

Tanggal: 2026-11-19
Status: menunggu persetujuan

## Kenapa

Arah user → model sudah berjalan: lampiran di chat dikirim sebagai URI `data:`
(`core/Attachments.kt`) dan salinannya ditaruh di `uploads/` sesi. Arah
sebaliknya tidak ada sama sekali. Model bisa menghasilkan berkas di workspace
(`write` tool, `python`, `ffmpeg`, `curl`), tapi di chat tidak ada apa pun yang
bisa diklik untuk melihat atau mengambil berkas itu — user harus tahu sendiri
bahwa berkasnya ada, lalu membuka tab Workspace dan mencarinya.

Untuk model teks, gambar dan mp4 tidak "dikarang" langsung; berkas itu lahir
dari perintah yang dijalankan model di workspace, atau dari provider yang
mengembalikan part lampiran pada pesan. Dua-duanya harus ditangkap.

## Tujuan

- Berkas yang dihasilkan selama satu giliran muncul **di bubble jawaban giliran
  itu**, bukan di layar lain.
- Gambar tampil sebagai pratinjau inline di chat.
- Video (mp4/webm) bisa diputar inline di chat.
- Berkas lain tampil sebagai baris nama + ukuran dengan aksi Unduh dan Bagikan.
- Kartu berkas tetap muncul saat riwayat sesi dimuat ulang atau app dibuka lagi.

## Bukan tujuan

- Model "mengarang" berkas biner tanpa menjalankan perintah (di luar kendali app).
- Transcoding, thumbnail video, atau streaming potong-potong.
- Editor/pratinjau PDF, DOCX, ZIP di dalam chat.
- Memutar audio inline (mp3, wav) — audio diperlakukan sebagai berkas biasa
  dengan aksi Unduh/Bagikan.
- Berkas yang ditulis model ke luar workspace sesi (mis. `/tmp`, `/root` di
  distro, folder sesi lain).
- Galeri berkas sepanjang sesi. Tab Workspace tetap tempat resminya.

## Anti-tujuan

- Membanjiri chat setelah giliran yang menjalankan `npm install` / `pip install`
  (ribuan berkas) — itu gagal, bukan berhasil.
- Menjadikan fitur ini jalur utama melihat isi workspace; menggandakan file
  explorer di chat juga gagal.

## Kendala yang ditemukan

- `OpenCodeClient.parseMessage` membuang semua detail part selain teks,
  reasoning, dan nama+status tool. Part `type: "file"` tidak dibaca.
- `ChatItem.Assistant` belum punya tempat untuk daftar berkas.
- Penyelesaian giliran terdeteksi di dua jalur: polling (`pollUntilDone`) dan
  SSE `Status(idle)`. Jalur polling yang selalu jalan, jadi hook utamanya di sana.
- `WorkspaceExport.saveFileToDownloads` ada, tapi menerima path relatif workspace
  dan membaca lewat HTTP server. Chat bisa membaca berkas langsung dari
  `VirtualFs` tanpa server.
- FileProvider baru mengekspos `tts-engine/`; berbagi berkas workspace butuh satu
  entri `<files-path>` tambahan.
- Tidak ada library pemuat gambar (Coil/Glide) maupun media3. `FilesScreen`
  sudah memakai pola decode `BitmapFactory` — pola yang sama dipakai ulang.
- `ChatScreen` tidak memegang port/password, jadi jalur unduh tidak boleh
  bergantung pada client HTTP.

## Pendekatan

**A. Dari isi pesan (tool parts).** Simpan `state.input`/`state.output` tool
`write`/`edit` dan part `type: "file"`; kartu berkas menempel di bubble. Tahan
restart karena datanya ikut pesan di server. Gagal untuk kasus utama: berkas
hasil `python`/`ffmpeg`/`curl` tidak terlihat, padahal gambar dan mp4 justru
lahir begitu.

**B. Diff folder sesi per giliran.** Catat isi `workspace/sessions/<id>` saat
prompt dikirim, diff saat giliran selesai, tampilkan berkas baru/berubah sebagai
kartu di bubble giliran itu. Menangkap semua cara pembuatan. Kartu hilang setelah
app di-restart karena daftarnya tidak disimpan.

**C. B + mencatat hasil diff (dipilih).** Sama seperti B, hasil diff disimpan per
id pesan asisten sehingga kartu muncul lagi saat riwayat dimuat ulang. Ditambah
pembacaan part `type: "file"` untuk provider yang benar-benar mengirim lampiran.

Dipilih C: kesenjangan yang paling dirasakan ada pada berkas hasil perintah
shell, dan C menutupnya tanpa kehilangan riwayat. A saja tidak menyentuh kasus
itu; B saja kehilangan riwayat.

## Desain

### 1. Data model

`net/ServerModels.kt`, di dalam `ChatItem.Assistant`:

```kotlin
data class Assistant(
    ...,
    val files: List<ChatFile> = emptyList(),
) : ChatItem
```

`ChatFile` (di `net/ServerModels.kt`, bersebelahan dengan `ChatItem`): `path` (relatif ke folder sesi), `name`,
`size: Long`, `mime: String`. `ChatFile.kind` diturunkan dari mime:
`IMAGE`, `VIDEO`, `OTHER`.

### 2. Mencatat berkas per giliran (`core/TurnFiles.kt`)

- `snapshot(sessionId): Map<String, Stamp>` — telusuri
  `VirtualFs.workspaceDir(sessionId)` rekursif, catat `path → (size, mtime)`.
  Dilewati: `uploads/`, `node_modules/`, `.git/`, `.venv/`, `__pycache__/`,
  `build/`, `.gradle/`. Batas 4000 entri (kalau lebih, snapshot ditandai
  "terlalu besar" dan giliran itu tidak menghasilkan kartu — lebih baik diam
  daripada salah).
- `diff(before, sessionId): List<ChatFile>` — berkas baru atau `size`/`mtime`
  berubah, diurutkan berdasarkan mtime menurun. Maksimum 12 ditampilkan;
  sisanya jadi baris "+N berkas lagi" yang membuka tab Workspace.
- Simpan `Map<messageId, List<String>>` (path relatif saja) di
  `<filesDir>/chat_turn_files.json` lewat kotlinx.serialization. Dipangkas ke 200
  pesan terakhir. Entri yang berkasnya sudah tidak ada dibuang saat dibaca.
- `filesFor(messageId): List<ChatFile>` — dipakai saat memuat riwayat.

### 3. Titik hook

`core/SessionRunRegistry.kt`:

- `send()`: setelah pesan dimuat dan sebelum `sendPrompt` → `run.preFiles =
  TurnFiles.snapshot(sessionId)`.
- `startPolling()` (kasus app dibuka saat turn masih jalan) → snapshot juga.
- `pollUntilDone()` blok `finally`: `diff(preFiles)` → simpan ke store dengan id
  asisten barunya (`lastOrNull { id !in preIds }`, fallback: asisten terakhir).
  Simpan juga ke store untuk atribusi saat render.
- Setiap kali `r.messages.value` diisi dari server (`loadSession`, `refresh`,
  `pollUntilDone`), jalankan `mergeFiles(...)`: untuk tiap `ChatItem.Assistant`,
  `copy(files = TurnFiles.filesFor(it.id) + filesDariPart)`, dedup per `path`.

### 4. Part `type: "file"` dari server

`OpenCodeClient.parseMessage`: part `{type:"file", mime, filename, url}` →
`ChatFile`. `url` yang menunjuk path workspace dipetakan apa adanya (relatif ke
folder sesi). `url` berbentuk `data:` diabaikan pada versi pertama ini: parser
tidak memegang `Context`, dan model yang dipakai app menghasilkan berkas lewat
tool di workspace, bukan lewat part `data:`. Kalau nanti terbukti provider
mengirim part `data:`, decode-nya ditambahkan di sisi UI yang sudah punya
`Context`.

### 5. Render di `ui/ChatScreen.kt`

Di akhir `AssistantBubble`, sebelum baris tombol Salin:

- `IMAGE` → `Image` selebar bubble, decode `BitmapFactory` dengan subsample ke
  ≤ 2048 px (fungsi `decodePreview` yang sudah ada di `FilesScreen` dipindah ke
  `ui/ImagePreview.kt` dan dipakai bersama). Tap → dialog layar penuh.
  Berkas > 32 MB tidak didecode; tampil sebagai baris berkas biasa.
- `VIDEO` → kartu 16:9 dengan `VideoView` (`AndroidView`) dan
  `MediaController`, poster abu-abu + ikon play sebelum diputar. Video
  > 512 MB tampil sebagai baris berkas biasa dengan aksi "Buka" (pemutar
  sistem).
- `OTHER` → baris: ikon berdasarkan mime, nama (satu baris, ellipsis), ukuran
  terformat.
- **Tombol Unduh dan Bagikan ada di semua jenis** — gambar, video, dan berkas
  biasa. Untuk gambar, tombolnya berada di bawah pratinjau (bukan overlay di
  atas gambar) supaya tetap terlihat pada gambar bertema terang. Tidak ada
  berkas yang tampil di chat tanpa jalan untuk menyimpannya ke HP.
- Saat `dispose`, `VideoView` dihentikan (`stopPlayback`) supaya tidak lanjut
  memutar saat di-scroll keluar.

### 6. Unduh dan bagikan

- `WorkspaceExport`: `writeToDownloads` dijadikan `internal`, tambah overload
  `saveFileToDownloads(context, file: File, mime: String): Long` yang membaca
  langsung dari `File` (tanpa server). Chip memakai ini untuk berkas di folder
  sesi.
- Bagikan: `ACTION_SEND` (`text/plain` = nama berkas? tidak) — `type: mime`,
  `EXTRA_STREAM` = URI FileProvider, `FLAG_GRANT_READ_URI_PERMISSION`, plus
  `clipData = ClipData.newRawUri(...)` mengikuti pola `TtsInstaller` (tanpa
  ClipData, sebagian penerima menolak URI).
- `res/xml/file_paths.xml`: tambah `<files-path name="workspace" path="workspace/"/>`.

### 7. Kasus tepi

- Turn yang timeout (`MAX_POLL_MS`) → diff tetap dihitung di `finally`.
- Giliran tanpa berkas baru → tidak ada kartu, tidak ada noise.
- Berkas dibuat lalu dihapus dalam giliran yang sama → tidak muncul (diff
  melihat kondisi akhir).
- Nama berkas non-ASCII/ber spasi → dipakai apa adanya; sanitasi hanya untuk
  penulisan ke Downloads (`MediaStore` sudah menangani bentrok nama).
- Server mati saat turn selesai → kartu tetap ada karena berkasnya lokal.

### 8. i18n

Kunci baru (id/en, paritas dijaga): `fileSave`, `fileShare`, `fileOpen`,
`fileSaved`, `fileSaveFailed`, `filesMore(n)`, `fileTooLargePreview`. Teks
"gagal tampilkan gambar" memakai `previewFail` yang sudah ada.

## Yang diserahkan ke builder

- Bentuk `Stamp` (size+mtime saja cukup) dan nama kelas persisnya.
- Cara memangkas `chat_turn_files.json` (cukup: buang id terlama saat > 200).
- Apakah `ImagePreview.kt` memuat dialog layar penuh atau hanya fungsi decode —
  dialog layar penuh boleh dipotong kalau menambah banyak kode.
- Bahasa Indonesia untuk komentar kode, mengikuti isi repo.
