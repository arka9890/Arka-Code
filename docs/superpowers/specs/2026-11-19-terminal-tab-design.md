# Tab Terminal — Design

Tanggal: 2026-11-19
Status: menunggu persetujuan

## Kenapa

Sekarang satu-satunya jalan menjalankan perintah di distro adalah `ExecRunner`
(satu perintah, pipa, tanpa TTY) lewat `BuildScreen` atau tool milik agent.
User tidak bisa membuka shell sendiri: tidak ada `cd`, tidak ada `apk add`
interaktif, tidak ada `vim`/`htop`.

Tujuan: tab Terminal di dalam app, tersambung ke shell distro **aktif**,
dengan TTY sungguhan, sehingga aktivitas interaktif dan instalasi paket
berjalan seperti di terminal biasa.

## Tujuan

- Sesi shell interaktif di dalam proot distro aktif (`engine.distroSpec`).
- TTY asli: `isatty` true, warna, progress bar, prompt `(y/N)`, `Ctrl-C`,
  job control (`Ctrl-Z`, `fg`, `bg`).
- Aplikasi layar penuh berfungsi dan ikut ukuran layar: `vim`, `top`,
  `htop`, `nano`, `less`.
- Ganti distro → sesi lama ditutup, sesi baru memakai distro baru.
- Terminal tetap hidup saat user pindah ke layar lain, dan kembali utuh.
- Dibuka dari layar Ready **dan** dari layar gagal Boot, jadi tetap bisa dipakai
  saat server belum atau tidak jalan.

## Bukan tujuan

- Runtime Termux. `TermuxBridge` bekerja lewat Intent RUN_COMMAND dan tidak
  mengalirkan pty balik ke app — terminal hanya untuk `RuntimeMode.PROOT`.
- Banyak sesi/tab terminal, split pane, SSH, tmux.
- Mengirim output terminal ke chat atau ke agent.
- Emulator terminal buatan sendiri.
- Foreground service. Sesi hidup selama proses app hidup; kalau Android
  membunuh proses, shell ikut mati.

## Anti-tujuan

- Menggantungkan fitur ini pada server engine hidup. Terminal harus jalan
  walau `opencode serve` belum/tidak berjalan; yang wajib hanya rootfs +
  binary proot terpasang.
- Menyalin kode GPLv3 (modul Termux) ke repo tanpa LICENSE — lihat
  "Pendekatan".

## Kendala

- Android tidak menyediakan PTY di Java/Kotlin. Butuh native code (NDK).
- Repo belum punya NDK/native build; CI `ubuntu-latest` menyediakan NDK, tapi
  durasi build bertambah dan lisensi SDK harus sudah diterima di runner.
- `proot` harus dijalankan dengan argv yang sama pola-nya dengan
  `DistroManager.buildProotCommand` / `EngineManager.serveArgv` — tiga builder
  sudah ada, ini yang keempat.
- `jniLibs` memakai `useLegacyPackaging = true` dan binary proot diekstrak ke
  `nativeLibraryDir`; native lib baru ikut aturan yang sama.
- 4 ABI yang didukung proot: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.

## Pendekatan

**A. Vendor modul Termux** (`terminal-emulator` + `terminal-view` + `termux.c`).
Paling halus, semua perilaku sudah teruji. Ditolak: ~4 ribu baris disalin,
Termux berlisensi GPLv3 sementara repo ini tidak punya LICENSE — menyalinnya
mengunci seluruh app ke GPLv3.

**B. xterm.js di WebView + pty JNI kecil.** xterm.js (MIT) mengurus parsing
ANSI, layar alternatif, scrollback, seleksi, dan resize; kode native hanya
pty + exec + `TIOCSWINSZ`. Dipilih: fitur penuh, lisensi aman, kode native
paling sedikit.

**C. `script` + renderer ANSI sendiri.** Tanpa NDK, tapi ukuran tetap dan
renderer harus ditulis manual. Ditolak: hasil lebih buruk dari B dengan usaha
serupa.

## Rancangan

### Native (`app/src/main/cpp/`)

`terminal_pty.c` + `CMakeLists.txt`, dimuat lewat `externalNativeBuild.cmake`.

- `nativeSpawn(argv, envp, cwd, rows, cols): Int` — `posix_openpt` →
  `grantpt` → `unlockpt` → `fork` → `setsid` + `TIOCSCTTY` di anak →
  `dup2` slave ke 0/1/2 → `chdir` → `execve`. Induk mengembalikan fd master;
  pid dikembalikan lewat method terpisah `nativePid()` atau dibungkus struct —
  implementasi memakai `int[2]` hasil (fd, pid) agar satu panggilan.
- `nativeResize(fd, rows, cols)` — `ioctl(TIOCSWINSZ)`.
- `nativeWait(pid)` — `waitpid`; dipanggil di `Dispatchers.IO` supaya anak
  tidak jadi zombie.
- `nativeSignal(pid, sig)` — untuk menutup sesi (`SIGHUP`, lalu `SIGKILL`).

Binding Kotlin: `core/TerminalPty.kt`, `System.loadLibrary("terminalpty")`.

### Sesi (`core/TerminalSession.kt`)

- `start(spec, workspaceDir, containersDir, rows, cols)` membangun argv lewat
  `TerminalCommand.build(...)` dan memanggil `TerminalPty`.
- Output dibaca `FileInputStream(fd)` di thread IO, di-batch ~16 ms, lalu
  dikirim ke UI (satu callback `onOutput(String)`); UTF-8 dengan penggantian
  karakter rusak agar escape sequence tidak patah.
- `write(text)`, `resize(rows, cols)`, `close()` (SIGHUP → tunggu → SIGKILL).
- `state`: `Starting`, `Running`, `Exited(code)`.

### Backpressure

`evaluateJavascript` tidak punya batas antrean. Tanpa kendali, `yes` atau
`cat /dev/urandom` menumpuk pesan di WebView sampai OOM/ANR.

- Kirim output sebagai base64 → `termWrite(b64)`; JS memanggil
  `term.write(bytes, () => Bridge.ack(b64.length))` setelah render selesai.
- Sisi Kotlin menyimpan semaphore berisi jatah 8 MB byte yang belum di-ack.
  Habis jatah → thread pembaca pty berhenti membaca (backpressure ke proot),
  bukan mengantre di memori.
- Sesi yang ditutup melepas semua izin supaya pembaca tidak menggantung.

### Buffer layar

- WebView dan `Terminal` xterm dibuat per pemasangan layar, tidak dipegang
  lintas Activity — jadi tidak ada Context bocor saat rotasi.
- Sesi menyimpan ring buffer 128 KB output mentah terakhir; tiap kali WebView
  baru siap, buffer diputar ulang sebelum output baru. Ini berlaku baik untuk
  rotasi maupun untuk menutup lalu membuka lagi layar terminal. Pendekatan
  aproksimatif untuk aplikasi layar penuh, dan itu diterima.

### argv (`core/TerminalCommand.kt`, fungsi murni + unit test)

Cermin `buildProotCommand`, dengan urutan: binary proot → `--kill-on-exit` →
`--link2symlink` → `-0` (dibuang untuk layout `TERMUX_PREFIX`) → `-r rootfs` →
`-w <guestHome>` → bind `/dev`, `/proc`, `/sys`, `rootfs/tmp:/dev/shm` →
bind workspace → bind `containers → /opt/containers` →
`extraBindArgs()` → `guestEnvPath()` + `-i` + env (`HOME`, `PATH` dengan
`/opt/containers/bin`, `TERM=xterm-256color`, `LANG=C.UTF-8`, `PROOT_LOADER`,
`PROOT_TMP_DIR`, `PROOT_NO_SECCOMP=1`, env khas Termux) →
`guestShellPath() -l`.

Tidak ada `--port`, tidak ada `OPENCODE_SERVER_PASSWORD`: terminal mandiri.

### UI (`ui/TerminalScreen.kt`)

Overlay layar penuh dari `EngineScreen` (`showTerminal`, pola sama dengan
`showBuild`), dibuka dari layar Ready dan layar gagal Boot.

- `AndroidView(WebView)` memuat `file:///android_asset/terminal/index.html`.
  Aset: `xterm.js`, `xterm.css`, `index.html` (MIT, sertakan `LICENSE`).
- `index.html`: `new Terminal({ fontFamily: monospace, fontSize: 13,
  scrollback: 5000, convertEol: false })` → `term.open(...)`;
  `term.onData(d => Bridge.input(d))`;
  `term.onResize(({rows, cols}) => Bridge.resize(rows, cols))`;
  fungsi global `termWrite(s)` untuk output; `fit()` saat orientasi berubah.
- Jembatan: `TerminalBridge` dengan `addJavascriptInterface`:
  `input(base64)` (base64 agar byte aman), `resize(rows, cols)`,
  `ack(bytes)` untuk backpressure.
- Output ke JS lewat `evaluateJavascript("termWrite(...)")` berisi base64.
- Baris tombol di bawah (Compose): `Esc`, `Tab`, `Ctrl` (sticky), `Alt`
  (sticky), panah kiri/atas/bawah/kanan, `PgUp`, `PgDn`, `Ctrl-C`, tempel,
  salin.
  Sticky modifier mengubah karakter berikutnya jadi control byte.
- Salin memakai `evaluateJavascript("termGetSelection()")` lalu menaruh
  hasilnya di clipboard Android; seleksi native WebView tidak dipakai karena
  renderer canvas tidak mengeksposnya. Tempel membaca clipboard Compose lalu
  mengirimnya lewat `session.write`.
- Status bar tipis: distro aktif, tombol `Restart`, tombol `Tutup` (mengakhiri
  sesi dan melepas kunci hapus distro), dan pesan saat `Exited` / runtime
  Termux.

### Titik masuk

Tombol Terminal ada di layar Ready dan di layar gagal Boot (`Screen.Boot`
dengan `failMsg`). Tidak ada di `PickDistro`: tanpa rootfs terminal tidak bisa
dijalankan. Keduanya memakai host yang sama, jadi sesi tidak berubah saat
dibuka dari layar berbeda.

### Siklus hidup

- Sesi dipegang satu host tingkat proses (`core/TerminalHost`), bukan
  `remember` di Activity. Menutup layar atau rotasi tidak mematikan shell.
- WebView dibuat per pemasangan layar, lalu memutar ulang ring buffer 128 KB
  terakhir, jadi isi layar kembali (aproksimatif untuk alt-screen).
- `engine.setDistro(...)` (dari `DistroScreen.selectDistro`) → sesi lama
  ditutup, sesi berikutnya memakai distro baru.
- Hapus **dan** pasang ulang distro yang sesinya hidup **ditolak**: tombol
  nonaktif + teks alasan. User melepasnya lewat tombol `Tutup` di terminal.

### i18n

Key baru ID+EN: `terminalTitle`, `terminalStarting`, `terminalExited`,
`terminalRestart`, `terminalClose`, `terminalOnlyProot`, `terminalNotReady`,
`terminalPaste`, `terminalCopy`, `terminalDistroBusy`.
Nama tombol lain berupa ikon (tanpa teks).

## Yang diserahkan ke builder

- Versi xterm.js yang dipin dan cara mengambilnya saat build (vendor ke
  `assets/` sekali, bukan unduh runtime).
- `ndkVersion` dan `cmake` yang dipakai; kalau runner CI tidak punya versi
  yang dipin, naikkan/turunkan agar build deterministik.
- Detail batching output (ukuran/interval) setelah diukur di perangkat.
- Apakah `term.onData` perlu `preventDefault` untuk IME tertentu.

## Verifikasi

Unit test (JVM, tanpa device):
- `TerminalCommand.build` menyusun argv: `-r` rootfs, env tail lengkap,
  `-0` hilang untuk layout Termux, shell diakhiri `-l`.
- Ring buffer dan buffer output: batas ukuran, urutan byte utuh.

Bukti di perangkat (manual, dicatat di PR):
- `apk add htop` lalu `htop` tampil penuh dan ikut lebar layar.
- `vim` masuk/keluar tanpa sisa layar.
- `sleep 100` lalu `Ctrl-C` menghentikan.
- `yes` 3 detik lalu `Ctrl-C` → UI tetap responsif (backpressure).
- Salin teks dari terminal → tempel di chat; tempel ke terminal.
- Terminal dibuka saat server **mati** (layar gagal Boot) → shell tetap jalan.
- Rotasi layar → isi terminal diputar ulang, `top` menyesuaikan.
- Ganti distro → sesi baru memakai distro baru (`cat /etc/os-release`).
- Coba hapus/pasang ulang distro saat sesi hidup → ditolak dengan alasan;
  setelah `Tutup`, tombol aktif lagi.
- Runtime Termux → layar menjelaskan terminal tidak tersedia.

CI: `assembleDebug` + unit test hijau, termasuk kompilasi native untuk 4 ABI.
