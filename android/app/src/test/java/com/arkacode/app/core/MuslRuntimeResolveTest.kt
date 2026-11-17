package com.arkacode.app.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * Tes penyelesaian nama paket runtime musl (regresi "Runtime musl gagal
 * dipasang").
 *
 * Repository Alpine hanya menyimpan satu revisi terbaru per paket. Saat Alpine
 * menaikkan musl ke `1.2.5-r4`, berkas `musl-1.2.5-r3.apk` dihapus dari CDN
 * dan semua mirror. Nama berkas yang dipatok di kode karena itu jadi 404,
 * unduhan gagal, loader musl tidak pernah terpasang, dan engine mati dengan
 * pesan "cek koneksi/VPN" padahal jaringan sehat.
 *
 * Perbaikannya: nama berkas dibaca dari `APKINDEX.tar.gz` saat runtime dan
 * isinya diverifikasi lewat checksum `C:` milik indeks. Semua di bawah ini
 * murni (tanpa jaringan) — diuji di JVM.
 */
class MuslRuntimeResolveTest {

    /** Bikin satu member gzip berisi tar dengan beberapa berkas. */
    private fun gzipMember(vararg files: Pair<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            files.forEach { (name, content) ->
                val entry = TarArchiveEntry(name)
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

    /** `APKINDEX.tar.gz` seperti yang ada di repo Alpine. */
    private fun indexArchive(text: String): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            listOf("DESCRIPTION" to "main".toByteArray(), "APKINDEX" to text.toByteArray())
                .forEach { (name, content) ->
                    val entry = TarArchiveEntry(name)
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

    private val indexText = """
        C:Q1wN3NRweFi/b96vOzdjuRPFSzDvQ=
        P:musl
        V:1.2.5-r4
        A:aarch64
        S:416399
        T:the musl c library (libc) implementation
        o:musl
        m:Timo Teräs <timo.teras@iki.fi>
        t:1761000000

        C:Q1FTE0omNmZI5hxUup7fE39ClWGcE=
        P:libstdc++
        V:13.2.1_git20240309-r1
        A:aarch64
        S:885578
        T:GNU C++ standard runtime library

        C:Q16STP1bUsfmwNZlDQpJnTblP6rIM=
        P:libgcc
        V:13.2.1_git20240309-r1
        A:aarch64
        S:50808
        T:GNU C compiler runtime library
    """.trimIndent()

    @Test
    fun `indeks dibaca jadi nama paket versi terbaru`() {
        val index = MuslRuntime.parseApkIndex(indexText)

        val musl = index["musl"]
        assertNotNull(musl)
        assertEquals("1.2.5-r4", musl!!.version)
        assertEquals("aarch64", musl.arch)
        assertEquals(416399L, musl.size)
        assertEquals("Q1wN3NRweFi/b96vOzdjuRPFSzDvQ=", musl.checksum)
        // Inti regresi: nama berkas yang benar-benar ada di repo, bukan r3.
        assertEquals("musl-1.2.5-r4.apk", musl.fileName)

        assertEquals("libstdc++-13.2.1_git20240309-r1.apk", index["libstdc++"]?.fileName)
        assertEquals("libgcc-13.2.1_git20240309-r1.apk", index["libgcc"]?.fileName)
    }

    @Test
    fun `versi tertinggi menang saat satu paket muncul lebih dari sekali`() {
        val text = """
            P:musl
            V:1.2.5-r3
            A:aarch64
            S:416000

            P:musl
            V:1.2.5-r4
            A:aarch64
            S:416399

            P:musl
            V:1.2.5-r10
            A:aarch64
            S:416500
        """.trimIndent()
        val index = MuslRuntime.parseApkIndex(text)
        // r10 > r4 secara numerik, bukan urutan abjad.
        assertEquals("1.2.5-r10", index["musl"]?.version)
    }

    @Test
    fun `blok rusak diabaikan tanpa melempar exception`() {
        val text = "sampah tanpa field\n\nP:musl\nV:1.2.5-r4\nA:aarch64\n\nV:tanpa-nama\n"
        val index = MuslRuntime.parseApkIndex(text)
        assertEquals(1, index.size)
        assertEquals("1.2.5-r4", index["musl"]?.version)
    }

    @Test
    fun `indeks kosong tidak menghasilkan entri`() {
        assertTrue(MuslRuntime.parseApkIndex("").isEmpty())
        assertTrue(MuslRuntime.parseApkIndex("\n\n").isEmpty())
    }

    @Test
    fun `APKINDEX dibaca dari arsip tar gzip`() {
        val text = MuslRuntime.parseIndexArchive(indexArchive(indexText))
        assertNotNull(text)
        assertTrue(text!!.contains("P:musl"))
        // DESCRIPTION diabaikan, hanya APKINDEX yang diambil.
        assertTrue(!text.contains("main"))
    }

    @Test
    fun `arsip indeks rusak menghasilkan null`() {
        assertNull(MuslRuntime.parseIndexArchive(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `checksum control cocok dengan SHA-1 member control`() {
        val signature = gzipMember(".SIGN.RSA.key" to ByteArray(10))
        val control = gzipMember(".PKGINFO" to ByteArray(20) { 3 })
        val data = gzipMember("lib/ld-musl-aarch64.so.1" to ByteArray(30) { 7 })
        val bytes = apk(signature, control, data)

        // Dihitung independen dari implementasi: SHA-1 atas SELURUH member
        // gzip control (header + deflate + footer), lalu base64 berawalan "Q1".
        val expected = "Q1" + Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-1").digest(control))

        assertEquals(expected, MuslRuntime.apkControlChecksum(bytes))
    }

    @Test
    fun `checksum control berubah kalau isi paket berubah`() {
        val control = gzipMember(".PKGINFO" to ByteArray(20) { 3 })
        val original = MuslRuntime.apkControlChecksum(
            apk(gzipMember(".SIGN" to ByteArray(10)), control, gzipMember("a" to ByteArray(5))),
        )
        val tampered = MuslRuntime.apkControlChecksum(
            apk(gzipMember(".SIGN" to ByteArray(10)), gzipMember(".PKGINFO" to ByteArray(21) { 3 }), gzipMember("a" to ByteArray(5))),
        )
        assertNotNull(original)
        assertTrue("checksum harus berbeda untuk paket berbeda", original != tampered)
    }

    @Test
    fun `checksum control null untuk berkas yang bukan apk`() {
        assertNull(MuslRuntime.apkControlChecksum(byteArrayOf(1, 2, 3, 4, 5)))
        // Hanya satu member (tanpa control) -> tidak bisa diverifikasi.
        assertNull(MuslRuntime.apkControlChecksum(gzipMember("x" to ByteArray(10))))
    }

    @Test
    fun `perbandingan versi alpine`() {
        assertTrue(MuslRuntime.compareVersions("1.2.5-r4", "1.2.5-r3") > 0)
        assertTrue(MuslRuntime.compareVersions("1.2.5-r3", "1.2.5-r4") < 0)
        assertEquals(0, MuslRuntime.compareVersions("1.2.5-r4", "1.2.5-r4"))
        assertTrue(MuslRuntime.compareVersions("1.2.10-r1", "1.2.9-r9") > 0)
        assertTrue(MuslRuntime.compareVersions("1.3.0-r1", "1.2.5-r9") > 0)
    }

    @Test
    fun `cabang utama dicoba lebih dulu lalu cadangan`() {
        val branches = MuslRuntime.branches()
        assertEquals(MuslRuntime.ALPINE_BRANCH, branches.first())
        assertEquals(MuslRuntime.FALLBACK_BRANCHES, branches.drop(1))
    }

    @Test
    fun `URL indeks memakai cabang yang diminta`() {
        val urls = MuslRuntime.indexUrls("v3.21", "aarch64")
        assertEquals(
            "${MuslRuntime.repoBase("v3.21")}/aarch64/APKINDEX.tar.gz",
            urls.first(),
        )
        assertEquals(1 + MuslRuntime.MIRRORS.size, urls.size)
        assertTrue(urls.all { it.contains("/v3.21/main/") })
    }

    @Test
    fun `URL paket mengikuti cabang cadangan`() {
        val urls = MuslRuntime.repoUrls("v3.21", "x86_64", "musl-1.2.5-r4.apk")
        assertTrue(urls.all { it.contains("/v3.21/main/x86_64/musl-1.2.5-r4.apk") })
    }

    @Test
    fun `magic ELF dikenali`() {
        assertTrue(MuslRuntime.isElf(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1)))
        // Halaman HTML mirror / berkas kosong bukan ELF.
        assertTrue(!MuslRuntime.isElf("<!DOCTYPE html>".toByteArray()))
        assertTrue(!MuslRuntime.isElf(ByteArray(0)))
        assertTrue(!MuslRuntime.isElf(byteArrayOf(0x7f, 'E'.code.toByte())))
    }
}
