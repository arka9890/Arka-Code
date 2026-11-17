# Task 2 Brief: ContainerRootfs helpers

## Files
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerRootfs.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerRootfsTest.kt`

## Context

Arka-Code (Android Kotlin) menambah "tool container": mini-rootfs Alpine di `<filesDir>/containers/<id>/rootfs`. Task ini membuat helper murni untuk post-install rootfs container dan penggabungan path musl.

Pola yang diikuti:
- `android/app/src/main/java/com/arkacode/app/core/DistroManager.kt` → `postInstall()` (mkdir workspace/tmp, tulis resolv.conf/hosts, chmod ELF/shebang) dan `extractZip` (deteksi ELF/shebang + setExecutable).
- `android/app/src/main/java/com/arkacode/app/core/MuslRuntime.kt` → `GUEST_LIB_DIR = "/opt/arkacode/lib"`.

## What to Build

`ContainerRootfs` (object, fungsi murni / util):

1. `fun appendMuslPath(existingPath: String, guestDir: String): String`
   - Jika `guestDir` sudah ada sebagai elemen (split ':' trim) dari `existingPath` → kembalikan `existingPath` apa adanya (idempoten).
   - Jika `existingPath` kosong → `guestDir`.
   - Selain itu → `"$existingPath:$guestDir"`.

2. `fun ensureContainerLinks(containerRootfsDir: File)` — meniru `postInstall` DistroManager untuk container:
   - mkdir `<rootfs>/root/workspace` dan `<rootfs>/tmp`
   - tulis `<rootfs>/etc/resolv.conf` = `"nameserver 1.1.1.1\nnameserver 8.8.8.8\n"` dan `<rootfs>/etc/hosts` = `"127.0.0.1 localhost\n::1 localhost\n"` (pola sama DistroManager.postInstall)
   - chmod +x untuk semua file di `<rootfs>/bin`, `<rootfs>/usr/bin`, `<rootfs>/usr/local/bin` yang ELF (magic `0x7F 45 4C 46`) atau shebang (`#!`) — pola dari `extractZip`.

3. `fun ensureShForTermuxLayout(rootfsDir: File, prefix: String)` — bila layout Termux (`prefix` non-empty, mis. `data/data/com.termux/files/usr`) dan `<rootfs>/bin/sh` tidak ada sementara `<rootfs>/<prefix>/bin/sh` ada → buat symlink `<rootfs>/bin/sh` → `<prefix>/bin/sh` (path relatif dari `/bin`). Idempoten.

## Tests (ContainerRootfsTest)

Tiga tes untuk `appendMuslPath` (kosong, append, idempoten) — lihat plan. `ensureContainerLinks`/`ensureShForTermuxLayout` diuji via tmpdir (JUnit `TemporaryFolder` atau `File.createTempFile`) bila praktis; minimal appendMuslPath wajib.

## Constraints

- Kotlin, JUnit4, tanpa Android.
- Nilai resolv/hosts PERSIS sama dengan DistroManager.postInstall (sumber kebenaran: DistroManager.kt baris 366-367).

## Build/Test Rules (PENTING — override instruksi umum)

- **JANGAN menjalankan gradle build/test apa pun di mesin lokal** (tidak ada `./gradlew`, tidak ada `gradle test`). User mem-build APK langsung di GitHub Actions.
- Verifikasi: cukup pastikan kode kompilasi secara logis (self-review diff) + tulis tes yang benar. Laporkan di report bahwa tes ditulis tapi BELUM dijalankan (reason: build offloaded ke CI).
- Jangan install dependency baru.

## Report

Tulis laporan ke `docs/superpowers/sdd/task-2-report.md`.