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

/**
 * Fully automatic foreground runner.
 *
 * Design goals:
 * - Event first: react immediately when Pikmin Bloom changes its UI.
 * - Node first: direct Accessibility ACTION_CLICK is always preferred.
 * - OCR only when Unity does not expose enough semantic UI.
 * - Never run OCR on the main thread.
 * - Stop automatically after today's free mushroom attempts reach zero.
 * - Weekend search is two-pass: GIANT across the list first, then EVENT.
 * - Weekday search is two-pass: EVENT first, then ANY.
 */
class AutonomousRaceService : AccessibilityService() {

    private lateinit var prefs: HelperPrefs
    private val main = Handler(Looper.getMainLooper())

    private val imageThread = HandlerThread("MushroomHelperOCR")
    private lateinit var imageHandler: Handler
    private lateinit var imageExecutor: Executor

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val ocrBusy = AtomicBoolean(false)

    @Volatile private var nextActionAt = 0L
    @Volatile private var lastOcrAt = 0L
    @Volatile private var lastProcessedOcrAt = 0L
    @Volatile private var lastFrameFingerprint = Long.MIN_VALUE
    @Volatile private var burstUntil = 0L

    @Volatile private var listSwipeCount = 0
    @Volatile private var forceAdvanceOnList = false
    @Volatile private var backOutStepsRemaining = 0
    @Volatile private var autoTapAttempts = 0
    @Volatile private var joinTapAttempts = 0
    @Volatile private var refreshPending = false

    @Volatile private var dailyRemaining: Int? = null
    @Volatile private var dailyDoneDate: String? = null
    @Volatile private var phase: SearchPhase = SearchPhase.EVENT

    private enum class SearchPhase { GIANT, EVENT, ANY }
    private enum class SelectionButton { AUTO, GO }

    private val loop = object : Runnable {
        override fun run() {
            scanOnce()
            main.postDelayed(this, nodeIntervalMs())
        }
    }

    private val eventKick = Runnable { scanOnce() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = HelperPrefs(this)
        phase = firstPhase()

        if (!imageThread.isAlive) imageThread.start()
        imageHandler = Handler(imageThread.looper)
        imageExecutor = Executor { imageHandler.post(it) }

        main.removeCallbacks(loop)
        main.post(loop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized || !prefs.enabled) return
        if (event?.packageName?.toString() != PIKMIN_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val now = SystemClock.elapsedRealtime()
                if (effectiveMode() == RunMode.RACE) {
                    burstUntil = now + EVENT_BURST_MS
                }

                // Do not wait for the normal polling interval when the game reports
                // new server/UI content. The current screen state still gates every tap.
                main.removeCallbacks(eventKick)
                val delay = max(0L, nextActionAt - now).coerceAtMost(180L)
                main.postDelayed(eventKick, delay)
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun effectiveMode(): RunMode = prefs.mode

    private fun nodeIntervalMs(): Long {
        return when (effectiveMode()) {
            RunMode.ECO -> 3_000L
            RunMode.WATCH -> 500L
            RunMode.RACE -> if (SystemClock.elapsedRealtime() < burstUntil) 80L else 150L
        }
    }

    private fun ocrIntervalMs(): Long {
        return when (effectiveMode()) {
            RunMode.ECO -> 15_000L
            RunMode.WATCH -> 1_400L
            RunMode.RACE -> if (SystemClock.elapsedRealtime() < burstUntil) 260L else 420L
        }
    }

    private fun unchangedFrameHoldMs(): Long {
        return when (effectiveMode()) {
            RunMode.ECO -> 8_000L
            RunMode.WATCH -> 2_500L
            RunMode.RACE -> 850L
        }
    }

    private fun scanOnce() {
        if (!prefs.enabled) return
        if (SystemClock.elapsedRealtime() < nextActionAt) return
        if (prefs.pauseLowBattery && batteryPct() < 20) return

        resetDailyStopIfNeeded()
        if (isDoneForToday()) return

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
        updateDailyRemaining(normalized)
        if (isDoneForToday()) return true

        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {
            backOutStepsRemaining = 2
            forceAdvanceOnList = true
            refreshPending = true
            autoTapAttempts = 0
            joinTapAttempts = 0

            findNode(entries, "關閉")?.let {
                clickNode(it.node, 240)
                return true
            }
            goBack(240)
            return true
        }

        if (backOutStepsRemaining > 0) {
            if (isMushroomList(normalized)) {
                backOutStepsRemaining = 0
                forceAdvanceOnList = true
                return false
            }
            backOutStepsRemaining--
            goBack(220)
            return true
        }

        if (normalized.contains("選擇派出皮克敏")) {
            joinTapAttempts = 0

            if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
                autoTapAttempts = 0
                markJoinSubmission()

                findNode(entries, "GO")?.let {
                    clickNode(it.node, 260)
                    return true
                }

                tapVerifiedSelectionButton(SelectionButton.GO, 260)
                return true
            }

            autoTapAttempts++
            if (autoTapAttempts > MAX_AUTO_TAP_ATTEMPTS) {
                startBackOutToNextTarget(2)
                return true
            }

            findNode(entries, "自動")?.let {
                clickNode(it.node, 200)
                return true
            }

            tapVerifiedSelectionButton(SelectionButton.AUTO, 200)
            return true
        }

        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            autoTapAttempts = 0

            val participantCount = extractParticipantCountFromText(normalized)
            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                rejectDetailAndAdvance()
                return true
            }

            if (!isDesiredForCurrentPhase(normalized)) {
                rejectDetailAndAdvance()
                return true
            }

            // If Unity exposed the detail screen but not the participant count,
            // OCR gets one chance to verify the avatar row before we commit.
            if (participantCount == null) return false

            joinTapAttempts++
            if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
                startBackOutToNextTarget(1)
                return true
            }

            findNode(entries, "參加")?.let {
                clickNode(it.node, 240)
                return true
            }
            return false
        }

        // The list is intentionally handled by OCR because participant avatars and
        // some Unity card titles are not consistently exposed as nodes.
        if (isMushroomList(normalized)) return false

        // After GO or after an explicit refresh, return to Explore immediately.
        findExactNode(entries, "探險")?.let {
            listSwipeCount = 0
            refreshPending = false
            clickNode(it.node, 240)
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
        if (now - lastOcrAt < ocrIntervalMs()) return
        if (!ocrBusy.compareAndSet(false, true)) return

        lastOcrAt = now

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
                        ocrBusy.set(false)
                        return
                    }

                    val fingerprint = frameFingerprint(original)
                    val current = SystemClock.elapsedRealtime()
                    if (
                        fingerprint == lastFrameFingerprint &&
                        current - lastProcessedOcrAt < unchangedFrameHoldMs()
                    ) {
                        original.recycle()
                        ocrBusy.set(false)
                        return
                    }

                    lastFrameFingerprint = fingerprint
                    lastProcessedOcrAt = current
                    val frame = prepareOcrFrame(original)
                    recognize(frame)
                }

                override fun onFailure(errorCode: Int) {
                    ocrBusy.set(false)
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

    private fun recognize(frame: OcrFrame) {
        recognizer.process(InputImage.fromBitmap(frame.bitmap, 0))
            .addOnSuccessListener(imageExecutor) { handleOcrScreen(frame, it) }
            .addOnCompleteListener(imageExecutor) {
                frame.bitmap.recycle()
                ocrBusy.set(false)
            }
    }

    private fun handleOcrScreen(frame: OcrFrame, result: Text) {
        if (!prefs.enabled) return

        val lines = result.textBlocks.flatMap { it.lines }
        val normalized = clean(result.text)

        updateDailyRemaining(normalized)
        if (isDoneForToday()) return

        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {
            backOutStepsRemaining = 2
            forceAdvanceOnList = true
            refreshPending = true
            autoTapAttempts = 0
            joinTapAttempts = 0

            findOcrLine(lines, "關閉")?.let {
                tapOcrButton(frame, it, 240)
            } ?: goBack(240)
            return
        }

        if (backOutStepsRemaining > 0) {
            if (isMushroomList(normalized)) {
                backOutStepsRemaining = 0
                forceAdvanceOnList = true
            } else {
                backOutStepsRemaining--
                goBack(220)
                return
            }
        }

        if (normalized.contains("選擇派出皮克敏")) {
            joinTapAttempts = 0

            if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
                autoTapAttempts = 0
                markJoinSubmission()
                findOcrLine(lines, "GO")?.let {
                    tapOcrButton(frame, it, 260)
                } ?: tapVerifiedSelectionButton(SelectionButton.GO, 260)
                return
            }

            autoTapAttempts++
            if (autoTapAttempts > MAX_AUTO_TAP_ATTEMPTS) {
                startBackOutToNextTarget(2)
                return
            }

            findOcrLine(lines, "自動")?.let {
                tapOcrButton(frame, it, 200)
            } ?: tapVerifiedSelectionButton(SelectionButton.AUTO, 200)
            return
        }

        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            autoTapAttempts = 0

            if (!isDesiredForCurrentPhase(normalized)) {
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
                tapOcrButton(frame, it, 240)
            }
            return
        }

        if (isMushroomList(normalized)) {
            refreshPending = false
            autoTapAttempts = 0
            joinTapAttempts = 0

            if (forceAdvanceOnList) {
                forceAdvanceOnList = false
                listSwipeCount++
                swipeListReliable(260)
                return
            }

            val target = chooseOcrTarget(lines)
            if (target != null) {
                val participantCount = estimateListParticipantCount(frame.bitmap, target)

                if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                    listSwipeCount++
                    swipeListReliable(260)
                    return
                }

                tapOcrButton(frame, target, 240)
                return
            }

            if (listSwipeCount < MAX_LIST_SWIPES) {
                listSwipeCount++
                swipeListReliable(260)
            } else {
                advanceSearchPhaseAndRefresh()
            }
            return
        }

        findExactOcrLine(lines, "探險")?.let {
            listSwipeCount = 0
            refreshPending = false
            tapOcrButton(frame, it, 240)
        }
    }

    private fun chooseOcrTarget(lines: List<Text.Line>): Text.Line? {
        return when (phase) {
            SearchPhase.GIANT -> lines.firstOrNull {
                val s = clean(it.text)
                s.contains("巨大") && s.contains("蘑菇")
            }
            SearchPhase.EVENT -> lines.firstOrNull { isEventText(it.text) }
            SearchPhase.ANY -> lines.firstOrNull {
                val s = clean(it.text)
                s.contains("蘑菇") &&
                    !s.contains("今天還剩下") &&
                    !s.contains("蘑菇儲值券") &&
                    !s.contains("蘑菇：")
            }
        }
    }

    private fun firstPhase(): SearchPhase =
        if (isWeekend()) SearchPhase.GIANT else SearchPhase.EVENT

    private fun advanceSearchPhaseAndRefresh() {
        phase = when {
            isWeekend() && phase == SearchPhase.GIANT -> SearchPhase.EVENT
            isWeekend() -> SearchPhase.GIANT
            phase == SearchPhase.EVENT -> SearchPhase.ANY
            else -> SearchPhase.EVENT
        }

        listSwipeCount = 0
        forceAdvanceOnList = false
        refreshPending = true
        goBack(260)
    }

    private fun markJoinSubmission() {
        listSwipeCount = 0
        forceAdvanceOnList = false
        refreshPending = true
        phase = firstPhase()
    }

    private fun findOcrLine(lines: List<Text.Line>, needle: String): Text.Line? {
        val n = clean(needle)
        return lines.firstOrNull { clean(it.text).contains(n) }
    }

    private fun findExactOcrLine(lines: List<Text.Line>, needle: String): Text.Line? {
        val n = clean(needle)
        return lines.firstOrNull { clean(it.text) == n }
    }

    private fun tapOcrButton(frame: OcrFrame, line: Text.Line, cooldownMs: Long) {
        val box = line.boundingBox ?: return
        verifiedTap(
            box.exactCenterX() * frame.scaleX,
            box.exactCenterY() * frame.scaleY,
            cooldownMs
        )
    }

    private fun tapVerifiedSelectionButton(button: SelectionButton, cooldownMs: Long) {
        val dm = resources.displayMetrics
        when (button) {
            SelectionButton.AUTO -> verifiedTap(
                dm.widthPixels * 0.22f,
                dm.heightPixels * 0.405f,
                cooldownMs
            )
            SelectionButton.GO -> verifiedTap(
                dm.widthPixels * 0.855f,
                dm.heightPixels * 0.895f,
                cooldownMs
            )
        }
    }

    private fun updateDailyRemaining(value: String) {
        val match = DAILY_REMAINING_REGEX.find(clean(value)) ?: return
        val remaining = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return
        dailyRemaining = remaining

        if (remaining <= 0) {
            dailyDoneDate = LocalDate.now().toString()
            nextActionAt = SystemClock.elapsedRealtime() + DAILY_DONE_RECHECK_MS
        }
    }

    private fun isDoneForToday(): Boolean {
        return dailyRemaining == 0 && dailyDoneDate == LocalDate.now().toString()
    }

    private fun resetDailyStopIfNeeded() {
        val today = LocalDate.now().toString()
        if (dailyDoneDate != null && dailyDoneDate != today) {
            dailyDoneDate = null
            dailyRemaining = null
            phase = firstPhase()
            listSwipeCount = 0
            nextActionAt = 0L
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

        val left = max(0, box.left - (bitmap.width * 0.01f).toInt())
        val right = min(bitmap.width, box.left + (bitmap.width * 0.60f).toInt())
        val top = min(bitmap.height - 1, box.bottom + (bitmap.height * 0.024f).toInt())
        val bottom = min(bitmap.height, box.bottom + (bitmap.height * 0.090f).toInt())

        if (right <= left || bottom <= top) return null

        return countColorClusters(bitmap, left, top, right, bottom)
            ?.takeIf { it in 1..8 }
    }

    private fun estimateDetailParticipantCount(
        bitmap: Bitmap,
        lines: List<Text.Line>
    ): Int? {
        val left = (bitmap.width * 0.04f).toInt()
        val right = (bitmap.width * 0.82f).toInt()
        val top = (bitmap.height * 0.80f).toInt()
        val bottom = (bitmap.height * 0.95f).toInt()

        countColorClusters(bitmap, left, top, right, bottom)?.let {
            if (it in 1..8) return it
        }

        val nameXs = mutableListOf<Int>()
        val minY = (bitmap.height * 0.84f).toInt()
        val maxY = (bitmap.height * 0.995f).toInt()

        lines.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            if (box.centerY() !in minY..maxY) return@forEach

            val s = clean(line.text)
            if (s.isEmpty() || s.any { it.isDigit() } || s.length > 20) return@forEach
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

    private fun frameFingerprint(bitmap: Bitmap): Long {
        var h = 1125899906842597L
        val cols = 8
        val rows = 10
        val yStart = (bitmap.height * 0.30f).toInt()
        val yEnd = (bitmap.height * 0.96f).toInt().coerceAtMost(bitmap.height - 1)

        for (ry in 0 until rows) {
            val y = yStart + ((yEnd - yStart) * ry / max(1, rows - 1))
            for (rx in 0 until cols) {
                val x = (bitmap.width - 1) * rx / max(1, cols - 1)
                val c = bitmap.getPixel(x, y)
                val qr = Color.red(c) shr 4
                val qg = Color.green(c) shr 4
                val qb = Color.blue(c) shr 4
                h = h * 31L + ((qr shl 8) or (qg shl 4) or qb)
            }
        }
        return h
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

    private fun isDesiredForCurrentPhase(normalized: String): Boolean {
        return when (phase) {
            SearchPhase.GIANT -> normalized.contains("巨大")
            SearchPhase.EVENT -> isEventText(normalized)
            SearchPhase.ANY -> normalized.contains("蘑菇")
        }
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

    private fun clean(value: String): String = value.replace(Regex("\\s+"), "")

    private fun rejectDetailAndAdvance() {
        forceAdvanceOnList = true
        joinTapAttempts = 0
        autoTapAttempts = 0
        goBack(220)
    }

    private fun startBackOutToNextTarget(steps: Int) {
        forceAdvanceOnList = true
        refreshPending = true
        backOutStepsRemaining = steps.coerceAtLeast(1)
        joinTapAttempts = 0
        autoTapAttempts = 0
        backOutStepsRemaining--
        goBack(220)
    }

    private fun swipeListReliable(cooldownMs: Long) {
        if (!prefs.enabled) return

        main.post {
            if (!prefs.enabled) return@post

            val root = rootInActiveWindow
            val scrollable = root?.let { findListScrollable(it) }
            if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
                return@post
            }

            val dm = resources.displayMetrics
            val yRatio = if (listSwipeCount % 2 == 0) 0.70f else 0.74f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.82f, dm.heightPixels * yRatio)
                lineTo(dm.widthPixels * 0.17f, dm.heightPixels * yRatio)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
                .build()

            dispatchGesture(gesture, null, null)
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
                if (rect.centerY() in (screenH * 0.44f).toInt()..(screenH * 0.91f).toInt()) {
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

        main.post {
            if (!prefs.enabled) return@post
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
                .build()
            dispatchGesture(gesture, null, null)
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
        }
    }

    private fun goBack(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
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
        main.removeCallbacksAndMessages(null)
        ocrBusy.set(false)
        recognizer.close()
        if (imageThread.isAlive) imageThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"
        private const val FREE_SLOT_LIMIT = 5
        private const val MAX_LIST_SWIPES = 14
        private const val MAX_AUTO_TAP_ATTEMPTS = 5
        private const val MAX_JOIN_TAP_ATTEMPTS = 4
        private const val MAX_NODE_VISITS = 180
        private const val MAX_CHILDREN_PER_NODE = 40
        private const val OCR_MAX_WIDTH = 900
        private const val EVENT_BURST_MS = 1_500L
        private const val DAILY_DONE_RECHECK_MS = 60_000L

        private val FULL_TEAM_REGEX = Regex("(\\d{1,3})/\\1")
        private val DAILY_REMAINING_REGEX = Regex("今天還剩下([0-9]{1,2})次")
        private val PARTICIPANT_REGEXES = listOf(
            Regex("參加者?([0-9]{1,2})人"),
            Regex("([0-9]{1,2})人參加"),
            Regex("([0-9]{1,2})/5人?"),
            Regex("目前([0-9]{1,2})人")
        )
    }
}
