package com.kiosko.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Kiosco web: muestra una única URL a pantalla completa, sin barras del sistema
 * y manteniendo la pantalla encendida.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KioscoWeb"

        const val PREFS = "kiosk_prefs"
        const val KEY_URL = "start_url"
        const val KEY_PIN = "admin_pin"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_ADVANCED_WAKE_LOCK = "advanced_wake_lock"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_LOCK_TASK = "lock_task"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_CONFIGURED = "configured"

        const val EXTRA_URL = "url"
        const val DEFAULT_PIN = "1234"

        private const val EXIT_TAPS = 7
        private const val EXIT_TAP_WINDOW_MS = 4000L
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val RETRY_DELAY_MS = 7000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offlinePanel: LinearLayout

    private var wakeLock: PowerManager.WakeLock? = null
    private var settingsDialog: AlertDialog? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var exitTapCount = 0
    private var lastExitTapAt = 0L

    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable {
        offlinePanel.visibility = View.GONE
        if (webView.url != null) webView.reload() else navigate(prefs.getString(KEY_URL, "").orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)
        progress = findViewById(R.id.progress)
        offlinePanel = findViewById(R.id.offline_panel)

        findViewById<Button>(R.id.retry_button).setOnClickListener {
            offlinePanel.visibility = View.GONE
            retryRunnable.run()
        }

        findViewById<View>(R.id.exit_hotspot).setOnClickListener { onHotspotTap() }

        applyOrientation()
        configureWebView()
        applyImmersiveMode()
        applyScreenAwake()

        val fromIntent = intentUrl(intent)
        if (fromIntent != null) {
            prefs.edit().putString(KEY_URL, fromIntent).putBoolean(KEY_CONFIGURED, true).apply()
            navigate(fromIntent)
        } else {
            navigate(prefs.getString(KEY_URL, "").orEmpty())
            if (!prefs.getBoolean(KEY_CONFIGURED, false)) showSettings(firstRun = true)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        val url = intentUrl(intent) ?: return
        prefs.edit().putString(KEY_URL, url).putBoolean(KEY_CONFIGURED, true).apply()
        navigate(url)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        applyScreenAwake()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { applyImmersiveMode() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
    }

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** Nunca cerramos la app con "atrás": solo navegamos dentro del sitio. */
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Compatibilidad con versiones anteriores")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    private fun intentUrl(intent: Intent?): String? {
        if (intent == null) return null
        val extra = intent.getStringExtra(EXTRA_URL)
        val data = if (intent.action == Intent.ACTION_VIEW) intent.data?.toString() else null
        val raw = extra ?: data ?: return null
        return normalizeUrl(raw)
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            trimmed.startsWith("file://", true) ||
            trimmed.startsWith("content://", true)
        ) trimmed else "https://$trimmed"
    }

    private fun navigate(url: String) {
        if (url.isBlank()) {
            showSettings(firstRun = true)
            return
        }
        webView.loadUrl(normalizeUrl(url))
    }

    // ---------------------------------------------------------------- pantalla

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun applyScreenAwake() {
        val keepOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (keepOn && prefs.getBoolean(KEY_ADVANCED_WAKE_LOCK, false)) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kiosko:screen"
        ).apply { setReferenceCounted(false) }
        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir el WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo liberar el WakeLock", e)
        }
        wakeLock = null
    }

    private fun applyOrientation() {
        requestedOrientation = when (prefs.getString(KEY_ORIENTATION, "sensor")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun applyKioskLock() {
        if (!prefs.getBoolean(KEY_LOCK_TASK, false)) return

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo habilitar LockTask como device owner", e)
            }
        }

        try {
            startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask falló", e)
            toast(getString(R.string.kiosk_lock_failed))
        }
    }

    private fun releaseKioskLock() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask falló", e)
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val debugging = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debugging)

        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString KioscoWeb/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = handleUrl(request?.url)

            @Deprecated("Compatibilidad API < 24")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleUrl(url?.let { Uri.parse(it) })

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                applyImmersiveMode()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) showOffline()
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Nunca ignoramos errores de certificado.
                handler?.cancel()
                showOffline()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val chooser = fileChooserParams?.createIntent() ?: return false
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                (window.decorView as ViewGroup).addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                applyImmersiveMode()
            }

            override fun onHideCustomView() {
                customView?.let { (window.decorView as ViewGroup).removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                applyImmersiveMode()
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue.orEmpty())
                    setSelection(text.length)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast(getString(R.string.download_started))
            } catch (e: Exception) {
                Log.w(TAG, "Descarga fallida", e)
                toast(getString(R.string.download_failed))
            }
        })
    }

    private fun handleUrl(uri: Uri?): Boolean {
        if (uri == null) return false
        return when (uri.scheme?.lowercase()) {
            "http", "https", "file", "content", "about", "data" -> false
            "intent" -> {
                try {
                    startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME))
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir intent", e)
                }
                true
            }
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir $uri", e)
                }
                true
            }
        }
    }

    private fun showOffline() {
        progress.visibility = View.GONE
        offlinePanel.visibility = View.VISIBLE
        retryHandler.removeCallbacks(retryRunnable)
        retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    @Deprecated("Compatibilidad con versiones anteriores")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            var results: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val clipData = data.clipData
                results = if (clipData != null && clipData.itemCount > 0) {
                    Array(clipData.itemCount) { clipData.getItemAt(it).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // --------------------------------------------------------------- interfaz

    private fun onHotspotTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastExitTapAt > EXIT_TAP_WINDOW_MS) exitTapCount = 0
        lastExitTapAt = now
        exitTapCount++
        if (exitTapCount >= EXIT_TAPS) {
            exitTapCount = 0
            requestPin()
        }
    }

    private fun requestPin() {
        val pin = prefs.getString(KEY_PIN, DEFAULT_PIN).orEmpty()
        if (pin.isEmpty()) {
            showSettings(firstRun = false)
            return
        }

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pin_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.accept) { _, _ ->
                if (input.text.toString() == pin) {
                    showSettings(firstRun = false)
                } else {
                    toast(getString(R.string.pin_error))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettings(firstRun: Boolean) {
        if (settingsDialog?.isShowing == true) return

        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val urlInput = view.findViewById<EditText>(R.id.url_input)
        val pinInput = view.findViewById<EditText>(R.id.pin_input)
        val keepScreenOn = view.findViewById<CheckBox>(R.id.keep_screen_on)
        val advancedWakeLock = view.findViewById<CheckBox>(R.id.advanced_wakelock)
        val autostart = view.findViewById<CheckBox>(R.id.autostart)
        val lockTask = view.findViewById<CheckBox>(R.id.lock_task)
        val orientation = view.findViewById<Spinner>(R.id.orientation_spinner)

        urlInput.setText(prefs.getString(KEY_URL, "").orEmpty())
        pinInput.setText(prefs.getString(KEY_PIN, DEFAULT_PIN).orEmpty())
        keepScreenOn.isChecked = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        advancedWakeLock.isChecked = prefs.getBoolean(KEY_ADVANCED_WAKE_LOCK, false)
        autostart.isChecked = prefs.getBoolean(KEY_AUTOSTART, false)
        lockTask.isChecked = prefs.getBoolean(KEY_LOCK_TASK, false)

        val values = resources.getStringArray(R.array.orientation_values)
        orientation.setSelection(
            values.indexOf(prefs.getString(KEY_ORIENTATION, "sensor")).coerceAtLeast(0)
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (firstRun) R.string.setup_title else R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.exit_app, null)
            .create()

        dialog.setCancelable(!firstRun)
        dialog.setCanceledOnTouchOutside(!firstRun)

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val rawUrl = urlInput.text.toString().trim()
                if (rawUrl.isEmpty()) {
                    urlInput.error = getString(R.string.url_required)
                    return@setOnClickListener
                }
                val url = normalizeUrl(rawUrl)

                prefs.edit()
                    .putString(KEY_URL, url)
                    .putString(KEY_PIN, pinInput.text.toString().trim())
                    .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn.isChecked)
                    .putBoolean(KEY_ADVANCED_WAKE_LOCK, advancedWakeLock.isChecked)
                    .putBoolean(KEY_AUTOSTART, autostart.isChecked)
                    .putBoolean(KEY_LOCK_TASK, lockTask.isChecked)
                    .putString(
                        KEY_ORIENTATION,
                        values.getOrElse(orientation.selectedItemPosition) { "sensor" }
                    )
                    .putBoolean(KEY_CONFIGURED, true)
                    .apply()

                applyOrientation()
                applyImmersiveMode()
                applyScreenAwake()
                if (lockTask.isChecked) applyKioskLock() else releaseKioskLock()

                dialog.dismiss()
                navigate(url)
            }

            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                releaseKioskLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finishAffinity()
                }
            }

            if (firstRun) {
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).visibility = View.GONE
            }
        }

        dialog.setOnDismissListener { settingsDialog = null }
        settingsDialog = dialog
        dialog.show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/** Receptor de administrador de dispositivo (opcional, para modo kiosco real). */
class KioskDeviceAdminReceiver : DeviceAdminReceiver()
