# Design — Tab Build APK

Tanggal: 2026-10-22 · Status: menunggu persetujuan

## Ringkas

Overlay baru **"Build"** (mengikuti pola `showSettings`): pilih folder proyek dari
workspace → deteksi toolchain → tombol **"Siapkan toolchain"** kalau belum ada
(opsi A, user memilih ini) → jalankan `./gradlew assembleDebug` dengan log
streaming ke layar **dan** ke file di workspace (biar model AI di sesi bisa
baca) → setelah sukses, pasang APK lewat **ADB wireless** (pair kode + install).

## Cakupan

- Folder proyek = subfolder `/root/workspace` yang mengandung `gradlew` atau
  `settings.gradle*`. Import dari storage HP (SAF): **tidak**, menyusul.
- Build hanya `assembleDebug`, satu proyek berjalan pada satu waktu.
- Termux mode: tidak didukung — probe gagal, alasan tampil di log.
- ADB: pairing butuh kode 6 digit dari layar HP (diinput di tab, sekali per
  perangkat); kode tidak disimpan, port pair/connect disimpan.

## Komponen

1. **EngineScreen.kt** — flag `showBuild` + case di `BackHandler` +
   `onOpenBuild` + `ClayNavItem` (ikon Build) di nav row sebelah `navSettings`.
2. **BuildScreen.kt** (overlay baru, gaya `SettingsScreen`):
   - Seksi **Proyek**: daftar folder (scan sisi-app workspace), tombol muat ulang.
   - Seksi **Toolchain**: probe satu command (java? sdk? aapt2-override?) →
     chip status; tombol `Siapkan toolchain` hanya kalau ada yang kurang
     (tombol mencantumkan estimasi "≈2 GB").
   - Seksi **Build**: tombol `Build assembleDebug` (disabled saat berjalan),
     tombol Batal (destroy proses — `execute()` sudah mendukung).
   - **Log pane**: tail incremental dari file (baca offset tiap ~500 ms) —
     dipakai log setup dan build.
   - Seksi **Hasil**: glob `**/build/outputs/apk/debug/*.apk` setelah exit 0.
     - **Keluaran otomatis ke folder SAF**: sekali pilih tujuan
       (`OpenDocumentTree` + `takePersistableUriPermission`, tree Uri disimpan
       bersama preferensi ADB) → setiap build sukses APK **dicopy** otomatis ke
       sana via `DocumentsContract.createDocument` + `openOutputStream`
       (nama unik `<proyek>-debug-<timestamp>.apk`, tanpa logika bentrok).
       Aslinya tetap di `build/outputs` (dipakai gradle + tombol Pasang ADB).
       Kalau belum pilih tujuan → tombol "Pilih folder tujuan" di seksi ini;
       kalau grant gagal/revoked → pesan di log pane + tombol pilih ulang.
       Salin di `Dispatchers.IO`, baris mulai/selesai masuk log pane.
   - Seksi **ADB**: input IP (auto-probe `ip -4 addr show wlan0`, bisa diedit),
     port pair, kode pairing, port connect; tombol `Pasang`. **Peringatan di
     tombol**: memasang APK atas app yang sedang berjalan = proses app dibunuh
     Android → sesi AI ikut terputus (terbukti saat spike).
3. **ToolchainSetup.kt** — skrip bash embedded sebagai string Kotlin → ditulis
   ke `/root/workspace/.arka/setup-toolchain.sh` (tulis langsung dari sisi app)
   → `ExecRunner.run("bash ...", timeout 30 m)` dengan log ke file.
   Isi skrip = spike yang sudah terbukti, idempoten (cek dulu tiap bagian):
   - apt: `openjdk-17-jdk-headless iproute2 qemu-user-static
     libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross unzip curl`
   - cmdline-tools → `/opt/android-sdk`; `yes | sdkmanager --licenses`;
     `platforms;android-35` + `build-tools;35.0.0`
   - unduh `aapt2-8.9.3-12782657-linux.jar` langsung dari dl.google (versi
     disamakan dengan `gradle/libs.versions.toml`), ekstrak, wrapper qemu
     (`qemu-x86_64-static -L /usr/x86_64-linux-gnu`), append
     `android.aapt2FromMavenOverride=...` ke `~/.gradle/gradle.properties`
   - adb: **platform-tools resmi Google** (Debian `adb` tidak punya perintah
     `pair`) → `/opt/android-sdk/platform-tools` + wrapper qemu + symlink
     `/usr/local/bin/adb`
4. **BuildRunner** — satu command lewat `ExecRunner.run`:
   ```
   cd '/root/workspace/<p>' && chmod +x gradlew ; \
   ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug --console=plain \
     > '/root/workspace/build-logs/<p>-<ts>.log' 2>&1
   ```
   `ExecSettings(timeoutMs = 30 m)`, `sessionId = null`. stdout sengaja ke file
   (capture ExecRunner dibatasi 200KB), exit code tetap dari `ExecResult`.
   Log di **`/root/workspace/build-logs/`** — path tetap, mudah ditemukan AI.
5. **ADB ops**: `adb pair <ip:port> <kode>` → `adb connect <ip:port>` →
   `adb install -r <apk>`. Output tiap langkah masuk log pane yang sama.
6. **I18n** — semua label baru di properti + blok `id` + blok `en`
   (kompilasi memaksa lengkap).

## Alur utama

Buka tab → scan proyek → pilih → probe toolchain → (`Siapkan toolchain` bila
perlu) → Build → log live → exit 0 → daftar APK muncul → **salin otomatis ke
folder SAF** (sekali pilih tujuan) → isi ADB (pair sekali) → Pasang → output
adb tampil di log pane.

## Risiko & validasi

- **R1**: `adb pair` ke HP itu-sendiri **belum teruji** (butuh kode on-screen
  sekali saat test). Fallback: konek dari PC/Tailscale. → diuji di langkah
  terakhir implementasi, minta kode sekali ke user.
- **R2**: build pertama > 12 menit di HP → timeout 30 menit + tombol Batal.
- **R3**: setup ≈ 2 GB kuota → hanya jalan saat tombol ditekan (sesuai opsi A).
- **R4**: APK lokal ditandatangani `arkacode.keystore` milik repo (sama dengan
  CI) → `install -r` aman, data app tidak hilang.

## Non-goal

SAF import, build release/variabel pilihan, riwayat & antrian multi-build,
dukungan Termux mode, auto-pair tanpa kode, integrasi git.
