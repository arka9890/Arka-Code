package com.arkacode.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkacode.app.core.DistroSpec
import com.arkacode.app.core.OpenCodeConfig
import com.arkacode.app.core.SecureStore
import com.arkacode.app.net.ModelInfo
import com.arkacode.app.net.OpenCodeClient
import com.arkacode.app.net.ProviderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nama env yang dibaca opencode per provider (sama dengan EngineManager). */
private val ENV_HINTS = mapOf(
    "openai" to listOf("OPENAI_API_KEY"),
    "opencode" to listOf("OPENCODE_API_KEY"),
    "anthropic" to listOf("ANTHROPIC_API_KEY"),
    "google" to listOf("GOOGLE_GENERATIVE_AI_API_KEY", "GEMINI_API_KEY"),
    "groq" to listOf("GROQ_API_KEY"),
    "openrouter" to listOf("OPENROUTER_API_KEY"),
    "mistral" to listOf("MISTRAL_API_KEY"),
    "deepseek" to listOf("DEEPSEEK_API_KEY"),
    "xai" to listOf("XAI_API_KEY"),
)

/**
 * Layar Penyedia AI (F2+): daftar provider + model dari server, isi API key
 * per provider (tersimpan terenkripsi, disuntik sebagai env saat serve nyala).
 * Berlaku setelah Stop → Start ulang server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    port: Int,
    password: String,
    distroSpec: DistroSpec,
    onBack: () -> Unit,
    onRestartEngine: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(port, password) { OpenCodeClient(port = port, password = password) }

    var providers by remember { mutableStateOf<List<ProviderInfo>?>(null) }
    var models by remember { mutableStateOf(listOf<ModelInfo>()) }
    var saved by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showIntegrations by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(listOf<OpenCodeConfig.CustomProvider>()) }
    var distroReady by remember { mutableStateOf(true) }
    var showAddCustom by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            val ps = runCatching {
                withContext(Dispatchers.IO) { client.listProviders() }
            }.onFailure { error = it.message }.getOrNull()
            providers = ps
            models = runCatching {
                withContext(Dispatchers.IO) { client.listModels() }
            }.getOrDefault(emptyList())
            saved = withContext(Dispatchers.IO) {
                // tandai provider yang sudah punya kunci tersimpan
                (ps ?: emptyList()).mapNotNull { p ->
                    val has = runCatching { SecureStore.getAuth(context, p.id) }.getOrNull()
                    if (!has.isNullOrBlank()) p.id else null
                }.toSet()
            }
            distroReady = withContext(Dispatchers.IO) { OpenCodeConfig.isAvailable(context, distroSpec) }
            custom = withContext(Dispatchers.IO) {
                runCatching { OpenCodeConfig.listCustom(context, distroSpec) }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    fun saveCustom(name: String, baseUrl: String, modelsCsv: String, apiKey: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val id = OpenCodeConfig.makeId(name, OpenCodeConfig.existingIds(context, distroSpec))
                val models = modelsCsv.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                OpenCodeConfig.upsert(context, distroSpec, id, name, baseUrl, models, newKey = apiKey.ifBlank { null })
            }
            custom = withContext(Dispatchers.IO) { OpenCodeConfig.listCustom(context, distroSpec) }
            if (ok) {
                showAddCustom = false
                notice = I18n.s.customSavedNotice
            } else {
                notice = I18n.s.customSaveFailed
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            ClaySurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                elevation = 10.dp,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.s.back)
                    }
                    Text(
                        I18n.s.providersTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClayCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Text(
                    I18n.s.providersHelp,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            ClayCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showIntegrations = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            I18n.s.integrationsTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            I18n.s.integrationsOpen,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›")
                }
            }
            error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { error = null }) { Text(I18n.s.close) }
                }
            }
            notice?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { Text(I18n.s.close) }
                }
            }
            if (loading && providers == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text(I18n.s.loading, style = MaterialTheme.typography.bodySmall)
                }
            }
            val list = providers ?: emptyList()
            val byProvider = models.groupBy { it.providerID }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "custom-providers") {
                    ClayCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    I18n.s.customProvidersTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { showAddCustom = true }, enabled = distroReady) {
                                    Text(I18n.s.customAdd)
                                }
                            }
                            Text(
                                I18n.s.customProvidersHelp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!distroReady) {
                                Text(
                                    I18n.s.customNeedDistro,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (custom.isEmpty()) {
                                Text(
                                    I18n.s.customEmpty,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                custom.forEach { p ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                p.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                if (p.models.isEmpty()) p.baseUrl
                                                else "${p.baseUrl}  ·  ${I18n.s.modelsCount(p.models.size)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        if (p.hasKey) {
                                            Text("🔑", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = {
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    OpenCodeConfig.remove(context, distroSpec, p.id)
                                                }
                                                custom = withContext(Dispatchers.IO) {
                                                    OpenCodeConfig.listCustom(context, distroSpec)
                                                }
                                                notice = if (ok) I18n.s.customDeletedNotice(p.name)
                                                else I18n.s.customSaveFailed
                                            }
                                        }) { Text(I18n.s.deleteKey, color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }
                items(list.distinctBy { it.id }, key = { it.id }) { p ->
                    val ms = byProvider[p.id] ?: emptyList()
                    ClayCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (p.id in expanded) expanded - p.id else expanded + p.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (p.id in saved) {
                                    Text("●", color = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(I18n.s.modelsCount(ms.size), style = MaterialTheme.typography.labelSmall)
                            }
                            if (p.id in expanded) {
                                Spacer(Modifier.height(6.dp))
                                val envs = ENV_HINTS[p.id]
                                if (envs != null) {
                                    Text(
                                        I18n.s.envFmt(envs.joinToString(", ")),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = drafts[p.id] ?: "",
                                        onValueChange = { drafts = drafts + (p.id to it) },
                                        placeholder = { Text(if (p.id in saved) I18n.s.savedHintReplace else I18n.s.pasteKey) },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            val v = drafts[p.id] ?: ""
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    SecureStore.setAuth(context, p.id, v)
                                                }
                                                drafts = drafts - p.id
                                                saved = if (v.isBlank()) saved - p.id else saved + p.id
                                                notice = if (v.isBlank()) I18n.s.keyDeleted(p.name) else I18n.s.keySaved(p.name)
                                            }
                                        }) { Text(I18n.s.saveKey) }
                                        TextButton(onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    SecureStore.setAuth(context, p.id, "")
                                                }
                                                drafts = drafts - p.id
                                                saved = saved - p.id
                                                notice = I18n.s.keyDeleted(p.name)
                                            }
                                        }) { Text(I18n.s.deleteKey, color = MaterialTheme.colorScheme.error) }
                                    }
                                } else {
                                    Text(
                                        I18n.s.viaJson,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (ms.isNotEmpty()) {
                                    Text(I18n.s.modelsLabel, style = MaterialTheme.typography.labelMedium)
                                    ms.distinctBy { m -> m.id }.take(30).forEach { m ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                m.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            if (m.free) {
                                                Text(
                                                    I18n.s.free,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                )
                                            }
                                        }
                                    }
                                    if (ms.size > 30) {
                                        Text(I18n.s.othersCount(ms.size - 30), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showIntegrations) {
        IntegrationsSheet(
            port = port,
            password = password,
            onDismiss = { showIntegrations = false },
            onRestartEngine = onRestartEngine,
        )
    }

    if (showAddCustom) {
        AddCustomProviderDialog(
            onDismiss = { showAddCustom = false },
            onSave = { name, baseUrl, models, apiKey -> saveCustom(name, baseUrl, models, apiKey) },
        )
    }
}

/** Form tambah provider OpenAI-compatible (ditulis ke opencode.json server). */
@Composable
private fun AddCustomProviderDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, models: String, apiKey: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.s.customProvidersTitle) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(I18n.s.customNameLabel) },
                    placeholder = { Text("LM Studio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(I18n.s.customBaseUrlLabel) },
                    placeholder = { Text("http://192.168.1.2:1234/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    label = { Text(I18n.s.customModelsHintLabel) },
                    placeholder = { Text(I18n.s.customModelsHint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(I18n.s.customApiKeyLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                err?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || baseUrl.isBlank()) err = I18n.s.customInvalid
                else onSave(name, baseUrl, models, apiKey)
            }) { Text(I18n.s.saveKey) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.s.close) }
        },
    )
}
