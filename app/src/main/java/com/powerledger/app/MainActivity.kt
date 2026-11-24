package com.powerledger.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyExcludeFromRecents(PendingQueueStore.isExcludeFromRecents(applicationContext))

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val statusBarSpacer = View(this).apply {
            setBackgroundColor(Color.parseColor("#10B981"))
        }
        val navBarSpacer = View(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        webView = WebView(this)

        rootLayout.addView(statusBarSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        rootLayout.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        rootLayout.addView(navBarSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarSpacer.layoutParams = statusBarSpacer.layoutParams.apply { height = bars.top }
            navBarSpacer.layoutParams = navBarSpacer.layoutParams.apply { height = bars.bottom }
            rootLayout.setPadding(bars.left, 0, bars.right, 0)
            insets
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.addJavascriptInterface(PowerLedgerBridge(), "PowerLedgerNative")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
            webView.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null)
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun applyExcludeFromRecents(exclude: Boolean) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appTasks?.firstOrNull()?.setExcludeFromRecents(exclude)
        } catch (_: Exception) {
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (enabled != 1) return false
        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val target = ComponentName(this, PaymentAccessibilityService::class.java)
        return services.split(":").any { ComponentName.unflattenFromString(it) == target }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    inner class PowerLedgerBridge {
        @JavascriptInterface
        fun pullPendingRecords(): String = PendingQueueStore.pullAndClearAll(applicationContext)

        @JavascriptInterface
        fun getDebugLogs(): String = PendingQueueStore.getDebugLogs(applicationContext)

        @JavascriptInterface
        fun getAutoSettings(): String {
            return JSONObject().apply {
                put("enabled", PendingQueueStore.isAutoEnabled(applicationContext))
                put("hasOverlay", Settings.canDrawOverlays(this@MainActivity))
                put("hasA11y", isAccessibilityEnabled())
                put("ignoringBattery", isIgnoringBatteryOptimizations())
                put("excludeRecents", PendingQueueStore.isExcludeFromRecents(applicationContext))
            }.toString()
        }

        @JavascriptInterface
        fun setAutoEnabled(enabled: Boolean) {
            PendingQueueStore.setAutoEnabled(applicationContext, enabled)
        }

        @JavascriptInterface
        fun setExcludeFromRecents(exclude: Boolean) {
            PendingQueueStore.setExcludeFromRecents(applicationContext, exclude)
            runOnUiThread { applyExcludeFromRecents(exclude) }
        }

        @JavascriptInterface
        fun openOverlaySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun openBatteryOptimizationSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        }
    }
}
