# Task 3 Report: ContainerShim generator

## Status

**DONE_WITH_CONCERNS** — implementasi + tes selesai dan di-commit. Satu-satunya concern: unit test belum dijalankan di mesin ini (sesuai kebijakan user: build/test di GitHub Actions CI).

## Files Changed

- `android/app/src/main/java/com/arkacode/app/core/ContainerShim.kt` (new, 61 lines)
- `android/app/src/test/java/com/arkacode/app/core/ContainerShimTest.kt` (new, 62 lines)

## Commit

`5b85534` — `feat(core): container shim generator (HOME + SSL_CERT_FILE + exec binary)`

## Implementation Summary

### ContainerShim.kt — `object` fungsi murni (tanpa import Android, JUnit-free)

**`fun script(spec: ContainerSpec, binary: String, abi: String): String`**

Menghasilkan tepat 4 baris + newline akhir (tersusun dari literal, jadi hanya keempat baris itu — tidak ada indentasi tersisa atau baris kosong):

```
#!/bin/sh
export HOME=/opt/containers/<id>/home
export SSL_CERT_FILE=/opt/containers/<id>/rootfs/etc/ssl/certs/ca-certificates.crt
exec /opt/containers/<id>/rootfs/usr/bin/<binary> "$@"
```

Disusun dengan `buildString` dari literal string biasa (bukan raw string + `trimIndent`) supaya hasilnya hanya keempat baris itu — tidak ada indentasi tersisa atau baris kosong. Path binary selalu `usr/bin/<binary>` (Alpine 3.20 merged-usr) untuk semua shimBinaries katalog: `python3`, `pip3`, `node`, `npm`, `npx`.

Kenapa `exec` langsung ke binary (bukan via proot): PT_INTERP binary container = `/lib/ld-musl-<arch>.so.1` yang sudah dipasang `MuslRuntime.ensureMuslRuntime()` di rootfs distro aktif, dan bind `containers → /opt/containers` (Task 5) membuat rootfs container terlihat di path yang sama di distro mana pun. Ini didokumentasikan di KDoc.

**`fun shimPath(id: String, binary: String): String`** = `"containers/bin/$id-$binary"` (relatif terhadap `filesDir`; Task 4 yang menulis + `setExecutable(true)`).

Catatan: parameter `abi` diterima sesuai signature brief/plan tetapi tidak mempengaruhi isi skrip — loader musl dipilih kernel lewat PT_INTERP yang sudah per-ABI dari sisi distro. Ini disadari dan didokumentasikan di KDoc, bukan diabaikan.

### ContainerShimTest.kt — 3 tes (JUnit4, pure JVM)

Nama & assertion mengikuti plan Task 3, diperketat dengan assertion 4-baris:

1. `shim python3 benar` — shebang, HOME, SSL_CERT_FILE, `exec … /usr/bin/python3 "$@"` persis; ditambah: tepat 4 baris dan diakhiri newline (menangkap bug "baris kosong dari trimIndent" yang tidak tertangkap `contains`).
2. `shim pip3 memakai path rootfs yang sama` — `exec … /usr/bin/pip3 "$@"`; HOME & SSL_CERT_FILE identik dengan shim python3.
3. `shim path host tidak bentrok antar container` — `containers/bin/python-python3`, `containers/bin/node-node`; ditambah cek `python`-`node` ≠ `node`-`node`.

## Tests: Written but NOT executed locally

Sesuai instruksi user, **tidak ada** `./gradlew` / gradle test yang dijalankan di mesin ini (JVM lokal juga gagal start: grsecurity/PaX menolak halaman executable — lihat task-1/2 report). Tes ditulis dan siap diverifikasi CI via `cd android && ./gradlew testDebugUnitTest`.

Verifikasi yang dilakukan tanpa build:

- Self-review diff baris per baris (import JUnit yang ada di `testImplementation("junit:junit:4.13.2")` — tanpa dependency baru; tidak ada API Android terlibat).
- Logika `script()`/`shimPath()` disimulasikan di Python dengan input yang sama seperti tes: keluaran persis `'#!/bin/sh\nexport HOME=/opt/containers/python/home\nexport SSL_CERT_FILE=/opt/containers/python/rootfs/etc/ssl/certs/ca-certificates.crt\nexec /opt/containers/python/rootfs/usr/bin/python3 "$@"\n'` — 4 baris, newline akhir, semua assertion `contains` bernilai benar.
- `"\$@"` di Kotlin string menghasilkan `$@` (escape `$` Kotlin), sesuai isi skrip di brief.

## Self-Review Notes

- Signature cocok dengan konsumen: `ContainerShim.script(spec, binary, abi)` dan `ContainerShim.shimPath(id, binary)` — persis yang dipanggil Task 4 (`ContainerManager` menulis `shimDir/<id>-<binary>`).
- `ContainerSpec` dipakai apa adanya (field `id` saja); tidak ada perubahan pada file Task 1.
- KDoc memakai referensi `[MuslRuntime]`, `[shimPath]`, `[android.content.Context.getFilesDir]` — semuanya valid (package sama / FQN), tidak dipakai compiler sebagai symbol (KDoc bukan kode).
- Skrip tidak di-quote dengan path berisi spasi: `id` selalu dari katalog (python/node), binary dari `shimBinaries` — tidak ada input user. Aman.

## Concerns

1. **Unit test belum dijalankan** (build offloaded ke CI). Risiko residual kecil: hanya soal escape `"\$@"` — sudah diverifikasi manual di runtime Kotlin equivalent (Python) bahwa hasilnya `$@`.
2. **Parameter `abi` tidak dipakai** — sesuai desain (PT_INTERP per-ABI diselesaikan loader distro), tapi kalau ke depan shim perlu memilih loader eksplisit (mis. beda path loader untuk non-Alpine), `script()` adalah tempatnya.
3. **Handoff Task 4/5**: shim hanya berguna kalau (a) Task 5 menambah bind `containers → /opt/containers` + `PATH=/opt/containers/bin:...` di semua argv proot, dan (b) container rootfs terpasang lewat Task 4 (`ContainerManager` memanggil `ContainerShim.script` untuk tiap `shimBinaries` + `setExecutable(true)`). Keduanya sudah tercermin di brief task-4/task-5.

## Handoff / Catatan untuk Task 4 & 5

- Task 4: tulis shim ke `File(context.filesDir, ContainerShim.shimPath(spec.id, binary))` (path relatif dari `filesDir`), `setExecutable(true, false)`, lalu cek keberadaannya di `isInstalled`.
- Task 5: `PATH=/opt/containers/bin:${guestPathValue()}` + bind `-b <filesDir>/containers:/opt/containers` di `DistroManager.buildProotCommand`, `EngineManager.baseArgv`/`serveArgv` — tanpa ini shim tidak pernah ada di PATH guest.