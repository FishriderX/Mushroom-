package com.example.pikminhelper.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.max
import kotlin.math.min

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
    @Volatile private var forceAdvanceOnList = false
    @Volatile private var backOutStepsRemaining = 0
    @Volatile private var autoTapAttempts = 0
    @Volatile private var joinTapAttempts = 0

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
        RunMode.WATCH -> 2_000L
        RunMode.RACE -> 850L
    }

    private fun scanOnce() {
        if (!prefs.enabled) return
        if (SystemClock.elapsedRealtime() < nextActionAt) return
        if (prefs.pauseLowBattery && batteryPct() < 20) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PIKMIN_PACKAGE) return

        if (handleAccessibilityTree(root)) return
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

        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {
            backOutStepsRemaining = 2
            forceAdvanceOnList = true
            autoTapAttempts = 0
            joinTapAttempts = 0

            findNode(entries, "關閉")?.let {
                clickNode(it.node, 850)
                return true
            }
            goBack(850)
            return true
        }

        if (backOutStepsRemaining > 0) {
            if (isMushroomList(normalized)) {
                backOutStepsRemaining = 0
                forceAdvanceOnList = true
                return false
            }

            backOutStepsRemaining--
            goBack(650)
            return true
        }

        if (normalized.contains("選擇派出皮克敏")) {
            joinTapAttempts = 0

            if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
                autoTapAttempts = 0

                findNode(entries, "GO")?.let {
                    clickNode(it.node, 900)
                    return true
                }

                tapVerifiedSelectionButton(SelectionButton.GO, 900)
                return true
            }

            autoTapAttempts++
            if (autoTapAttempts > MAX_AUTO_TAP_ATTEMPTS) {
                startBackOutToNextTarget(2)
                return true
            }

            findNode(entries, "自動")?.let {
                clickNode(it.node, 650)
                return true
            }

            tapVerifiedSelectionButton(SelectionButton.AUTO, 650)
            return true
        }

        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            autoTapAttempts = 0

            val semanticCount = extractParticipantCountFromText(normalized)
            if (semanticCount != null && semanticCount >= FREE_SLOT_LIMIT) {
                rejectDetailAndAdvance()
                return true
            }

            if (semanticCount == null) return false

            if (!isDesiredMushroom(normalized)) {
                rejectDetailAndAdvance()
                return true
            }

            joinTapAttempts++
            if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
                startBackOutToNextTarget(1)
                return true
            }

            findNode(entries, "參加")?.let {
                clickNode(it.node, 800)
                return true
            }

            return false
        }

        if (isMushroomList(normalized)) {
            return false
        }

        findExactNode(entries, "探險")?.let {
            listSwipeCount = 0
            clickNode(it.node, 800)
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
        if (!rect.isEmpty) {
            verifiedTap(rect.exactCenterX(), rect.exactCenterY(), cooldownMs)
        }
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
        RunMode.RACE -> 450L
        RunMode.WATCH -> 1_500L
        RunMode.ECO -> 5_000L
    }

    private fun handleOcrScreen(frame: OcrFrame, result: Text) {
        if (!prefs.enabled) return

        val lines = result.textBlocks.flatMap { it.lines }
        val normalized = clean(result.text)

        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {
            backOutStepsRemaining = 2
            forceAdvanceOnList = true
            autoTapAttempts = 0
            joinTapAttempts = 0

            findOcrLine(lines, "關閉")?.let {
                tapOcrLine(frame, it, 850)
            } ?: goBack(850)
            return
        }

        if (backOutStepsRemaining > 0) {
            if (isMushroomList(normalized)) {
                backOutStepsRemaining = 0
                forceAdvanceOnList = true
            } else {
                backOutStepsRemaining--
                goBack(650)
                return
            }
        }

        if (normalized.contains("選擇派出皮克敏")) {
            joinTapAttempts = 0

            if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
                autoTapAttempts = 0
                findOcrLine(lines, "GO")?.let {
                    tapOcrButton(frame, it, 900)
                } ?: tapVerifiedSelectionButton(SelectionButton.GO, 900)
                return
            }

            autoTapAttempts++
            if (autoTapAttempts > MAX_AUTO_TAP_ATTEMPTS) {
                startBackOutToNextTarget(2)
                return
            }

            findOcrLine(lines, "自動")?.let {
                tapOcrButton(frame, it, 650)
            } ?: tapVerifiedSelectionButton(SelectionButton.AUTO, 650)
            return
        }

        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            autoTapAttempts = 0

            if (!isDesiredMushroom(normalized)) {
                rejectDetailAndAdvance()
                return
            }

            val participantCount =
                extractParticipantCountFromText(normalized)
                    ?: estimateDetailParticipantCount(frame.bitmap, lines)

            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                rejectDetailAndAdvance()
                return
            }

            joinTapAttempts++
            if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
                startBackOutToNextTarget(1)
                return
            }

            findOcrLine(lines, "參加")?.let {
                tapOcrButton(frame, it, 800)
            }
            return
        }

        if (isMushroomList(normalized)) {
            autoTapAttempts = 0
            joinTapAttempts = 0

            if (forceAdvanceOnList) {
                forceAdvanceOnList = false
                listSwipeCount++
                swipeListReliable(700)
                return
            }

            val target = chooseOcrTarget(lines)
            if (target != null) {
                val participantCount = estimateListParticipantCount(frame.bitmap, target)

                if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                    listSwipeCount++
                    swipeListReliable(700)
                    return
                }

                listSwipeCount = 0
                tapOcrButton(frame, target, 800)
                return
            }

            if (listSwipeCount < MAX_LIST_SWIPES) {
                listSwipeCount++
                swipeListReliable(700)
            } else {
                listSwipeCount = 0
                nextActionAt = SystemClock.elapsedRealtime() + 1_800L
            }
            return
        }

        findExactOcrLine(lines, "探險")?.let {
            listSwipeCount = 0
            tapOcrButton(frame, it, 800)
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
            s.contains("蘑菇") &&
                !s.contains("今天還剩下") &&
                !s.contains("蘑菇儲值券")
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

    private fun tapOcrButton(frame: OcrFrame, line: Text.Line, cooldownMs: Long) {
        val box = line.boundingBox ?: return
        val x = box.exactCenterX() * frame.scaleX
        val y = box.exactCenterY() * frame.scaleY
        verifiedTap(x, y, cooldownMs)
    }

    private enum class SelectionButton { AUTO, GO }

    private fun tapVerifiedSelectionButton(button: SelectionButton, cooldownMs: Long) {
        val dm = resources.displayMetrics
        when (button) {
            SelectionButton.AUTO -> {
                verifiedTap(
                    dm.widthPixels * 0.245f,
                    dm.heightPixels * 0.405f,
                    cooldownMs
                )
            }
            SelectionButton.GO -> {
                verifiedTap(
                    dm.widthPixels * 0.865f,
                    dm.heightPixels * 0.905f,
                    cooldownMs
                )
            }
        }
    }

    private fun extractParticipantCountFromText(value: String): Int? {
        val s = clean(value)

        PARTICIPANT_REGEXES.forEach { regex ->
            val match = regex.find(s) ?: return@forEach
            val count = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (count != null && count in 0..99) return count
        }

        return null
    }

    private fun estimateListParticipantCount(bitmap: Bitmap, titleLine: Text.Line): Int? {
        val box = titleLine.boundingBox ?: return null

        val left = max(0, box.left - (bitmap.width * 0.015f).toInt())
        val right = min(bitmap.width, box.left + (bitmap.width * 0.62f).toInt())
        val top = min(bitmap.height - 1, box.bottom + (bitmap.height * 0.025f).toInt())
        val bottom = min(bitmap.height, box.bottom + (bitmap.height * 0.095f).toInt())

        if (right <= left || bottom <= top) return null

        return countColorClusters(bitmap, left, top, right, bottom)
            ?.takeIf { it in 1..8 }
    }

    private fun estimateDetailParticipantCount(
        bitmap: Bitmap,
        lines: List<Text.Line>
    ): Int? {
        val left = (bitmap.width * 0.04f).toInt()
        val right = (bitmap.width * 0.79f).toInt()
        val top = (bitmap.height * 0.82f).toInt()
        val bottom = (bitmap.height * 0.94f).toInt()

        countColorClusters(bitmap, left, top, right, bottom)?.let {
            if (it in 1..8) return it
        }

        val nameXs = mutableListOf<Int>()
        val minY = (bitmap.height * 0.86f).toInt()
        val maxY = (bitmap.height * 0.995f).toInt()

        lines.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            if (box.centerY() !in minY..maxY) return@forEach

            val s = clean(line.text)
            if (s.isEmpty()) return@forEach
            if (s.any { it.isDigit() }) return@forEach
            if (s.length > 20) return@forEach
            if (
                s.contains("前往這裡") ||
                s.contains("參加") ||
                s.contains("蘑菇") ||
                s.contains("關閉")
            ) return@forEach

            nameXs.add(box.centerX())
        }

        if (nameXs.isEmpty()) return null
        nameXs.sort()

        var groups = 0
        var last = Int.MIN_VALUE
        val minGap = max(20, bitmap.width / 14)

        for (x in nameXs) {
            if (last == Int.MIN_VALUE || x - last >= minGap) {
                groups++
                last = x
            }
        }

        return groups.takeIf { it in 1..8 }
    }

    private fun countColorClusters(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int? {
        if (left < 0 || top < 0 || right > bitmap.width || bottom > bitmap.height) return null
        if (right - left < 30 || bottom - top < 20) return null

        val width = right - left
        val columnScore = IntArray(width)
        val yStep = 3

        var x = left
        while (x < right) {
            var y = top
            var score = 0

            while (y < bottom) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val hi = max(r, max(g, b))
                val lo = min(r, min(g, b))

                if (hi > 65 && hi - lo > 28 && !(r > 235 && g > 235 && b > 235)) {
                    score++
                }
                y += yStep
            }

            columnScore[x - left] = score
            x += 2
        }

        val threshold = max(3, (bottom - top) / 30)
        var groups = 0
        var inGroup = false
        var groupStart = 0
        var gap = 0
        val minGroupWidth = max(10, bitmap.width / 90)
        val maxGap = max(4, bitmap.width / 270)

        var i = 0
        while (i < width) {
            val active = columnScore[i] >= threshold

            if (active) {
                if (!inGroup) {
                    inGroup = true
                    groupStart = i
                }
                gap = 0
            } else if (inGroup) {
                gap++
                if (gap > maxGap) {
                    val groupWidth = i - gap - groupStart
                    if (groupWidth >= minGroupWidth) groups++
                    inGroup = false
                    gap = 0
                }
            }

            i++
        }

        if (inGroup) {
            val groupWidth = width - groupStart
            if (groupWidth >= minGroupWidth) groups++
        }

        return groups.takeIf { it > 0 }
    }

    private fun containsTicketWarning(normalized: String): Boolean {
        return normalized.contains("沒有蘑菇儲值券") ||
            normalized.contains("需要蘑菇儲值券") ||
            normalized.contains("新增票券") ||
            normalized.contains("蘑菇儲值券不足")
    }

    private fun containsJoinFailure(normalized: String): Boolean {
        return normalized.contains("人數已滿") ||
            normalized.contains("已達上限") ||
            normalized.contains("無法參加") ||
            normalized.contains("無法加入") ||
            normalized.contains("參加人數已滿")
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

    private fun rejectDetailAndAdvance() {
        forceAdvanceOnList = true
        joinTapAttempts = 0
        autoTapAttempts = 0
        goBack(650)
    }

    private fun startBackOutToNextTarget(steps: Int) {
        forceAdvanceOnList = true
        backOutStepsRemaining = steps.coerceAtLeast(1)
        joinTapAttempts = 0
        autoTapAttempts = 0
        backOutStepsRemaining--
        goBack(650)
    }

    private fun swipeListReliable(cooldownMs: Long) {
        if (!prefs.enabled) return

        mainHandler.post {
            if (!prefs.enabled) return@post

            val root = rootInActiveWindow
            val scrollable = root?.let { findListScrollable(it) }

            if (scrollable != null &&
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
                return@post
            }

            val dm = resources.displayMetrics
            val yRatio = if (listSwipeCount % 2 == 0) 0.70f else 0.75f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.80f, dm.heightPixels * yRatio)
                lineTo(dm.widthPixels * 0.18f, dm.heightPixels * yRatio)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
                .build()

            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        nextActionAt = SystemClock.elapsedRealtime() + 450L
                    }
                },
                null
            )

            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
        }
    }

    private fun findListScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val screenH = resources.displayMetrics.heightPixels

        while (queue.isNotEmpty() && visited < 120) {
            val node = queue.removeFirst()
            visited++

            if (node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerY() in (screenH * 0.45f).toInt()..(screenH * 0.90f).toInt()) {
                    return node
                }
            }

            val childCount = node.childCount.coerceAtMost(30)
            for (i in 0 until childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }

        return null
    }

    private fun verifiedTap(x: Float, y: Float, cooldownMs: Long) {
        if (!prefs.enabled) return

        mainHandler.post {
            if (!prefs.enabled) return@post

            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
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
        private const val FREE_SLOT_LIMIT = 5
        private const val MAX_LIST_SWIPES = 12
        private const val MAX_AUTO_TAP_ATTEMPTS = 4
        private const val MAX_JOIN_TAP_ATTEMPTS = 3
        private const val MAX_NODE_VISITS = 180
        private const val MAX_CHILDREN_PER_NODE = 40
        private const val OCR_MAX_WIDTH = 1080
        private const val SLOW_OCR_MS = 700L
        private const val MAX_OCR_FAILURES = 3
        private const val OCR_FAILURE_BACKOFF_MS = 5_000L

        private val FULL_TEAM_REGEX = Regex("(\\d{1,3})/\\1")
        private val PARTICIPANT_REGEXES = listOf(
            Regex("參加者?([0-9]{1,2})人"),
            Regex("([0-9]{1,2})人參加"),
            Regex("([0-9]{1,2})/5人?"),
            Regex("目前([0-9]{1,2})人")
        )
    }
}
