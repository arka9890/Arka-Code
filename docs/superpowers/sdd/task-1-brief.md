# Task 1 Brief: ContainerSpec + ContainerCatalog

## Files
- Create: `android/app/src/main/java/com/arkacode/app/core/ContainerSpec.kt`
- Test: `android/app/src/test/java/com/arkacode/app/core/ContainerSpecTest.kt`

## Context

Project Arka-Code (Android app, Kotlin, proot-based Linux distro runtime) sedang menambah fitur "tool container": mini-rootfs Alpine 3.20 persistent di `<filesDir>/containers/<id>/rootfs`, di-bind ke `/opt/containers` di semua distro. Task ini adalah fondasi: definisi katalog container + peta ABI/arch/loader.

Kode yang sudah ada yang jadi pola:
- `android/app/src/main/java/com/arkacode/app/core/DistroSpec.kt` — `DistroCatalog.ALPINE_320.rootfsUrls(abi)` membangun URL minirootfs Alpine dengan `DistroCatalog.CDN` + `DistroCatalog.MIRRORS`; pola `distroCatalog.archFor(abi)`.
- Unit test pola: `android/app/src/test/java/com/arkacode/app/core/DistroSpecTest.kt` (JUnit4, fungsi murni, tanpa Android).

## What to Build

`data class ContainerSpec(id, label, summary, approxSizeMb, releaseMarkers, setupScript, shimBinaries, rootfsUrls)` + `object ContainerCatalog` berisi:
- `PYTHON` (id="python", shimBinaries=["python3","pip3"], setupScript apk add python3 py3-pip ca-certificates)
- `NODE` (id="node", shimBinaries=["node","npm","npx"], setupScript apk add nodejs npm ca-certificates)
- `ALL`, `byId(id)`, `archFor(abi)`, `loaderName(abi)`
- `archFor`: arm64-v8a→aarch64, armeabi-v7a→armv7, x86_64→x86_64, x86→x86, else null
- `loaderName`: "ld-musl-$arch.so.1" dengan armv7→armhf, x86→i386
- rootfsUrls: URL Alpine minirootfs 3.20.3 per arch + mirror (pola DistroCatalog.ALPINE_320, tapi hardcode v3.20.3)

Tes lengkap ada di plan (lihat task-brief dari plan file — tapi plan tidak dipakai, ini brief mandiri): buat `ContainerSpecTest` dengan 7 test: arch mapping, loader name, URL rootfs, mirror+https, setupScript python, setupScript node, byId aman.

## Constraints

- Kotlin, JUnit4, fungsi murni (tanpa Android Context) agar bisa diuji di JVM.
- URL harus https dan punya >1 kandidat (CDN + mirror).
- String setupScript adalah raw string Kotlin yang isinya script sh valid (`apk add --no-cache ...`).
- Ikuti gaya kode DistroSpec.kt (naming, komentar KDoc ringkas di objek).

## Report

Tulis laporan ke `docs/superpowers/sdd/task-1-report.md` (buat folder). Status akhir di reply: DONE / DONE_WITH_CONCERNS / BLOCKED.