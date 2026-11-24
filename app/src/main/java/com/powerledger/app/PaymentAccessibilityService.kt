package com.powerledger.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 仿照钱迹思路的多窗口、事件驱动无障碍自动记账服务：
 * - 支持多 App 适配器扩展（AdapterRegistry），当前首发适配微信
 * - 多窗口遍历（windows + rootInActiveWindow），且不因父容器 isVisibleToUser=false 截断子节点
 * - 窗口切换时仅安排一次 250ms 延迟复扫等待文字渲染，平时零轮询极省电
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastContentScanMs = 0L
    private var delayedScanRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        PendingQueueStore.appendDebugLog(applicationContext, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PendingQueueStore.isAutoEnabled(applicationContext)) return
        val pkg = event?.packageName?.toString() ?: return
        val adapter = AdapterRegistry.get(pkg) ?: return

        val now = System.currentTimeMillis()
        val isWindowSwitch = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isWindowSwitch) {
            lastContentScanMs = 0L
        } else {
            if (now - lastContentScanMs < 350L) return
            lastContentScanMs = now
        }

        val matched = scanPackageWindows(pkg, adapter, event.source, isWindowSwitch)
        if (!matched && isWindowSwitch) {
            // 微信部分页面在窗口切换后约 150~250ms 才渲染出文字，仅安排一次延迟复扫（非轮询）
            delayedScanRunnable?.let { mainHandler.removeCallbacks(it) }
            val retry = Runnable {
                scanPackageWindows(pkg, adapter, null, logIfUnmatched = true)
            }
            delayedScanRunnable = retry
            mainHandler.postDelayed(retry, 250L)
        }
    }

    override fun onInterrupt() {
        delayedScanRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedScanRunnable = null
    }

    private fun scanPackageWindows(
        pkg: String,
        adapter: AppBillAdapter,
        eventSource: AccessibilityNodeInfo?,
        logIfUnmatched: Boolean
    ): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>(4)

        // 1. 优先从多窗口列表获取目标包名的所有窗口（含弹窗/覆盖层）
        try {
            windows?.forEach { win ->
                val r = win.root
                if (r != null && r.packageName?.toString() == pkg) {
                    roots.add(r)
                }
            }
        } catch (_: Exception) {
        }

        // 2. 兜底加入当前活动窗口与事件源
        rootInActiveWindow?.let { r ->
            if (r.packageName?.toString() == pkg && roots.none { it == r }) {
                roots.add(r)
            }
        }
        if (roots.isEmpty() && eventSource != null) {
            roots.add(eventSource)
        }

        if (roots.isEmpty()) {
            if (logIfUnmatched) PendingQueueStore.appendDebugLog(applicationContext, "[$pkg] 未获取到窗口")
            return false
        }

        val texts = ArrayList<String>(64)
        for (root in roots) {
            collectTexts(root, texts, maxNodes = 300)
        }

        if (texts.isEmpty()) {
            if (logIfUnmatched) PendingQueueStore.appendDebugLog(applicationContext, "[$pkg] 文本为空(请检查系统支付保护)")
            return false
        }

        val parsed = adapter.parse(texts)
        if (parsed == null) {
            if (logIfUnmatched) {
                PendingQueueStore.appendDebugLog(
                    applicationContext,
                    "未命中: ${texts.take(5).joinToString("|").take(46)}"
                )
            }
            return false
        }

        val record = ExpenseRecord(
            amount = parsed.amount,
            merchant = parsed.merchant,
            category = parsed.category,
            channel = parsed.channel,
            timestamp = System.currentTimeMillis(),
            dedupKey = parsed.dedupKey
        )

        val added = PendingQueueStore.enqueueIfNew(applicationContext, record)
        PendingQueueStore.appendDebugLog(
            applicationContext,
            if (added) "入账: ${record.merchant} -${record.formattedAmount()} [${record.category}]"
            else "防重跳过: ${record.merchant} -${record.formattedAmount()}"
        )
        if (added) {
            FloatingReceiptCard.show(applicationContext, record)
        }
        return true
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, maxNodes: Int) {
        if (out.size >= maxNodes) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            out.add(t)
        } else {
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty()) {
                out.add(desc)
            }
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (out.size >= maxNodes) break
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, maxNodes)
        }
    }
}
