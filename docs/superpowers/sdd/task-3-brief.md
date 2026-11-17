# Task 3 Brief: ContainerShim generator

## Files
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerShim.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerShimTest.kt`

## Context

Arka-Code (Android Kotlin) "tool container": tool Alpine (musl) dijalankan dari distro aktif (bisa glibc). Shim adalah skrip `#!/bin/sh` di `containers/bin/` yang dieksekusi lewat PATH guest; shim mengeksekusi binary tool LANGSUNG (PT_INTERP musl resolve ke loader yang sudah dipasang MuslRuntime di distro mana pun — jalur yang sama dengan binary opencode hari ini).

Referensi: `android/app/src/main/java/com/arkacode/app/core/MuslRuntime.kt` (mekanisme loader), `DistroManager.kt` (layout guest: bind containers → `/opt/containers`).

## What to Build

`ContainerShim` (object, fungsi murni):

1. `fun script(spec: ContainerSpec, binary: String, abi: String): String` — isi skrip persis:

```
#!/bin/sh
export HOME=/opt/containers/<id>/home
export SSL_CERT_FILE=/opt/containers/<id>/rootfs/etc/ssl/certs/ca-certificates.crt
exec /opt/containers/<id>/rootfs/usr/bin/<binary> "$@"
```

Dengan `<id>` = `spec.id`, `<binary>` dipetakan ke path sebenarnya: `python3`→`usr/bin/python3`, `pip3`→`usr/bin/pip3`, `node`→`usr/bin/node`, `npm`→`usr/bin/npm`, `npx`→`usr/bin/npx`. (Semua di `usr/bin` — bootstrap Alpine 3.20 merged-usr.)

2. `fun shimPath(id: String, binary: String): String` = `"containers/bin/$id-$binary"`.

## Tests (ContainerShimTest)

Tiga tes (lihat plan Task 3): shim python3 benar (4 baris persis), shim pip3 path rootfs sama, shimPath tidak bentrok antar container.

## Constraints

- Kotlin, JUnit4, fungsi murni.
- Output `script()` harus persis 3 baris + shebang, newline akhir.

## Build/Test Rules (PENTING — override instruksi umum)

- **JANGAN menjalankan gradle build/test apa pun di mesin lokal** (tidak ada `./gradlew`, tidak ada `gradle test`). User mem-build APK langsung di GitHub Actions.
- Verifikasi: cukup pastikan kode kompilasi secara logis (self-review diff) + tulis tes yang benar. Laporkan di report bahwa tes ditulis tapi BELUM dijalankan (reason: build offloaded ke CI).
- Jangan install dependency baru.

## Report

Tulis laporan ke `docs/superpowers/sdd/task-3-report.md`.