# SDD ledger — plan: docs/superpowers/plans/2026-11-17-tool-containers.md

Spec: docs/superpowers/specs/2026-11-17-tool-containers-design.md

- Task 1: complete (commits bab99bd..df091c1, review clean — DONE_WITH_CONCERNS karena tidak ada build lokal; sesuai kebijakan user: build di GitHub Actions CI)
- Task 2: complete (commits 5a4f3ac..94caf77, review clean) — catatan: ensureShForTermuxLayout belum ada pemanggil; Task 5 harus memanggilnya saat distro aktif TERMUX_PREFIX.
- Task 3: complete (commit 5b85534 — ContainerShim.script/shimPath + 3 tes; review clean) — catatan: tes ditulis tapi belum dijalankan (build di CI).
- Task 4: complete (commit a0981d6 — ContainerManager + 3 tes; review clean) — catatan: tes ditulis tapi belum dijalankan (build di CI); pemanggil produksi di Task 5 (bind/PATH) & Task 6 (UI).