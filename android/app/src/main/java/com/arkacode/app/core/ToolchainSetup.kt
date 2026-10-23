package com.arkacode.app.core

import java.io.File

/**
 * Toolchain build Android yang hidup di dalam distro proot.
 * Opsi A: skrip hanya ditulis/dijalankan saat pengguna menekan
 * "Siapkan toolchain" (≈2 GB) — tidak ada unduhan diam-diam.
 */
object ToolchainSetup {

    /** Satu baris sh: echo flag per komponen → BuildSupport.parseToolchain. */
    const val PROBE_CMD: String =
        "{ command -v java >/dev/null && echo JAVA_OK; " +
            "[ -f /opt/android-sdk/platforms/android-35/android.jar ] && echo SDK_OK; " +
            "[ -x /opt/android-sdk/aapt2/aapt2 ] && echo AAPT2_OK; " +
            "[ -x /usr/local/bin/adb ] && echo ADB_OK; }"

    /**
     * Skrip setup idempoten. Setiap blok punya guard, aman dijalankan
     * berulang. Nilai `${'$'}` di bawah literal — raw string Kotlin.
     */
    fun scriptContent(): String = """#!/bin/sh
# arka-toolchain-setup
set -e
# shortcut: versi aapt2 mengikuti agp (gradle/libs.versions.toml); kalau AGP naik, baris aapt2 di bawah ikut.
AAPT2_VER=8.9.3-12782657
SDK=/opt/android-sdk
DEBIAN_FRONTEND=noninteractive

command -v java >/dev/null 2>&1 || { apt-get update -qq &&
  apt-get install -y -qq openjdk-17-jdk-headless iproute2 qemu-user-static \
    libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross unzip curl ca-certificates; }

if [ ! -x "${'$'}SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "${'$'}SDK/cmdline-tools" && cd /tmp
  curl -sSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o clt.zip -d clt && rm -rf "${'$'}SDK/cmdline-tools/latest" && mv clt/cmdline-tools "${'$'}SDK/cmdline-tools/latest"
fi

if [ ! -f "${'$'}SDK/platforms/android-35/android.jar" ]; then
  yes | "${'$'}SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root=${'$'}SDK --licenses >/dev/null 2>&1 || true
  i=1; while [ ${'$'}i -le 3 ]; do
    "${'$'}SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root=${'$'}SDK "platforms;android-35" "build-tools;35.0.0" </dev/null && break
    i=${'$'}((i+1)); sleep 5
  done
fi

if [ ! -x "${'$'}SDK/aapt2/aapt2" ]; then
  mkdir -p "${'$'}SDK/aapt2" && cd "${'$'}SDK/aapt2"
  curl -sSL -o a.jar "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/${'$'}AAPT2_VER/aapt2-${'$'}AAPT2_VER-linux.jar"
  unzip -q -o a.jar aapt2 && mv -f aapt2 aapt2.bin
  printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/android-sdk/aapt2/aapt2.bin "$@"\n' > aapt2
  chmod +x aapt2
fi
grep -q aapt2FromMavenOverride /root/.gradle/gradle.properties 2>/dev/null ||
  { mkdir -p /root/.gradle && echo "android.aapt2FromMavenOverride=${'$'}SDK/aapt2/aapt2" >> /root/.gradle/gradle.properties; }

if [ ! -x /usr/local/bin/adb ]; then
  cd /tmp && curl -sSL -o pt.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
  # rm: kalau platform-tools sudah ada sebagai dir, mv akan bersarang di dalamnya
  rm -rf /tmp/ptx "${'$'}SDK/platform-tools"
  unzip -q -o pt.zip -d /tmp/ptx && mv /tmp/ptx/platform-tools "${'$'}SDK/platform-tools"
  mv -f "${'$'}SDK/platform-tools/adb" "${'$'}SDK/platform-tools/adb.bin"
  printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/android-sdk/platform-tools/adb.bin "$@"\n' > "${'$'}SDK/platform-tools/adb"
  chmod +x "${'$'}SDK/platform-tools/adb" && ln -sf "${'$'}SDK/platform-tools/adb" /usr/local/bin/adb
fi
echo "TOOLCHAIN_OK"
"""

    /** Tulis `<workspace>/.arka/setup-toolchain.sh`; timpa hanya bila konten beda. */
    fun ensureScript(workspaceRoot: File): File {
        val dir = File(workspaceRoot, ".arka")
        dir.mkdirs()
        val f = File(dir, "setup-toolchain.sh")
        val isi = scriptContent()
        if (!f.isFile || f.readText() != isi) f.writeText(isi)
        f.setExecutable(true)
        return f
    }
}
