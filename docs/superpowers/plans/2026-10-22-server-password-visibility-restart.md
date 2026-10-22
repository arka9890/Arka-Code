# Server Password: Lihat + Terapkan Langsung — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** User dapat melihat password server (toggle mata) dan menggantinya dari Settings agar langsung aktif di server yang berjalan (web login menerima nilai baru).

**Architecture:** SettingsScreen mendapat callback `onPasswordSaved` yang menyalakan balik (port, password) ke EngineScreen; EngineScreen memperbarui state `password` lalu memicu `restartEngine()` yang sudah ada (pipeline Boot menangani stop → start → health). Tanpa perubahan di SecureStore/EngineManager/OpenCodeClient.

**Tech Stack:** Kotlin, Jetpack Compose (material-icons-extended sudah ada di build.gradle.kts).

**Spec:** Desain yang disetujui user di sesi (Opsi A): (1) ikon mata di kolom password, (2) Save → update state + restart otomatis.

## Global Constraints

- Bangun HANYA via CI GitHub Actions `workflow_dispatch` (workflow id `392020412`) — tidak ada JDK lokal; CI = satu-satunya compile check.
- Commit author: `-c user.name=arkacode -c user.email=arkacode@local`.
- Pesan commit bahasa Indonesia, gaya repo (conventional prefix `feat:`/`fix:`).
- I18n: semua string baru punya pasangan ID + EN (constructor data class I18n menolak kompilasi tanpanya).

## Review Focus

- Save TANPA perubahan nilai tidak boleh restart server (pemborosan & memutus sesi chat) — dijaga oleh cek `changed` di langkah 3.
- Restart saat server jalan harus memakai kredensial BARU: state `password`/`port` di EngineScreen wajib diperbarui SEBELUM `onRestartEngine()` dipanggil — urutan dijamin di langkah 3 (callback dipanggil lebih dulu).
- Kolom password lain (IntegrationsSheet/API key, L318) tidak boleh ikut tersentuh — hanya kolom password server di kartu Settings Server.

---

### Task 1: Toggle mata + save-terapkan-langsung

**Files:**
- Modify: `android/app/src/main/java/com/arkacode/app/ui/I18n.kt` (property ~L39, blok ID ~L274, blok EN ~L498)
- Modify: `android/app/src/main/java/com/arkacode/app/ui/SettingsScreen.kt` (params ~L54-65, state ~L71-72, field password L172-179, tombol Save L181-188)
- Modify: `android/app/src/main/java/com/arkacode/app/ui/EngineScreen.kt` (panggilan SettingsScreen L337-355)
- Test: compile check via CI (tidak ada harness unit test di repo ini)

**Interfaces:**
- Produces: `SettingsScreen(..., onPasswordSaved: ((Int, String) -> Unit)? = null)` — dipanggil SETELAH SecureStore.save berhasil, dengan nilai (port, password) yang baru disimpan.
- Consumes: `EngineScreen.restartEngine()` (sudah ada, L291-293) via param `onRestartEngine`.

- [ ] **Step 1: Tambah string I18n `showPassword`/`hidePassword`**

Properti baru di dekat `serverPassword`; ID: `"Tampilkan password"` / `"Sembunyikan password"`; EN: `"Show password"` / `"Hide password"`.

- [ ] **Step 2: SettingsScreen — state `pwVisible` + ikon mata**

Import `androidx.compose.material.icons.filled.Visibility`/`VisibilityOff` dan `VisualTransformation`. State `pwVisible` di samping `currentPassword`. Kolom password: `visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation()` + `trailingIcon = IconButton { Icon(if (pwVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (pwVisible) I18n.s.hidePassword else I18n.s.showPassword) }`.

- [ ] **Step 3: SettingsScreen — param `onPasswordSaved` + alur Save**

Param baru `onPasswordSaved: ((Int, String) -> Unit)? = null`. Tombol Save setelah `SecureStore.save`:
```kotlin
val changed = currentPort != port || currentPassword != password
notice = I18n.s.serverSaved
if (changed) {
    onPasswordSaved?.invoke(currentPort, currentPassword)
    onRestartEngine?.invoke()
}
```

- [ ] **Step 4: EngineScreen — salakan callback**

Di panggilan `SettingsScreen` (L337): tambah `onPasswordSaved = { p, pw -> port = p; password = pw }`.

- [ ] **Step 5: Commit, push, dispatch CI**

`git add` ketiga file; commit `feat(settings): toggle lihat password + ganti langsung aktif (restart server)`; push; `POST .../dispatches` workflow `392020412`; poll sampai hijau.
