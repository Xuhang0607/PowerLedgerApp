package com.powerledger.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 流体云风格短暂浮动卡片：
 * 仅在捕获新支出且已授权 SYSTEM_ALERT_WINDOW 时创建，3.5 秒后自动销毁并从 WindowManager 移除，
 * 平时不占用任何后台渲染资源。
 */
object FloatingReceiptCard {

    private const val DISPLAY_DURATION_MS = 3500L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null

    fun show(context: Context, record: ExpenseRecord) {
        if (!Settings.canDrawOverlays(context)) return

        mainHandler.post {
            val appContext = context.applicationContext
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return@post

            removeCurrentView(windowManager)

            val density = appContext.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(12), dp(10))
                elevation = dp(10).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(Color.parseColor("#EE14161B"))
                    setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                }
            }

            val dotView = View(appContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#10B981"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(10)
                }
            }

            val summaryText = TextView(appContext).apply {
                text = if (record.isRefund) {
                    "已冲账 +${record.formattedAmount()} 元 · ${record.merchant} [退款]"
                } else {
                    "已记 -${record.formattedAmount()} 元 · ${record.merchant} [${record.category}]"
                }
                setTextColor(Color.parseColor("#F9FAFB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = dp(10)
                }
            }

            val undoButton = TextView(appContext).apply {
                text = "撤销"
                setTextColor(Color.parseColor("#FCA5A5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#26EF4444"))
                }
                setOnClickListener {
                    PendingQueueStore.removeById(appContext, record.id)
                    removeCurrentView(windowManager)
                    Toast.makeText(appContext, "已撤销该笔自动记账", Toast.LENGTH_SHORT).show()
                }
            }

            container.addView(dotView)
            container.addView(summaryText)
            container.addView(undoButton)

            val maxWidth = (appContext.resources.displayMetrics.widthPixels * 0.92).toInt()
            val params = WindowManager.LayoutParams(
                maxWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(42)
            }

            try {
                container.alpha = 0f
                windowManager.addView(container, params)
                container.animate().alpha(1f).setDuration(180L).start()
                currentView = container

                val runnable = Runnable { removeCurrentView(windowManager) }
                dismissRunnable = runnable
                mainHandler.postDelayed(runnable, DISPLAY_DURATION_MS)
            } catch (_: Exception) {
                currentView = null
            }
        }
    }

    private fun removeCurrentView(windowManager: WindowManager) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        currentView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                }
            } catch (_: Exception) {
            }
        }
        currentView = null
    }
}
