# Arkacode

![Build Android APK](https://github.com/Dien45/Arkacode/actions/workflows/build-android-apk.yml/badge.svg)

**Frontend Android untuk [opencode](https://opencode.ai).** UI Kotlin + Jetpack
Compose murni yang mengendalikan server headless `opencode serve` yang berjalan
di dalam **proot Alpine di perangkat itu sendiri**.

Nol logika agent di sisi app — semua kecerdasan (loop chat, tools, memory,
skills, agents, MCP) milik opencode A–Z. Aplikasi ini hanya frontend.

> Repo terpisah dari `Arka`. Backend khas Arka (AiClient multi-provider,
> ChatController loop, Tools.kt, MemorySystem, SkillsManager, parser tool-call)
> sengaja dibuang dan digantikan bawaan opencode.

## Logo

Mark launcher: jendela terminal dengan huruf **A** brand dan baris kode di
sampingnya, putih di atas biru brand `#5B9BE0`.

- `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- `android/app/src/main/res/values/colors.xml`

## Fitur

- **Chat & streaming** — kirim async + SSE (`session.status`, `session.text/
  reasoning.delta`, `session.tool.*`, `permission.v2.*`), abort/interrupt,
  approval (Tolak / Izinkan / Selalu), render markdown + blok kode + output
  tool, thinking collapse, picker model & agent per sesi.
- **Sessions** — list/buat/hapus/rename, status semua sesi, revert + diff
  berkas, ringkas (`compact`).
- **Files** — tree, baca, cari, preview (markdown/gambar) via API fs.
- **Providers & auth** — daftar provider + model (badge Gratis), isi/hapus API
  key per provider (terenkripsi, disuntik sebagai env saat serve nyala).
- **Provider kustom (OpenAI-compatible)** — tambah endpoint `/v1` gaya OpenAI
  (LM Studio, llama.cpp, vLLM, Ollama, OpenRouter, …). Ditulis ke `opencode.json`
  server dengan `apiKey` = `{env:ARKACODE_<ID>_API_KEY}` (tanpa secret plaintext).
- **Integrasi** — hubungkan via API key atau OAuth (buka tautan → poll/tempel
  kode → selesai), kredensial tersimpan di server.
- **Kapabilitas** — daftar agent, command, dan skill.
- **Arena AI** — WebView bawaan ke <https://arena.ai/> (login lewat browser,
  cookie & localStorage disimpan internal WebView → sesi login tetap walau app
  ditutup). Unduhan dari halaman web masuk ke folder `Download` Android, lalu
  dapat diimpor ke workspace proot via tombol **Impor dari Downloads** di layar
  Files. Tanpa API key, tanpa logika AI di sisi app.
- **Engine** — unduh binary opencode pinned (sha256), start/stop, health,
  auto-provisioning saat pertama buka, editor port & password server, log.
- **Distro & runtime** — pilih distro rootfs yang dipakai untuk eksekusi
  command. Katalog bawaan:
  - **Alpine** 3.20 (LTS) / 3.21 / 3.22 / edge
  - **Ubuntu** 24.04 LTS / 22.04 LTS (image resmi Canonical) & 25.04
  - **Debian** 13 (trixie) / 12 (bookworm)
  - **Termux (built-in)** — userland Termux asli (`bash`, `pkg`, `apt`) tanpa
    perlu memasang aplikasi Termux
  - **Lain-lain** — Fedora, Arch Linux, openSUSE, Void, Rocky, AlmaLinux,
    Manjaro, Deepin
  - **Rootfs sendiri** — URL `.tar.gz`/`.tar.xz`/`.zip` atau berkas dari
    penyimpanan (mis. basis Debian/Ubuntu lain)

  Runtime alternatif: jalankan `opencode serve` di dalam aplikasi **Termux**
  yang sudah terpasang, tanpa rootfs sama sekali. Rootfs tiap distro disimpan
  terpisah, jadi bisa berganti tanpa mengunduh ulang.
- **i18n ID/EN**, tema claymorphism terang/gelap persisten, backup rules.

## Arsitektur

```
┌─ APP (Kotlin + Compose, frontend murni) ──────────────┐
│ UI: Chat · Sessions · Files · Agents · MCP ·          │
│     Providers/Auth · Config · Approvals · Diff ·      │
│     Arena AI (WebView)                                │
│                          │ HTTP 127.0.0.1:4096        │
│ EngineManager: install/update binary, start/stop,     │
│ health, basic-auth, working dir, log                  │
└──────────────────────────┼───────────────────────────┘
                           │ satu network namespace (proot tidak isolasi net)
┌─ PROOT ALPINE ───────────┼───────────────────────────┐
│ opencode serve --port 4096 --hostname 127.0.0.1       │
│ working dir: /root/workspace/<project>                │
│ config: opencode.json + auth.json (ditulis frontend)  │
└──────────────────────────────────────────────────────┘
```

- Binary `opencode` pinned **v1.19.0** (Linux musl **arm64**) diunduh dari
  GitHub Releases + verifikasi sha256. `serve` adalah subcommand headless; TUI
  tidak pernah dibuka.
- Server default `127.0.0.1:4096`, basic auth (username `opencode`, password
  disimpan di EncryptedSharedPreferences).
- Config server: `<rootfs>/root/.config/opencode/opencode.json`; auth:
  `<rootfs>/root/.local/share/opencode/auth.json`.

## Kebutuhan

- **JDK 21** (CI memakai Temurin 21); source/target Java 17.
- **Android SDK** platform 35 (`compileSdk`/`targetSdk` 35, `minSdk` 26).
- Gradle wrapper **8.13** (sudah disertakan).
- Perangkat/emulator **arm64** Android 8.0+ (minSdk 26).

## Build & jalankan

Windows (PowerShell):

```powershell
$env:ANDROID_HOME='C:\Users\Dien\AppData\Local\Android\Sdk'
.\android\gradlew.bat -p android :app:assembleDebug --console=plain
```

Linux/macOS:

```bash
cd android && ./gradlew :app:assembleDebug
```

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Install: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`

Catatan: task `downloadProotBinaries` mengunduh binary proot (paket Termux) ke
`jniLibs`. Bila mirror tidak terjangkau, build tetap sukses tetapi fitur
distro/`run_command` tidak aktif.

### Task unduhan saat build

| Task | Isi | Lewati dengan |
|---|---|---|
| `downloadProotBinaries` | `libproot.so`, `libproot-loader.so`, `libtalloc.so`, `libandroid-shmem.so` per ABI (dari paket Termux, diverifikasi SHA256) | `-Pproot.skip=true` |
| `downloadTtsEngineApk` | installer TTS engine sherpa-onnx (~148 MB) | `-Ptts.skip=true` |

`downloadProotBinaries` **menggagalkan build** kalau tidak ada satu pun ABI yang
siap — supaya APK tanpa binary proot tidak terbit diam-diam dan `run_command`
mati total di perangkat. Untuk build offline:

```bash
cd android && ./gradlew :app:assembleDebug -Pproot.allowMissing=true
```

Versi paket proot yang di-pin harus masih ada di mirror Termux. Kalau build
gagal dengan `HTTP 404`, berarti versi tersebut sudah dihapus — naikkan
`PROOT_VERSION` di `android/buildSrc/src/main/kotlin/arka/ProotBinaries.kt`
**dan** perbarui peta `SHA256` di file yang sama (ambil dari index resmi
`dists/stable/main/binary-<arch>/Packages`).

### Memilih distro & runtime

Menu **Pengaturan → Distro** menyediakan:

- **Runtime** — `proot` (default) atau `Termux`.
  - Mode Termux menjalankan `opencode serve` di dalam aplikasi Termux, jadi
    tool bash opencode ikut berjalan di lingkungan Termux. Syaratnya Termux
    terpasang, "Allow execution of scripts" diizinkan, dan `opencode` sudah
    terpasang di Termux (`pkg install nodejs-lts && npm i -g opencode-ai`).
  - Mode proot memakai rootfs yang diunduh app sendiri.
- **Distro** — lihat daftar di atas. Rootfs kustom bisa berupa URL
  `.tar.gz`/`.tar.xz`/`.zip` (Debian/Ubuntu/Fedora dll.) maupun berkas yang
  dipilih lewat picker di aplikasi.

Sumber rootfs:

| Distro | Sumber |
|---|---|
| Alpine | `dl-cdn.alpinelinux.org` (minirootfs ±4 MB) + 4 mirror |
| Ubuntu | `cdimage.ubuntu.com` (ubuntu-base resmi Canonical) |
| Debian, Fedora, Arch, openSUSE, Void, Rocky, AlmaLinux, Manjaro, Deepin, Ubuntu 25.04 | release `termux/proot-distro` (rootfs siap pakai) |
| Termux (built-in) | bootstrap resmi `termux/termux-packages` (userland Termux asli) |

Detail teknis Termux built-in: bootstrap Termux adalah zip berisi isi `$PREFIX`
dan symlink-nya disimpan terpisah di `SYMLINKS.txt` (format `target←linkpath`).
App mengekstraknya ke `/data/data/com.termux/files/usr` di dalam guest —
persis lokasi yang dipakai RUNPATH binary Termux — lalu membuat ulang
symlink-nya. Karena binary Termux dibangun untuk bionic Android
(PT_INTERP `/system/bin/linker64`), app juga me-bind `/system`, `/apex`, dan
`/vendor` ke guest.

> Berganti distro/runtime butuh **restart server** (Pengaturan → Stop → jalankan
> lagi), karena proot dan env server sudah ditentukan saat proses start.

### Update di tempat (tanpa uninstall)

Semua APK dari CI ditandatangani dengan **keystore bersama** yang ikut di repo
(`android/arkacode.keystore` + `android/keystore.properties`). Sebelumnya build
debug CI memakai debug keystore acak yang berbeda tiap run → tanda tangan tidak
cocok → Android menolak update dan memaksa uninstall. Dengan kunci tetap,
build berikutnya meng-upgrade instalasi lama langsung:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`versionCode` naik otomatis dari nomor run CI (`GITHUB_RUN_NUMBER`), jadi sistem
mengenali tiap build sebagai versi lebih baru.

> **Sekali saja:** instalasi lama (dibuat sebelum kunci bersama ini ada) memakai
> debug keystore acak yang berbeda, jadi transisi pertama masih perlu uninstall
> dulu — `adb uninstall com.arkacode.app` — lalu pasang APK baru. Setelah itu
> semua update berikutnya lancar tanpa uninstall.

> Keystore bersama ini untuk CI/dev, **bukan** kunci rilis Play. Rilis produksi
> memakai keystore sendiri via GitHub Secrets (`.github/workflows/android-release.yml`).

## CI & alur kontribusi

- **CI**: `.github/workflows/build-android-apk.yml` menjalankan
  `:app:assembleDebug` pada PR (paths `android/**`) dan push ke `main`, lalu
  mengunggah artifact APK.
- **Release**: `.github/workflows/android-release.yml`.
- **Alur**: patch kecil → branch → PR → CI hijau → squash merge ke `main`.

## Struktur proyek

```
android/app/src/main/java/com/arkacode/app/
  ArkacodeApp.kt · MainActivity.kt
  core/  EngineManager · EngineRelease · DistroManager · DistroSpec · ExecRunner ·
         ExecSettings · SecureStore · GitCredentials · OpenCodeConfig ·
         Attachments · ArenaDownloadBridge · PromptExpander · SlashSuggest ·
         SessionRunRegistry · SessionUiPrefs · TermuxBridge · VirtualFs
  net/   OpenCodeClient · ServerModels
  ui/    ChatScreen · FilesScreen · ProvidersScreen · CapabilitiesScreen ·
         EngineScreen · DistroScreen · ArenaScreen · SettingsScreen ·
         IntegrationsSheet · ModelPickerSheet · MarkdownText · Clay · Theme ·
         I18n · SlashSuggest
```

## Status

Ringkas (detail di [`PRD.md`](PRD.md) §8):

- **Selesai**: engine + auto-provisioning, chat (SSE, abort, approval, model
  per sesi, markdown), sessions, files, providers + API key, provider kustom
  OpenAI-compatible, integrasi (OAuth/API key), kapabilitas (agent/command/
  skill), **Arena AI (WebView + impor unduhan)**, i18n ID/EN, tema clay
  terang/gelap, backup rules, CI debug + release.
- **Belum** (keterbatasan server v1.19, bukan app): editor tulis
  `opencode.json` berformulir, MCP/share/fork/summarize.

## Dokumen

- [`PRD.md`](PRD.md) — produk, arsitektur, kebutuhan fungsional, risiko.
- [`build-report.md`](build-report.md) — catatan implementasi tema clay.

## Lisensi

Belum ditentukan (TBD).
