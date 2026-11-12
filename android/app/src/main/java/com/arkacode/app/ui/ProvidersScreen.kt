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
import androidx.compose.material3.TextButton
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.size
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
    var editProvider by remember { mutableStateOf<OpenCodeConfig.CustomProvider?>(null) }
    var keepSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanning by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<List<String>?>(null) }

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

    fun saveCustom(name: String, baseUrl: String, modelsCsv: String, apiKey: String, fixedId: String? = null) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val id = fixedId ?: OpenCodeConfig.makeId(name, OpenCodeConfig.existingIds(context, distroSpec))
                val models = modelsCsv.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                OpenCodeConfig.upsert(context, distroSpec, id, name, baseUrl, models, newKey = apiKey.ifBlank { null })
            }
            custom = withContext(Dispatchers.IO) { OpenCodeConfig.listCustom(context, distroSpec) }
            if (ok) {
                showAddCustom = false
                editProvider = null
                notice = I18n.s.customSavedNotice
            } else {
                notice = I18n.s.customSaveFailed
            }
        }
    }

    fun scanModels(baseUrl: String, key: String) {
        scanning = true
        scanResult = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val okClient = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val url = baseUrl.trim().removeSuffix("/") + "/models"
                    val builder = Request.Builder().url(url).get()
                    if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                    val resp = okClient.newCall(builder.build()).execute()
                    resp.use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        val body = r.body?.string() ?: ""
                        val out = mutableListOf<String>()
                        // Beberapa API OpenAI-compatible mengembalikan {"data": [...]}, yang lain langsung [...].
                        try {
                            val obj = org.json.JSONObject(body)
                            val data = obj.optJSONArray("data")
                            if (data != null) {
                                for (i in 0 until data.length()) {
                                    val el = data.opt(i)
                                    if (el is org.json.JSONObject) {
                                        el.optString("id").ifBlank { el.optString("name") }.ifBlank { null }?.let { out.add(it) }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Fallback: body adalah array langsung.
                            val modelsArr = org.json.JSONArray(body)
                            for (i in 0 until modelsArr.length()) {
                                val el = modelsArr.opt(i)
                                if (el is org.json.JSONObject) {
                                    el.optString("id").ifBlank { el.optString("name") }.ifBlank { null }?.let { out.add(it) }
                                }
                            }
                        }
                        out
                    }
                }.getOrNull()
            }
            scanning = false
            scanResult = result ?: emptyList()
        }
    }

    fun applyKeep() {
        scope.launch {
            val keep = keepSet.toMutableSet()
            val ok = withContext(Dispatchers.IO) {
                val toRemove = custom.filter { it.id !in keep }
                var allOk = true
                toRemove.forEach { p ->
                    val r = OpenCodeConfig.remove(context, distroSpec, p.id)
                    if (!r) allOk = false
                }
                allOk
            }
            custom = withContext(Dispatchers.IO) { OpenCodeConfig.listCustom(context, distroSpec) }
            notice = if (ok) I18n.s.customSavedNotice else I18n.s.customSaveFailed
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
                                TextButton(onClick = {
                                    keepSet = custom.map { it.id }.toSet()
                                    showAddCustom = false
                                }, enabled = custom.isNotEmpty()) {
                                    Text(I18n.s.customApply)
                                }
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
                                    val checked = keepSet.isEmpty() || p.id in keepSet
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            Modifier.clickable {
                                                if (keepSet.isEmpty()) keepSet = custom.map { it.id }.toSet()
                                                keepSet = if (checked) keepSet - p.id else keepSet + p.id
                                            },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (checked) "☑" else "☐",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (checked) MaterialTheme.colorScheme.tertiary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
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
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        TextButton(onClick = { editProvider = p }) { Text(I18n.s.customEdit) }
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
        CustomProviderDialog(
            key = OpenCodeConfig.CustomProvider("", "", "", emptyList(), false),
            onDismiss = { showAddCustom = false },
            onSave = { name, baseUrl, models, apiKey ->
                saveCustom(name, baseUrl, models, apiKey)
            },
            onScan = { baseUrl, key -> scanModels(baseUrl, key) },
            scanning = scanning,
            scanResult = scanResult,
        )
    }

    editProvider?.let { p ->
        CustomProviderDialog(
            key = p,
            onDismiss = { editProvider = null },
            onSave = { name, baseUrl, models, apiKey ->
                saveCustom(name, baseUrl, models, apiKey, fixedId = p.id)
            },
            onScan = { baseUrl, key -> scanModels(baseUrl, key) },
            scanning = scanning,
            scanResult = scanResult,
        )
    }

    if (keepSet.isNotEmpty() && keepSet.size < custom.size) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { applyKeep() }) {
                Text(I18n.s.customApply, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                I18n.s.customApplyHint(keepSet.size, custom.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { keepSet = custom.map { it.id }.toSet() }) {
                Text(I18n.s.customKeepAll)
            }
        }
    }
}

/** Form tambah/edit provider OpenAI-compatible (ditulis ke opencode.json server). */
@Composable
private fun CustomProviderDialog(
    key: OpenCodeConfig.CustomProvider,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, models: String, apiKey: String) -> Unit,
    onScan: (baseUrl: String, key: String) -> Unit,
    scanning: Boolean,
    scanResult: List<String>?,
) {
    val isEdit = key.id.isNotBlank()
    var name by remember(key) { mutableStateOf(key.name) }
    var baseUrl by remember(key) { mutableStateOf(key.baseUrl) }
    var models by remember(key) { mutableStateOf(key.models.joinToString(", ")) }
    var apiKey by remember(key) { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) I18n.s.customEditTitle else I18n.s.customProvidersTitle) },
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = models,
                        onValueChange = { models = it },
                        label = { Text(I18n.s.customModelsHintLabel) },
                        placeholder = { Text(I18n.s.customModelsHint) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onScan(baseUrl, apiKey) },
                        enabled = baseUrl.isNotBlank() && !scanning,
                    ) {
                        if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(I18n.s.customScan)
                    }
                }
                scanResult?.let { sr ->
                    if (sr.isNotEmpty()) {
                        TextButton(onClick = {
                            models = sr.joinToString(", ")
                        }) { Text(I18n.s.customScanApply(sr.size)) }
                    } else {
                        Text(
                            I18n.s.customScanEmpty,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
