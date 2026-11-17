package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes katalog tool container: pemetaan ABI, nama loader musl, URL rootfs,
 * dan skrip instal per tool.
 *
 * Fungsi-fungsi di sini murni (tanpa jaringan) supaya bisa diuji di JVM.
 */
class ContainerSpecTest {

    @Test
    fun `arch dipetakan untuk empat abi`() {
        assertEquals("aarch64", ContainerCatalog.archFor("arm64-v8a"))
        assertEquals("armv7", ContainerCatalog.archFor("armeabi-v7a"))
        assertEquals("x86_64", ContainerCatalog.archFor("x86_64"))
        assertEquals("x86", ContainerCatalog.archFor("x86"))
        // ABI yang tidak dikenal -> null (container tidak punya rootfs untuk itu)
        assertNull(ContainerCatalog.archFor("mips"))
    }

    @Test
    fun `loader name mengikuti arch`() {
        assertEquals("ld-musl-aarch64.so.1", ContainerCatalog.loaderName("arm64-v8a"))
        assertEquals("ld-musl-armhf.so.1", ContainerCatalog.loaderName("armeabi-v7a"))
        assertEquals("ld-musl-x86_64.so.1", ContainerCatalog.loaderName("x86_64"))
        assertEquals("ld-musl-i386.so.1", ContainerCatalog.loaderName("x86"))
        // ABI yang tidak dikenal -> null
        assertNull(ContainerCatalog.loaderName("mips"))
    }

    @Test
    fun `url rootfs memakai minirootfs alpine 3_20 dengan arch benar`() {
        val urls = ContainerCatalog.PYTHON.rootfsUrls("arm64-v8a")
        assertTrue(urls.first(), urls.first().startsWith("https://dl-cdn.alpinelinux.org/alpine"))
        assertTrue(urls.first(), urls.first().contains("v3.20"))
        assertTrue(
            urls.first(),
            urls.first().contains("alpine-minirootfs-3.20.3-aarch64.tar.gz"),
        )
        assertTrue(
            ContainerCatalog.PYTHON.rootfsUrls("x86").first(),
            ContainerCatalog.PYTHON.rootfsUrls("x86").first()
                .contains("alpine-minirootfs-3.20.3-x86.tar.gz"),
        )
    }

    @Test
    fun `url punya mirror cadangan dan https`() {
        ContainerCatalog.ALL.forEach { spec ->
            val urls = spec.rootfsUrls("arm64-v8a")
            assertTrue("${spec.id} harus punya >1 kandidat", urls.size > 1)
            assertEquals("${spec.id} kandidat harus unik", urls.size, urls.distinct().size)
            urls.forEach { assertTrue("${spec.id} harus https", it.startsWith("https://")) }
        }
    }

    @Test
    fun `skrip instal python memuat python3 ca-certificates`() {
        assertTrue(ContainerCatalog.PYTHON.setupScript.contains("python3"))
        assertTrue(ContainerCatalog.PYTHON.setupScript.contains("ca-certificates"))
        assertTrue(ContainerCatalog.PYTHON.setupScript.contains("apk add --no-cache"))
        assertEquals(listOf("python3", "pip3"), ContainerCatalog.PYTHON.shimBinaries)
    }

    @Test
    fun `skrip instal node memuat nodejs ca-certificates`() {
        assertTrue(ContainerCatalog.NODE.setupScript.contains("nodejs"))
        assertTrue(ContainerCatalog.NODE.setupScript.contains("ca-certificates"))
        assertTrue(ContainerCatalog.NODE.setupScript.contains("apk add --no-cache"))
        assertEquals(listOf("node", "npm", "npx"), ContainerCatalog.NODE.shimBinaries)
    }

    @Test
    fun `byId aman untuk nilai tak dikenal`() {
        assertEquals(ContainerCatalog.PYTHON.id, ContainerCatalog.byId(null).id)
        assertEquals(ContainerCatalog.PYTHON.id, ContainerCatalog.byId("ngawur").id)
        assertEquals(ContainerCatalog.NODE.id, ContainerCatalog.byId("node").id)
        assertEquals(ContainerCatalog.PYTHON.id, ContainerCatalog.byId("python").id)
    }
}