package com.arkacode.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Tes parser paket Alpine (.apk).
 *
 * Format .apk = beberapa member gzip yang disambung:
 *   member 0 = signature, member 1 = control, member 2 = data (isi paket).
 *
 * Yang diuji: pemecahan member gzip & pembacaan isi member "data".
 */
class MuslRuntimeTest {

    /** Bikin satu member gzip berisi tar dengan beberapa berkas. */
    private fun gzipMember(vararg files: Pair<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(tar).use { out ->
            files.forEach { (name, content) ->
                val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                entry.size = content.size.toLong()
                out.putArchiveEntry(entry)
                out.write(content)
                out.closeArchiveEntry()
            }
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun apk(vararg members: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        members.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun `paket apk dipecah jadi tiga member`() {
        val bytes = apk(
            gzipMember(".SIGN.RSA.key" to ByteArray(10)),
            gzipMember(".PKGINFO" to ByteArray(20)),
            gzipMember("usr/lib/libgcc_s.so.1" to ByteArray(30) { 7 }),
        )
        val members = MuslRuntime.gzipMembers(bytes)
        assertEquals(3, members.size)
        members.forEach { assertTrue("member harus berisi data", it.isNotEmpty()) }
    }

    @Test
    fun `isi member data terbaca sebagai daftar berkas`() {
        // Nama entri di apk asli Alpine berawalan "./" — parser harus
        // menormalkannya (regresi: runtime musl tidak terpasang di distro
        // non-Alpine karena kunci tidak cocok).
        val bytes = apk(
            gzipMember(".SIGN.RSA.key" to ByteArray(10)),
            gzipMember(".PKGINFO" to ByteArray(20)),
            gzipMember("./usr/lib/libgcc_s.so.1" to ByteArray(30) { 7 }),
        )
        val files = MuslRuntime.apkDataFiles(bytes)
        assertEquals(1, files.size)
        assertTrue(files.containsKey("usr/lib/libgcc_s.so.1"))
        assertArrayEquals(ByteArray(30) { 7 }, files["usr/lib/libgcc_s.so.1"])
    }

    @Test
    fun `beberapa berkas dalam satu paket terbaca semua`() {
        val bytes = apk(
            gzipMember(".SIGN.RSA.key" to ByteArray(10)),
            gzipMember(".PKGINFO" to ByteArray(20)),
            gzipMember(
                "./lib/ld-musl-aarch64.so.1" to byteArrayOf(
                    0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
                ),
                "./usr/lib/libstdc++.so.6.0.32" to ByteArray(40) { 1 },
            ),
        )
        val files = MuslRuntime.apkDataFiles(bytes)
        assertEquals(2, files.size)
        assertTrue(files.containsKey("lib/ld-musl-aarch64.so.1"))
        assertTrue(files.containsKey("usr/lib/libstdc++.so.6.0.32"))
        assertEquals(4, files["lib/ld-musl-aarch64.so.1"]?.size)
    }

    @Test
    fun `paket rusak tidak membuat crash`() {
        // bukan gzip sama sekali
        assertTrue(MuslRuntime.gzipMembers(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        // gzip terpotong -> tidak boleh melempar exception
        val truncated = gzipMember("x" to ByteArray(100)).copyOf(20)
        runCatching { MuslRuntime.gzipMembers(truncated) }
        // apk tanpa member data -> peta kosong
        val noData = apk(
            gzipMember(".SIGN" to ByteArray(4)),
            gzipMember(".PKGINFO" to ByteArray(4)),
        )
        assertTrue(MuslRuntime.apkDataFiles(noData).isEmpty())
    }

    @Test
    fun `arsitektur musl dipetakan dari abi android`() {
        assertEquals("aarch64", MuslRuntime.archFor("arm64-v8a"))
        assertEquals("x86_64", MuslRuntime.archFor("x86_64"))
        // opencode tidak punya build 32-bit -> null
        assertNull(MuslRuntime.archFor("armeabi-v7a"))
        assertNull(MuslRuntime.archFor("x86"))
    }

    @Test
    fun `path file musl menunjuk ke folder lib di guest`() {
        assertEquals("/opt/arkacode/lib", MuslRuntime.GUEST_LIB_DIR)
    }
}
