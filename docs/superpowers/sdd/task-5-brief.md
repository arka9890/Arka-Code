# Task 5 Brief: Bind + PATH containers di tiga argv builder

## Files
- Modify: `android/app/src/main/java/com/arkacode/app/core/DistroManager.kt`
- Modify: `android/app/src/main/java/com/arkacode/app/core/EngineManager.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerExposureTest.kt`

## Context

Arka-Code (Android Kotlin). Semua eksekusi proot dibangun di tiga tempat; container harus di-bind ke `/opt/containers` dan `bin`-nya di depan PATH di ketiganya.

Tempat yang diubah:
1. `DistroManager.buildProotCommand()` — argv untuk bash command AI.
2. `DistroManager.ensureMuslRuntime()` — append path container ke `<rootfs>/etc/ld-musl-<arch>.path` (pola: file itu sekarang ditulis `MuslRuntime.GUEST_LIB_DIR`; ubah jadi `ContainerRootfs.appendMuslPath(old, guestDir)` per container terpasang).
3. `EngineManager.baseArgv()` — one-shot.
4. `EngineManager.guestEnvTail()` — PATH hardcoded.
5. `EngineManager.serveArgv()` — server.

## What to Build

1. `DistroManager` companion: `fun containerBindArgs(containersDir: String): List<String>` = `listOf("-b", "$containersDir:/opt/containers")`.
2. `buildProotCommand`: setelah blok bind workspace, sisip `argv += containerBindArgs(File(context.filesDir, "containers").absolutePath)`. Ganti `"PATH=${guestPathValue()}"` → `"PATH=/opt/containers/bin:${guestPathValue()}"`.
3. `ensureMuslRuntime`: setelah `File(etcDir, "ld-musl-$arch.path").writeText(MuslRuntime.GUEST_LIB_DIR)`, ganti jadi append: baca isi lama (jika ada), untuk tiap `ContainerCatalog.ALL` yang rootfs-nya ada → `appendMuslPath(isi, "${ContainerManager.guestPath(id)}/rootfs/usr/lib")` — dan juga `/rootfs/lib`? Tidak: cukup `usr/lib` (tempat lib python/node) + `lib` (libc musl sudah lewat PT_INTERP, tidak perlu di path). Tulis hasil akhir. Idempoten.
4. `EngineManager.baseArgv`: sisip bind containers setelah bind engineDir (pola sama).
5. `EngineManager.guestEnvTail`: PATH → `/opt/containers/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`.
6. `EngineManager.serveArgv`: bind containers + PATH → `/opt/containers/bin:${distro.guestPathValue()}`.

## Tests (ContainerExposureTest)

1. `containerBindArgs` mengembalikan `["-b", "<dir>:/opt/containers"]`.
2. `guestPathValueWithContainers()` — tambahkan fungsi murni `fun DistroManager.guestPathValueWithContainers(): String = "/opt/containers/bin:" + guestPathValue()` dan uji prefix + contains.
3. (Bila mudah) uji `appendMuslPath` dengan path container — sudah dicakup Task 2; cukup referensi.

## Constraints

- Jangan ubah perilaku lain: semua argumen proot yang ada (`--kill-on-exit`, `--link2symlink`, `-0`, bind dev/proc/sys, loader env) tetap.
- `guestEnvTail()` di EngineManager adalah env untuk one-shot; `serveArgv` punya PATH sendiri — dua-duanya diubah.

## Build/Test Rules (PENTING — override instruksi umum)

- **JANGAN menjalankan gradle build/test apa pun di mesin lokal** (tidak ada `./gradlew`, tidak ada `gradle test`). User mem-build APK langsung di GitHub Actions.
- Verifikasi: cukup pastikan kode kompilasi secara logis (self-review diff) + tulis tes yang benar. Laporkan di report bahwa tes ditulis tapi BELUM dijalankan (reason: build offloaded ke CI).
- Jangan install dependency baru.

## Report

Tulis laporan ke `docs/superpowers/sdd/task-5-report.md`.