package com.example.pikminhelper.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.pikminhelper.HelperPrefs
import com.example.pikminhelper.RunMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class PikminAccessibilityService : AccessibilityService() {

    private lateinit var prefs: HelperPrefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private val imageThread = HandlerThread("MushroomHelperImage")
    private lateinit var imageHandler: Handler
    private lateinit var imageExecutor: Executor

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val ocrBusy = AtomicBoolean(false)

    @Volatile private var nextActionAt = 0L
    @Volatile private var lastOcrAt = 0L
    @Volatile private var ocrPauseUntil = 0L
    @Volatile private var listSwipeCount = 0
    @Volatile private var consecutiveOcrFailures = 0

    private val loop = object : Runnable {
        override fun run() {
            scanOnce()
            mainHandler.postDelayed(this, nodeIntervalMs())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = HelperPrefs(this)

        if (!imageThread.isAlive) imageThread.start()
        imageHandler = Handler(imageThread.looper)
        imageExecutor = Executor { command -> imageHandler.post(command) }

        mainHandler.removeCallbacks(loop)
        mainHandler.post(loop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized || !prefs.enabled) return
        if (event?.packageName?.toString() != PIKMIN_PACKAGE) return

        // Event-driven node checks are cheap and remove the need for high-rate OCR.
        if (SystemClock.elapsedRealtime() >= nextActionAt) {
            mainHandler.removeCallbacks(loop)
            mainHandler.post(loop)
        }
    }

    override fun onInterrupt() = Unit

    private fun nodeIntervalMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 5_000L
        RunMode.WATCH -> 700L
        RunMode.RACE -> 220L
    }

    private fun ocrIntervalMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 30_000L
        RunMode.WATCH -> 3_000L
        RunMode.RACE -> 900L
    }

    private fun scanOnce() {
        if (!prefs.enabled) return
        if (SystemClock.elapsedRealtime() < nextActionAt) return
        if (prefs.pauseLowBattery && batteryPct() < 20) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PIKMIN_PACKAGE) return

        // First choice: programmatic UI detection. This is fast and allocates no bitmap.
        if (handleAccessibilityTree(root)) return

        // Only use OCR when Pikmin's Unity UI did not expose enough semantic nodes.
        requestOcrFallback()
    }

    private data class NodeEntry(
        val node: AccessibilityNodeInfo,
        val text: String,
        val bounds: Rect
    )

    private fun handleAccessibilityTree(root: AccessibilityNodeInfo): Boolean {
        val entries = collectNodes(root)
        if (entries.isEmpty()) return false

        val normalized = clean(entries.joinToString(" ") { it.text })

        // Safety modal. Never buy or confirm a mushroom ticket.
        if (containsTicketWarning(normalized)) {
            findNode(entries, "關閉")?.let {
                clickNode(it.node, 900)
                return true
            }
            goBack(900)
            return true
        }

        // Team selection: auto-select, then GO once the team is full.
        if (normalized.contains("選擇派出皮克敏")) {
            if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
                findNode(entries, "GO")?.let {
                    clickNode(it.node, 1_000)
                    return true
                }
            }

            findNode(entries, "自動")?.let {
                clickNode(it.node, 700)
                return true
            }
            return true
        }

        // Mushroom detail screen.
        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            if (isDesiredMushroom(normalized)) {
                findNode(entries, "參加")?.let {
                    clickNode(it.node, 900)
                }
            } else {
                goBack(700)
            }
            return true
        }

        // Exploration mushroom list: weekend = GIANT first, EVENT second.
        if (isMushroomList(normalized)) {
            val target = chooseNodeTarget(entries)
            if (target != null) {
                listSwipeCount = 0
                clickNode(target.node, 800)
                return true
            }

            if (listSwipeCount < MAX_LIST_SWIPES) {
                swipeList(650)
                listSwipeCount++
            } else {
                listSwipeCount = 0
                nextActionAt = SystemClock.elapsedRealtime() + 2_000L
            }
            return true
        }

        // If the GPS/map screen exposes mushroom semantics, use them directly.
        // Otherwise move to the exploration list, where names are reliable.
        if (looksLikeMapNodes(entries, normalized)) {
            val semanticTarget = chooseMapSemanticTarget(entries)
            if (semanticTarget != null) {
                clickNode(semanticTarget.node, 850)
                return true
            }

            findExactNode(entries, "探險")?.let {
                listSwipeCount = 0
                clickNode(it.node, 850)
                return true
            }
        }

        // Main screen path into exploration.
        findExactNode(entries, "探險")?.let {
            listSwipeCount = 0
            clickNode(it.node, 850)
            return true
        }

        return false
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<NodeEntry> {
        val out = ArrayList<NodeEntry>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODE_VISITS) {
            val node = queue.removeFirst()
            visited++

            val label = clean(
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .joinToString(" ")
            )

            if (label.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                out.add(NodeEntry(node, label, rect))
            }

            val childCount = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
            for (i in 0 until childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }

        return out
    }

    private fun chooseNodeTarget(entries: List<NodeEntry>): NodeEntry? {
        if (isWeekend()) {
            entries.firstOrNull { clean(it.text).contains("巨大") }?.let { return it }
            entries.firstOrNull { isEventText(it.text) }?.let { return it }
            return null
        }

        entries.firstOrNull { isEventText(it.text) }?.let { return it }
        return entries.firstOrNull {
            val s = clean(it.text)
            s.contains("蘑菇") && !s.contains("今天還剩下")
        }
    }

    private fun chooseMapSemanticTarget(entries: List<NodeEntry>): NodeEntry? {
        if (isWeekend()) {
            entries.firstOrNull { clean(it.text).contains("巨大") }?.let { return it }
            entries.firstOrNull { isEventText(it.text) }?.let { return it }
            return null
        }

        entries.firstOrNull { isEventText(it.text) }?.let { return it }
        return null
    }

    private fun looksLikeMapNodes(entries: List<NodeEntry>, normalized: String): Boolean {
        if (normalized.contains("鳥瞰風景")) return true
        if (entries.any { clean(it.text) == "探險" } && !isMushroomList(normalized)) return true
        return entries.any {
            val s = clean(it.text)
            (s.contains("巨大") || isEventText(s)) && s.contains("蘑菇")
        }
    }

    private fun findNode(entries: List<NodeEntry>, needle: String): NodeEntry? {
        val n = clean(needle)
        return entries.firstOrNull { clean(it.text).contains(n) }
    }

    private fun findExactNode(entries: List<NodeEntry>, needle: String): NodeEntry? {
        val n = clean(needle)
        return entries.firstOrNull { clean(it.text) == n }
    }

    private fun clickNode(node: AccessibilityNodeInfo, cooldownMs: Long) {
        if (!prefs.enabled) return

        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
                return
            }
            current = candidate.parent
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) verifiedTap(rect.exactCenterX(), rect.exactCenterY(), cooldownMs)
    }

    private fun requestOcrFallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val now = SystemClock.elapsedRealtime()
        if (now < ocrPauseUntil) return
        if (now - lastOcrAt < ocrIntervalMs()) return
        if (!ocrBusy.compareAndSet(false, true)) return

        lastOcrAt = now
        val startedAt = now

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            imageExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    val original = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    hw.close()

                    if (original == null) {
                        finishOcrFailure()
                        return
                    }

                    val frame = prepareOcrFrame(original)
                    recognize(frame, startedAt)
                }

                override fun onFailure(errorCode: Int) {
                    finishOcrFailure()
                }
            }
        )
    }

    private data class OcrFrame(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float
    )

    private fun prepareOcrFrame(original: Bitmap): OcrFrame {
        if (original.width <= OCR_MAX_WIDTH) {
            return OcrFrame(original, 1f, 1f)
        }

        val targetWidth = OCR_MAX_WIDTH
        val targetHeight = (original.height * (targetWidth.toFloat() / original.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
        val scaleX = original.width.toFloat() / scaled.width
        val scaleY = original.height.toFloat() / scaled.height
        original.recycle()
        return OcrFrame(scaled, scaleX, scaleY)
    }

    private fun recognize(frame: OcrFrame, startedAt: Long) {
        val image = InputImage.fromBitmap(frame.bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener(imageExecutor) { result ->
                consecutiveOcrFailures = 0
                handleOcrScreen(frame, result)
            }
            .addOnFailureListener(imageExecutor) {
                consecutiveOcrFailures++
            }
            .addOnCompleteListener(imageExecutor) {
                frame.bitmap.recycle()

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed > SLOW_OCR_MS) {
                    ocrPauseUntil = SystemClock.elapsedRealtime() + slowOcrBackoffMs()
                }

                if (consecutiveOcrFailures >= MAX_OCR_FAILURES) {
                    ocrPauseUntil = SystemClock.elapsedRealtime() + OCR_FAILURE_BACKOFF_MS
                    consecutiveOcrFailures = 0
                }

                ocrBusy.set(false)
            }
    }

    private fun finishOcrFailure() {
        consecutiveOcrFailures++
        if (consecutiveOcrFailures >= MAX_OCR_FAILURES) {
            ocrPauseUntil = SystemClock.elapsedRealtime() + OCR_FAILURE_BACKOFF_MS
            consecutiveOcrFailures = 0
        }
        ocrBusy.set(false)
    }

    private fun slowOcrBackoffMs(): Long = when (prefs.mode) {
        RunMode.RACE -> 500L
        RunMode.WATCH -> 1_500L
        RunMode.ECO -> 5_000L
    }

    private fun handleOcrScreen(frame: OcrFrame, result: Text) {
        if (!prefs.enabled) return

        val lines = result.textBlocks.flatMap { it.lines }
        val normalized = clean(result.text)

        if (containsTicketWarning(normalized)) {
            findOcrLine(lines, "關閉")?.let {
                tapOcrLine(frame, it, 900)
            } ?: goBack(900)
            return
        }

        if (normalized.contains("選擇派出皮克敏")) {
            if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
                findOcrLine(lines, "GO")?.let {
                    tapOcrLine(frame, it, 1_000)
                    return
                }
            }

            findOcrLine(lines, "自動")?.let {
                tapOcrLine(frame, it, 700)
            }
            return
        }

        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            if (isDesiredMushroom(normalized)) {
                findOcrLine(lines, "參加")?.let {
                    tapOcrLine(frame, it, 900)
                }
            } else {
                goBack(700)
            }
            return
        }

        if (isMushroomList(normalized)) {
            val target = chooseOcrTarget(lines)
            if (target != null) {
                listSwipeCount = 0
                tapOcrLine(frame, target, 800)
                return
            }

            if (listSwipeCount < MAX_LIST_SWIPES) {
                swipeList(650)
                listSwipeCount++
            } else {
                listSwipeCount = 0
                nextActionAt = SystemClock.elapsedRealtime() + 2_000L
            }
            return
        }

        // On GPS/main screens use OCR only to find the navigation label; do not
        // classify the whole map image continuously.
        findExactOcrLine(lines, "探險")?.let {
            listSwipeCount = 0
            tapOcrLine(frame, it, 850)
        }
    }

    private fun chooseOcrTarget(lines: List<Text.Line>): Text.Line? {
        if (isWeekend()) {
            lines.firstOrNull { clean(it.text).contains("巨大") }?.let { return it }
            lines.firstOrNull { isEventText(it.text) }?.let { return it }
            return null
        }

        lines.firstOrNull { isEventText(it.text) }?.let { return it }
        return lines.firstOrNull {
            val s = clean(it.text)
            s.contains("蘑菇") && !s.contains("今天還剩下")
        }
    }

    private fun findOcrLine(lines: List<Text.Line>, needle: String): Text.Line? {
        val n = clean(needle)
        return lines.firstOrNull { clean(it.text).contains(n) }
    }

    private fun findExactOcrLine(lines: List<Text.Line>, needle: String): Text.Line? {
        val n = clean(needle)
        return lines.firstOrNull { clean(it.text) == n }
    }

    private fun tapOcrLine(frame: OcrFrame, line: Text.Line, cooldownMs: Long) {
        val box = line.boundingBox ?: return
        verifiedTap(
            box.exactCenterX() * frame.scaleX,
            box.exactCenterY() * frame.scaleY,
            cooldownMs
        )
    }

    private fun containsTicketWarning(normalized: String): Boolean {
        return normalized.contains("沒有蘑菇儲值券") ||
            normalized.contains("需要蘑菇儲值券") ||
            normalized.contains("新增票券") ||
            normalized.contains("蘑菇儲值券不足")
    }

    private fun isMushroomList(normalized: String): Boolean {
        return normalized.contains("今天還剩下") && normalized.contains("蘑菇")
    }

    private fun isDesiredMushroom(normalized: String): Boolean {
        if (!isWeekend()) return true
        if (normalized.contains("巨大")) return true
        return isEventText(normalized)
    }

    private fun isEventText(value: String): Boolean {
        val s = clean(value)
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

    private fun clean(value: String): String {
        return value.replace(Regex("\\s+"), "")
    }

    private fun swipeList(cooldownMs: Long) {
        val dm = resources.displayMetrics
        swipeHorizontal(
            fromX = dm.widthPixels * 0.82f,
            toX = dm.widthPixels * 0.22f,
            y = dm.heightPixels * 0.69f,
            durationMs = 280,
            cooldownMs = cooldownMs
        )
    }

    private fun verifiedTap(x: Float, y: Float, cooldownMs: Long) {
        if (!prefs.enabled) return

        mainHandler.post {
            if (!prefs.enabled) return@post
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
                .build()
            dispatchGesture(gesture, null, null)
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
        }
    }

    private fun swipeHorizontal(
        fromX: Float,
        toX: Float,
        y: Float,
        durationMs: Long,
        cooldownMs: Long
    ) {
        if (!prefs.enabled) return

        mainHandler.post {
            if (!prefs.enabled) return@post
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
    }

    private fun goBack(cooldownMs: Long) {
        if (!prefs.enabled) return
        mainHandler.post {
            if (!prefs.enabled) return@post
            performGlobalAction(GLOBAL_ACTION_BACK)
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
        }
    }

    private fun batteryPct(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        ocrBusy.set(false)
        recognizer.close()
        if (imageThread.isAlive) imageThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"
        private const val MAX_LIST_SWIPES = 6
        private const val MAX_NODE_VISITS = 180
        private const val MAX_CHILDREN_PER_NODE = 40
        private const val OCR_MAX_WIDTH = 1080
        private const val SLOW_OCR_MS = 700L
        private const val MAX_OCR_FAILURES = 3
        private const val OCR_FAILURE_BACKOFF_MS = 5_000L
        private val FULL_TEAM_REGEX = Regex("(\\d{1,3})/\\1")
    }
}
