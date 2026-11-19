package com.arkacode.app.core

import java.io.File

/**
 * Semua path yang dibutuhkan [TerminalCommand.build] untuk satu shell interaktif.
 *
 * Dipisah dari [DistroManager] supaya urutan argv bisa diuji tanpa Android.
 */
data class TerminalTarget(
    val proot: String,
    val loader: String,
    val rootfs: String,
    val prootTmp: String,
    val envPath: String,
    val shellPath: String,
    val home: String,
    val path: String,
    val termuxLayout: Boolean,
    val workspaceDir: String? = null,
    val containersDir: String? = null,
    val shmDir: String? = null,
    val extraBinds: List<String> = emptyList(),
    val extraEnv: Map<String, String> = emptyMap(),
)

/**
 * Argv proot untuk shell terminal interaktif.
 *
 * Sama dengan `DistroManager.buildProotCommand` (urutan flag, bind, dan env tail),
 * tapi berakhir di shell login, bukan `-c <command>`, dan tanpa `--port` /
 * `OPENCODE_SERVER_PASSWORD` milik server opencode.
 */
object TerminalCommand {

    fun build(target: TerminalTarget): List<String> = buildList {
        add(target.proot)
        add("--kill-on-exit")
        // SELinux Android melarang link() untuk app biasa; proot
        // menerjemahkannya jadi symlink agar dpkg/apt bisa jalan.
        add("--link2symlink")
        // Distro layout Termux berjalan sebagai user biasa seperti app Termux
        // asli — fake-root malah memblokir pkg/apt di dalamnya.
        if (!target.termuxLayout) add("-0")
        add("-r"); add(target.rootfs)
        add("-w"); add(target.home)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        target.shmDir?.let { add("-b"); add("$it:/dev/shm") }
        target.workspaceDir?.let { add("-b"); add("$it:/root/workspace") }
        target.containersDir?.let { add("-b"); add("$it:/opt/containers") }
        target.extraBinds.forEach { path -> add("-b"); add(path) }
        add(target.envPath)
        add("-i")
        add("HOME=${target.home}")
        add("PATH=${target.path}")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("PROOT_LOADER=${target.loader}")
        add("PROOT_TMP_DIR=${target.prootTmp}")
        add("PROOT_NO_SECCOMP=1")
        target.extraEnv.forEach { (key, value) -> add("$key=$value") }
        add(target.shellPath)
        add("-l")
    }

    /** Null bila proot/loader belum ada di APK atau rootfs belum terpasang. */
    fun forDistro(distro: DistroManager, workspaceDir: File?, containersDir: File): List<String>? {
        val proot = distro.prootBinary() ?: return null
        val loader = distro.loaderBinary() ?: return null
        if (!distro.isInstalled()) return null
        val rootfs = distro.rootfsDir
        return build(
            TerminalTarget(
                proot = proot.absolutePath,
                loader = loader.absolutePath,
                rootfs = rootfs.absolutePath,
                prootTmp = prootTmpDir(rootfs).absolutePath,
                envPath = distro.guestEnvPath(),
                shellPath = distro.guestShellPath(),
                home = distro.guestHomePath(),
                path = distro.guestPathValueWithContainers(),
                termuxLayout = distro.spec.layout == RootfsLayout.TERMUX_PREFIX,
                workspaceDir = workspaceDir?.absolutePath,
                containersDir = containersDir.absolutePath,
                shmDir = File(rootfs, "tmp").absolutePath,
                // extraBindArgs() menyisipkan "-b" di antara path; build() menambahkannya sendiri.
                extraBinds = distro.extraBindArgs().filter { it != "-b" },
                extraEnv = distro.guestExtraEnv(),
            ),
        )
    }

    /**
     * `DistroManager` menyimpan folder proot-tmp sebagai properti privat, jadi
     * path-nya diturunkan dari layout yang dipakai kelas itu:
     * `<filesDir>/distro/<folder>/` → `<filesDir>/proot-tmp`.
     */
    private fun prootTmpDir(rootfs: File): File =
        File(rootfs.absoluteFile.parentFile?.parentFile ?: rootfs, "proot-tmp")
}
