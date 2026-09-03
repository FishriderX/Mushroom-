package com.example.pikminhelper.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.pikminhelper.HelperPrefs
import com.example.pikminhelper.RunMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.Executor

class PikminAccessibilityService : AccessibilityService() {

    private lateinit var prefs: HelperPrefs
    private val handler = Handler(Looper.getMainLooper())
    private val executor: Executor by lazy { mainExecutor }
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var busy = false
    private var nextActionAt = 0L
    private var listSwipeCount = 0
    private var lastListSeenAt = 0L

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
        RunMode.WATCH -> 2_500L
        RunMode.RACE -> 350L
    }

    private fun tryScan() {
        if (!prefs.enabled || busy) return
        if (SystemClock.elapsedRealtime() < nextActionAt) return
        if (prefs.pauseLowBattery && batteryPct() < 20) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PIKMIN_PACKAGE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        busy = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()

                    if (bitmap == null) {
                        busy = false
                        return
                    }

                    analyze(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    busy = false
                }
            }
        )
    }

    private fun analyze(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                handleScreen(bitmap, text)
            }
            .addOnFailureListener {
                // OCR failure should never cause a blind tap.
            }
            .addOnCompleteListener {
                bitmap.recycle()
                busy = false
            }
    }

    private fun handleScreen(bitmap: Bitmap, result: Text) {
        if (!prefs.enabled) return

        val lines = result.textBlocks.flatMap { it.lines }
        val normalized = clean(result.text)

        // 1) Hard safety stop: never buy mushroom tickets automatically.
        if (
            normalized.contains("沒有蘑菇儲值券") ||
            normalized.contains("需要蘑菇儲值券") ||
            normalized.contains("新增票券")
        ) {
            findLine(lines, "關閉")?.let {
                tapLine(it, cooldownMs = 900)
            } ?: run {
                // The close button is centered near the lower part of the modal.
                verifiedTap(bitmap.width * 0.50f, bitmap.height * 0.70f, 900)
            }
            return
        }

        // 2) Pikmin selection screen.
        if (normalized.contains("選擇派出皮克敏")) {
            val selectedFullTeam = normalized.contains("40/40")

            if (selectedFullTeam) {
                findLine(lines, "GO")?.let {
                    tapLine(it, cooldownMs = 1_000)
                } ?: run {
                    // Only use this fallback after the screen is positively verified
                    // and a full 40/40 team is visible.
                    verifiedTap(bitmap.width * 0.86f, bitmap.height * 0.91f, 1_000)
                }
            } else {
                findLine(lines, "自動")?.let {
                    tapLine(it, cooldownMs = 700)
                }
            }
            return
        }

        // 3) Mushroom detail page. The title + 參加 button are enough to verify it.
        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            val desired = isDesiredMushroom(normalized)

            if (desired) {
                findLine(lines, "參加")?.let {
                    tapLine(it, cooldownMs = 900)
                }
            } else {
                // We opened a non-target mushroom while scouting. Go back instead
                // of guessing at any screen coordinate.
                goBack(700)
            }
            return
        }

        // 4) Exploration mushroom list.
        if (isMushroomList(normalized)) {
            lastListSeenAt = SystemClock.elapsedRealtime()

            val target = chooseListTarget(lines)
            if (target != null) {
                listSwipeCount = 0
                tapLine(target, cooldownMs = 800)
                return
            }

            // No target is visible yet. Swipe the horizontal mushroom carousel a
            // few times so the helper can inspect more nearby mushrooms.
            if (listSwipeCount < MAX_LIST_SWIPES) {
                swipeHorizontal(
                    fromX = bitmap.width * 0.82f,
                    toX = bitmap.width * 0.22f,
                    y = bitmap.height * 0.69f,
                    durationMs = 280,
                    cooldownMs = 650
                )
                listSwipeCount++
            } else {
                // Keep the screen stable after one pass. A future refresh will be
                // OCR'd again and can immediately trigger RACE mode behavior.
                listSwipeCount = 0
                nextActionAt = SystemClock.elapsedRealtime() + 2_000L
            }
            return
        }

        // 5) Main Pikmin screen. If the helper sees the 探險 tab, open it so the
        // exact mushroom names can be read instead of guessing from icons.
        findExactLine(lines, "探險")?.let {
            listSwipeCount = 0
            tapLine(it, cooldownMs = 850)
            return
        }

        // 6) GPS / bird's-eye map. We intentionally use the map only as an entry
        // point. The exact "巨大" priority is verified in the exploration list.
        // This is much safer than classifying a mushroom by map artwork alone.
        if (looksLikeGpsMap(bitmap, normalized)) {
            goBack(800)
            return
        }
    }

    private fun isMushroomList(normalized: String): Boolean {
        return normalized.contains("今天還剩下") && normalized.contains("蘑菇")
    }

    private fun chooseListTarget(lines: List<Text.Line>): Text.Line? {
        val weekend = isWeekend()

        if (weekend) {
            lines.firstOrNull { clean(it.text).contains("巨大") }?.let { return it }

            lines.firstOrNull {
                val s = clean(it.text)
                isEventText(s)
            }?.let { return it }

            return null
        }

        // On weekdays preserve the original "join nearby mushrooms" behavior.
        // Prefer event mushrooms if visible, otherwise any mushroom title line.
        lines.firstOrNull { isEventText(clean(it.text)) }?.let { return it }

        return lines.firstOrNull {
            val s = clean(it.text)
            s.contains("蘑菇") && !s.contains("今天還剩下")
        }
    }

    private fun isDesiredMushroom(normalized: String): Boolean {
        if (!isWeekend()) return true
        if (normalized.contains("巨大")) return true
        return isEventText(normalized)
    }

    private fun isEventText(text: String): Boolean {
        val s = clean(text)
        return s.contains("華麗蘑菇") ||
            s.contains("活動蘑菇") ||
            s.contains("特殊活動")
    }

    private fun isWeekend(): Boolean {
        return when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> true
            else -> false
        }
    }

    private fun findLine(lines: List<Text.Line>, needle: String): Text.Line? {
        val n = clean(needle)
        return lines.firstOrNull { clean(it.text).contains(n) }
    }

    private fun findExactLine(lines: List<Text.Line>, needle: String): Text.Line? {
        val n = clean(needle)
        return lines.firstOrNull { clean(it.text) == n }
    }

    private fun clean(value: String): String {
        return value.replace(Regex("\\s+"), "")
    }

    private fun tapLine(line: Text.Line, cooldownMs: Long) {
        val box = line.boundingBox ?: return
        verifiedTap(box.exactCenterX(), box.exactCenterY(), cooldownMs)
    }

    private fun verifiedTap(x: Float, y: Float, cooldownMs: Long) {
        if (!prefs.enabled) return

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
            .build()

        dispatchGesture(gesture, null, null)
        nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
    }

    private fun swipeHorizontal(
        fromX: Float,
        toX: Float,
        y: Float,
        durationMs: Long,
        cooldownMs: Long
    ) {
        if (!prefs.enabled) return

        val path = Path().apply {
            moveTo(fromX, y)
            lineTo(toX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, null, null)
        nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
    }

    private fun goBack(cooldownMs: Long) {
        if (!prefs.enabled) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
    }

    private fun looksLikeGpsMap(bitmap: Bitmap, normalized: String): Boolean {
        if (normalized.contains("鳥瞰風景")) return true

        // Cheap sampled green-ratio test. It is only reached after all known
        // Pikmin screens above failed, so it cannot trigger a blind action on the
        // mushroom list, detail page, or team screen.
        var green = 0
        var sampled = 0
        val step = 24
        val top = (bitmap.height * 0.08f).toInt()
        val bottom = (bitmap.height * 0.83f).toInt()

        var y = top
        while (y < bottom) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                if (g > r * 1.08f && g > b * 1.05f && g > 90) green++
                sampled++
                x += step
            }
            y += step
        }

        return sampled > 0 && green.toFloat() / sampled > 0.38f
    }

    private fun batteryPct(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer.close()
        super.onDestroy()
    }

    companion object {
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"
        private const val MAX_LIST_SWIPES = 6
    }
}
