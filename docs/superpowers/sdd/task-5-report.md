# Task 5 Report: Bind + PATH containers in all proot argv builders

## Changes (5 touch points)

1. **`DistroManager.companion`** — added `fun containerBindArgs(containersDir: String): List<String>` delegating to `ContainerManager.containerBindArgs` (single source of truth).
2. **`DistroManager.buildProotCommand()`** — after the workspace bind block: `argv += containerBindArgs(File(context.filesDir, "containers").absolutePath)`; PATH changed from `guestPathValue()` to new `guestPathValueWithContainers()` (`/opt/containers/bin:` prefix).
3. **`DistroManager.ensureMuslRuntime()`** — `<rootfs>/etc/ld-musl-<arch>.path` now written as `MuslRuntime.GUEST_LIB_DIR` + `ContainerRootfs.appendMuslPath(current, ContainerManager.guestPath(id) + "/usr/lib")` for each `ContainerCatalog.ALL` whose rootfs exists. Idempotent.
4. **`DistroManager.postInstall()`** — after the Termux block: `ContainerRootfs.ensureShForTermuxLayout(rootfsDir, spec.guestPrefix)` when `spec.guestPrefix` non-empty (no-op otherwise); `root/.profile` PATH now uses `guestPathValueWithContainers()`.
5. **`EngineManager.baseArgv()` / `guestEnvTail()` / `serveArgv()`** — containers bind appended after engineDir bind (`ContainerManager.containerBindArgs`); `guestEnvTail` PATH = `/opt/containers/bin:/usr/local/sbin:...`; `serveArgv` PATH = `distro.guestPathValueWithContainers()`.

New helper: `DistroManager.guestPathValueWithContainers()` = `"/opt/containers/bin:" + guestPathValue()`.

## Unchanged (verified in diff)

All existing proot args preserved: `--kill-on-exit`, `--link2symlink`, `-0` (removed only for TERMUX_PREFIX as before), `-b /dev`, `-b /proc`, `-b /sys`, `<rootfs>/tmp:/dev/shm`, workspace/engine/opencode-data binds, `PROOT_LOADER`, `PROOT_TMP_DIR`, `PROOT_NO_SECCOMP`, `TERM`, `LANG`, HOME, Termux `extraBinds`/`guestExtraEnv`.

## Tests

`ContainerExposureTest` (4 tests): `containerBindArgs` pairs, DistroManager delegate = ContainerManager source, `guestPathValueWithContainers` prefix/contains (Alpine + Termux mirror of `guestPathValue` composition — instance test avoided because `DistroManager` ctor requires a real `Context`).

Tests written but NOT executed locally (build offloaded to GitHub Actions CI).