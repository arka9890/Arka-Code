package com.arkacode.app.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Helper post-install mini-rootfs **tool container**.
 *
 * Container adalah rootfs Alpine kecil di `<filesDir>/containers/<id>/rootfs`
 * yang di-bind ke `/opt/containers` di semua distro (lihat [ContainerSpec]).
 * Isinya dipakai lewat shim `#!/bin/sh` di `/opt/containers/bin`, jadi rootfs
 * container harus punya hal-hal dasar yang sama dengan distro hasil
 * [DistroManager] install:
 *
 *  - `/root/workspace` + `/tmp` — proot butuh keduanya ada sebelum bisa
 *    mengikat `-b <rootfs>/tmp:/dev/shm`.
 *  - `/etc/resolv.conf` + `/etc/hosts` — tanpa ini `apk add` / `pip install`
 *    gagal resolve DNS.
 *  - Bit executable pada binary ELF & script bershebang — minirootfs Alpine
 *    yang diekstrak dari tar bisa kehilangan bit x (arsip dibuat tanpa mode),
 *    dan shim `#!/bin/sh` tidak bisa jalan tanpa `/bin/sh` yang executable.
 *
 * Semua fungsi di sini **tidak melempar exception** untuk kondisi wajar
 * (folder belum ada, symlink sudah ada, filesystem menolak chmod): post-install
 * berjalan di thread IO saat instalasi dan satu file aneh tidak boleh
 * menggagalkan seluruh pemasangan container.
 */
object ContainerRootfs {

    /**
     * Isi `/etc/resolv.conf` — PERSIS sama dengan [DistroManager] postInstall
     * (sumber kebenaran: DistroManager.kt, `postInstall()`).
     */
    const val RESOLV_CONF = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"

    /** Isi `/etc/hosts` — PERSIS sama dengan [DistroManager] postInstall. */
    const val HOSTS = "127.0.0.1 localhost\n::1 localhost\n"

    /** Folder di dalam rootfs yang isinya harus bisa dieksekusi. */
    private val BIN_DIRS = listOf("bin", "usr/bin", "usr/local/bin")

    /** Magic number ELF (`0x7F 'E' 'L' 'F'`). */
    private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

    // ------------------------------------------------------------ PATH musl

    /**
     * Tambahkan [guestDir] ke [existingPath] (dipisah `:`) secara idempoten.
     *
     *  - `existingPath` kosong → [guestDir].
     *  - [guestDir] sudah ada sebagai elemen (dibanding setelah `trim`) →
     *    kembalikan [existingPath] apa adanya.
     *  - selain itu → `"$existingPath:$guestDir"`.
     *
     * Dipakai `DistroManager.ensureMuslRuntime` untuk menyusun isi
     * `<rootfs>/etc/ld-musl-<arch>.path`: satu baris berisi semua direktori
     * lib guest yang harus dicari loader musl (runtime musl + tiap container
     * terpasang). Idempoten penting karena file itu ditulis ulang tiap kali
     * engine start — tanpa cek ini path container menumpuk terus.
     */
    fun appendMuslPath(existingPath: String, guestDir: String): String {
        val dir = guestDir.trim()
        if (dir.isEmpty()) return existingPath
        val elements = existingPath.split(':').map { it.trim() }
        if (dir in elements) return existingPath
        return if (existingPath.isEmpty()) dir else "$existingPath:$dir"
    }

    // ------------------------------------------------------- post-install

    /**
     * Siapkan rootfs container untuk dipakai proot (idempoten).
     *
     * Meniru [DistroManager] `postInstall()` untuk bagian yang berlaku sama
     * untuk semua container: `/root/workspace`, `/tmp`, `/etc/resolv.conf`,
     * `/etc/hosts`, dan bit executable pada binary ELF/shebang di
     * `bin`, `usr/bin`, `usr/local/bin`.
     *
     * Tidak menyentuh konfigurasi khas distro (mis. `$PREFIX/etc` layout
     * Termux) — rootfs container selalu layout Alpine biasa.
     */
    fun ensureContainerLinks(containerRootfsDir: File) {
        runCatching {
            File(containerRootfsDir, "root/workspace").mkdirs()
            // tmp top-level: dipakai untuk bind `-b <rootfs>/tmp:/dev/shm`.
            File(containerRootfsDir, "tmp").mkdirs()
        }
        val etcDir = File(containerRootfsDir, "etc")
        runCatching { etcDir.mkdirs() }
        runCatching { File(etcDir, "resolv.conf").writeText(RESOLV_CONF) }
        runCatching { File(etcDir, "hosts").writeText(HOSTS) }
        BIN_DIRS.forEach { dir -> markExecutables(File(containerRootfsDir, dir)) }
    }

    /**
     * Set bit executable (+x untuk owner) pada tiap file di [dir] yang
     * berformat ELF atau script bershebang — pola dari [DistroManager]
     * `extractZip` (zip streaming tidak menyimpan bit eksekusi, jadi dideteksi
     * dari ISI file).
     *
     * Symlink (mis. `bin/busybox` di Alpine) ikut terdeteksi lewat target-nya;
     * kalau target tidak ada, file-nya dilewati tanpa error.
     */
    private fun markExecutables(dir: File) {
        val files = dir.listFiles() ?: return
        files.forEach { file ->
            if (!file.isFile) return@forEach
            if (isElfOrShebang(file)) {
                runCatching { file.setExecutable(true, false) }
            }
        }
    }

    /** True kalau 4 byte pertama file adalah magic ELF atau `#!`. */
    private fun isElfOrShebang(file: File): Boolean {
        val head = readHead(file, 4) ?: return false
        if (head.size >= 4 && head.contentEquals(ELF_MAGIC)) return true
        return head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
    }

    /**
     * Baca sampai [count] byte awal file; null kalau tidak bisa dibaca.
     *
     * Loop manual (bukan `readNBytes`) karena `InputStream.readNBytes` baru ada
     * di API 33, sedangkan minSdk proyek ini 26.
     */
    private fun readHead(file: File, count: Int): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(buf, read, count - read)
                if (n < 0) break
                read += n
            }
            if (read <= 0) null else buf.copyOf(read)
        }
    }.getOrNull()

    // --------------------------------------------------- layout Termux

    /**
     * Pastikan `<rootfs>/bin/sh` ada untuk layout Termux.
     *
     * Rootfs Termux (prefix `data/data/com.termux/files/usr`) tidak punya
     * `/bin/sh` di root rootfs — semuanya di dalam prefix. Shim container
     * memakai `#!/bin/sh`, jadi tanpa ini shim gagal dieksekusi dan fitur
     * container mati total di device Termux.
     *
     * Kalau `bin/sh` di root rootfs belum ada sementara `<rootfs>/<prefix>/bin/sh`
     * ada → buat symlink relatif `<rootfs>/bin/sh` → `../<prefix>/bin/sh`.
     * Relatif (bukan absolut) supaya rootfs tetap bisa dipindah.
     *
     * Idempoten: symlink yang sudah ada tidak pernah ditimpa.
     *
     * @return true kalau symlink baru dibuat.
     */
    fun ensureShForTermuxLayout(rootfsDir: File, prefix: String): Boolean {
        val normalized = normalizePrefix(prefix)
        if (normalized.isEmpty()) return false
        val link = File(rootfsDir, "bin/sh")
        // Cek tanpa mengikuti symlink: `File.exists()` false untuk symlink
        // yang menggantung, sehingga tanpa ini kita mencoba membuat ulang.
        if (existsNoFollow(link)) return false
        val target = File(rootfsDir, "$normalized/bin/sh")
        if (!target.isFile) return false
        val binDir = link.parentFile ?: return false
        runCatching { binDir.mkdirs() }
        val relative = relativePath(binDir, target)
        return runCatching {
            link.delete() // no-op bila belum ada
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(relative))
            true
        }.getOrDefault(false)
    }

    /** Path relatif dari [from] (folder) ke [to], mis. `../usr/bin/sh`. */
    private fun relativePath(from: File, to: File): String {
        val sep = File.separatorChar
        val fromParts = from.absolutePath.split(sep).filter { it.isNotEmpty() }
        val toParts = to.absolutePath.split(sep).filter { it.isNotEmpty() }
        val common = fromParts.zip(toParts).takeWhile { it.first == it.second }.size
        val ups = List(fromParts.size - common) { ".." }
        return (ups + toParts.drop(common)).joinToString("/")
    }

    /** Prefix guest (`data/data/com.termux/files/usr`) tanpa garis miring ujung. */
    private fun normalizePrefix(prefix: String): String =
        prefix.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

    /** `Files.exists` tanpa mengikuti symlink. */
    private fun existsNoFollow(file: File): Boolean =
        Files.exists(Paths.get(file.absolutePath), LinkOption.NOFOLLOW_LINKS)
}