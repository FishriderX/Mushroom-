package com.example.pikminhelper.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.pikminhelper.HelperPrefs
import com.example.pikminhelper.RunMode
import java.util.concurrent.Executor

class PikminAccessibilityService : AccessibilityService() {
    private lateinit var prefs: HelperPrefs
    private val handler = Handler(Looper.getMainLooper())
    private val executor: Executor by lazy { mainExecutor }
    private var state = AutomationState.IDLE
    private var busy = false

    private val loop = object : Runnable {
        override fun run() {
            tryScan()
            handler.postDelayed(this, intervalMs())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = HelperPrefs(this)
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun intervalMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 60_000L
        RunMode.WATCH -> 3_000L
        RunMode.RACE -> 350L
    }

    private fun tryScan() {
        if (!prefs.enabled || busy) return
        if (prefs.pauseLowBattery && batteryPct() < 20) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PIKMIN_PACKAGE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()
                    if (bitmap != null) analyze(bitmap)
                } finally {
                    busy = false
                }
            }
            override fun onFailure(errorCode: Int) { busy = false }
        })
    }

    private fun analyze(bitmap: Bitmap) {
        when (state) {
            AutomationState.IDLE -> state = AutomationState.LOOKING_FOR_MUSHROOM
            AutomationState.LOOKING_FOR_MUSHROOM -> {
                // Mushroom detector hook. Detected candidates should be sent to
                // MushroomPolicy.chooseTarget(candidates). On weekends it chooses
                // GIANT first, EVENT only when no GIANT candidate exists.
            }
            else -> Unit
        }
    }

    private fun tap(x: Float, y: Float) {
        if (!prefs.enabled) return
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun batteryPct(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object { private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin" }
}
