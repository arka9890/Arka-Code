package com.arkacode.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arkacode.app.core.ArenaImport
import com.arkacode.app.core.SessionUiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ARENA_URL = "https://arena.ai/"

/**
 * Pembuka Arena AI.
 *
 * Website Arena AI diproteksi reCAPTCHA Enterprise yang memakai Storage Access
 * API — API itu ditolak oleh WebView bawaan (pesan `requestStorageAccess:
 * Permission denied`), sehingga isi halaman tak pernah muncul (layar kosong).
 * Chrome (Custom Tab) melewati proteksi itu dengan normal.
 *
 * Karena itu Arena dibuka lewat Chrome Custom Tab (tetap di dalam aplikasi,
 * berbagi cookie/login Chrome). Bila tak ada browser yang mendukung, baru
 * jatuh ke WebView bawaan [ArenaScreenActivity] sebagai cadangan.
 */
object ArenaLauncher {
    const val URL = ARENA_URL

    fun open(context: Context) {
        val uri = Uri.parse(URL)
        val launched = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
                .launchUrl(context, uri)
        }.isSuccess
        if (launched) return

        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (opened) return

        runCatching { context.startActivity(Intent(context, ArenaScreenActivity::class.java)) }
            .onFailure {
                Toast.makeText(context, "Tidak ada browser terpasang", Toast.LENGTH_LONG).show()
            }
    }
}

/**
 * Layar WebView bawaan untuk https://arena.ai/.
 *
 * - Muat website Arena AI asli (tanpa API key, login lewat browser).
 * - Cookie & localStorage disimpan internal WebView → sesi login tetap
 *   walau aplikasi ditutup dan dibuka lagi.
 * - Ada indikator muat + status galat dengan tombol Coba lagi / Buka di
 *   browser, supaya layar tak pernah tinggal kosong tanpa kejelasan.
 * - Unduhan yang lewat lewat WebView langsung disimpan ke proot workspace
 *   oleh [com.arkacode.app.core.ArenaImport].
 *
 * Catatan: jalur normal Arena adalah Custom Tab (lihat [ArenaLauncher]), jadi
 * listener di sini jarang sekali terpanggil. Untuk memasukkan berkas, pakai
 * tombol unggah di panel Files.
 */
class ArenaScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sama seperti MainActivity: baca tema tersimpan (default ikut sistem)
        // agar Arena AI ikut mode gelap, bukan selalu skema terang default.
        setContent {
            val systemDark = isSystemInDarkTheme()
            var themeOverride by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                themeOverride = withContext(Dispatchers.IO) {
                    runCatching { SessionUiPrefs(this@ArenaScreenActivity).themeMode() }.getOrNull()
                }
            }
            val dark = themeOverride?.let { it == "dark" } ?: systemDark
            ArkacodeTheme(darkTheme = dark) {
                ArenaWebView(onClose = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArenaWebView(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var lastUrl by remember { mutableStateOf(ARENA_URL) }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // Biarkan UA bawaan WebView (sesuai versi engine asli) agar
                // proteksi bot/CDN tidak menolak UA palsu yang tak cocok.
                userAgentString = userAgentString.replace("; wv", "")
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                setCacheMode(WebSettings.LOAD_DEFAULT)
            }

            // Cookie agar login Arena AI tidak hilang saat tutup-buka aplikasi.
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            cookieManager.flush()

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    lastUrl = request.url.toString()
                    return false
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    lastUrl = url ?: lastUrl
                    loading = true
                    error = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorRes: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        loading = false
                        error = "Gagal memuat Arena AI: ${errorRes.description}"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        loading = false
                        error = "Server Arena AI membalas HTTP ${errorResponse.statusCode}"
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    error = "Proses tampilan WebView berhenti. Tekan Coba lagi."
                    loading = false
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.i(
                        "ArenaWV",
                        "${msg.messageLevel()} ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                    )
                    return true
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                    if (newProgress >= 100) loading = false
                }
            }

            setDownloadListener(object : android.webkit.DownloadListener {                override fun onDownloadStart(
                    url: String,
                    userAgent: String?,
                    contentDisposition: String?,
                    mimeType: String?,
                    contentLength: Long
                ) {
                    val fileName = ArenaImport.fileNameFrom(contentDisposition)
                        ?: Uri.parse(url).lastPathSegment
                        ?: "arena-download"

                    // Unduh langsung ke workspace proot. DownloadManager tidak bisa
                    // dipakai: Android 8–10 menolak membaca /Download tanpa izin
                    // penyimpanan eksternal, dan jalurnya butuh impor manual.
                    scope.launch {
                        Toast.makeText(context, "Mengunduh: $fileName", Toast.LENGTH_SHORT).show()
                        runCatching { ArenaImport.saveToWorkspace(context, url, fileName) }
                            .onSuccess {
                                Toast.makeText(
                                    context,
                                    "Tersimpan ke workspace: ${it.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    "Gagal mengunduh $fileName: ${it.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }
            })

            loadUrl(ARENA_URL)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(I18n.s.arenaTitle) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            error = null
                            loading = true
                            webView.reload()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val err = error
            if (err != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = {
                            error = null
                            loading = true
                            webView.loadUrl(lastUrl)
                        },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Coba lagi")
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(lastUrl))
                                )
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Tidak ada browser terpasang",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Text("Buka di browser", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
