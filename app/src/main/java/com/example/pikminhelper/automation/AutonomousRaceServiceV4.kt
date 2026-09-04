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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
class AutonomousRaceServiceV4 : AccessibilityService() {
    private lateinit var prefs: HelperPrefs
    private val main = Handler(Looper.getMainLooper())
    private val imageThread = HandlerThread("MushroomHelperV4Image")
    private lateinit var imageHandler: Handler
    private lateinit var imageExecutor: Executor
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val ocrBusy = AtomicBoolean(false)
    @Volatile private var nextActionAt = 0L
    @Volatile private var lastOcrAt = 0L
    @Volatile private var burstUntil = 0L
    @Volatile private var lastFrameFingerprint = Long.MIN_VALUE
    @Volatile private var lastProcessedFrameAt = 0L
    @Volatile private var listSwipeCount = 0
    @Volatile private var lastListPosition = -1
    @Volatile private var stuckListPositionCount = 0
    @Volatile private var rewindListPending = false
    @Volatile private var rewindSwipeCount = 0
    @Volatile private var listProgressGeneration = 0L
    @Volatile private var listContextActive = false
    @Volatile private var listGestureInFlight = false
    @Volatile private var raceSweepActive = false
    @Volatile private var raceBackupSweepAt = 0L
    @Volatile private var urgentListChange = false
    @Volatile private var suppressListMutationEventsUntil = 0L
    @Volatile private var lastUrgentListEventAt = 0L
    @Volatile private var parkedAtStart = false
    @Volatile private var etaInspectionActive = false
    @Volatile private var etaInspectionAdvancePending = false
    @Volatile private var etaInspectionCurrentWasLast = false
    @Volatile private var etaInspectionCurrentPosition = -1
    @Volatile private var etaInspectionCount = 0
    @Volatile private var nextEtaInspectionAt = 0L
    @Volatile private var predictedFinishAt = 0L
    @Volatile private var predictedSpawnAt = 0L
    @Volatile private var predictionReadyAt = 0L
    @Volatile private var predictionWindowUntil = 0L
    @Volatile private var predictionLastSweepAt = 0L
    @Volatile private var predictedSourcePosition = -1
    @Volatile private var targetPositioning = false
    @Volatile private var targetAdvanceRemaining = 0
    @Volatile private var targetDetailOpenPending = false
    @Volatile private var targetMapOpenAttempts = 0
    @Volatile private var targetMapLockPending = false
    @Volatile private var targetMapLockActive = false
    @Volatile private var targetMapAnchorReady = false
    @Volatile private var targetMapAnchorX = 0f
    @Volatile private var targetMapAnchorY = 0f
    @Volatile private var rewindResume: RewindResume = RewindResume.SEARCH
    @Volatile private var forceAdvanceOnList = false
    @Volatile private var refreshPending = false
    @Volatile private var reopenExploreAt = 0L
    @Volatile private var backOutStepsRemaining = 0
    @Volatile private var autoTapAttempts = 0
    @Volatile private var joinTapAttempts = 0
    @Volatile private var dailyRemaining: Int? = null
    @Volatile private var dailyDoneDate: String? = null
    @Volatile private var phase: SearchPhase = SearchPhase.EVENT
    @Volatile private var detailCameFromBirdMap = false
    @Volatile private var lastBirdMapTapKey: Int? = null
    private val rejectedBirdMapPoints = ConcurrentHashMap<Int, Long>()
    private enum class SearchPhase { GIANT, EVENT, ANY }
    private enum class RewindResume { SEARCH, INSPECT, PARK, TARGET }
    private enum class SelectionButton { AUTO, GO }
    private data class NodeEntry(
        val node: AccessibilityNodeInfo,
        val text: String,
        val bounds: Rect
    )
    private data class OcrFrame(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float
    )
    private data class MapBadge(
        val count: Int,
        val x: Float,
        val y: Float,
        val key: Int
    )
    private data class MapCandidate(
        val x: Float,
        val y: Float,
        val key: Int,
        val participantCount: Int?,
        val score: Int
    )
    private val loop = object : Runnable {
        override fun run() {
            scanOnce()
            main.postDelayed(this, nodeIntervalMs())
        }
    }
    private val eventKick = Runnable { scanOnce() }
    private val listWatchdogKick: Runnable = object : Runnable {
        override fun run() {
            if (!::prefs.isInitialized || !prefs.enabled) return
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != PIKMIN_PACKAGE) return
            if (!listContextActive) return

            if (listGestureInFlight) {
                main.postDelayed(this, LIST_GESTURE_BUSY_RETRY_MS)
                return
            }
            if (ocrBusy.get()) {
                main.postDelayed(this, LIST_BUSY_RETRY_MS)
                return
            }

            nextActionAt = 0L
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            val generationBefore = listProgressGeneration
            scanOnce()

            main.postDelayed({
                if (!::prefs.isInitialized || !prefs.enabled) return@postDelayed
                if (!listContextActive) return@postDelayed
                if (generationBefore != listProgressGeneration) return@postDelayed
                val currentRoot = rootInActiveWindow ?: return@postDelayed
                if (currentRoot.packageName?.toString() != PIKMIN_PACKAGE) return@postDelayed

                if (ocrBusy.get()) {
                    main.removeCallbacks(listWatchdogKick)
                    main.postDelayed(listWatchdogKick, LIST_BUSY_RETRY_MS)
                    return@postDelayed
                }

                // No tap/swipe happened after a forced list scan. Wake the
                // screenshot/OCR path again instead of leaving card 1 idle.
                nextActionAt = 0L
                lastFrameFingerprint = Long.MIN_VALUE
                lastProcessedFrameAt = 0L
                lastOcrAt = 0L
                main.removeCallbacks(listWatchdogKick)
                main.postDelayed(listWatchdogKick, LIST_STALL_RETRY_MS)
            }, LIST_PROGRESS_GUARD_MS)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = HelperPrefs(this)
        phase = firstPhase()
        if (!imageThread.isAlive) imageThread.start()
        imageHandler = Handler(imageThread.looper)
        imageExecutor = Executor { command -> imageHandler.post(command) }
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
                if (prefs.mode == RunMode.RACE) {
                    burstUntil = now + EVENT_BURST_MS
                    val isMutation =
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    if (
                        listContextActive &&
                        isMutation &&
                        now >= suppressListMutationEventsUntil &&
                        now - lastUrgentListEventAt >= RACE_LIST_EVENT_DEBOUNCE_MS
                    ) {
                        lastUrgentListEventAt = now
                        urgentListChange = true
                        nextActionAt = 0L
                        lastFrameFingerprint = Long.MIN_VALUE
                        lastProcessedFrameAt = 0L
                        lastOcrAt = 0L
                        main.removeCallbacks(listWatchdogKick)
                    }
                }
                main.removeCallbacks(eventKick)
                val wait = if (urgentListChange) 0L
                    else max(0L, nextActionAt - now).coerceAtMost(120L)
                main.postDelayed(eventKick, wait)
            }
        }
    }
    override fun onInterrupt() = Unit
    private fun nodeIntervalMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return when (prefs.mode) {
            RunMode.ECO -> 3_000L
            RunMode.WATCH -> 450L
            RunMode.RACE -> when {
                isPredictionPrewarm(now) || now < burstUntil -> 80L
                predictedSpawnAt > 0L && now < predictionReadyAt -> 900L
                else -> 160L
            }
        }
    }
    private fun ocrIntervalMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return when (prefs.mode) {
            RunMode.ECO -> 12_000L
            RunMode.WATCH -> 1_200L
            RunMode.RACE -> when {
                isPredictionPrewarm(now) || now < burstUntil -> 240L
                predictedSpawnAt > 0L && now < predictionReadyAt -> 2_500L
                else -> 520L
            }
        }
    }
    private fun unchangedFrameHoldMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 8_000L
        RunMode.WATCH -> 2_000L
        RunMode.RACE -> 650L
    }
    private fun refreshDelayMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 5_000L
        RunMode.WATCH -> 1_800L
        RunMode.RACE -> 650L
    }
    private fun birdIdleRescanMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 4_000L
        RunMode.WATCH -> 1_100L
        RunMode.RACE -> 420L
    }
    private fun scanOnce() {
        if (!prefs.enabled) return
        val now = SystemClock.elapsedRealtime()
        updatePredictionState(now)
        if (now < nextActionAt) return
        if (prefs.pauseLowBattery && batteryPct() < 20) return
        resetDailyStopIfNeeded()
        if (isDoneForToday()) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PIKMIN_PACKAGE) return
        if (handleAccessibilityTree(root)) return
        requestOcrFallback()
    }
    private fun handleAccessibilityTree(root: AccessibilityNodeInfo): Boolean {
        val entries = collectNodes(root)
        if (entries.isEmpty()) return false
        val normalized = clean(entries.joinToString(" ") { it.text })
        updateDailyRemaining(normalized)
        if (isDoneForToday()) return true
        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {
            prepareFailureRecovery()
            findNode(entries, "關閉")?.let {
                clickNode(it.node, 220)
                return true
            }
            goBack(220)
            return true
        }
        if (backOutStepsRemaining > 0) {
            if (isMushroomList(normalized)) {
                backOutStepsRemaining = 0
                forceAdvanceOnList = true
                return false
            }
            backOutStepsRemaining--
            goBack(200)
            return true
        }
        if (normalized.contains("選擇派出皮克敏")) {
            return handleTeamSelectionNodes(entries, normalized)
        }
        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            findGoToMapNode(entries)?.let {
                targetDetailOpenPending = false
                targetMapOpenAttempts = 0
                targetMapLockPending = true
                clickNode(it.node, TARGET_MAP_OPEN_COOLDOWN_MS)
                return true
            }
            return false
        }
        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
            handleEtaInspectionDetail(normalized)
            return true
        }
        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            return handleDetailNodes(entries, normalized)
        }
        if (ExploreScreenRules.isDecorOnlyExplore(normalized)) {
            listContextActive = false
            main.removeCallbacks(listWatchdogKick)
            nextActionAt = SystemClock.elapsedRealtime() + DECOR_GUARD_RETRY_MS
            return true
        }
        if (isMushroomList(normalized) || normalized.contains("鳥瞰風景")) return false
        findExactNode(entries, "探險")?.let {
            val now = SystemClock.elapsedRealtime()
            if (refreshPending && now < reopenExploreAt) {
                nextActionAt = reopenExploreAt
                return true
            }
            listSwipeCount = 0
            lastListPosition = -1
            stuckListPositionCount = 0
            refreshPending = false
            clickNode(it.node, 220)
            return true
        }
        return false
    }
    private fun handleTeamSelectionNodes(
        entries: List<NodeEntry>,
        normalized: String
    ): Boolean {
        joinTapAttempts = 0
        if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
            autoTapAttempts = 0
            markJoinSubmission()
            findNode(entries, "GO")?.let {
                clickNode(it.node, 240)
                return true
            }
            tapSelectionFallback(SelectionButton.GO, 240)
            return true
        }
        autoTapAttempts++
        if (autoTapAttempts > MAX_AUTO_TAP_ATTEMPTS) {
            startBackOutToNextTarget(2)
            return true
        }
        findNode(entries, "自動")?.let {
            clickNode(it.node, 180)
            return true
        }
        tapSelectionFallback(SelectionButton.AUTO, 180)
        return true
    }
    private fun handleDetailNodes(
        entries: List<NodeEntry>,
        normalized: String
    ): Boolean {
        autoTapAttempts = 0
        if (!isDesiredForCurrentPhase(normalized)) {
            rejectDetailAndAdvance()
            return true
        }
        val participantCount = extractParticipantCountFromText(normalized)
        if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
            rejectDetailAndAdvance()
            return true
        }
        joinTapAttempts++
        if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
            startBackOutToNextTarget(1)
            return true
        }
        findNode(entries, "參加")?.let {
            clickNode(it.node, 220)
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
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                out.add(NodeEntry(node, label, bounds))
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
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            verifiedTap(bounds.exactCenterX(), bounds.exactCenterY(), cooldownMs)
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
                        current - lastProcessedFrameAt < unchangedFrameHoldMs()
                    ) {
                        original.recycle()
                        ocrBusy.set(false)
                        return
                    }
                    lastFrameFingerprint = fingerprint
                    lastProcessedFrameAt = current
                    val frame = prepareOcrFrame(original)
                    recognize(frame)
                }
                override fun onFailure(errorCode: Int) {
                    ocrBusy.set(false)
                }
            }
        )
    }
    private fun prepareOcrFrame(original: Bitmap): OcrFrame {
        if (original.width <= OCR_MAX_WIDTH) return OcrFrame(original, 1f, 1f)
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
            .addOnSuccessListener(imageExecutor) { result -> handleOcrScreen(frame, result) }
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
            prepareFailureRecovery()
            findOcrLine(lines, "關閉")?.let {
                tapOcrButton(frame, it, 220)
            } ?: goBack(220)
            return
        }
        if (backOutStepsRemaining > 0) {
            if (isMushroomList(normalized)) {
                backOutStepsRemaining = 0
                forceAdvanceOnList = true
            } else {
                backOutStepsRemaining--
                goBack(200)
                return
            }
        }
        if (normalized.contains("選擇派出皮克敏")) {
            listContextActive = false
            handleTeamSelectionOcr(frame, lines, normalized)
            return
        }
        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            handleTargetDetailToBirdMap(frame, lines)
            return
        }
        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            handleEtaInspectionDetail(normalized)
            return
        }
        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            listContextActive = false
            handleDetailOcr(frame, lines, normalized)
            return
        }
        if (ExploreScreenRules.isDecorOnlyExplore(normalized)) {
            listContextActive = false
            main.removeCallbacks(listWatchdogKick)
            nextActionAt = SystemClock.elapsedRealtime() + DECOR_GUARD_RETRY_MS
            return
        }
        if (isMushroomList(normalized)) {
            handleMushroomList(frame, lines, normalized)
            return
        }
        if (normalized.contains("飾品一覽") && !normalized.contains("花苗和水果")) {
            goBack(220)
            return
        }
        val forcedTargetBirdMap =
            targetMapLockPending &&
                !looksLikeMushroomDetail(normalized) &&
                !isMushroomList(normalized) &&
                !ExploreScreenRules.isDecorOnlyExplore(normalized)
        if (forcedTargetBirdMap || looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            listContextActive = false
            if (targetMapLockPending) {
                targetMapLockPending = false
                targetMapLockActive = true
                targetMapAnchorReady = false
                targetMapOpenAttempts = 0
            }
            handleBirdMap(frame, lines)
            return
        }
        findExactOcrLine(lines, "探險")?.let {
            val now = SystemClock.elapsedRealtime()
            if (refreshPending && now < reopenExploreAt) {
                nextActionAt = reopenExploreAt
                return
            }
            listSwipeCount = 0
            lastListPosition = -1
            stuckListPositionCount = 0
            refreshPending = false
            tapOcrButton(frame, it, 220)
        }
    }
    private fun handleTeamSelectionOcr(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        joinTapAttempts = 0
        if (FULL_TEAM_REGEX.containsMatchIn(normalized)) {
            autoTapAttempts = 0
            markJoinSubmission()
            findOcrLine(lines, "GO")?.let {
                tapOcrButton(frame, it, 240)
            } ?: tapSelectionFallback(SelectionButton.GO, 240)
            return
        }
        autoTapAttempts++
        if (autoTapAttempts > MAX_AUTO_TAP_ATTEMPTS) {
            startBackOutToNextTarget(2)
            return
        }
        findOcrLine(lines, "自動")?.let {
            tapOcrButton(frame, it, 180)
        } ?: tapSelectionFallback(SelectionButton.AUTO, 180)
    }
    private fun handleDetailOcr(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
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
            tapOcrButton(frame, it, 220)
        } ?: rejectDetailAndAdvance()
    }
    private fun handleMushroomList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        listContextActive = true
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
        refreshPending = false
        autoTapAttempts = 0
        joinTapAttempts = 0

        val now = SystemClock.elapsedRealtime()
        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)

        if (targetPositioning) {
            handleTargetPositioningList(frame, lines, reachedEnd, position)
            return
        }

        // A genuine Pikmin list mutation outranks whatever old scan was doing.
        // Stop inspection/patrol, return to card 1 if needed, then run a fresh
        // priority search immediately.
        if (prefs.mode == RunMode.RACE && urgentListChange) {
            urgentListChange = false
            etaInspectionActive = false
            etaInspectionAdvancePending = false
            parkedAtStart = false
            phase = firstPhase()
            raceSweepActive = true
            raceBackupSweepAt = 0L
            val awayFromStart = (position?.first ?: if (listSwipeCount > 0) 2 else 1) > 1
            if (awayFromStart || rewindListPending) {
                beginRewind(RewindResume.SEARCH)
                return
            }
        }

        if (rewindListPending) {
            handleRewindOnList(position)
            return
        }

        if (etaInspectionActive) {
            handleEtaInspectionList(frame, lines, reachedEnd, position)
            return
        }

        if (forceAdvanceOnList) {
            forceAdvanceOnList = false
            if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                advanceSearchPhaseAndRefresh()
            } else {
                listSwipeCount++
                swipeListReliable(searchSwipeCooldownMs())
            }
            return
        }

        val target = chooseOcrTarget(lines)
        if (target != null) {
            val participantCount = estimateListParticipantCount(frame.bitmap, target)
            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                    advanceSearchPhaseAndRefresh()
                } else {
                    listSwipeCount++
                    swipeListReliable(searchSwipeCooldownMs())
                }
                return
            }

            parkedAtStart = false
            listProgressGeneration++
            tapOcrButton(frame, target, RACE_TARGET_TAP_COOLDOWN_MS)
            return
        }

        // After a complete no-target cycle, RACE parks at card 1. While parked,
        // real UI mutations wake a full search instantly. If an ETA prediction
        // exists, stay quiet until the prewarm window and then poll without
        // wandering away from the start card.
        if (prefs.mode == RunMode.RACE && parkedAtStart) {
            if (predictedSpawnAt > 0L) {
                if (now < predictionReadyAt) {
                    scheduleFreshListScan((predictionReadyAt - now).coerceAtLeast(250L))
                    return
                }
                if (isPredictionPrewarm(now)) {
                    burstUntil = max(burstUntil, predictionWindowUntil)
                    if (
                        now >= predictedSpawnAt &&
                        now - predictionLastSweepAt >= PREDICTION_SWEEP_INTERVAL_MS
                    ) {
                        predictionLastSweepAt = now
                        parkedAtStart = false
                        raceSweepActive = true
                        listSwipeCount = 0
                        lastListPosition = -1
                        stuckListPositionCount = 0
                    } else {
                        scheduleFreshListScan(PREDICTION_PARK_POLL_MS)
                        return
                    }
                }
            }

            if (parkedAtStart) {
                if (raceBackupSweepAt <= 0L) {
                    raceBackupSweepAt = now + RACE_BACKUP_SWEEP_MS
                }
                if (now < raceBackupSweepAt) {
                    scheduleFreshListScan((raceBackupSweepAt - now).coerceAtLeast(250L))
                    return
                }
                parkedAtStart = false
                raceSweepActive = true
                raceBackupSweepAt = 0L
                listSwipeCount = 0
                lastListPosition = -1
                stuckListPositionCount = 0
            }
        }

        if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
            advanceSearchPhaseAndRefresh()
        } else {
            listSwipeCount++
            swipeListReliable(searchSwipeCooldownMs())
        }
    }

    private fun searchSwipeCooldownMs(): Long =
        if (prefs.mode == RunMode.RACE) RACE_SWEEP_COOLDOWN_MS else 240L

    private fun beginRewind(resume: RewindResume) {
        parkedAtStart = false
        rewindResume = resume
        rewindListPending = true
        rewindSwipeCount = 1
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        swipeListBackwardReliable(
            if (prefs.mode == RunMode.RACE) RACE_REWIND_COOLDOWN_MS else 220L
        )
    }

    private fun handleRewindOnList(position: Pair<Int, Int>?) {
        val atStart = position?.first == 1
        if (atStart || rewindSwipeCount >= MAX_REWIND_SWIPES) {
            rewindListPending = false
            rewindSwipeCount = 0
            listSwipeCount = 0
            lastListPosition = -1
            stuckListPositionCount = 0
            when (rewindResume) {
                RewindResume.SEARCH -> {
                    parkedAtStart = false
                    raceSweepActive = true
                    raceBackupSweepAt = 0L
                    scheduleFreshListScan(120L)
                }
                RewindResume.INSPECT -> {
                    parkedAtStart = false
                    etaInspectionActive = true
                    etaInspectionAdvancePending = false
                    etaInspectionCurrentWasLast = false
                    etaInspectionCurrentPosition = -1
                    etaInspectionCount = 0
                    scheduleFreshListScan(180L)
                }
                RewindResume.PARK -> parkAtStart()
                RewindResume.TARGET -> {
                    parkedAtStart = false
                    targetPositioning = true
                    targetAdvanceRemaining = (predictedSourcePosition - 1).coerceAtLeast(0)
                    scheduleFreshListScan(140L)
                }
            }
            return
        }

        rewindSwipeCount++
        swipeListBackwardReliable(
            if (prefs.mode == RunMode.RACE) RACE_REWIND_COOLDOWN_MS else 220L
        )
    }

    private fun handleEtaInspectionList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        reachedEnd: Boolean,
        position: Pair<Int, Int>?
    ) {
        if (etaInspectionAdvancePending) {
            etaInspectionAdvancePending = false
            if (
                etaInspectionCurrentWasLast ||
                reachedEnd ||
                etaInspectionCount >= MAX_ETA_INSPECTION_CARDS
            ) {
                finishEtaInspection()
            } else {
                listSwipeCount++
                swipeListReliable(ETA_INSPECTION_SWIPE_COOLDOWN_MS)
            }
            return
        }

        val title = findAnyMushroomTitle(lines)
        if (title != null) {
            etaInspectionCurrentPosition = position?.first ?: (etaInspectionCount + 1)
            etaInspectionCurrentWasLast = reachedEnd
            etaInspectionCount++
            listProgressGeneration++
            tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)
            return
        }

        if (reachedEnd || etaInspectionCount >= MAX_ETA_INSPECTION_CARDS) {
            finishEtaInspection()
        } else {
            listSwipeCount++
            swipeListReliable(ETA_INSPECTION_SWIPE_COOLDOWN_MS)
        }
    }

    private fun handleTargetPositioningList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        reachedEnd: Boolean,
        position: Pair<Int, Int>?
    ) {
        if (predictedSpawnAt <= 0L || predictedSourcePosition <= 0) {
            targetPositioning = false
            beginRewind(RewindResume.PARK)
            return
        }

        val current = position?.first
        if (current != null) {
            when {
                current < predictedSourcePosition && !reachedEnd -> {
                    swipeListReliable(ETA_INSPECTION_SWIPE_COOLDOWN_MS)
                    return
                }
                current > predictedSourcePosition -> {
                    beginRewind(RewindResume.TARGET)
                    return
                }
            }
        } else if (targetAdvanceRemaining > 0 && !reachedEnd) {
            targetAdvanceRemaining--
            swipeListReliable(ETA_INSPECTION_SWIPE_COOLDOWN_MS)
            return
        }

        val title = findAnyMushroomTitle(lines)
        if (title == null) {
            // Do not wander into unrelated Explore content. Re-scan this frame
            // briefly; if the target card truly vanished, prediction recovery
            // will fall back through the normal list watchdog.
            scheduleFreshListScan(TARGET_CARD_RETRY_MS)
            return
        }

        targetPositioning = false
        targetDetailOpenPending = true
        targetMapOpenAttempts = 0
        listProgressGeneration++
        tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)
    }

    private fun handleTargetDetailToBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        val goToMap = findGoToMapLine(lines)
        if (goToMap != null) {
            targetDetailOpenPending = false
            targetMapOpenAttempts = 0
            targetMapLockPending = true
            tapOcrButton(frame, goToMap, TARGET_MAP_OPEN_COOLDOWN_MS)
            return
        }

        targetMapOpenAttempts++
        if (targetMapOpenAttempts <= MAX_TARGET_MAP_OPEN_ATTEMPTS) {
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            nextActionAt = SystemClock.elapsedRealtime() + TARGET_MAP_BUTTON_RETRY_MS
            return
        }

        // Safe failure: return to list and keep the prediction rather than
        // tapping an unverified coordinate on the detail page.
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        goBack(220L)
    }

    private fun findGoToMapLine(lines: List<Text.Line>): Text.Line? =
        lines.firstOrNull { line ->
            val t = clean(line.text)
            GO_TO_MAP_TEXTS.any { t.contains(it) }
        }

    private fun findGoToMapNode(entries: List<NodeEntry>): NodeEntry? =
        entries.firstOrNull { entry ->
            val t = clean(entry.text)
            GO_TO_MAP_TEXTS.any { t.contains(it) }
        }

    private fun findAnyMushroomTitle(lines: List<Text.Line>): Text.Line? {
        return lines.firstOrNull {
            val t = clean(it.text)
            t.contains("蘑菇") &&
                !t.contains("今天還剩下") &&
                !t.contains("蘑菇儲值券") &&
                !t.startsWith("蘑菇：") &&
                !t.startsWith("蘑菇:")
        }
    }

    private fun handleEtaInspectionDetail(normalized: String) {
        val finishEtaMs = MushroomTiming.parseFinishEtaMillis(normalized)
        if (finishEtaMs != null && finishEtaMs in 0L..ETA_INSPECTION_HORIZON_MS) {
            recordPredictedRespawn(finishEtaMs, etaInspectionCurrentPosition)
        }
        etaInspectionAdvancePending = true
        listContextActive = false
        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)
    }

    private fun recordPredictedRespawn(finishEtaMs: Long, sourcePosition: Int) {
        val now = SystemClock.elapsedRealtime()
        val finishAt = now + finishEtaMs
        val predicted = finishAt + MUSHROOM_RESPAWN_DELAY_MS
        if (predictedSpawnAt == 0L || predicted < predictedSpawnAt) {
            predictedFinishAt = finishAt
            predictedSpawnAt = predicted
            predictedSourcePosition = sourcePosition
            predictionReadyAt = (predicted - PREDICTION_PREWARM_LEAD_MS).coerceAtLeast(now)
            predictionWindowUntil = predicted + PREDICTION_AFTER_WINDOW_MS
            predictionLastSweepAt = 0L
        }
    }

    private fun finishEtaInspection() {
        etaInspectionActive = false
        etaInspectionAdvancePending = false
        etaInspectionCurrentWasLast = false
        etaInspectionCurrentPosition = -1
        etaInspectionCount = 0
        nextEtaInspectionAt = SystemClock.elapsedRealtime() + ETA_REINSPECTION_MS
        phase = firstPhase()
        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {
            beginRewind(RewindResume.TARGET)
        } else {
            beginRewind(RewindResume.PARK)
        }
    }

    private fun parkAtStart() {
        parkedAtStart = true
        raceSweepActive = false
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        val now = SystemClock.elapsedRealtime()

        if (predictedSpawnAt > 0L) {
            when {
                now < predictionReadyAt ->
                    scheduleFreshListScan((predictionReadyAt - now).coerceAtLeast(250L))
                isPredictionPrewarm(now) -> {
                    burstUntil = max(burstUntil, predictionWindowUntil)
                    scheduleFreshListScan(PREDICTION_PARK_POLL_MS)
                }
                else -> {
                    clearPrediction()
                    raceBackupSweepAt = now + RACE_BACKUP_SWEEP_MS
                    scheduleFreshListScan(RACE_BACKUP_SWEEP_MS)
                }
            }
            return
        }

        val delay = when (prefs.mode) {
            RunMode.RACE -> RACE_BACKUP_SWEEP_MS
            RunMode.WATCH -> 8_000L
            RunMode.ECO -> 30_000L
        }
        raceBackupSweepAt = now + delay
        scheduleFreshListScan(delay)
    }

    private fun isPredictionPrewarm(now: Long = SystemClock.elapsedRealtime()): Boolean =
        predictedSpawnAt > 0L &&
            now >= predictionReadyAt &&
            now <= predictionWindowUntil

    private fun updatePredictionState(now: Long) {
        if (predictedSpawnAt <= 0L) return

        if (isPredictionPrewarm(now)) {
            burstUntil = max(burstUntil, predictionWindowUntil)
            return
        }

        if (now > predictionWindowUntil) {
            if (targetMapLockActive || targetMapLockPending) return
            clearPrediction()
            nextEtaInspectionAt = 0L
            if (parkedAtStart && listContextActive) {
                parkedAtStart = false
                phase = firstPhase()
                raceSweepActive = true
                raceBackupSweepAt = 0L
                nextActionAt = 0L
                lastFrameFingerprint = Long.MIN_VALUE
                lastProcessedFrameAt = 0L
                lastOcrAt = 0L
                main.removeCallbacks(listWatchdogKick)
                main.post(listWatchdogKick)
            }
        }
    }

    private fun clearPrediction() {
        predictedFinishAt = 0L
        predictedSpawnAt = 0L
        predictionReadyAt = 0L
        predictionWindowUntil = 0L
        predictionLastSweepAt = 0L
        predictedSourcePosition = -1
        targetPositioning = false
        targetAdvanceRemaining = 0
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        targetMapLockPending = false
        targetMapLockActive = false
        targetMapAnchorReady = false
        targetMapAnchorX = 0f
        targetMapAnchorY = 0f
    }

    private fun updateListPositionAndCheckEnd(position: Pair<Int, Int>?): Boolean {
        if (position == null) return listSwipeCount >= MAX_LIST_SWIPES
        val current = position.first
        val total = position.second
        if (current == lastListPosition && listSwipeCount > 0) {
            stuckListPositionCount++
        } else {
            stuckListPositionCount = 0
            lastListPosition = current
        }
        return current >= total || stuckListPositionCount >= MAX_STUCK_LIST_POSITION
    }
    private fun extractListPosition(value: String): Pair<Int, Int>? {
        val match = LIST_POSITION_REGEX.find(clean(value)) ?: return null
        val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (current <= 0 || total <= 0) return null
        return current to total
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
                    !s.startsWith("蘑菇：") &&
                    !s.startsWith("蘑菇:")
            }
        }
    }
    private fun handleBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        refreshPending = false
        cleanupRejectedMapPoints()

        if (targetMapLockActive) {
            handleTargetLockedBirdMap(frame, lines)
            return
        }
        val badges = findMapBadges(frame.bitmap, lines)
        val candidates = ArrayList<MapCandidate>()
        badges.forEach { badge ->
            if (badge.count < FREE_SLOT_LIMIT && !isMapPointRejected(badge.key)) {
                candidates.add(
                    MapCandidate(
                        x = badge.x,
                        y = badge.y,
                        key = badge.key,
                        participantCount = badge.count,
                        score = 10_000 - badge.count * 500
                    )
                )
            }
        }
        val explicitBirdView = lines.any { clean(it.text).contains("鳥瞰風景") }
        if (explicitBirdView || badges.size >= 2) {
            candidates.addAll(findUnbadgedMushroomCandidates(frame.bitmap, badges))
        }
        val best = candidates
            .filterNot { isMapPointRejected(it.key) }
            .maxByOrNull { it.score }
        if (best == null) {
            nextActionAt = SystemClock.elapsedRealtime() + birdIdleRescanMs()
            return
        }
        detailCameFromBirdMap = true
        lastBirdMapTapKey = best.key
        verifiedTap(
            best.x * frame.scaleX,
            best.y * frame.scaleY,
            220
        )
    }
    private fun handleTargetLockedBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        val now = SystemClock.elapsedRealtime()
        if (predictedSpawnAt <= 0L) {
            targetMapLockActive = false
            targetMapAnchorReady = false
            return
        }

        val badges = findMapBadges(frame.bitmap, lines)
        if (!targetMapAnchorReady) {
            learnTargetMapAnchor(frame.bitmap, badges)
        }

        if (now < predictionReadyAt) {
            val remaining = predictionReadyAt - now
            nextActionAt = now + remaining.coerceIn(TARGET_MAP_IDLE_MIN_MS, TARGET_MAP_IDLE_MAX_MS)
            return
        }

        if (now > predictionWindowUntil) {
            targetMapLockActive = false
            targetMapAnchorReady = false
            clearPrediction()
            // One safe back step returns toward Explore/detail. Subsequent
            // normal detection re-enters the list without any blind tap.
            goBack(250L)
            return
        }

        burstUntil = max(burstUntil, predictionWindowUntil)
        val candidate = findTargetLockedCandidate(frame.bitmap, badges)
        if (candidate != null) {
            detailCameFromBirdMap = true
            lastBirdMapTapKey = candidate.key
            verifiedTap(
                candidate.x * frame.scaleX,
                candidate.y * frame.scaleY,
                RACE_TARGET_TAP_COOLDOWN_MS
            )
            return
        }

        lastFrameFingerprint = Long.MIN_VALUE
        lastProcessedFrameAt = 0L
        lastOcrAt = 0L
        nextActionAt = now + TARGET_MAP_RACE_POLL_MS
    }

    private fun learnTargetMapAnchor(bitmap: Bitmap, badges: List<MapBadge>) {
        val centerX = bitmap.width * TARGET_MAP_CENTER_X
        val centerY = bitmap.height * TARGET_MAP_CENTER_Y
        val points = ArrayList<Pair<Float, Float>>()
        badges.forEach { points.add(it.x to it.y) }
        findUnbadgedMushroomCandidates(bitmap, badges).forEach { points.add(it.x to it.y) }

        val best = points.minByOrNull { (x, y) ->
            val dx = (x - centerX) / bitmap.width
            val dy = (y - centerY) / bitmap.height
            dx * dx + dy * dy
        }

        if (best != null) {
            targetMapAnchorX = best.first
            targetMapAnchorY = best.second
        } else {
            // "前往這裡" centers the requested mushroom; if visual detection
            // cannot identify the old icon, use the map center as the anchor
            // rather than searching/tapping elsewhere.
            targetMapAnchorX = centerX
            targetMapAnchorY = centerY
        }
        targetMapAnchorReady = true
    }

    private fun findTargetLockedCandidate(
        bitmap: Bitmap,
        badges: List<MapBadge>
    ): MapCandidate? {
        val candidates = ArrayList<MapCandidate>()

        badges.forEach { badge ->
            if (badge.count < FREE_SLOT_LIMIT && !isMapPointRejected(badge.key)) {
                candidates.add(
                    MapCandidate(
                        x = badge.x,
                        y = badge.y,
                        key = badge.key,
                        participantCount = badge.count,
                        score = 20_000 - badge.count * 750
                    )
                )
            }
        }

        candidates.addAll(
            findUnbadgedMushroomCandidates(bitmap, badges)
                .filterNot { isMapPointRejected(it.key) }
        )

        return candidates
            .filter { isNearTargetMapAnchor(it.x, it.y, bitmap) }
            .maxByOrNull { candidate ->
                val dx = (candidate.x - targetMapAnchorX) / bitmap.width
                val dy = (candidate.y - targetMapAnchorY) / bitmap.height
                candidate.score - ((dx * dx + dy * dy) * 50_000f).toInt()
            }
    }

    private fun isNearTargetMapAnchor(x: Float, y: Float, bitmap: Bitmap): Boolean {
        if (!targetMapAnchorReady) return false
        val dx = (x - targetMapAnchorX) / bitmap.width
        val dy = (y - targetMapAnchorY) / bitmap.height
        return dx * dx + dy * dy <= TARGET_MAP_ANCHOR_RADIUS_NORM_SQ
    }

    private fun looksLikeBirdMap(
        bitmap: Bitmap,
        lines: List<Text.Line>,
        normalized: String
    ): Boolean {
        if (
            isMushroomList(normalized) ||
            normalized.contains("飾品一覽") ||
            normalized.contains("花苗和水果") ||
            normalized.contains("選擇派出皮克敏") ||
            normalized.contains("參加")
        ) return false
        if (normalized.contains("鳥瞰風景")) return true
        val badges = findMapBadges(bitmap, lines)
        return badges.size >= 2
    }
    private fun findMapBadges(bitmap: Bitmap, lines: List<Text.Line>): List<MapBadge> {
        val out = ArrayList<MapBadge>()
        val minY = (bitmap.height * 0.02f).toInt()
        val maxY = (bitmap.height * 0.88f).toInt()
        lines.forEach { line ->
            val text = clean(line.text)
            if (!MAP_COUNT_REGEX.matches(text)) return@forEach
            val count = text.toIntOrNull() ?: return@forEach
            if (count !in 0..99) return@forEach
            val box = line.boundingBox ?: return@forEach
            if (box.centerY() !in minY..maxY) return@forEach
            if (!hasTealParticipantBadge(bitmap, box)) return@forEach
            val x = (box.centerX() - bitmap.width * 0.018f)
                .coerceIn(4f, bitmap.width - 5f)
            val y = (box.bottom + bitmap.height * 0.030f)
                .coerceIn(4f, bitmap.height - 5f)
            val key = mapPointKey(x, y, bitmap.width, bitmap.height)
            out.add(MapBadge(count, x, y, key))
        }
        return out
    }
    private fun hasTealParticipantBadge(bitmap: Bitmap, box: Rect): Boolean {
        val padX = max(20, bitmap.width / 28)
        val padY = max(8, bitmap.height / 160)
        val left = max(0, box.left - padX)
        val right = min(bitmap.width, box.right + padX / 2)
        val top = max(0, box.top - padY)
        val bottom = min(bitmap.height, box.bottom + padY)
        if (right <= left || bottom <= top) return false
        var total = 0
        var teal = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (
                    g in 70..195 &&
                    b in 55..175 &&
                    r < 125 &&
                    g > r + 12 &&
                    g >= b - 35
                ) teal++
                total++
                x += 3
            }
            y += 3
        }
        return total > 0 && teal.toFloat() / total >= 0.10f
    }
    private fun findUnbadgedMushroomCandidates(
        bitmap: Bitmap,
        badges: List<MapBadge>
    ): List<MapCandidate> {
        val out = ArrayList<MapCandidate>()
        val step = max(5, bitmap.width / 180)
        val cell = max(28, bitmap.width / 24)
        val minY = (bitmap.height * 0.03f).toInt()
        val maxY = (bitmap.height * 0.80f).toInt()
        var cy = minY + cell / 2
        while (cy < maxY - cell) {
            var cx = cell / 2
            while (cx < bitmap.width - cell / 2) {
                val capScore = mushroomPatchScore(bitmap, cx, cy, cell, step)
                if (capScore >= MIN_MUSHROOM_PATCH_SCORE) {
                    val tapX = cx.toFloat()
                    val tapY = (cy + cell * 0.35f).coerceAtMost(bitmap.height - 5f)
                    val nearKnownBadge = badges.any { badge ->
                        val dx = badge.x - tapX
                        val dy = badge.y - tapY
                        dx * dx + dy * dy < (cell * 1.9f) * (cell * 1.9f)
                    }
                    if (!nearKnownBadge) {
                        val key = mapPointKey(tapX, tapY, bitmap.width, bitmap.height)
                        if (!isMapPointRejected(key) && out.none { sameMapArea(it.x, it.y, tapX, tapY, cell) }) {
                            out.add(
                                MapCandidate(
                                    x = tapX,
                                    y = tapY,
                                    key = key,
                                    participantCount = 0,
                                    score = capScore
                                )
                            )
                        }
                    }
                }
                cx += cell
            }
            cy += cell
        }
        return out
    }
    private fun mushroomPatchScore(
        bitmap: Bitmap,
        cx: Int,
        cy: Int,
        cell: Int,
        step: Int
    ): Int {
        val left = max(0, cx - cell / 2)
        val right = min(bitmap.width, cx + cell / 2)
        val top = max(0, cy - cell / 3)
        val mid = min(bitmap.height, cy + cell / 5)
        val bottom = min(bitmap.height, cy + cell)
        var red = 0
        var yellow = 0
        var pale = 0
        var y = top
        while (y < mid) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r > 185 && r > g + 35 && b < 155) red++
                if (r > 195 && g > 145 && b < 135 && abs(r - g) < 105) yellow++
                x += step
            }
            y += step
        }
        y = mid
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r > 170 && g > 145 && b > 95 && max(r, max(g, b)) - min(r, min(g, b)) < 115) {
                    pale++
                }
                x += step
            }
            y += step
        }
        if (red < 3 || yellow < 1 || pale < 3) return 0
        return red * 5 + yellow * 3 + pale
    }
    private fun sameMapArea(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Int
    ): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy < radius.toFloat() * radius.toFloat()
    }
    private fun estimateMapGreenRatio(bitmap: Bitmap): Float {
        var green = 0
        var total = 0
        val stepX = max(8, bitmap.width / 45)
        val stepY = max(8, bitmap.height / 60)
        val yMax = (bitmap.height * 0.88f).toInt()
        var y = 0
        while (y < yMax) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (g > 105 && g > r * 1.05f && g > b * 1.05f) green++
                total++
                x += stepX
            }
            y += stepY
        }
        return if (total == 0) 0f else green.toFloat() / total
    }
    private fun mapPointKey(x: Float, y: Float, width: Int, height: Int): Int {
        val qx = (x / max(1f, width / 24f)).toInt().coerceIn(0, 31)
        val qy = (y / max(1f, height / 32f)).toInt().coerceIn(0, 63)
        return qy * 64 + qx
    }
    private fun blacklistLastBirdMapPoint() {
        val key = lastBirdMapTapKey ?: return
        rejectedBirdMapPoints[key] = SystemClock.elapsedRealtime() + MAP_REJECT_MS
    }
    private fun isMapPointRejected(key: Int): Boolean {
        val until = rejectedBirdMapPoints[key] ?: return false
        if (SystemClock.elapsedRealtime() >= until) {
            rejectedBirdMapPoints.remove(key)
            return false
        }
        return true
    }
    private fun cleanupRejectedMapPoints() {
        val now = SystemClock.elapsedRealtime()
        rejectedBirdMapPoints.entries.removeIf { it.value <= now }
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
                if (hi > 65 && hi - lo > 28 && !(r > 235 && g > 235 && b > 235)) score++
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
        var hash = 1125899906842597L
        val cols = 8
        val rows = 10
        val yStart = (bitmap.height * 0.08f).toInt()
        val yEnd = (bitmap.height * 0.94f).toInt().coerceAtMost(bitmap.height - 1)
        for (ry in 0 until rows) {
            val y = yStart + ((yEnd - yStart) * ry / max(1, rows - 1))
            for (rx in 0 until cols) {
                val x = (bitmap.width - 1) * rx / max(1, cols - 1)
                val c = bitmap.getPixel(x, y)
                val qr = Color.red(c) shr 4
                val qg = Color.green(c) shr 4
                val qb = Color.blue(c) shr 4
                hash = hash * 31L + ((qr shl 8) or (qg shl 4) or qb)
            }
        }
        return hash
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
    private fun tapSelectionFallback(button: SelectionButton, cooldownMs: Long) {
        val dm = resources.displayMetrics
        when (button) {
            SelectionButton.AUTO -> verifiedTap(
                dm.widthPixels * 0.255f,
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
    private fun firstPhase(): SearchPhase =
        if (isWeekend()) SearchPhase.GIANT else SearchPhase.EVENT
    private fun advanceSearchPhaseAndRefresh() {
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        forceAdvanceOnList = false
        refreshPending = false
        raceSweepActive = false

        val hasNextPhase = when {
            isWeekend() && phase == SearchPhase.GIANT -> {
                phase = SearchPhase.EVENT
                true
            }
            !isWeekend() && phase == SearchPhase.EVENT -> {
                phase = SearchPhase.ANY
                true
            }
            else -> false
        }

        if (hasNextPhase) {
            beginRewind(RewindResume.SEARCH)
            return
        }

        phase = firstPhase()
        val now = SystemClock.elapsedRealtime()
        if (predictedSpawnAt > 0L || now < nextEtaInspectionAt) {
            beginRewind(RewindResume.PARK)
        } else {
            beginRewind(RewindResume.INSPECT)
        }
    }
    private fun markJoinSubmission() {
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        forceAdvanceOnList = false
        refreshPending = true
        reopenExploreAt = SystemClock.elapsedRealtime() + POST_JOIN_REFRESH_MS
        phase = firstPhase()
        rewindListPending = false
        rewindSwipeCount = 0
        rewindResume = RewindResume.SEARCH
        raceSweepActive = false
        raceBackupSweepAt = 0L
        urgentListChange = false
        parkedAtStart = false
        etaInspectionActive = false
        etaInspectionAdvancePending = false
        etaInspectionCount = 0
        nextEtaInspectionAt = 0L
        clearPrediction()
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
    }
    private fun rejectDetailAndAdvance() {
        if (detailCameFromBirdMap) {
            blacklistLastBirdMapPoint()
            detailCameFromBirdMap = false
            lastBirdMapTapKey = null
            joinTapAttempts = 0
            autoTapAttempts = 0
            goBack(200)
            return
        }
        forceAdvanceOnList = true
        joinTapAttempts = 0
        autoTapAttempts = 0
        goBack(200)
    }
    private fun prepareFailureRecovery() {
        if (detailCameFromBirdMap) blacklistLastBirdMapPoint()
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
        backOutStepsRemaining = 2
        forceAdvanceOnList = true
        refreshPending = true
        reopenExploreAt = SystemClock.elapsedRealtime() + refreshDelayMs()
        autoTapAttempts = 0
        joinTapAttempts = 0
    }
    private fun startBackOutToNextTarget(steps: Int) {
        if (detailCameFromBirdMap) blacklistLastBirdMapPoint()
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
        forceAdvanceOnList = true
        refreshPending = true
        reopenExploreAt = SystemClock.elapsedRealtime() + refreshDelayMs()
        backOutStepsRemaining = steps.coerceAtLeast(1)
        joinTapAttempts = 0
        autoTapAttempts = 0
        backOutStepsRemaining--
        goBack(200)
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
    private fun isDoneForToday(): Boolean =
        dailyRemaining == 0 && dailyDoneDate == LocalDate.now().toString()
    private fun resetDailyStopIfNeeded() {
        val today = LocalDate.now().toString()
        if (dailyDoneDate != null && dailyDoneDate != today) {
            dailyDoneDate = null
            dailyRemaining = null
            phase = firstPhase()
            listSwipeCount = 0
            lastListPosition = -1
            stuckListPositionCount = 0
            rewindListPending = false
            rewindSwipeCount = 0
            rewindResume = RewindResume.SEARCH
            raceSweepActive = false
            raceBackupSweepAt = 0L
            urgentListChange = false
            parkedAtStart = false
            etaInspectionActive = false
            etaInspectionAdvancePending = false
            etaInspectionCount = 0
            nextEtaInspectionAt = 0L
            clearPrediction()
            nextActionAt = 0L
        }
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
    private fun isMushroomList(normalized: String): Boolean =
        ExploreScreenRules.isMushroomList(normalized)
    private fun looksLikeMushroomDetail(normalized: String): Boolean {
        val value = clean(normalized)
        if (!value.contains("蘑菇")) return false
        return value.contains("參加") ||
            value.contains("預計") ||
            value.contains("結束") ||
            value.contains("完成") ||
            value.contains("剩餘") ||
            value.contains("還有")
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
    private fun isWeekend(): Boolean = when (LocalDate.now().dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> true
        else -> false
    }
    private fun clean(value: String): String = value.replace(Regex("\\s+"), "")
    private fun listCycleIdleMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 15_000L
        RunMode.WATCH -> 4_000L
        RunMode.RACE -> 1_200L
    }
    private fun scheduleFreshListScan(delayMs: Long) {
        if (!prefs.enabled) return
        listContextActive = true
        val whenAt = SystemClock.elapsedRealtime() + delayMs
        nextActionAt = whenAt
        main.removeCallbacks(listWatchdogKick)
        main.postDelayed(listWatchdogKick, delayMs + 20L)
    }
    private fun swipeListReliable(cooldownMs: Long) {
        dispatchListSwipe(forward = true, cooldownMs = cooldownMs)
    }

    private fun swipeListBackwardReliable(cooldownMs: Long) {
        dispatchListSwipe(forward = false, cooldownMs = cooldownMs)
    }

    private fun dispatchListSwipe(forward: Boolean, cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            if (listGestureInFlight) {
                main.removeCallbacks(listWatchdogKick)
                main.postDelayed(listWatchdogKick, LIST_GESTURE_BUSY_RETRY_MS)
                return@post
            }

            listContextActive = true
            listProgressGeneration++
            listGestureInFlight = true
            suppressListMutationEventsUntil =
                SystemClock.elapsedRealtime() + RACE_SELF_GESTURE_EVENT_SUPPRESS_MS

            val dm = resources.displayMetrics
            val y = dm.heightPixels * 0.705f
            val fromX = dm.widthPixels * if (forward) 0.86f else 0.14f
            val toX = dm.widthPixels * if (forward) 0.14f else 0.86f
            val path = Path().apply {
                moveTo(fromX, y)
                lineTo(toX, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, LIST_GESTURE_DURATION_MS))
                .build()

            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
            main.removeCallbacks(listWatchdogKick)

            val accepted = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        listGestureInFlight = false
                        armListAfterGesture(LIST_GESTURE_SETTLE_MS)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        listGestureInFlight = false
                        armListAfterGesture(LIST_GESTURE_CANCEL_RETRY_MS)
                    }
                },
                main
            )

            if (!accepted) {
                listGestureInFlight = false
                armListAfterGesture(LIST_GESTURE_CANCEL_RETRY_MS)
            } else {
                // Hard fallback in case a device/Unity combination delays the
                // normal callback. The watchdog sees in-flight and waits rather
                // than injecting a second gesture on top of this one.
                main.postDelayed(listWatchdogKick, LIST_GESTURE_HARD_TIMEOUT_MS)
            }
        }
    }

    private fun armListAfterGesture(delayMs: Long) {
        if (!prefs.enabled || !listContextActive) return
        lastFrameFingerprint = Long.MIN_VALUE
        lastProcessedFrameAt = 0L
        lastOcrAt = 0L
        nextActionAt = SystemClock.elapsedRealtime() + delayMs
        main.removeCallbacks(listWatchdogKick)
        main.postDelayed(listWatchdogKick, delayMs)
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
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.centerY() in (screenH * 0.44f).toInt()..(screenH * 0.91f).toInt()) {
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
                .addStroke(GestureDescription.StrokeDescription(path, 0, 42))
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
        listContextActive = false
        listGestureInFlight = false
        raceSweepActive = false
        urgentListChange = false
        parkedAtStart = false
        etaInspectionActive = false
        targetPositioning = false
        targetDetailOpenPending = false
        targetMapLockPending = false
        targetMapLockActive = false
        targetMapAnchorReady = false
        main.removeCallbacks(listWatchdogKick)
        main.removeCallbacksAndMessages(null)
        ocrBusy.set(false)
        recognizer.close()
        if (imageThread.isAlive) imageThread.quitSafely()
        super.onDestroy()
    }
    companion object {
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"
        private const val FREE_SLOT_LIMIT = 5
        private const val MAX_LIST_SWIPES = 18
        private const val MAX_STUCK_LIST_POSITION = 2
        private const val MAX_REWIND_SWIPES = 18
        private const val MAX_AUTO_TAP_ATTEMPTS = 5
        private const val MAX_JOIN_TAP_ATTEMPTS = 4
        private const val MAX_NODE_VISITS = 180
        private const val MAX_CHILDREN_PER_NODE = 40
        private const val OCR_MAX_WIDTH = 900
        private const val EVENT_BURST_MS = 1_600L
        private const val DAILY_DONE_RECHECK_MS = 60_000L
        private const val POST_JOIN_REFRESH_MS = 900L
        private const val MAP_REJECT_MS = 30_000L
        private const val MIN_MUSHROOM_PATCH_SCORE = 32
        private const val LIST_BUSY_RETRY_MS = 140L
        private const val LIST_PROGRESS_GUARD_MS = 900L
        private const val LIST_STALL_RETRY_MS = 180L
        private const val DECOR_GUARD_RETRY_MS = 350L
        private const val LIST_GESTURE_DURATION_MS = 190L
        private const val LIST_GESTURE_SETTLE_MS = 90L
        private const val LIST_GESTURE_CANCEL_RETRY_MS = 140L
        private const val LIST_GESTURE_BUSY_RETRY_MS = 80L
        private const val LIST_GESTURE_HARD_TIMEOUT_MS = 850L
        private const val RACE_BACKUP_SWEEP_MS = 20_000L
        private const val RACE_SWEEP_COOLDOWN_MS = 150L
        private const val RACE_REWIND_COOLDOWN_MS = 120L
        private const val RACE_TARGET_TAP_COOLDOWN_MS = 160L
        private const val RACE_SELF_GESTURE_EVENT_SUPPRESS_MS = 420L
        private const val RACE_LIST_EVENT_DEBOUNCE_MS = 180L
        private const val MAX_ETA_INSPECTION_CARDS = 18
        private const val ETA_INSPECTION_HORIZON_MS = 10L * 60L * 1000L
        private const val ETA_REINSPECTION_MS = 2L * 60L * 1000L
        private const val ETA_INSPECTION_SWIPE_COOLDOWN_MS = 210L
        private const val ETA_DETAIL_OPEN_COOLDOWN_MS = 220L
        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L
        private const val TARGET_CARD_RETRY_MS = 220L
        private const val TARGET_MAP_OPEN_COOLDOWN_MS = 300L
        private const val TARGET_MAP_BUTTON_RETRY_MS = 180L
        private const val MAX_TARGET_MAP_OPEN_ATTEMPTS = 4
        private const val TARGET_MAP_IDLE_MIN_MS = 600L
        private const val TARGET_MAP_IDLE_MAX_MS = 2_000L
        private const val TARGET_MAP_RACE_POLL_MS = 120L
        private const val MUSHROOM_RESPAWN_DELAY_MS = 5L * 60L * 1000L
        private const val PREDICTION_PREWARM_LEAD_MS = 30_000L
        private const val PREDICTION_AFTER_WINDOW_MS = 90_000L
        private const val PREDICTION_PARK_POLL_MS = 220L
        private const val PREDICTION_SWEEP_INTERVAL_MS = 1_500L
        private val FULL_TEAM_REGEX = Regex("(\\d{1,3})/\\1")
        private const val TARGET_MAP_CENTER_X = 0.50f
        private const val TARGET_MAP_CENTER_Y = 0.46f
        private const val TARGET_MAP_ANCHOR_RADIUS_NORM_SQ = 0.040f
        private val GO_TO_MAP_TEXTS = listOf("前往這裡", "前往此處", "前往該處")
        private val DAILY_REMAINING_REGEX = Regex("今天還剩下([0-9]{1,2})次")
        private val LIST_POSITION_REGEX = Regex("蘑菇[:：]?([0-9]{1,2})/([0-9]{1,2})")
        private val MAP_COUNT_REGEX = Regex("[0-9]{1,2}")
        private val PARTICIPANT_REGEXES = listOf(
            Regex("參加者?([0-9]{1,2})人"),
            Regex("([0-9]{1,2})人參加"),
            Regex("([0-9]{1,2})/5人?"),
            Regex("目前([0-9]{1,2})人")
        )
    }
}
