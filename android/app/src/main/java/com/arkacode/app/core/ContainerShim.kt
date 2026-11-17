package com.arkacode.app.core

/**
 * Generator shim tool container.
 *
 * Satu shim = skrip `#!/bin/sh` kecil di `containers/bin/<id>-<binary>` yang
 * mengeksekusi binary di dalam rootfs container LANGSUNG:
 *
 * ```
 * exec /opt/containers/<id>/rootfs/usr/bin/<binary> "$@"
 * ```
 *
 * Kenapa langsung (tanpa `proot`): binary container dibangun untuk musl dan
 * dinamis (`PT_INTERP=/lib/ld-musl-<arch>.so.1`). Loader itu sudah dipasang di
 * rootfs distro aktif oleh [MuslRuntime.ensureMuslRuntime] (jalur yang sama
 * dengan binary opencode), dan bind `-b <filesDir>/containers:/opt/containers`
 * membuat rootfs container terlihat di path yang sama di distro mana pun —
 * termasuk distro glibc. Jadi satu shim jalan di semua distro tanpa berganti
 * distro, dan `exec` mewarisi environment (termasuk `PATH` yang sudah diberi
 * `/opt/containers/bin`).
 *
 * Shim juga mengekspor:
 *  - `HOME` → folder home milik container (persisten, ikut container saat user
 *    berganti distro; `pip install --user` & `npm` cache tidak jatuh ke `/root`
 *    distro yang bisa berganti).
 *  - `SSL_CERT_FILE` → CA bundle di dalam rootfs container. `setupScript` memasang
 *    `ca-certificates`; tanpa env ini `pip`/`npm` via HTTPS bisa gagal di
 *    mini-rootfs yang CA-nya belum terpasang.
 *
 * [abi] disediakan untuk konsistensi API (dan untuk konsumen yang perlu tahu
 * arsitektur) — isi skrip tidak berbeda per ABI: loader memilih sendiri lewat
 * PT_INTERP `/lib/ld-musl-<arch>.so.1` yang sudah dipasang per distro.
 */
object ContainerShim {

    /**
     * Isi skrip shim untuk [binary] milik container [spec].
     *
     * Tepat empat baris (shebang + 2 export + exec), diakhiri newline — siap
     * ditulis ke [shimPath] lalu `setExecutable(true)`.
     */
    fun script(spec: ContainerSpec, binary: String, abi: String): String {
        val id = spec.id
        return buildString {
            append("#!/bin/sh\n")
            append("export HOME=/opt/containers/$id/home\n")
            append("export SSL_CERT_FILE=/opt/containers/$id/rootfs/etc/ssl/certs/ca-certificates.crt\n")
            append("exec /opt/containers/$id/rootfs/usr/bin/$binary \"\$@\"\n")
        }
    }

    /**
     * Path host file shim di folder data aplikasi:
     * `containers/bin/<id>-<binary>`.
     *
     * Prefiks `<id>-` penting supaya container berbeda dengan binary bernama
     * sama tidak bentrok (mis. `python3` milik `python` vs binary lain).
     * Relatif terhadap [android.content.Context.getFilesDir].
     */
    fun shimPath(id: String, binary: String): String = "containers/bin/$id-$binary"
}