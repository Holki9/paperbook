package com.holki9.paperbook

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var pendingPdfUri: Uri? = null
    private var isViewerReady: Boolean = false

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadPdfFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupFullscreen()
        setupWebView()

        // Check if opened via external intent (e.g. from Files or Telegram)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            intent.data?.let { uri ->
                loadPdfFromUri(uri)
            }
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = findViewById(R.id.webView)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val urlPath = request.url.path ?: ""
                if (urlPath.contains("opened_pdf/current.pdf")) {
                    val cacheFile = File(cacheDir, "current_book.pdf")
                    if (cacheFile.exists()) {
                        try {
                            val stream = FileInputStream(cacheFile)
                            val headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Content-Type" to "application/pdf"
                            )
                            return WebResourceResponse(
                                "application/pdf",
                                null,
                                200,
                                "OK",
                                headers,
                                stream
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("PaperBook", "Error reading cached PDF", e)
                        }
                    }
                }
                if (urlPath.contains("/library_books/")) {
                    val fileName = urlPath.substringAfterLast("/")
                    val bookFile = File(File(filesDir, "books"), fileName)
                    if (bookFile.exists()) {
                        try {
                            val stream = FileInputStream(bookFile)
                            val headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Content-Type" to "application/pdf"
                            )
                            return WebResourceResponse(
                                "application/pdf",
                                null,
                                200,
                                "OK",
                                headers,
                                stream
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("PaperBook", "Error reading library PDF", e)
                        }
                    }
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.d("PaperBookJS", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/viewer/index.html")
    }

    private fun loadPdfFromUri(uri: Uri) {
        if (!isViewerReady) {
            pendingPdfUri = uri
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var fileName = "Document.pdf"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                val bookId = "book_" + System.currentTimeMillis()
                val booksDir = File(filesDir, "books")
                if (!booksDir.exists()) {
                    booksDir.mkdirs()
                }

                val persistentFile = File(booksDir, "$bookId.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    persistentFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // Also update current_book.pdf in cache
                try {
                    val cacheFile = File(cacheDir, "current_book.pdf")
                    persistentFile.inputStream().use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {}

                withContext(Dispatchers.Main) {
                    val sanitizedName = fileName.replace("'", "\\'")
                    val bookUrl = "https://appassets.androidplatform.net/library_books/$bookId.pdf"
                    webView.evaluateJavascript(
                        "if (window.onBookAdded) { window.onBookAdded('$bookId', '$sanitizedName', '$bookUrl'); } else { window.loadPdfFromUrl('$bookUrl', '$sanitizedName'); }",
                        null
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PaperBook", "Error loading PDF from uri", e)
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun pickPdf() {
            runOnUiThread {
                openPdfLauncher.launch(arrayOf("application/pdf"))
            }
        }

        @JavascriptInterface
        fun deleteBook(bookId: String) {
            try {
                val bookFile = File(File(filesDir, "books"), "$bookId.pdf")
                if (bookFile.exists()) {
                    bookFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("PaperBook", "Error deleting book $bookId", e)
            }
        }

        @JavascriptInterface
        fun onViewerReady() {
            runOnUiThread {
                isViewerReady = true
                pendingPdfUri?.let { uri ->
                    pendingPdfUri = null
                    loadPdfFromUri(uri)
                }
            }
        }
    }
}
