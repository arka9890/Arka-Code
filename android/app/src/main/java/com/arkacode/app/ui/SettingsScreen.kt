package com.arkacode.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.EngineManager
import com.arkacode.app.core.EngineRelease
import com.arkacode.app.core.EngineService
import com.arkacode.app.core.GitCredentials
import com.arkacode.app.core.SecureStore
import com.arkacode.app.core.SessionUiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    port: Int,
    password: String,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onStop: (() -> Unit)? = null,
    onShowIntegrations: (() -> Unit)? = null,
    onOpenDistro: (() -> Unit)? = null,
    engine: EngineManager? = null,
    onRestartEngine: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiPrefs = remember { SessionUiPrefs(context) }
    val eng = engine ?: remember { EngineManager(context) }

    var currentPort by remember { mutableStateOf(port) }
    var currentPassword by remember { mutableStateOf(password) }
    var gitPat by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var showIntegrationsSheet by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf<String?>(null) }
    var autoUpdate by remember { mutableStateOf(true) }
    var engineBusy by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<EngineManager.Update?>(null) }
    var engineMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { SecureStore.getGitPat(context) }.getOrNull()?.let { gitPat = it }
            runCatching { uiPrefs.autoUpdateEnabled() }.getOrNull()?.let { autoUpdate = it }
            runCatching { eng.version() }.getOrNull()?.let { engineVersion = it }
        }
    }

    if (showIntegrationsSheet) {
        IntegrationsSheet(
            port = currentPort,
            password = currentPassword,
            onDismiss = { showIntegrationsSheet = false },
            onRestartEngine = onRestartEngine,
        )
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ClayHeader(
                title = I18n.s.navSettings,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.s.back)
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notice?.let {
                    ClayCard(modifier = Modifier.fillMaxWidth()) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                        TextButton(onClick = { notice = null }) { Text(I18n.s.close) }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.settingsLanguage, fontWeight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = {
                                I18n.lang = AppLang.ID
                                scope.launch { runCatching { uiPrefs.setLangCode(I18n.lang.code) } }
                            }) { Text("ID") }
                            TextButton(onClick = {
                                I18n.lang = AppLang.EN
                                scope.launch { runCatching { uiPrefs.setLangCode(I18n.lang.code) } }
                            }) { Text("EN") }
                            Spacer(Modifier.weight(1f))
                            Text(if (I18n.lang == AppLang.ID) "Bahasa Indonesia" else "English", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.settingsTheme, fontWeight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = onToggleTheme) { Text(if (isDark) "\u2600\uFE0F" else "\uD83C\uDF19") }
                            Spacer(Modifier.weight(1f))
                            Text(if (isDark) I18n.s.themeDark else I18n.s.themeLight, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.settingsServer, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = currentPort.toString(),
                            onValueChange = { v -> currentPort = v.filter(Char::isDigit).take(5).toIntOrNull() ?: currentPort },
                            label = { Text(I18n.s.port) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = currentPassword,
                            onValueChange = { currentPassword = it },
                            label = { Text(I18n.s.serverPassword) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        SecureStore.save(context, currentPort, currentPassword)
                                    }
                                    notice = I18n.s.serverSaved
                                }
                            }) { Text(I18n.s.saveKey) }
                            onStop?.let { stop -> TextButton(onClick = stop) { Text(I18n.s.stop) } }
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(I18n.s.settingsEngine, fontWeight = FontWeight.SemiBold)
                        Text(
                            engineVersion?.let { I18n.s.engineInstalled(EngineRelease.bare(it)) }
                                ?: I18n.s.engineNotInstalled,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.s.engineAutoUpdate, modifier = Modifier.weight(1f))
                            Switch(
                                checked = autoUpdate,
                                enabled = !engineBusy,
                                onCheckedChange = { v ->
                                    autoUpdate = v
                                    scope.launch { runCatching { uiPrefs.setAutoUpdateEnabled(v) } }
                                },
                            )
                        }
                        Text(
                            I18n.s.engineAutoUpdateHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        engineMsg?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        engineBusy = true
                                        engineMsg = null
                                        val u = withContext(Dispatchers.IO) { eng.checkUpdate() }
                                        engineBusy = false
                                        if (u == null) {
                                            engineMsg = I18n.s.engineCheckFailed
                                        } else {
                                            updateInfo = u
                                            engineVersion = u.current
                                            engineMsg = if (u.hasUpdate) {
                                                I18n.s.engineUpdateAvailable(
                                                    EngineRelease.bare(u.latest),
                                                    EngineRelease.bare(u.current ?: "?"),
                                                )
                                            } else {
                                                I18n.s.engineUpToDate(EngineRelease.bare(u.latest))
                                            }
                                        }
                                    }
                                },
                                enabled = !engineBusy,
                            ) { Text(if (engineBusy) I18n.s.engineChecking else I18n.s.engineCheck) }
                            if (updateInfo?.hasUpdate == true) {
                                TextButton(
                                    onClick = {
                                        updateInfo?.let { u ->
                                            scope.launch {
                                                engineBusy = true
                                                engineMsg = I18n.s.engineUpdating
                                                val r = withContext(Dispatchers.IO) {
                                                    eng.stopServer()
                                                    EngineService.stop(context)
                                                    eng.applyUpdate(u)
                                                }
                                                engineBusy = false
                                                r.onSuccess { v ->
                                                    engineVersion = v
                                                    updateInfo = null
                                                    engineMsg = I18n.s.engineUpdated(EngineRelease.bare(v))
                                                    onStop?.invoke()
                                                }.onFailure { e ->
                                                    engineMsg = I18n.s.engineUpdateFailed(e.message ?: "?")
                                                }
                                            }
                                        }
                                    },
                                    enabled = !engineBusy,
                                ) { Text(I18n.s.engineUpdateNow) }
                            }
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.navDistro, fontWeight = FontWeight.SemiBold)
                        Text(
                            I18n.s.distroSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onOpenDistro?.invoke() }) {
                            Text(I18n.s.distroOpen)
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.settingsIntegrations, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { onShowIntegrations?.invoke() ?: run { showIntegrationsSheet = true } }) {
                            Text(I18n.s.integrationsOpen)
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.settingsGitPat, fontWeight = FontWeight.SemiBold)
                        Text(
                            I18n.s.gitPatHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = gitPat,
                            onValueChange = { gitPat = it },
                            placeholder = { Text(I18n.s.gitPatPlaceholder) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        SecureStore.setGitPat(context, gitPat)
                                        GitCredentials.sync(context, gitPat, eng.distroSpec)
                                    }
                                    notice = I18n.s.gitPatSaved
                                }
                            }) { Text(I18n.s.saveKey) }
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        SecureStore.setGitPat(context, "")
                                        GitCredentials.sync(context, "", eng.distroSpec)
                                    }
                                    gitPat = ""
                                    notice = I18n.s.gitPatDeleted
                                }
                            }) { Text(I18n.s.deleteKey, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }

                ClayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.s.settingsAbout, fontWeight = FontWeight.SemiBold)
                        Text("Arkacode", style = MaterialTheme.typography.bodySmall)
                        Text("android/app @ Arkacode", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
