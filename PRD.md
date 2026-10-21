# PRD — Frontend Android untuk OpenCode (engine 100% opencode)

Status: F0+F1 selesai di `main` · Dibuat: 2026-10-13 · Diperbarui: 2026-10-16
Keputusan terkunci: engine on-device (proot Alpine) · repo baru terpisah dari Arka · paritas fitur penuh · sistem khas Arka dibuang · Arka lama tidak disentuh.

## 1. Latar & Tujuan

Arka (aplikasi lama) membangun ulang seluruh backend agent sendiri (loop chat, 10 tools, memory, skills, FTS, parser) dan terus menambal gap perilaku model. OpenCode menyediakan semua itu sebagai **server headless resmi** (`opencode serve` + OpenAPI di `/doc` + SSE `/event`) — TUI-nya sendiri hanyalah salah satu client.

Tujuan: bangun aplikasi Android **frontend murni** (Kotlin + Jetpack Compose) di repo baru, yang mengendalikan `opencode serve` berjalan di proot Alpine. Nol logika agent di sisi app; semua kecerdasan milik opencode A–Z. Konfigurasi opencode 100% dilakukan dari frontend.

Non-tujuan: mengubah Arka; mem-fork/memodifikasi binary opencode; backend sendiri dalam bentuk apa pun.

## 2. Arsitektur

```
┌─ APP BARU (Kotlin + Compose, frontend murni) ─────────┐
│ UI: Chat · Sessions · Files · Agents · MCP ·          │
│     Providers/Auth · Config editor · Approvals · Diff │
│                          │ HTTP 127.0.0.1:4096        │
│ ServerManager: install/update binary, start/stop,     │
│ health, basic-auth, working dir, log                  │
└──────────────────────────┼───────────────────────────┘
                           │ satu network namespace (proot tidak isolasi net)
┌─ PROOT ALPINE ───────────┼───────────────────────────┐
│ opencode serve --port 4096 --hostname 127.0.0.1       │
│ working dir: /root/workspace/<project>                │
│ config: opencode.json + auth.json (ditulis frontend)  │
└──────────────────────────────────────────────────────┘
```

Fakta kunci (terverifikasi dari docs + uji user):
- Satu binary `opencode`; `serve` adalah subcommand headless — tidak ada paket server-only, TUI tidak pernah dibuka.
- Binary Linux-ARM64 **terbukti jalan di Alpine** (risiko musl gugur).
- Client cukup HTTP ke `localhost:4096` + basic auth (`OPENCODE_SERVER_PASSWORD`, username default `opencode`).

## 3. Kebutuhan Fungsional (paritas penuh)

### 3.1 Chat & streaming (wajib F1)
- Kirim pesan sinkron (`POST /session/:id/message`) untuk turn pendek dan async (`prompt_async` + SSE) untuk turn panjang.
- Render potongan balasan: teks, tool-call, diff, error — dari skema `Message` + `Part[]` di `/doc`.
- Stop/abort (`POST /session/:id/abort`), salin pesan & blok kode (pola UX Arka dipertahankan).
- Pilihan model/agent/tools per pesan (body `message` mendukung `model?`, `agent?`, `tools?`).

### 3.2 Sessions (F2)
- List/buat/hapus/rename/fork/abort/share/unshare (`/session*`); status semua sesi (`/session/status`).
- Diff per sesi (`/session/:id/diff`), summarize, revert/unrevert, `init` (buat AGENTS.md).

### 3.3 Permissions = approvals (F2)
- Sheet persetujuan → `POST /session/:id/permissions/:permissionID` (`response`, `remember=true` = allow-all bawaan opencode — pengganti toggle Arka).
- Event permission masuk via SSE; tidak ada antrean custom.

### 3.4 Files (F2)
- Tree + baca + cari via `/file`, `/file/content`, `/find`, `/find/file`, `/find/symbol`, `/file/status`.
- Aturan performa warisan Arka: listing di background, skip folder berat, cap preview, impor file/ZIP berbatas.

### 3.5 Providers, model, auth (F2)
- List provider + model default (`/config/providers`, `/provider`), status koneksi.
- Login OAuth (`/provider/auth`, `/provider/:id/oauth/*`) dan `PUT /auth/:id` untuk API key — semua dari frontend, tidak ada file config manual.
- Model picker per sesi (pin model per sesi dipertahankan sebagai state frontend).

### 3.6 Agents, commands, MCP (F3)
- List agents (`/agent`), eksekusi slash command (`/session/:id/command`, `/command`), init/summarize.
- MCP: status (`GET /mcp`) + tambah dinamis (`POST /mcp`).
- Skills/references/rules dikelola sebagai file proyek + config — tanpa backend custom.

### 3.7 Config editor (F3)
- Baca (`GET /config`) + tulis (`PATCH /config`) opencode.json dari UI berformulir (bukan editor teks mentah): providers, agents default, permissions default, MCP, LSP/formatter.
- Share/unshare sesi, revert, diff viewer.

### 3.8 Sisi device (bukan fitur AI, tetap wajib)
- ServerManager: unduh binary pinned dari Releases (checksum), start/stop/dispose (`/instance/dispose`), health (`/global/health`), restart saat crash, logcat ringkas.
- Password server disimpan di EncryptedSharedPreferences; port/hostname dapat diubah.
- GitCredentials-equivalent: tulis `.git-credentials` + `.gitconfig` ke rootfs agar bash tool opencode bisa git push (pola Arka, dipertahankan karena tool jalan sebagai user yang sama).
- Backup rules (kecualikan workspace + cache), i18n ID/EN, tema, CI debug+release meniru Arka.

## 4. Yang dibuang dari Arka (sengaja)

AiClient 7 provider, ChatController loop, Tools.kt, MemorySystem, SkillsManager, SessionIndex FTS, TextToolCallParser, I18n sistem meotools. Diganti bawaan opencode: permissions+remember, skills, agents, MCP, share, session search bawaan (bila ada; bila belum, pakai `/find` + session list).

## 5. Fase & Kriteria Terima

- **F0 — Fondasi + smoke (cepat, risiko sudah kecil):** skeleton repo + unduh binary pinned + `serve` jalan dari app + satu chat via API dari UI debug. Terima: health OK, satu balasan AI tampil di layar.
- **F1 — Chat MVP:** streaming SSE, abort, salin, stop, model picker per sesi, error mapping. Terima: percakapan 10 turn stabil, ganti model per sesi independen.
- **F2 — Sessions/permissions/files/providers:** semua §3.2–§3.5 bekerja; approval sheet + remember; impor/ekspor file aman. Terima: task coding kecil end-to-end (baca–ubah–diff–share).
- **F3 — Paritas penuh:** §3.6–§3.7. Terima: checklist §3 tanpa item terbuka.
- **F4 — Polish & rilis:** i18n, tema, backup rules, CI hijau, APK release bertanda tangan.

Pola kerja tiap fase (warisan Arka): patch kecil → PR → CI build APK → merge. Tanpa build lokal.

## 6. Risiko & Mitigasi

| Risiko | Status | Mitigasi |
|---|---|---|
| Binary tak jalan di musl/proot | GUGUR (teruji user) | Pin versi; uji smoke tiap naik versi |
| API berubah antar versi opencode | Aktif | Pin versi + baca `/doc` saat bump; kunci versi minimum |
| Satu server multi-project | Terbuka | F0: uji ganti working dir vs satu server per project |
| Baterai/thermal untuk turn panjang | Terbuka | `prompt_async` + notifikasi; stop selalu tersedia |
| Secret (password server, auth.json) | Terkontrol | EncryptedSharedPreferences; tidak pernah di-log |

## 8. Status implementasi (di `main`, per 2026-10-14; +SSE semantik/OAuth Zen)

Sudah:
- F0: skeleton Gradle+CI, proot Alpine, ServerManager (unduh v1.19.0 musl+sha256, `serve` + tunggu health, stop, serve.log), OpenCodeClient (basic-auth, health, GET mentah).
- Diagnostik engine: tes exec terisolasi, info asset/ABI, log tail di error, `apk libstdc++/libgcc` otomatis, password sampai ke guest env.
- Auto-provisioning: buka app → pipeline jalan sendiri di layar log → selesai masuk frontend sesi (deteksi server lama, retry, stop/start lagi).
- F1 chat: kirim async, refresh near-live (poll 2 dtk), abort/interrupt, approval (Tolak/Izinkan/Selalu), salin pesan + blok kode + output tool, picker model per sesi (+ switch server-side), render markdown, thinking collapse, input aman keyboard.
- Fondasi: backup rules terpasang di manifest, CI debug + release hijau, password server terenkripsi (+editor port/password).
- SSE penuh: `serverEvents()` mengurai event semantik (`session.status` idle/retry/busy, `session.text/reasoning.delta|ended`, `session.tool.*`, `permission.v2.*`, V1 `message.*`), bubble asisten live + label tool, dedup vs pesan tersimpan; polling tetap jaring pengaman.
- Layar Penyedia AI: daftar provider + model (badge Gratis), isi/hapus API key per provider (terenkripsi, disuntik sebagai env saat serve nyala).
- Files UI: tree/baca/cari + preview (markdown/gambar) via `/api/fs/*`.
- Navbar bawah (Sesi | AI | Kapabilitas | Setelan | Arena AI); header aman status-bar; tema claymorphism terang/gelap persisten.
- Layar Kapabilitas: daftar agent (`GET /api/agent`), perintah (`GET /api/command`), skill (`GET /api/skill`).
- Chat F3: pemilih agent per sesi (`POST /api/session/:id/agent`), palet perintah (sisip `/nama`), ringkas (`POST .../compact`), revert+dif berkas (`POST .../revert/stage|commit|clear`, menampilkan patch per file).
- i18n ID/EN: `I18n` (state Compose → recompose otomatis) + `Strings` data class ID/EN; toggle bahasa di header layar siap; pref persisten di `SessionUiPrefs`. Teks UI chat, files, penyedia, model, kapabilitas, integrasi, dan sesi dua bahasa.
- Integrasi server (`/api/integration`): daftar integrasi + metode (oauth/key/env); hubungkan via API key atau OAuth Zen end-to-end (buka tautan, poll status utk mode auto, tempel kode utk mode code, selesaikan/batalkan). Kredensial tersimpan di server; Stop → Start agar berlaku. Misi: `IntegrationsSheet` dari layar Penyedia.
- Arena AI (WebView): item navbar ke-5 membuka `ArenaScreenActivity` — WebView penuh ke <https://arena.ai/> (JS + DOM storage, cookie pihak ketiga, user-agent mobile) dengan persistensi login via `CookieManager`. Unduhan dari halaman ditangkap `DownloadListener` → `DownloadManager` ke folder `Download` publik. `ArenaImport` mengimpor berkas dari `Download` ke workspace proot, dipicu tombol **Impor dari Downloads** di layar Files. String i18n ID/EN (`arenaTitle/Subtitle/Open/ImportDownloads/Imported`).
- Distro & runtime (layar **Pengaturan → Distro**): katalog distro rootfs — Alpine 3.20 (LTS) / 3.21 / 3.22 / edge, Ubuntu 24.04 LTS / 22.04 LTS (image `ubuntu-base` resmi Canonical) & 25.04, Debian 13 (trixie) / 12 (bookworm), Fedora, Arch Linux, openSUSE, Void, Rocky, AlmaLinux, Manjaro, Deepin, plus "rootfs sendiri" (URL `.tar.gz`/`.tar.xz`/`.zip` atau berkas SAF). Sumber rootfs: CDN Alpine, cdimage Canonical, release `termux/proot-distro` (rootfs multi-distro siap pakai), dan bootstrap resmi `termux/termux-packages`. Tiap distro punya folder sendiri di `files/distro/<id>/` sehingga bisa berganti tanpa unduh ulang; fallback otomatis mencari versi minirootfs terbaru kalau URL yang di-pin sudah dihapus CDN. Format arsip ditangani per sumber: tar datar (Alpine, ubuntu-base), tar ber-folder-teratas yang dilepas (proot-distro), dan zip + manifest `SYMLINKS.txt` (bootstrap Termux). Runtime alternatif **Termux**: `opencode serve` dijalankan di dalam app Termux (butuh `opencode` terpasang di Termux + allowlist script), sehingga tool bash opencode ikut jalan di lingkungan Termux dan rootfs proot tidak dipakai sama sekali. Selain itu tersedia **Termux built-in**: userland Termux asli (bash/pkg/apt) di dalam proot, tanpa perlu memasang app Termux — prefix-nya ditaruh di `/data/data/com.termux/files/usr` sesuai RUNPATH binary Termux, dengan bind `/system` + `/apex` + `/vendor` untuk bionic Android. `ExecRunner` (backend `run_command` internal) juga punya jalur Termux.

Belum:
- Config editor (bilah berkas opencode.json) — tanpa endpoint tulis fs di v1.19.
- MCP / share / fork / summarize: server v1.19 tidak menyediakan endpoint-nya (bukan keterbatasan app).

## 7. Pertanyaan Terbuka (tidak menghalangi F0)

1. Cara install di app: unduh binary release langsung (disarankan, reproducible) vs jalankan install script.
2. Port default 4096 vs acak per install (hindari bentrok).
3. Kebijakan update binary: manual per tombol vs cek otomatis (default: manual).
