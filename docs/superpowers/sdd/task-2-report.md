# Task 2 Report: ContainerRootfs helpers

## Status

**DONE_WITH_CONCERNS** — implementasi + tes selesai; satu-satunya concern adalah unit test belum dijalankan (sesuai kebijakan user: build/test di GitHub Actions CI).

## Files Changed

- `android/app/src/main/java/com/arkacode/app/core/ContainerRootfs.kt` (new, 199 lines)
- `android/app/src/test/java/com/arkacode/app/core/ContainerRootfsTest.kt` (new, 218 lines)

## Commits

| SHA | Subject |
| --- | --- |
| `5a4f3ac` | `feat(core): container rootfs post-install helpers + musl path append` |
| (berikutnya) | `docs(sdd): task 2 report` |

## Implementation Summary

### ContainerRootfs.kt (object, pure Kotlin/JVM — tanpa import Android)

Konstanta publik (dipakai tes & dipakai ulang oleh caller):

- `const val RESOLV_CONF = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"`
- `const val HOSTS = "127.0.0.1 localhost\n::1 localhost\n"`

Nilai ini **persis** sama dengan `DistroManager.postInstall()` (DistroManager.kt baris 366-367) — diverifikasi dengan membaca sumbernya, bukan dari ingatan.

Fungsi:

1. `fun appendMuslPath(existingPath: String, guestDir: String): String`
   - `guestDir` di-`trim()`; bila kosong → kembalikan `existingPath` (tidak ada path kosong yang disisipkan).
   - split `existingPath` dengan `':'`, tiap elemen di-`trim()`; bila `guestDir` ada di daftar → kembalikan `existingPath` **apa adanya** (idempoten, spasi asli tidak dirusak).
   - `existingPath` kosong → `guestDir`; selain itu → `"$existingPath:$guestDir"`.

2. `fun ensureContainerLinks(containerRootfsDir: File)` — idempoten:
   - `mkdirs` `<rootfs>/root/workspace` dan `<rootfs>/tmp`.
   - `mkdirs` `<rootfs>/etc` lalu tulis `resolv.conf` + `hosts` dengan konstanta di atas.
   - Bit executable untuk file **ELF** (magic `7F 45 4C 46`) atau **shebang** (`#!`) di `bin`, `usr/bin`, `usr/local/bin` — `setExecutable(true, false)`, pola dari `DistroManager.extractZip`.
   - Semua langkah dibungkus `runCatching` per-operasi (bukan satu blok besar seperti `postInstall`) supaya satu file aneh tidak membatalkan sisa post-install. Fungsi ini tidak pernah melempar exception.
   - Deteksi 4 byte awal pakai loop manual (`read`), **bukan** `InputStream.readNBytes` — `readNBytes` baru ada di API 33 sedangkan `minSdk = 26`.

3. `fun ensureShForTermuxLayout(rootfsDir: File, prefix: String): Boolean`
   - Normalisasi prefix (buang garis miring ujung & segmen `.`/kosong, `\` → `/`), mis. `data/data/com.termux/files/usr`.
   - Bila `<rootfs>/bin/sh` sudah ada (dicek **tanpa** mengikuti symlink via `Files.exists(..., NOFOLLOW_LINKS)`, supaya symlink menggantung dianggap "sudah ada") → `false`, tidak menimpa.
   - Bila `<rootfs>/<prefix>/bin/sh` bukan file → `false` (tidak membuat symlink menggantung).
   - Selain itu buat symlink **relatif** `<rootfs>/bin/sh` → `../<prefix>/bin/sh` (dihitung generic dari path absolut link & target), `true` bila berhasil, `false` bila filesystem menolak (mis. tanpa izin symlink). Idempoten.

### ContainerRootfsTest.kt (JUnit4, 12 test)

Tiga tes wajib dari brief/plan, nama & assertion identik dengan plan:

1. `append musl path saat kosong`
2. `append musl path menambahkan setelah path lama`
3. `append musl path idempoten`

Tes tambahan (semua pure JVM, `TemporaryFolder` — tidak butuh Robolectric):

4. `append musl path membandingkan elemen ter-trim` — elemen `" /b "` = `"/b"`; guestDir berspasi dikenali.
5. `ensure container links membuat workspace tmp resolv hosts` — isi `resolv.conf`/`hosts` dicek byte-per-byte.
6. `ensure container links menandai ELF dan shebang executable` — ELF di `bin` & `usr/bin`, shebang di `usr/local/bin`.
7. `ensure container links idempoten` — dua panggilan, konten tetap benar.
8. `ensure container links aman untuk rootfs kosong` — tidak ada `bin/`/`etc/` → tanpa exception.
9. `ensure sh membuat symlink bin sh relatif untuk layout termux` — symlink ada, target = `../data/data/com.termux/files/usr/bin/sh`, resolve OK.
10. `ensure sh prefix dengan garis miring ujung dinormalkan`.
11. `ensure sh idempoten dan tidak menimpa bin sh yang sudah ada`.
12. `ensure sh tanpa prefix tidak melakukan apa pun` + `ensure sh tidak dibuat kalau sh prefix tidak ada` (2 tes).

## Tests: Written but NOT executed locally

Sesuai instruksi user, **tidak ada** `./gradlew` / gradle test yang dijalankan di mesin ini. Tes ditulis dan siap diverifikasi CI lewat `cd android && ./gradlew testDebugUnitTest`.

Verifikasi yang dilakukan secara lokal (tanpa build):

- Self-review diff baris per baris untuk compile-level correctness (import, tipe, overload JUnit, API level).
- Logika `appendMuslPath` dan `relativePath` disimulasikan di Python dengan input yang sama seperti tes → keluaran persis sama dengan nilai yang di-assert (`/opt/containers/python/rootfs/usr/lib`, `/opt/arkacode/lib:/opt/containers/node/rootfs/usr/lib`, `../data/data/com.termux/files/usr/bin/sh`).
- Cek `build.gradle.kts`: `testImplementation("junit:junit:4.13.2")` sudah ada (`TemporaryFolder` termasuk JUnit4) → **tidak ada dependency baru**. Tidak ada ktlint/detekt yang bisa menolak format.

## Self-Review Notes

- Semua fungsi yang diminta brief ada dengan signature yang diminta (`ensureContainerLinks(File)` satu argumen — cocok dengan pemanggilan di `task-4-brief.md` baris 39).
- `relativePath` memakai `File.separatorChar` hanya untuk split, output selalu `/` — benar untuk symlink POSIX.
- `listFiles()` null-safe; symlink di folder bin (mis. Alpine `bin/busybox`) aman: `isFile` mengikuti target, `setExecutable` mengenai target, file tanpa target dilewati.
- KDoc memakai `[DistroManager]`/`[ContainerSpec]` (ada di package yang sama) — tidak ada referensi yang dipakai compiler.

## Concerns

1. **Unit test belum dijalankan** (karena kebijakan build offloaded ke CI). Risiko residual: assert `canExecute()` bisa lolos trivial bila CI jalan sebagai root (bit x selalu terlihat), tapi itu membuat tes lebih longgar — bukan gagal.
2. **Beda dengan plan (bukan brief):** `docs/superpowers/plans/2026-11-17-tool-containers.md` baris 125 menuliskan `ensureContainerLinks(containerRootfsDir: File, spec: ContainerSpec)` (dua argumen). Brief task-2 dan brief task-4 memakai satu argumen, jadi saya ikut brief. `spec` memang tidak diperlukan: rootfs container selalu layout Alpine biasa, jadi tidak ada `$PREFIX/etc` yang perlu ditulis ganda seperti `postInstall` distro. Kalau nanti butuh, overload dua argumen bisa ditambah tanpa mengubah caller.
3. **`ensureShForTermuxLayout` belum dipanggil dari mana pun** — Task 2 hanya menyediakan helper (sesuai brief). Handoff: Task 4 (`ContainerManager.install`) atau Task 5 perlu memanggilnya saat distro aktif berlayout `TERMUX_PREFIX` (`DistroSpec.extractDir.isNotEmpty()`), dengan `prefix = distro.spec.guestPrefix.trim('/')`, kalau device Termux ingin bisa memakai shim `#!/bin/sh`. Tanpa pemanggilan itu, fungsi ini mati (tidak ada efek samping negatif).
4. **Fallback saat symlink ditolak** (mis. filesystem tanpa dukungan symlink): fungsi mengembalikan `false` tanpa menyalin file. Menyalin `sh` saja biasanya tidak menolong (target `dash`/`busybox` juga harus ikut), jadi saya sengaja tidak menambah fallback copy — kalau nanti terbukti perlu, itu perubahan sadar di `ContainerManager`.

## Handoff / Catatan untuk Task 5

`appendMuslPath` siap dipakai di `DistroManager.ensureMuslRuntime()`:

```kotlin
val old = runCatching { File(etcDir, "ld-musl-$arch.path").readText().trim() }.getOrDefault("")
val merged = containers.joinToString(":") { ... }
    .let { ContainerRootfs.appendMuslPath(old, it) }   // atau loop per container
```

Sifatnya idempoten, jadi aman dipanggil tiap engine start (file ditulis ulang dengan nilai gabungan).