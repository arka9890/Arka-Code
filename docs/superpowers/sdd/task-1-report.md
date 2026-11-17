# Task 1 Report: ContainerSpec + ContainerCatalog

## Status

**DONE_WITH_CONCERNS** — implementasi selesai, tetapi unit test tidak dijalankan secara lokal sesuai instruksi user (build/test offloaded ke GitHub Actions CI).

## Files Changed

- `android/app/src/main/java/com/arkacode/app/core/ContainerSpec.kt` (new, 138 lines)
- `android/app/src/test/java/com/arkacode/app/core/ContainerSpecTest.kt` (new, 85 lines)

## Commit

`feat(core): add ContainerSpec + ContainerCatalog for tool containers`

## Implementation Summary

### ContainerSpec.kt

Pure Kotlin/JVM, no Android dependencies (same package `com.arkacode.app.core` as `DistroSpec.kt`).

**`data class ContainerSpec`** with fields exactly per brief:
`id`, `label`, `summary`, `approxSizeMb`, `releaseMarkers`, `setupScript`, `shimBinaries`, `rootfsUrls`.

Two extra computed properties (kept minimal, mirror `DistroSpec.folderName`):
- `folderName` = `id`
- `guestRootfs` = `"/opt/containers/$id/rootfs"`

**`object ContainerCatalog`**:
- `PYTHON` — id="python", shimBinaries=`["python3","pip3"]`, setupScript: `apk add --no-cache python3 py3-pip ca-certificates`
- `NODE` — id="node", shimBinaries=`["node","npm","npx"]`, setupScript: `apk add --no-cache nodejs npm ca-certificates`
- `ALL = [PYTHON, NODE]`
- `byId(id)` — safe, returns PYTHON for unknown/null
- `archFor(abi)` — 4 ABIs → aarch64/armv7/x86_64/x86; null for unknown
- `loaderName(abi)` — `"ld-musl-$arch.so.1"` with armv7→armhf, x86→i386; null for unknown ABI
- `rootfsUrls` — private helper: builds Alpine minirootfs 3.20.3 URL from `DistroCatalog.CDN` + `DistroCatalog.MIRRORS`, returns emptyList for unknown ABI
- Constants `ALPINE_VERSION="3.20.3"`, `ALPINE_BRANCH="v3.20"`

### ContainerSpecTest.kt

7 test functions, all pure JVM (JUnit4), following the exact names and assertions from the plan:

1. `arch dipetakan untuk empat abi` — 4 ABI mappings + null for mips
2. `loader name mengikuti arch` — 4 loader names + null for mips
3. `url rootfs memakai minirootfs alpine 3_20 dengan arch benar` — CDN prefix, v3.20, correct tar.gz filename, x86 arch
4. `url punya mirror cadangan dan https` — all containers have >1 unique https URL
5. `skrip instal python memuat python3 ca-certificates` — contains python3, ca-certificates, apk add --no-cache; shimBinaries check
6. `skrip instal node memuat nodejs ca-certificates` — contains nodejs, ca-certificates, apk add --no-cache; shimBinaries check
7. `byId aman untuk nilai tak dikenal` — null→PYTHON, unknown→PYTHON, node→NODE, python→PYTHON

## Tests: Written but NOT executed locally

Per user clarification, no gradle build or unit test was run locally. The tests are ready for CI verification via `cd android && ./gradlew testDebugUnitTest`.

## Concerns

1. **Tests not locally verified.** The gradle wrapper uses JDK 21; the local environment's JDK 21 fails under grsecurity/PaX (JVM can't allocate executable memory for JIT). I installed JDK 17 via Alpine packages but the user's revised instructions arrived before the build completed. Tests are syntactically correct Kotlin and the logic is straightforward — but a compile error or assertion mismatch can only be confirmed by CI.

2. **`rootfsUrls` is a named-arg lambda in the data class constructor** — this is the same pattern as `DistroSpec.rootfsUrls`. The private factory `rootfsUrls()` function in `ContainerCatalog` shadows the property name; Kotlin allows this but the lambda param name `abi` in the declaration matches the function param name `abi` in the helper. No name clash issue, but worth noting for readability.

3. **`setupScript` uses `trimIndent()`** — the `#!/bin/sh` shebang line is included in the string. If downstream code executes the script by writing it to a file and running `/bin/sh <file>`, the shebang is harmless (it's a comment). If it execs the file directly, `chmod +x` is needed before exec. This is a non-issue for Task 4 (ContainerManager), but worth noting.

4. **`releaseMarkers` paths are relative to rootfs root** (`usr/bin/python3`, not `/usr/bin/python3`). This is consistent with how `DistroSpec.releaseMarkers` is used (paths relative to rootfs root). No issue.