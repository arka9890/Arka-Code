package com.arkacode.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes [ContainerShim] — fungsi murni, tanpa Android.
 *
 * Yang diperiksa: isi shim persis (shebang + 2 export + exec, 4 baris),
 * `pip3` memakai path rootfs yang sama dengan `python3`, dan nama file shim
 * tidak bentrok antar container.
 */
class ContainerShimTest {

    @Test
    fun `shim python3 benar`() {
        val s = ContainerShim.script(ContainerCatalog.PYTHON, "python3", "arm64-v8a")
        assertTrue(s.startsWith("#!/bin/sh"))
        assertTrue(s.contains("export HOME=/opt/containers/python/home"))
        assertTrue(
            s.contains(
                "export SSL_CERT_FILE=/opt/containers/python/rootfs/etc/ssl/certs/ca-certificates.crt",
            ),
        )
        assertTrue(s.contains("exec /opt/containers/python/rootfs/usr/bin/python3 \"\$@\""))

        // tepat 4 baris (shebang + 2 export + exec), newline akhir — tanpa baris
        // kosong tersisa dari raw string/trimIndent.
        val lines = s.trimEnd().lines()
        assertEquals(4, lines.size)
        assertEquals("#!/bin/sh", lines[0])
        assertEquals("export HOME=/opt/containers/python/home", lines[1])
        assertEquals(
            "export SSL_CERT_FILE=/opt/containers/python/rootfs/etc/ssl/certs/ca-certificates.crt",
            lines[2],
        )
        assertEquals("exec /opt/containers/python/rootfs/usr/bin/python3 \"\$@\"", lines[3])
        assertTrue("skrip harus diakhiri newline", s.endsWith("\n"))
    }

    @Test
    fun `shim pip3 memakai path rootfs yang sama`() {
        val s = ContainerShim.script(ContainerCatalog.PYTHON, "pip3", "arm64-v8a")
        assertTrue(s.contains("exec /opt/containers/python/rootfs/usr/bin/pip3 \"\$@\""))
        // HOME & SSL_CERT_FILE sama dengan shim python3 (satu container, satu home).
        assertTrue(s.contains("export HOME=/opt/containers/python/home"))
        assertTrue(
            s.contains(
                "export SSL_CERT_FILE=/opt/containers/python/rootfs/etc/ssl/certs/ca-certificates.crt",
            ),
        )
    }

    @Test
    fun `shim path host tidak bentrok antar container`() {
        assertEquals("containers/bin/python-python3", ContainerShim.shimPath("python", "python3"))
        assertEquals("containers/bin/node-node", ContainerShim.shimPath("node", "node"))
        // binary yang sama di container berbeda tetap beda file.
        assertTrue(ContainerShim.shimPath("python", "node") != ContainerShim.shimPath("node", "node"))
    }
}