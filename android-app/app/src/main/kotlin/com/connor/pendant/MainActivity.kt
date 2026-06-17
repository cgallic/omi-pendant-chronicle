package com.connor.pendant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import java.util.Locale
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.connor.pendant.ble.OmiBleClient
import com.connor.pendant.health.HealthConnectReader
import com.connor.pendant.net.ChronicleApi
import com.connor.pendant.net.ChronicleSnapshot
import com.connor.pendant.net.ChronicleSnapshotCache
import com.connor.pendant.service.PendantForegroundService
import com.connor.pendant.store.AudioStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"
    private val handler = Handler(Looper.getMainLooper())
    private val healthReader = HealthConnectReader(this)
    private lateinit var api: ChronicleApi
    private lateinit var cache: ChronicleSnapshotCache
    private lateinit var body: LinearLayout
    private lateinit var navStrip: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var serviceChip: TextView
    private lateinit var listeningChip: TextView
    private lateinit var rawChip: TextView
    private lateinit var translateChip: TextView
    private lateinit var batteryChip: TextView

    private val visibleScreens = listOf(
        Screen.TODAY,
        Screen.MEMORIES,
        Screen.ACTIONS,
        Screen.ASK,
        Screen.MAP
    )

    private var selectedScreen = Screen.TODAY
    private var permissionAction: (() -> Unit)? = null
    private var snapshot: ChronicleSnapshot? = null
    private var isRefreshing = false
    private var cacheLoadedAt = 0L
    private var lastRefreshAt = 0L
    private var lastError: String? = null
    private var firstResume = true
    private var lastChatQuestion = ""
    private var lastChatAnswer: JSONObject? = null
    private var chatLoading = false
    private var livePollInFlight = false
    private var lastLivePollAt = 0L
    private var lastLiveError: String? = null
    private var storePollInFlight = false
    private var lastStorePollAt = 0L
    private var storeStatus: AudioStore.Status? = null
    private var dismissedInsightKey: String? = null
    private var liveTranscriptContainer: LinearLayout? = null
    private var liveStreamStatusText: TextView? = null
    private var transferCardVisible = false
    private var recordingProofActiveVisible = false
    private var transferTitleText: TextView? = null
    private var transferDetailText: TextView? = null
    private var transferProgress: ProgressBar? = null
    private var transferStatsText: TextView? = null
    private var bottomGestureInsetPx = 0

    private val statusTicker = object : Runnable {
        override fun run() {
            val transferActive = PendantForegroundService.instance
                ?.bleClient
                ?.storageSync
                ?.value
                ?.active == true
            val proofActive = PendantForegroundService.instance
                ?.bleClient
                ?.storageStats
                ?.value
                ?.let { it.active || it.proofActive } == true
            if (transferActive != transferCardVisible || proofActive || proofActive != recordingProofActiveVisible) {
                recordingProofActiveVisible = proofActive
                renderContent()
            } else {
                renderHeader()
                updateTransferCard()
            }
            maybePollStoreStatus()
            maybePollLiveTranscript()
            handler.postDelayed(this, 10_000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            val action = permissionAction ?: { startPendantService() }
            permissionAction = null
            action()
        } else {
            permissionAction = null
            toast("Bluetooth and context permissions are needed for pendant controls.")
        }
    }

    private val hcPermissionLauncher = registerForActivityResult(
        healthReader.permissionContract()
    ) { granted ->
        Log.i(tag, "Health Connect permissions granted: ${granted.size}/${healthReader.requestedPermissions.size}")
    }

    private var quickNoteInput: EditText? = null

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                quickNoteInput?.let {
                    val currentText = it.text.toString()
                    val newText = if (currentText.isEmpty()) spokenText else "$currentText $spokenText"
                    it.setText(newText)
                    it.setSelection(newText.length)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = NAV
        api = ChronicleApi(apiBase(), audioOutHttpBase(), BuildConfig.PENDANT_SECRET)
        cache = ChronicleSnapshotCache(this)
        snapshot = cache.load()
        cacheLoadedAt = cache.savedAt()
        lastRefreshAt = cacheLoadedAt

        setContentView(buildShell())
        askPermissionsThen { startPendantService() }
        maybeRequestHealthConnect()
        reloadData(showBlank = snapshot == null)
        startStatusTicker()
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setOnApplyWindowInsetsListener { view, insets ->
                val top = insets.getInsets(WindowInsets.Type.statusBars()).top
                val bottom = maxOf(
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom,
                )
                bottomGestureInsetPx = bottom
                view.setPadding(0, top, 0, 0)
                applyNavSafeAreaPadding()
                insets
            }
        }

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 14.dp(), 14.dp(), 20.dp())
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
        }

        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(NAV)
            addView(navRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        applyNavSafeAreaPadding()

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )
        root.addView(
            navStrip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        renderContent()
        return root
    }

    private fun applyNavSafeAreaPadding() {
        if (!::navStrip.isInitialized) return
        val bottomPad = maxOf(18.dp(), bottomGestureInsetPx + 12.dp())
        navStrip.setPadding(12.dp(), 8.dp(), 12.dp(), bottomPad)
        navStrip.clipToPadding = false
    }

    private fun reloadData(showBlank: Boolean = false) {
        lastError = null
        isRefreshing = true
        if (showBlank || snapshot == null) {
            showLoading()
        } else {
            renderContent()
        }
        lifecycleScope.launch {
            var cockpitLoaded = false
            var cockpitError: String? = null

            runCatching { api.cockpitSnapshot() }
                .onSuccess { fresh ->
                    cockpitLoaded = true
                    applyFreshSnapshot(fresh, keepRefreshing = true)
                }
                .onFailure {
                    Log.w(tag, "cockpit refresh failed: ${it.message}", it)
                    cockpitError = it.message ?: "Network error"
                    if (snapshot == null) {
                        lastError = cockpitError
                        renderContent()
                    }
                }

            runCatching { api.snapshot() }
                .onSuccess { fresh ->
                    applyFreshSnapshot(fresh, keepRefreshing = false)
                }
                .onFailure {
                    Log.w(tag, "full snapshot failed: ${it.message}", it)
                    isRefreshing = false
                    if (!cockpitLoaded) {
                        lastError = cockpitError ?: it.message ?: "Network error"
                        renderContent()
                    } else {
                        renderContent()
                    }
                }
        }
    }

    private fun applyFreshSnapshot(fresh: ChronicleSnapshot, keepRefreshing: Boolean) {
        val previous = snapshot
        val merged = mergeWithCachedSnapshot(fresh, previous)
        snapshot = merged
        cache.save(merged)
        cacheLoadedAt = 0L
        lastRefreshAt = System.currentTimeMillis()
        isRefreshing = keepRefreshing
        lastError = primarySnapshotError(fresh, merged, previous)
        renderContent()
    }

    private fun primarySnapshotError(
        fresh: ChronicleSnapshot,
        merged: ChronicleSnapshot,
        previous: ChronicleSnapshot?,
    ): String? {
        val coreFailures = listOf("memories", "actionItems", "approvals").filter { fresh.errors.has(it) }
        if (coreFailures.isEmpty()) return null
        if (previous != null) {
            return "Using cached ${coreFailures.joinToString(", ")} while Chronicle refresh catches up."
        }
        return if (merged.memories.optInt("count", 0) == 0 && fresh.errors.has("memories")) {
            fresh.errors.optString("memories")
        } else {
            null
        }
    }

    private fun mergeWithCachedSnapshot(fresh: ChronicleSnapshot, previous: ChronicleSnapshot?): ChronicleSnapshot {
        if (previous == null) return fresh
        return fresh.copy(
            status = keepCachedIfErrored(fresh.status, previous.status),
            memories = keepCachedIfErrored(fresh.memories, previous.memories),
            actionItems = keepCachedIfErrored(fresh.actionItems, previous.actionItems),
            approvals = keepCachedIfErrored(fresh.approvals, previous.approvals),
            live = keepCachedIfErrored(fresh.live, previous.live),
            listening = keepCachedIfErrored(fresh.listening, previous.listening),
            health = keepCachedIfErrored(fresh.health, previous.health),
            translation = keepCachedIfErrored(fresh.translation, previous.translation),
            graph = keepCachedIfErrored(fresh.graph, previous.graph),
            insights = keepCachedIfErrored(fresh.insights, previous.insights),
            audio = keepCachedIfErrored(fresh.audio, previous.audio),
            audioHistory = keepCachedIfErrored(fresh.audioHistory, previous.audioHistory),
            rawTranscripts = keepCachedIfErrored(fresh.rawTranscripts, previous.rawTranscripts),
        )
    }

    private fun keepCachedIfErrored(fresh: JSONObject, cached: JSONObject): JSONObject =
        if (fresh.has("_error") && cached.length() > 0) cached else fresh

    private fun renderContent() {
        if (selectedScreen == Screen.CAPTURE) {
            selectedScreen = Screen.TODAY
        } else if (selectedScreen == Screen.APPROVALS) {
            selectedScreen = Screen.ACTIONS
        }
        liveTranscriptContainer = null
        liveStreamStatusText = null
        transferTitleText = null
        transferDetailText = null
        transferProgress = null
        transferStatsText = null
        body.removeAllViews()
        body.addView(header())
        val transferCard = storageTransferCard()
        transferCardVisible = transferCard != null
        if (transferCard != null) {
            body.addView(transferCard)
        }
        when (selectedScreen) {
            Screen.TODAY -> renderToday()
            Screen.CAPTURE -> renderCapture()
            Screen.TRANSLATE -> renderTranslate()
            Screen.ACTIONS -> renderActions()
            Screen.APPROVALS -> renderApprovals()
            Screen.MEMORIES -> renderMemories()
            Screen.MAP -> renderMap()
            Screen.HEALTH -> renderHealth()
            Screen.ASK -> renderAsk()
            Screen.PROCESSING -> renderProcessing()
        }
        renderHeader()
        renderNav()
    }

    private fun renderNav() {
        if (!::navRow.isInitialized) return
        navRow.removeAllViews()
        visibleScreens.forEach { screen ->
            navRow.addView(navButton(screen))
        }
    }

    private fun navButton(screen: Screen): MaterialButton =
        MaterialButton(this).apply {
            text = if (screen == Screen.ACTIONS) "Inbox" else screen.label
            isAllCaps = false
            scaledTextSize = 11f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            minHeight = 0
            minimumHeight = 0
            cornerRadius = 10.dp()
            insetTop = 0
            insetBottom = 0
            setPadding(0, 10.dp(), 0, 10.dp())
            val selected = selectedScreen == screen
            setTextColor(if (selected) ACCENT else MUTED)
            backgroundTintList = ColorStateList.valueOf(if (selected) 0x1A9F7AEA.toInt() else 0)
            strokeColor = ColorStateList.valueOf(0)
            strokeWidth = 0
            setOnClickListener { selectScreen(screen) }
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

    private fun selectScreen(screen: Screen) {
        val target = when (screen) {
            Screen.CAPTURE -> Screen.TODAY
            Screen.APPROVALS -> Screen.ACTIONS
            else -> screen
        }
        selectedScreen = target
        renderContent()
        if (target == Screen.TODAY) {
            maybePollLiveTranscript(force = true)
        }
    }

    private fun header(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2.dp(), 0, 18.dp())
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            TextView(this).apply {
                text = "Chronicle"
                scaledTextSize = 12f
                setTextColor(ACCENT)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(
            pillButton("Settings", Variant.QUIET) { showSettingsSheet() }
        )

        val title = TextView(this).apply {
            text = screenTitle()
            scaledTextSize = 24f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, 12.dp(), 0, 8.dp())
        }

        statusText = TextView(this).apply {
            scaledTextSize = 13f
            setTextColor(MUTED)
            setLineSpacing(2f, 1.03f)
        }

        val chips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 18.dp(), 0, 6.dp())
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            serviceChip = chip("Service")
            listeningChip = chip("Live")
            rawChip = chip("Audio")
            translateChip = chip("Translate")
            batteryChip = chip("Battery")
            addView(serviceChip)
            addView(listeningChip)
            addView(rawChip)
            addView(translateChip)
            addView(batteryChip)
        }
        chips.addView(chipRow)

        header.addView(topRow)
        header.addView(title)
        header.addView(statusText)
        header.addView(chips)
        return header
    }

    private fun renderHeader() {
        if (!::statusText.isInitialized) return
        val snap = snapshot
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val counts = snap?.status?.optJSONObject("counts")
        val raw = snap?.status?.optJSONObject("raw_frames")
        val streaming = raw?.optBoolean("is_streaming", false) == true ||
            snap?.live?.optBoolean("is_listening", false) == true ||
            service?.isLiveMode == true

        val bleState = ble?.state?.value?.name ?: "IDLE"
        val pendantConnected = bleState in setOf("READY", "SUBSCRIBED", "SYNCING")
        val battery = ble?.batteryPct?.value
        if (pendantConnected && battery != null) {
            batteryChip.visibility = View.VISIBLE
            batteryChip.text = "Battery $battery%"
            val batteryColor = when {
                battery > 50 -> GOOD
                battery > 20 -> 0xFFDD6B20.toInt()
                else -> DANGER
            }
            val batteryColorDark = when {
                battery > 50 -> GOOD_DARK
                battery > 20 -> 0xFF3E1F00.toInt()
                else -> DANGER_BG
            }
            batteryChip.background = rounded(batteryColorDark, batteryColor, 999f)
        } else {
            batteryChip.visibility = View.GONE
        }
        serviceChip.text = if (pendantConnected) "Pendant connected" else "Pendant ${bleState.lowercase()}"
        serviceChip.background = rounded(if (pendantConnected) GOOD_DARK else CHIP_BG, if (pendantConnected) GOOD else CARD_BORDER, 999f)
        listeningChip.text = if (streaming) "Live capture" else "Quiet"
        listeningChip.background = rounded(if (streaming) GOOD_DARK else CHIP_BG, if (streaming) GOOD else CARD_BORDER, 999f)
        val latestMemory = latestMemory()
        val latestMemoryTitle = memoryTitle(latestMemory)
        val latestMemoryWhen = memoryStartedAt(latestMemory)
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        rawChip.text = when {
            rawAge >= 0.0 -> "Last audio ${shortAgeSeconds(rawAge)}"
            latestMemoryWhen.isNotBlank() -> "Remembered ${ago(latestMemoryWhen)}"
            else -> "Audio unknown"
        }
        translateChip.text = "Translate ${translationSummary(compact = true)}"
        translateChip.background = rounded(
            if (translationActive()) TRANSLATE_DARK else CHIP_BG,
            if (translationActive()) TRANSLATE else CARD_BORDER,
            999f,
        )

        val openActions = snap?.actionItems?.optInt("count", counts?.optInt("open_actions", 0) ?: 0) ?: 0
        val approvals = (snap?.approvals?.optJSONArray("pending") ?: JSONArray()).length()
        val indexed = counts?.optInt("qdrant_points", 0) ?: 0
        val refreshLine = when {
            lastError != null && snap != null -> "Using cached data"
            isRefreshing && snap != null -> "Refreshing in background"
            lastRefreshAt > 0L -> "Updated ${agoMillis(System.currentTimeMillis() - lastRefreshAt)}"
            else -> "No cached Chronicle snapshot yet"
        }
        val memoryCount = snap?.memories?.optInt("count", -1)?.takeIf { it >= 0 } ?: indexed
        val countLine = "$memoryCount memories | $openActions TODOs | $approvals Scout"

        statusText.text = when {
            lastError != null && snap != null -> "Cached Chronicle state | $refreshLine\n$countLine"
            lastError != null -> "Chronicle API is unreachable.\n$lastError"
            latestMemoryTitle.isNotBlank() -> "$countLine\nLatest: $latestMemoryTitle${if (latestMemoryWhen.isNotBlank()) " | ${ago(latestMemoryWhen)}" else ""} | $refreshLine"
            else -> "$countLine\n$refreshLine"
        }
    }

    private fun renderToday() {
        snapshotStateBanner()?.let { body.addView(it) }
        if (isListening()) {
            body.addView(liveTranscriptCard(liveSegments(snapshot?.live ?: JSONObject())))
        }
        body.addView(companionNowCard())
        body.addView(quickNoteCard())
        body.addView(homeAskCard())
        body.addView(homeAttentionCardCombined())
        body.addView(homeMemoriesCard())
        body.addView(homeMapPreviewCard())
    }

    private fun homeAskCard(): View {
        return card {
            addView(meta("Query your day"))
            addView(cardTitle("Ask Chronicle"))
            addView(bodyText("Search or ask questions about what you've captured recently."))
            
            val searchContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp(), 0, 10.dp())
            }
            
            val input = EditText(this@MainActivity).apply {
                hint = "Ask about what just happened..."
                scaledTextSize = 14f
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                inputType = InputType.TYPE_CLASS_TEXT
                background = rounded(BG_SOFT, CARD_BORDER, 10.dp().toFloat())
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                maxLines = 1
                if (lastChatQuestion.isNotEmpty()) {
                    setText(lastChatQuestion)
                    setSelection(lastChatQuestion.length)
                }
            }
            
            val askButton = MaterialButton(this@MainActivity).apply {
                text = "Ask"
                isAllCaps = false
                scaledTextSize = 13f
                cornerRadius = 10.dp()
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(18.dp(), 12.dp(), 18.dp(), 12.dp())
                setTextColor(BG)
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                strokeColor = ColorStateList.valueOf(ACCENT)
                strokeWidth = 1.dp()
                setOnClickListener {
                    val query = input.text.toString().trim()
                    if (query.isNotEmpty()) {
                        selectedScreen = Screen.ASK
                        sendChat(query)
                    } else {
                        toast("Please enter a question first.")
                    }
                }
            }
            
            searchContainer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8.dp(), 0)
            })
            searchContainer.addView(askButton)
            addView(searchContainer)
            
            addView(TextView(this@MainActivity).apply {
                text = "SUGGESTED QUESTIONS"
                scaledTextSize = 11f
                letterSpacing = 0.06f
                setTextColor(MUTED)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(2.dp(), 6.dp(), 0, 8.dp())
            })
            
            val scroller = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            
            val suggestions = listOf(
                "Summarize today",
                "Who did I meet?",
                "What tasks did I promise?",
                "Key decisions made"
            )
            
            suggestions.forEach { suggestion ->
                val suggestionButton = MaterialButton(this@MainActivity).apply {
                    text = suggestion
                    isAllCaps = false
                    scaledTextSize = 12f
                    cornerRadius = 22.dp()
                    minHeight = 0
                    minimumHeight = 0
                    insetTop = 0
                    insetBottom = 0
                    setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
                    setTextColor(TEXT)
                    backgroundTintList = ColorStateList.valueOf(CHIP_BG)
                    strokeColor = ColorStateList.valueOf(CARD_BORDER)
                    strokeWidth = 1.dp()
                    setOnClickListener {
                        selectedScreen = Screen.ASK
                        sendChat(suggestion)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, 0, 6.dp(), 0) }
                }
                row.addView(suggestionButton)
            }
            
            scroller.addView(row)
            addView(scroller)
        }
    }

    private fun homeAttentionCardCombined(): View {
        val actions = snapshot?.actionItems?.optJSONArray("items") ?: JSONArray()
        val approvals = snapshot?.approvals?.optJSONArray("pending") ?: JSONArray()
        val total = actions.length() + approvals.length()
        
        return card {
            addView(meta("Work Queue"))
            if (total == 0) {
                addView(cardTitle("All Caught Up"))
                addView(bodyText("You have no pending TODOs or Scout approvals. Everything is clear!"))
                addView(scrollButtonRow {
                    addView(pillButton("To-dos", Variant.QUIET) { selectScreen(Screen.ACTIONS) })
                    addView(pillButton("Scout", Variant.QUIET) { selectScreen(Screen.APPROVALS) })
                })
            } else {
                addView(cardTitle("$total items need your attention"))
                addView(bodyText("Review your personal TODOs and approve Scout's automated actions below."))
                
                val showTodosCount = minOf(actions.length(), 3)
                if (showTodosCount > 0) {
                    addView(TextView(this@MainActivity).apply {
                        text = "PERSONAL TODOs"
                        scaledTextSize = 10f
                        letterSpacing = 0.06f
                        setTextColor(ACCENT)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(2.dp(), 12.dp(), 0, 4.dp())
                    })
                    for (i in 0 until showTodosCount) {
                        val item = actions.optJSONObject(i) ?: continue
                        addView(homeTodoRow(item))
                    }
                }
                
                val showApprovalsCount = minOf(approvals.length(), 2)
                if (showApprovalsCount > 0) {
                    addView(TextView(this@MainActivity).apply {
                        text = "SCOUT APPROVALS"
                        scaledTextSize = 10f
                        letterSpacing = 0.06f
                        setTextColor(ACCENT)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(2.dp(), 12.dp(), 0, 4.dp())
                    })
                    for (i in 0 until showApprovalsCount) {
                        val approval = approvals.optJSONObject(i) ?: continue
                        addView(homeScoutRow(approval))
                    }
                }
                
                addView(scrollButtonRow {
                    addView(pillButton("Manage To-dos", if (actions.length() > 0) Variant.PRIMARY else Variant.QUIET) { selectScreen(Screen.ACTIONS) })
                    addView(pillButton("Manage Scout", if (approvals.length() > 0) Variant.PRIMARY else Variant.QUIET) { selectScreen(Screen.APPROVALS) })
                })
            }
        }
    }

    private fun homeMapPreviewCard(): View {
        val graph = snapshot?.graph ?: JSONObject()
        val nodes = graph.optJSONArray("nodes") ?: JSONArray()
        val nodeCount = nodes.length()
        
        return card {
            addView(meta("Knowledge Map Preview"))
            addView(cardTitle("Your Knowledge Graph"))
            addView(bodyText("Key people, topics, and organizations extracted from your recent conversations. Tap an entity to ask Chronicle about it."))
            
            if (nodeCount == 0) {
                addView(bodyText("No entities extracted yet. Speak into the pendant or sync offline files to populate the map."))
                addView(scrollButtonRow {
                    addView(pillButton("Open Map", Variant.QUIET) { selectScreen(Screen.MAP) })
                })
            } else {
                val scroller = HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, 10.dp(), 0, 10.dp())
                }
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                
                val topNodes = mutableListOf<JSONObject>()
                for (i in 0 until minOf(nodeCount, 8)) {
                    nodes.optJSONObject(i)?.let { topNodes.add(it) }
                }
                
                topNodes.forEach { node ->
                    val label = node.cleanString("label")
                        .ifBlank { node.cleanString("name") }
                        .ifBlank { node.cleanString("id", "Entity") }
                    val type = node.cleanString("type", "topic")
                    
                    val chipButton = MaterialButton(this@MainActivity).apply {
                        text = label
                        isAllCaps = false
                        scaledTextSize = 12f
                        cornerRadius = 10.dp()
                        minHeight = 0
                        minimumHeight = 0
                        insetTop = 0
                        insetBottom = 0
                        setPadding(16.dp(), 11.dp(), 16.dp(), 11.dp())
                        
                        val typeColor = when (type.lowercase()) {
                            "person" -> 0xFF805AD5.toInt()
                            "organization" -> 0xFF3182CE.toInt()
                            "topic" -> 0xFF319795.toInt()
                            else -> CHIP_BG
                        }
                        
                        setTextColor(TEXT)
                        backgroundTintList = ColorStateList.valueOf(CHIP_BG)
                        strokeColor = ColorStateList.valueOf(if (typeColor != CHIP_BG) typeColor else CARD_BORDER)
                        strokeWidth = 1.dp()
                        
                        setOnClickListener {
                            selectedScreen = Screen.ASK
                            sendChat("What do my memories say about $label?")
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { setMargins(0, 0, 8.dp(), 0) }
                    }
                    row.addView(chipButton)
                }
                
                scroller.addView(row)
                addView(scroller)
                
                addView(scrollButtonRow {
                    addView(pillButton("View Full Map", Variant.PRIMARY) { selectScreen(Screen.MAP) })
                    addView(pillButton("Ask Graph", Variant.QUIET) {
                        selectedScreen = Screen.ASK
                        sendChat("Show me my recent knowledge graph summary.")
                    })
                })
            }
        }
    }

    private fun todayCaptureEvidenceCard(): View {
        val snap = snapshot
        val memories = snap?.memories?.optJSONArray("memories") ?: JSONArray()
        val todayMemories = memoriesForLocalDate(memories, LocalDate.now(ZoneId.systemDefault()))
        val latestTodayMemory = todayMemories.firstOrNull()
        val latestTodayTranscript = latestTodayTranscriptChunk()
        val raw = snap?.status?.optJSONObject("raw_frames")
        val audio = snap?.audio ?: JSONObject()
        val rawTranscripts = snap?.rawTranscripts ?: JSONObject()
        val transcriptItems = rawTranscripts.optJSONArray("items") ?: JSONArray()
        val transcriptCount = rawTranscripts.optInt("count", transcriptItems.length())
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val rawBytes = audio.optLong("raw_bytes", raw?.optLong("bytes_today", 0L) ?: 0L)
        val store = storeStatus
        val phoneHasAudio = store?.let {
            it.queueCount > 0 || it.storedBytes > 0L || it.currentBytes > 0L || it.currentFrames > 0
        } == true
        val recordedAt = memoryRecordedInstant(latestTodayMemory) ?: transcriptRecordedInstant(latestTodayTranscript)
        val transcriptMtime = parseIsoInstant(latestTodayTranscript?.cleanString("mtime").orEmpty())
        val hasBackendAudio = rawBytes > 0L || rawAge >= 0.0 || latestTodayTranscript != null
        val hasTodayMemory = latestTodayMemory != null

        return card {
            addView(meta("Today evidence"))
            addView(cardTitle(when {
                hasTodayMemory -> "Memory ready; source time needs proof"
                latestTodayTranscript != null -> "Transcript exists; memory pending"
                rawBytes > 0L || rawAge >= 0.0 -> "Uploaded today; waiting on memory"
                phoneHasAudio -> "Phone has local audio to reconcile"
                isRefreshing -> "Checking today's capture"
                else -> "No raw audio found today yet"
            }))
            addView(bodyText("Today uses recorded/transcript evidence in the device-local day. Older memories stay in Memories and should not imply that this morning was captured."))
            addView(statusGrid(
                listOf(
                    "Recorded" to when {
                        recordedAt != null && latestTodayMemory != null -> memoryTimestampWarning(recordedAt)
                        recordedAt != null -> evidenceTimeLine(recordedAt)
                        phoneHasAudio -> "phone-local evidence"
                        else -> "no raw audio found"
                    },
                    "Uploaded" to when {
                        rawBytes > 0L -> "${formatBytes(rawBytes)} today"
                        rawAge >= 0.0 -> "visible ${shortAgeSeconds(rawAge)}"
                        latestTodayTranscript != null -> "uploaded; transcript exists"
                        phoneHasAudio -> "pending phone ack"
                        else -> "not visible"
                    },
                    "Transcribing" to when {
                        latestTodayTranscript != null && transcriptMtime != null -> evidenceTimeLine(transcriptMtime)
                        latestTodayTranscript != null -> "$transcriptCount chunks"
                        hasBackendAudio -> "waiting"
                        else -> "not started"
                    },
                    "Memory" to when {
                        latestTodayMemory != null -> "ready; source unverified"
                        hasBackendAudio -> "memory pending"
                        else -> "no memory ready"
                    },
                    "Cache" to snapshotFreshnessLine(),
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Refresh", Variant.PRIMARY) { reloadData() })
                addView(pillButton("Processing", Variant.QUIET) { selectScreen(Screen.PROCESSING) })
                addView(pillButton("All Memories", Variant.QUIET) { selectScreen(Screen.MEMORIES) })
            })
        }
    }

    private fun recordingProofCard(): View {
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val stats = ble?.storageStats?.value
        val live = isListening()
        val now = System.currentTimeMillis()
        val error = stats?.lastError?.takeIf { it.isNotBlank() }
        val title = when {
            stats == null -> "Start companion to check recording"
            stats.active -> "Reading pendant flash"
            stats.proofActive -> "Talk now; checking flash growth"
            error != null -> "Recording proof needs attention"
            (stats.deltaBytes ?: 0L) > 0L -> "Recording to pendant flash"
            stats.deltaBytes == 0L -> "No flash growth detected"
            live -> "Live frame counter is active"
            stats.lastReadAt > 0L && stats.usedBytes > 0L -> "Flash has stored audio"
            stats.lastReadAt > 0L -> "No flash audio reported"
            else -> "Check if it is recording"
        }
        val proofLine = when {
            error != null -> error
            stats?.message?.isNotBlank() == true -> stats.message.orEmpty()
            live -> "Live capture is connected; watch live frames and transcript."
            else -> "Not checked yet"
        }

        return card {
            addView(meta("Recording proof"))
            addView(cardTitle(title))
            addView(bodyText("This reads pendant flash counters without syncing or deleting audio. It samples once, disconnects for at least 15 seconds while you talk, then samples again. Growth is the direct proof that offline recording is happening."))
            addView(statusGrid(
                listOf(
                    "Proof" to proofLine,
                    "Flash used" to stats?.usedBytes?.takeIf { it >= 0L }?.let { formatBytes(it) }.orEmpty().ifBlank { "unknown" },
                    "Files" to stats?.fileCount?.takeIf { it >= 0 }?.toString().orEmpty().ifBlank { "unknown" },
                    "Growth" to storageGrowthLine(stats),
                    "Last check" to stats?.lastReadAt?.takeIf { it > 0L }?.let { agoMillis(now - it) }.orEmpty().ifBlank { "never" },
                )
            ))
            if (stats?.deltaBytes == 0L) {
                addView(provenanceText("No growth means the pendant did not report new flash bytes during that 15-second sample. Run it while speaking with BLE otherwise idle; if it still stays flat, this is a recording/firmware issue, not a memory issue."))
            }
            addView(scrollButtonRow {
                addView(pillButton("Check Recording", Variant.PRIMARY) { proveRecording() })
                addView(pillButton("Sync Now", Variant.QUIET) { syncNow() })
                if (live) {
                    addView(pillButton("Pause Live", Variant.QUIET) { stopLiveMode() })
                }
            })
        }
    }

    private fun storageGrowthLine(stats: OmiBleClient.StorageStatsStatus?): String {
        val delta = stats?.deltaBytes ?: return "not sampled"
        return when {
            delta > 0L -> "+${formatBytes(delta)}"
            delta < 0L -> "counter reset"
            else -> "no growth"
        }
    }

    private fun latestMemoryCheckpointCard(): View? {
        val memory = latestMemory() ?: return null
        val title = memoryTitle(memory).ifBlank { "Chronicle remembered a conversation" }
        val overview = memoryOverview(memory)
        val startedAt = memoryStartedAt(memory)
        val createdAt = memoryCreatedAt(memory)
        val recordedToday = isMemoryRecordedToday(memory)
        val id = memory.cleanString("id")
        val whenLine = if (startedAt.isNotBlank()) memoryTimestampWarning(startedAt) else "not exposed"
        val createdLine = if (createdAt.isNotBlank()) evidenceTimeLine(createdAt) else "not exposed"

        return card {
            addView(meta(if (recordedToday) "Memory timestamp today; source unverified" else "Latest memory, not today"))
            addView(cardTitle(title))
            if (overview.isNotBlank()) {
                addView(bodyText(overview))
            } else if (!recordedToday) {
                addView(bodyText("This is the latest final memory. Processing shows raw audio and transcripts, so do not treat an older memory as proof that today's capture is missing."))
            } else {
                addView(bodyText("This conversation is in the memory layer, but the API does not link it to the raw audio that produced it. Treat the memory timestamp as unverified until Processing shows source audio and transcript evidence."))
            }
            addView(statusGrid(
                listOf(
                    "Memory timestamp" to whenLine,
                    "Created" to createdLine,
                    "State" to if (recordedToday) "source unverified" else "memory ready",
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Open Memory", Variant.PRIMARY) {
                    if (id.isNotBlank()) showConversation(id, title) else selectScreen(Screen.MEMORIES)
                })
                addView(pillButton("Ask About It", Variant.QUIET) {
                    sendChat("What should I remember from: $title?")
                })
                addView(pillButton("All Memories", Variant.QUIET) { selectScreen(Screen.MEMORIES) })
            })
        }
    }

    private fun categoryColor(category: String): Int =
        when (category.lowercase(Locale.ROOT)) {
            "work", "pitch", "business" -> 0xFF805AD5.toInt() // Purple
            "personal", "private" -> 0xFF3182CE.toInt() // Blue
            "social", "relationship", "chat" -> 0xFF319795.toInt() // Teal
            "health", "fitness", "sleep" -> 0xFF38A169.toInt() // Green
            "finance", "money", "deal" -> 0xFFDD6B20.toInt() // Orange
            else -> 0xFF718096.toInt() // Gray
        }

    private fun formatTimelineTime(iso: String): String {
        val instant = parseIsoInstant(iso) ?: return "unknown"
        val local = instant.atZone(ZoneId.systemDefault())
        return local.format(DateTimeFormatter.ofPattern("h:mm a"))
    }

    private fun homeMemoriesCard(): View {
        val memories = snapshot?.memories?.optJSONArray("memories") ?: JSONArray()
        val todayMemories = memoriesForLocalDate(memories, LocalDate.now(ZoneId.systemDefault()))
        val latest = memories.optJSONObject(0)
        return card {
            addView(meta("Today's memories"))
            addView(cardTitle(when {
                todayMemories.isNotEmpty() -> "Memories recorded today"
                memories.length() > 0 -> "No memories recorded today yet"
                else -> "No memories loaded"
            }))
            if (todayMemories.isEmpty()) {
                addView(bodyText(if (isRefreshing) {
                    "Refreshing recent memories from Chronicle."
                } else if (latest != null) {
                    "Latest final memory: ${memoryTitle(latest).ifBlank { "Untitled memory" }} recorded ${evidenceTimeLine(memoryStartedAt(latest))}. Check Diagnostics before assuming today's capture failed."
                } else {
                    "No recent memories are available on the phone yet."
                }))
                addView(scrollButtonRow {
                    addView(pillButton("Refresh", Variant.PRIMARY) { reloadData() })
                    addView(pillButton("Open Memories", Variant.QUIET) { selectScreen(Screen.MEMORIES) })
                    addView(pillButton("Diagnostics", Variant.QUIET) { selectScreen(Screen.PROCESSING) })
                })
                return@card
            }

            todayMemories.take(3).forEachIndexed { index, memory ->
                addView(homeMemoryTimelineRow(memory, index, minOf(todayMemories.size, 3)))
            }
            addView(scrollButtonRow {
                addView(pillButton("All Memories", Variant.PRIMARY) { selectScreen(Screen.MEMORIES) })
                addView(pillButton("Ask Memory", Variant.QUIET) { selectScreen(Screen.ASK) })
            })
        }
    }

    private fun homeMemoryTimelineRow(memory: JSONObject, index: Int, total: Int): View {
        val structured = memory.optJSONObject("structured") ?: JSONObject()
        val title = structured.cleanString("title", "Untitled memory")
        val overview = structured.cleanString("overview")
        val category = structured.cleanString("category", "Memory")
        val startedAt = memoryStartedAt(memory)
        val timeStr = if (startedAt.isBlank()) "" else formatTimelineTime(startedAt)
        val id = memory.cleanString("id")
        val color = categoryColor(category)
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8.dp(), 0, 0) }
            
            val timelineCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(28.dp(), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            
            val topLine = View(this@MainActivity).apply {
                setBackgroundColor(if (index == 0) 0 else CARD_BORDER)
                layoutParams = LinearLayout.LayoutParams(2.dp(), 0, 1f)
            }
            
            val dot = View(this@MainActivity).apply {
                background = rounded(color, color, 999f)
                layoutParams = LinearLayout.LayoutParams(12.dp(), 12.dp()).apply {
                    setMargins(0, 4.dp(), 0, 4.dp())
                }
            }
            
            val bottomLine = View(this@MainActivity).apply {
                setBackgroundColor(if (index == total - 1) 0 else CARD_BORDER)
                layoutParams = LinearLayout.LayoutParams(2.dp(), 0, 2f)
            }
            
            timelineCol.addView(topLine)
            timelineCol.addView(dot)
            timelineCol.addView(bottomLine)
            
            val contentCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp(), 0, 0, 18.dp())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                
                val metaRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                
                if (timeStr.isNotEmpty()) {
                    metaRow.addView(TextView(this@MainActivity).apply {
                        text = timeStr
                        scaledTextSize = 11f
                        setTextColor(MUTED)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    metaRow.addView(View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(6.dp(), 1.dp())
                    })
                }
                
                metaRow.addView(TextView(this@MainActivity).apply {
                    text = category.uppercase(Locale.ROOT)
                    scaledTextSize = 10f
                    setTextColor(TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                    val softBgColor = (0x1A000000.toInt() or (color and 0x00FFFFFF))
                    background = rounded(softBgColor, color, 6.dp().toFloat())
                    setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
                })
                
                addView(metaRow)
                addView(compactTitle(title))
                if (overview.isNotBlank()) {
                    addView(compactBody(overview))
                }
                addView(scrollButtonRow {
                    addView(miniButton("Open", Variant.QUIET) { if (id.isNotBlank()) showConversation(id, title) })
                    addView(miniButton("Ask", Variant.QUIET) { sendChat("What should I remember from: $title?") })
                })
            }
            
            addView(timelineCol)
            addView(contentCol)
        }
    }

    private fun latestMemory(): JSONObject? {
        val memories = snapshot?.memories?.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until memories.length()) {
            memories.optJSONObject(i)?.let { return it }
        }
        return snapshot?.status?.optJSONObject("latest_memory")
    }

    private fun memoryTitle(memory: JSONObject?): String {
        if (memory == null) return ""
        val structured = memory.optJSONObject("structured")
        return structured?.cleanString("title").orEmpty()
            .ifBlank { memory.cleanString("title") }
    }

    private fun memoryOverview(memory: JSONObject?): String {
        if (memory == null) return ""
        val structured = memory.optJSONObject("structured")
        return structured?.cleanString("overview").orEmpty()
            .ifBlank { memory.cleanString("overview") }
            .ifBlank { memory.cleanString("summary") }
    }

    private fun memoryStartedAt(memory: JSONObject?): String =
        memory?.cleanString("started_at").orEmpty()

    private fun memoryCreatedAt(memory: JSONObject?): String =
        memory?.cleanString("created_at").orEmpty()
            .ifBlank { memory?.cleanString("timestamp").orEmpty() }

    private fun homeAttentionListCard(): View {
        val actions = snapshot?.actionItems?.optJSONArray("items") ?: JSONArray()
        val approvals = snapshot?.approvals?.optJSONArray("pending") ?: JSONArray()
        val total = actions.length() + approvals.length()
        return card {
            addView(meta("Work queue"))
            addView(cardTitle(if (total > 0) "${actions.length()} TODOs | ${approvals.length()} Scout" else "Nothing waiting"))
            if (total == 0) {
                addView(bodyText("No TODOs or Scout approvals are waiting right now."))
            } else {
                for (i in 0 until minOf(actions.length(), 3)) {
                    val item = actions.optJSONObject(i) ?: continue
                    addView(homeTodoRow(item))
                }
                for (i in 0 until minOf(approvals.length(), 2)) {
                    val approval = approvals.optJSONObject(i) ?: continue
                    addView(homeScoutRow(approval))
                }
            }
            addView(scrollButtonRow {
                addView(pillButton("TODOs", if (actions.length() > 0) Variant.PRIMARY else Variant.QUIET) { selectScreen(Screen.ACTIONS) })
                addView(pillButton("Scout", if (approvals.length() > 0) Variant.PRIMARY else Variant.QUIET) { selectScreen(Screen.APPROVALS) })
            })
        }
    }

    private fun homeTodoRow(item: JSONObject): View =
        homeAttentionRow(
            label = "TODO",
            title = item.cleanString("text", "TODO"),
            detail = item.cleanString("conv_title", "Source memory"),
            primary = "Done",
            primaryVariant = Variant.PRIMARY,
            primaryAction = { toggleAction(item.cleanString("conv_id"), item.optInt("index", -1)) },
            secondary = "Open",
            secondaryAction = { showConversation(item.cleanString("conv_id"), item.cleanString("conv_title", "Source memory")) },
        )

    private fun homeScoutRow(approval: JSONObject): View {
        val id = approval.cleanString("job_id").ifBlank { approval.cleanString("id") }
        return homeAttentionRow(
            label = "Scout",
            title = approval.cleanString("raw_text", "Approval request"),
            detail = approval.cleanString("intent_skill", "Approval"),
            primary = "Review",
            primaryVariant = Variant.QUIET,
            primaryAction = { selectScreen(Screen.APPROVALS) },
            secondary = "Reject",
            secondaryAction = { decideApproval(id, false) },
        )
    }

    private fun homeAttentionRow(
        label: String,
        title: String,
        detail: String,
        primary: String,
        primaryVariant: Variant,
        primaryAction: () -> Unit,
        secondary: String,
        secondaryAction: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 9.dp(), 0, 9.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 4.dp(), 0, 0) }
            addView(hairline())
            addView(meta("$label | $detail"))
            addView(compactTitle(title))
            addView(scrollButtonRow {
                addView(miniButton(primary, primaryVariant, primaryAction))
                addView(miniButton(secondary, Variant.QUIET, secondaryAction))
            })
        }

    private fun homeCaptureCard(): View {
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val sync = ble?.storageSync?.value
        val rawAge = snapshot?.status?.optJSONObject("raw_frames")?.optDouble("age_s", -1.0) ?: -1.0
        val live = isListening()
        val requested = captureRequestedButNotLive()
        val bleState = ble?.state?.value?.name ?: "IDLE"
        val connected = bleState in setOf("READY", "SUBSCRIBED", "SYNCING")
        val title = when {
            sync?.active == true -> "Syncing pendant files"
            live -> "Recording live"
            requested -> "Capture requested, not recording yet"
            rawAge in 0.0..600.0 -> "Audio heard ${shortAgeSeconds(rawAge)}"
            service != null -> "Ready to capture"
            else -> "Phone bridge paused"
        }

        return card {
            addView(meta("Capture"))
            addView(cardTitle(title))
            addView(statusGrid(
                listOf(
                    "Pendant" to pendantHealthLine(service, bleState, connected, sync?.active == true),
                    "Last audio" to if (rawAge >= 0.0) shortAgeSeconds(rawAge) else "unknown",
                    "Translate" to translationSummary(compact = true),
                    "Audio out" to if (audioOutActive()) "active" else "idle",
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton(if (live) "Stop Recording" else "Start Recording", if (live) Variant.DANGER else Variant.PRIMARY) {
                    if (live) stopLiveMode() else startLiveMode()
                })
                addView(pillButton("Sync Pendant", Variant.QUIET) { syncNow() })
                addView(pillButton("Translate", Variant.TRANSLATE) { showTranslationSheet() })
            })
        }
    }

    private fun homeProcessingLineCard(): View =
        card {
            val stages = pipelineStages()
            val line = stages.joinToString(" -> ") { if (it.active) it.label else "${it.label}..." }
            addView(meta("Processing"))
            addView(bodyText(line))
            addView(scrollButtonRow {
                addView(pillButton("Open Processing", Variant.QUIET) { selectScreen(Screen.PROCESSING) })
            })
        }

    private fun renderCapture() {
        snapshotStateBanner()?.let { body.addView(it) }
        body.addView(quickControls())
        body.addView(recordingProofCard())
        renderLive()
        body.addView(deviceLifecycleCard())
        body.addView(capturedFilesCard())
        body.addView(phoneLocalStorageCard())
    }

    private fun renderProcessing() {
        snapshotStateBanner()?.let { body.addView(it) }
        body.addView(truthConsoleIntroCard())
        body.addView(devControls())
        body.addView(evidenceSpineCompactCard())
        body.addView(todayCaptureEvidenceCard())
        body.addView(recordingProofCard())
        body.addView(timestampAuditCard())
        body.addView(backendProcessingCard())
        body.addView(capturedFilesCard())
        body.addView(phoneLocalStorageCard())
        body.addView(webScreensCard())
        body.addView(card {
            addView(meta("Debug fallback"))
            addView(cardTitle("Raw backend endpoints"))
            addView(bodyText("These links open the backend JSON surfaces when you need to inspect the plumbing. The normal pipeline state above is native."))
            addView(scrollButtonRow {
                addView(pillButton("Status JSON", Variant.QUIET) { openChroniclePath("/pendant/api/v3/status") })
                addView(pillButton("Audio JSON", Variant.QUIET) { openChroniclePath("/pendant/api/v3/audio") })
                addView(pillButton("Raw JSON", Variant.QUIET) { openChroniclePath("/pendant/api/v3/raw") })
                addView(pillButton("Debug Activity", Variant.QUIET) {
                    startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                })
            })
        })
    }

    private fun devControls(): View =
        card {
            val service = PendantForegroundService.instance
            val isRunning = service != null
            
            addView(meta("Developer Controls"))
            addView(cardTitle("Hardware & Companion Settings"))
            addView(bodyText("Low-level developer controls for testing and debugging physical hardware connectivity, websocket connections, background services, and caching."))
            
            addView(scrollButtonRow {
                addView(pillButton(if (isRunning) "Stop Service" else "Start Service", if (isRunning) Variant.DANGER else Variant.PRIMARY) {
                    if (isRunning) {
                        PendantForegroundService.stop(this@MainActivity)
                        toast("Pendant service stopped.")
                    } else {
                        askPermissionsThen { startPendantService() }
                        toast("Pendant service started.")
                    }
                    handler.postDelayed({ renderContent() }, 500)
                })
                
                addView(pillButton("Reset Cache", Variant.QUIET) {
                    cache.clear()
                    snapshot = null
                    toast("Cache cleared.")
                    reloadData(showBlank = true)
                })
            })
            
            addView(scrollButtonRow {
                addView(pillButton("Reconnect BLE", Variant.QUIET) {
                    reconnectPendant()
                })
                
                addView(pillButton("Reconnect Voice", Variant.QUIET) {
                    reconnectAudio()
                })
                
                addView(pillButton("Arm Button", Variant.QUIET) {
                    armPendantButton()
                })
            })
        }

    private fun quickNoteCard(): View = card {
        addView(meta("Founder note to self"))
        addView(cardTitle("Record Note"))
        addView(bodyText("Save a text memo or use voice dictation. This sends as a note to self to your Chronicle context."))
        
        val input = EditText(this@MainActivity).apply {
            hint = "Type a note to self..."
            scaledTextSize = 14f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 5
            background = rounded(BG_SOFT, CARD_BORDER, 10.dp().toFloat())
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
        }
        quickNoteInput = input
        addView(input)
        
        addView(scrollButtonRow {
            addView(pillButton("Dictate Note", Variant.QUIET) {
                runCatching {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your note...")
                    }
                    speechLauncher.launch(intent)
                }.onFailure {
                    toast("Speech recognition is not supported on this device.")
                }
            })
            
            addView(pillButton("Save Note", Variant.PRIMARY) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    input.isEnabled = false
                    lifecycleScope.launch {
                        runCatching {
                            api.chat("Note to self: $text", askContext())
                        }.onSuccess {
                            toast("Note saved to Chronicle")
                            input.setText("")
                            input.isEnabled = true
                            reloadData()
                        }.onFailure {
                            toast("Failed to save note: ${it.message}")
                            input.isEnabled = true
                        }
                    }
                } else {
                    toast("Please enter or dictate a note first.")
                }
            })
        })
    }

    private fun renderTranslate() {
        snapshotStateBanner()?.let { body.addView(it) }
        body.addView(translationStatusCard())
        body.addView(translationCard())
        body.addView(card {
            addView(meta("Capture integration"))
            addView(cardTitle(if (isListening()) "Translation is attached to live capture" else "Start live capture to translate"))
            addView(bodyText("Translation uses the same live capture path as the pendant. Keep it in Auto for everyday use, force On when you know you need translated audio, or turn it Off when you want capture without voice output."))
            addView(statusLoop(
                listOf(
                    "Live capture" to isListening(),
                    "Translation" to translationActive(),
                    "Audio out" to audioOutActive(),
                    "Transcript" to (liveSegments(snapshot?.live ?: JSONObject()).length() > 0),
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton(if (isListening()) "Pause Capture" else "Start Live", if (isListening()) Variant.DANGER else Variant.PRIMARY) {
                    if (isListening()) stopLiveMode() else startLiveMode()
                })
                addView(pillButton("Reconnect Voice", Variant.QUIET) { reconnectAudio() })
                addView(pillButton("Open Capture", Variant.QUIET) { selectScreen(Screen.CAPTURE) })
            })
        })
        body.addView(translationSnippetsCard())
        body.addView(liveTranscriptCard(liveSegments(snapshot?.live ?: JSONObject())))
    }

    private fun renderMemories() {
        val snap = snapshot
        if (snap == null) {
            body.addView(stateCard(
                eyebrow = "Loading",
                title = "Opening your memory stream",
                text = "Chronicle is fetching the latest snapshot. Cached memories appear first when they exist.",
            ))
            return
        }
        val memories = snap.memories.optJSONArray("memories") ?: JSONArray()
        if (memories.length() == 0) {
            val processing = hasProcessingEvidence(snap)
            body.addView(stateCard(
                eyebrow = if (processing) "Processing" else "Memory stage",
                title = if (processing) "Memory still processing" else "No final memories in this window",
                text = if (processing) {
                    "Raw audio, decoded audio, or transcript chunks exist, so this is not a recording failure. Memories are the final artifact and can appear after backend processing catches up."
                } else {
                    "Chronicle did not return final memories for the last two days. Sync the pendant if it stored offline audio, or start a short live capture."
                },
                primaryLabel = "Sync Now",
                primaryAction = { syncNow() },
                secondaryLabel = if (isListening()) "Pause Capture" else "Start Live",
                secondaryAction = { if (isListening()) stopLiveMode() else startLiveMode() },
            ))
            return
        }

        body.addView(memoryArchiveIntroCard(memories))
        body.addView(sectionLabel("Latest memories"))
        for (i in 0 until memories.length()) {
            val memory = memories.optJSONObject(i) ?: continue
            body.addView(memoryCard(memory))
        }
    }

    private fun renderActions() {
        val snap = snapshot
        if (snap == null) {
            body.addView(stateCard(
                eyebrow = "Loading",
                title = "Checking your Inbox",
                text = "Chronicle is reading follow-ups and Scout approvals...",
            ))
            return
        }
        val items = snap.actionItems.optJSONArray("items") ?: JSONArray()
        val pending = snap.approvals.optJSONArray("pending") ?: JSONArray()
        val recent = snap.approvals.optJSONArray("recent") ?: JSONArray()
        
        body.addView(todoScopeCard(items.length(), pending.length()))
        
        body.addView(sectionLabel("Your To-dos (${items.length()})"))
        if (items.length() == 0) {
            body.addView(stateCard(
                eyebrow = "Clear",
                title = "No pending TODOs",
                text = "Everything caught up! No unresolved follow-ups are assigned to you.",
            ))
        } else {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                body.addView(actionCard(item))
            }
        }
        
        body.addView(sectionLabel("Scout Approvals (${pending.length()})"))
        if (pending.length() == 0) {
            body.addView(stateCard(
                eyebrow = "Clear",
                title = "No Scout approvals waiting",
                text = "Scout is quiet. proposed tasks, sends, and tool calls will appear here when ready.",
            ))
        } else {
            for (i in 0 until pending.length()) {
                val approval = pending.optJSONObject(i) ?: continue
                body.addView(approvalCard(approval))
            }
        }
        
        if (recent.length() > 0) {
            body.addView(sectionLabel("Recent Scout History"))
            for (i in 0 until minOf(recent.length(), 5)) {
                val approval = recent.optJSONObject(i) ?: continue
                body.addView(card {
                    addView(meta(approval.cleanString("status", "done").uppercase(Locale.ROOT)))
                    addView(itemTitle(approval.cleanString("raw_text", "Completed request")))
                    addView(bodyText(approval.cleanString("outcome", "No outcome text.")))
                })
            }
        }
    }

    private fun renderLive() {
        val snap = snapshot
        if (snap == null) {
            body.addView(stateCard(
                eyebrow = "Loading",
                title = "Preparing live capture",
                text = "Chronicle is checking the phone bridge, cloud capture state, and current transcript buffer.",
            ))
            return
        }
        val live = snap.live
        val listening = snap.listening.optBoolean("on", false)
        val phoneLive = PendantForegroundService.instance?.isLiveMode == true
        val backendLive = live.optBoolean("is_listening", false)
        val actuallyLive = phoneLive || backendLive
        val segments = liveSegments(live)

        body.addView(card {
            addView(meta(if (actuallyLive) "Active capture" else if (listening) "Capture requested" else "Standby capture"))
            addView(cardTitle(if (actuallyLive) "Companion mode is live" else if (listening) "Cloud requested capture; phone is not recording" else "Ready when you are"))
            addView(bodyText(liveStreamLine()))
            addView(statusLoop(
                listOf(
                    "Phone" to phoneLive,
                    "Cloud" to listening,
                    "Transcript" to (segments.length() > 0),
                    "Voice" to (lastLiveError == null),
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton(if (actuallyLive) "Pause Capture" else "Start Live", if (actuallyLive) Variant.DANGER else Variant.PRIMARY) {
                    if (actuallyLive) stopLiveMode() else startLiveMode()
                })
                addView(pillButton("Reconnect Voice", Variant.QUIET) { reconnectAudio() })
                addView(pillButton("Reconnect Pendant", Variant.QUIET) { reconnectPendant() })
            })
        })

        body.addView(liveTranscriptCard(segments))
        body.addView(translationCard())
        body.addView(chatCard())
    }

    private fun renderApprovals() {
        val snap = snapshot
        if (snap == null) {
            body.addView(stateCard(
                eyebrow = "Loading",
                title = "Checking Scout",
                text = "Chronicle is reading agent tasks that need your explicit approval.",
            ))
            return
        }
        val pending = snap.approvals.optJSONArray("pending") ?: JSONArray()
        val recent = snap.approvals.optJSONArray("recent") ?: JSONArray()

        body.addView(scoutQueueCard())
        if (pending.length() == 0) {
            body.addView(stateCard(
                eyebrow = "Clear",
                title = "No Scout approvals are waiting",
                text = "Scout is the agent work queue. Anything the agent does for you appears here for approval before it sends, edits, or commits.",
            ))
        } else {
            body.addView(sectionLabel("${pending.length()} Scout approvals"))
            for (i in 0 until pending.length()) {
                val approval = pending.optJSONObject(i) ?: continue
                body.addView(approvalCard(approval))
            }
        }

        if (recent.length() > 0) {
            body.addView(sectionLabel("Recent Scout history"))
            for (i in 0 until minOf(recent.length(), 8)) {
                val approval = recent.optJSONObject(i) ?: continue
                body.addView(card {
                    addView(meta(approval.cleanString("status", "done")))
                    addView(itemTitle(approval.cleanString("raw_text", "Completed request")))
                    addView(bodyText(approval.cleanString("outcome", "No outcome text.")))
                })
            }
        }
    }

    private fun renderHealth() {
        val snap = snapshot
        if (snap == null) {
            body.addView(stateCard(
                eyebrow = "Loading",
                title = "Reading body context",
                text = "Chronicle is checking Health Connect context that can help interpret your day.",
            ))
            return
        }
        val today = snap.health.optJSONObject("today") ?: JSONObject()
        body.addView(card {
            addView(meta("Today"))
            addView(cardTitle("Readiness context"))
            if (today.length() == 0) {
                addView(bodyText("No Health Connect context has synced yet. Chronicle will still work from pendant audio and memories."))
                addView(scrollButtonRow {
                    addView(pillButton("Reload", Variant.QUIET) { reloadData() })
                })
            } else {
                addView(metricRow(today))
            }
        })

        val phone = snap.status.optJSONObject("phone")
        if (phone != null) {
            body.addView(card {
                addView(meta("Phone"))
                addView(bodyText(phone.toString(2)))
            })
        }
    }

    private fun renderMap() {
        val snap = snapshot
        if (snap == null) {
            body.addView(stateCard(
                eyebrow = "Loading",
                title = "Opening the knowledge map",
                text = "Chronicle is loading the same entity graph used by the web Map tab.",
            ))
            return
        }
        val graph = snap.graph
        val nodes = graph.optJSONArray("nodes") ?: JSONArray()
        val edges = graph.optJSONArray("edges") ?: JSONArray()
        val counts = graphTypeCounts(nodes)

        body.addView(card {
            addView(meta("Knowledge map"))
            addView(cardTitle(if (nodes.length() > 0) "${nodes.length()} entities in the companion graph" else "No entities mapped yet"))
            addView(bodyText("This is the pendant memory map in native form: people, places, organizations, topics, and tools extracted from recent captured memories. The web graph remains available for the full visual layout."))
            addView(statusGrid(
                listOf(
                    "Entities" to nodes.length().toString(),
                    "Links" to edges.length().toString(),
                    "People" to (counts["person"] ?: 0).toString(),
                    "Organizations" to (counts["organization"] ?: 0).toString(),
                    "Topics" to (counts["topic"] ?: 0).toString(),
                    "Things" to (counts["thing"] ?: 0).toString(),
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Ask About Map", Variant.PRIMARY) {
                    sendChat("What patterns stand out in my current knowledge graph?")
                })
                addView(pillButton("Open Web Map", Variant.QUIET) { openChroniclePath("/pendant/v3#map") })
                addView(pillButton("Reload", Variant.QUIET) { reloadData() })
            })
        })

        val insight = snap.insights
        if (insight.length() > 0 &&
            insight.cleanString("body").isNotBlank() &&
            dismissedInsightKey != insightKey(insight)
        ) {
            body.addView(insightCard(insight))
        }

        if (nodes.length() == 0) {
            body.addView(stateCard(
                eyebrow = "Graph",
                title = "No graph nodes returned",
                text = "The Map tab will populate after Chronicle extracts entities from finished memories. Open Processing to confirm raw audio and transcript chunks are still moving.",
                primaryLabel = "Open Processing",
                primaryAction = { selectScreen(Screen.PROCESSING) },
            ))
            return
        }

        body.addView(sectionLabel("Top entities"))
        for (i in 0 until minOf(nodes.length(), 12)) {
            val node = nodes.optJSONObject(i) ?: continue
            body.addView(graphNodeCard(node))
        }

        if (edges.length() > 0) {
            body.addView(sectionLabel("Strong links"))
            for (i in 0 until minOf(edges.length(), 8)) {
                val edge = edges.optJSONObject(i) ?: continue
                body.addView(graphEdgeCard(edge))
            }
        }
    }

    private fun renderAsk() {
        snapshotStateBanner()?.let { body.addView(it) }
        body.addView(card {
            addView(meta("Ask"))
            addView(cardTitle("Ask Chronicle from the pendant loop"))
            addView(bodyText("This mirrors the web Chat screen, but it carries native context too: capture state, translation, TODOs, Scout approvals, health, recent memories, and the knowledge graph."))
            addView(statusGrid(
                listOf(
                    "Capture" to if (isListening()) "live" else "idle",
                    "Translate" to translationSummary(compact = true),
                    "Graph" to "${snapshot?.graph?.optJSONArray("nodes")?.length() ?: 0} entities",
                    "Scout" to "${snapshot?.approvals?.optJSONArray("pending")?.length() ?: 0} pending",
                )
            ))
        })
        body.addView(chatCard())
        body.addView(webScreensCard())
    }

    private fun companionNowCard(): View {
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val sync = ble?.storageSync?.value
        val snap = snapshot
        val raw = snap?.status?.optJSONObject("raw_frames")
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val latestWhen = memoryStartedAt(latestMemory())
        val lastEvidence = when {
            rawAge >= 0.0 -> shortAgeSeconds(rawAge)
            latestWhen.isNotBlank() -> "memory ${ago(latestWhen)}"
            else -> "unknown"
        }
        val bleState = ble?.state?.value?.name ?: "IDLE"
        val connected = bleState in setOf("READY", "SUBSCRIBED", "SYNCING")
        val phoneLive = service?.isLiveMode == true
        val backendLive = snap?.live?.optBoolean("is_listening", false) == true
        val cloudRequested = snap?.listening?.optBoolean("on", false) == true
        val live = phoneLive || backendLive
        val battery = ble?.batteryPct?.value
        val lastBattery = ble?.lastBatteryReadAt?.takeIf { it > 0L }
        val lastSync = sync?.lastCompletedAt?.takeIf { it > 0L }
        val title = when {
            sync?.active == true -> "Pendant files are syncing"
            phoneLive && connected -> "Live capture is running"
            phoneLive -> "Live capture is waking up"
            backendLive -> "Backend is receiving audio"
            cloudRequested -> "Capture requested, not recording"
            translationActive() -> "Translation is standing by"
            connected -> "Pendant is connected"
            service != null -> "Companion is ready"
            else -> "Companion service is paused"
        }
        val detail = when {
            sync?.active == true -> "The phone is pulling offline files, saving a local copy, then sending them to Chronicle."
            phoneLive -> liveStreamLine()
            backendLive -> "Chronicle has very recent raw audio. Keep the transcript open if you need live proof."
            cloudRequested -> "Chronicle's cloud flag is on, but the phone and pendant path are not recording. Start Live to capture the current moment, or Sync Now for offline files."
            connected -> "The pendant is connected for a phone task. Leave live mode or sync when you are done so offline storage can resume safely."
            rawAge in 0.0..600.0 -> "Chronicle heard audio ${shortAgeSeconds(rawAge)}. Memories, TODOs, and Scout notes can still be processing."
            service != null -> "The Android companion is awake. Use Sync for offline files or Live Capture for the current moment."
            else -> "Start with Sync Now or Live Capture to wake the phone bridge."
        }

        return card {
            addView(meta("What's happening now"))
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            titleRow.addView(cardTitle(title).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val orbColor = when {
                live -> 0xFFC53030.toInt()      // Red: Capture active
                sync?.active == true -> 0xFFDD6B20.toInt() // Orange: Syncing
                connected -> 0xFF38A169.toInt() // Green: Connected
                else -> 0xFF718096.toInt()      // Gray: Offline
            }
            val orb = View(this@MainActivity).apply {
                background = rounded(orbColor, orbColor, 999f)
                layoutParams = LinearLayout.LayoutParams(16.dp(), 16.dp()).apply {
                    setMargins(10.dp(), 0, 0, 0)
                }
                // Fix: Avoid infinite AlphaAnimation which blocks uiautomator's waitForIdle and makes agrep timeout.
                // Instead, use a Handler with postDelayed to toggle alpha discretely, keeping the main thread idle between frames.
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val toggleRunnable = object : Runnable {
                    var visible = true
                    override fun run() {
                        alpha = if (visible) 1.0f else 0.3f
                        visible = !visible
                        handler.postDelayed(this, 5000)
                    }
                }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        handler.post(toggleRunnable)
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        handler.removeCallbacks(toggleRunnable)
                    }
                })
            }
            titleRow.addView(orb)
            addView(titleRow)
            addView(bodyText(detail))
            addView(statusGrid(
                listOf(
                    "Pendant" to pendantHealthLine(service, bleState, connected, sync?.active == true),
                    "Battery" to when {
                        battery != null && lastBattery != null -> "$battery% (${agoMillis(System.currentTimeMillis() - lastBattery)})"
                        battery != null -> "$battery%"
                        else -> "not reported"
                    },
                    "Capture" to captureModeLine(connected, rawAge),
                    "Last sync" to when {
                        sync?.active == true -> "${sync.filesDone}/${sync.filesTotal} files"
                        lastSync != null -> agoMillis(System.currentTimeMillis() - lastSync)
                        else -> "never"
                    },
                    "Translate" to translationSummary(compact = false),
                    "Audio out" to if (audioOutActive()) "active" else "idle",
                    "Last evidence" to lastEvidence,
                    "Button" to buttonStatusLine(service, ble),
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton(if (live) "Pause Capture" else "Start Live", if (live) Variant.DANGER else Variant.PRIMARY) {
                    if (live) stopLiveMode() else startLiveMode()
                })
                addView(pillButton("Sync Now", Variant.QUIET) { syncNow() })
                addView(pillButton("Translate", Variant.TRANSLATE) { showTranslationSheet() })
                addView(pillButton("TODOs", Variant.QUIET) { selectScreen(Screen.ACTIONS) })
                addView(pillButton("Map", Variant.QUIET) { selectScreen(Screen.MAP) })
            })
        }
    }

    private fun homeAttentionCard(): View {
        val snap = snapshot
        val memories = snap?.memories?.optJSONArray("memories") ?: JSONArray()
        val actions = snap?.actionItems?.optJSONArray("items") ?: JSONArray()
        val approvals = snap?.approvals?.optJSONArray("pending") ?: JSONArray()
        val uncertain = countUncertainMemories(memories)
        val waiting = actions.length() + approvals.length() + uncertain

        return card {
            addView(meta("What needs me"))
            addView(cardTitle(if (waiting > 0) "$waiting things need attention" else "Nothing needs a decision"))
            addView(bodyText(
                if (waiting > 0) {
                    "Chronicle keeps your TODOs separate from Scout's proposed agent work. If you do it, it is a TODO; if Scout does it, it waits for approval."
                } else {
                    "No TODOs or Scout approvals are waiting in the current window. The companion can stay quiet."
                }
            ))
            addView(statusGrid(
                listOf(
                    "Scout" to approvals.length().toString(),
                    "TODOs" to actions.length().toString(),
                    "Memory review" to uncertain.toString(),
                    "Agent work" to if (approvals.length() > 0) "${approvals.length()} pending" else "none pending",
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Open TODOs", Variant.PRIMARY) { selectScreen(Screen.ACTIONS) })
                addView(pillButton("Open Scout", Variant.QUIET) { selectScreen(Screen.APPROVALS) })
                addView(pillButton("Ask Why", Variant.QUIET) {
                    sendChat("Which items are my TODOs and which items need Scout approval?")
                })
                addView(pillButton("Ask Follow-Up", Variant.QUIET) {
                    sendChat("What needs follow-up from my recent conversations?")
                })
            })
        }
    }

    private fun evidenceSpineCompactCard(): View =
        card {
            val stages = pipelineStages()
            addView(meta("Evidence spine"))
            addView(cardTitle(pipelineHeadline()))
            addView(bodyText("Pendant -> Phone -> Backend -> Transcript -> Memory"))
            addView(statusLoop(stages.map { it.label to it.active }))
            addView(provenanceText(backendLagLine()))
            addView(scrollButtonRow {
                addView(pillButton("Open Processing", Variant.QUIET) { selectScreen(Screen.PROCESSING) })
                addView(pillButton("Sync Now", Variant.QUIET) { syncNow() })
            })
        }

    private fun truthConsoleIntroCard(): View =
        stateCard(
            eyebrow = "Truth console",
            title = "Inspect the evidence spine",
            text = "Processing is where raw audio, phone-local files, uploads, decoded WAVs, transcript chunks, memories, and reconciliation live. Use it when you need proof, not as the daily cockpit.",
        )

    private fun deviceLifecycleCard(): View {
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val snap = snapshot
        val raw = snap?.status?.optJSONObject("raw_frames")
        val sync = ble?.storageSync?.value
        val state = ble?.state?.value?.name ?: "IDLE"
        val connected = state in setOf("READY", "SUBSCRIBED", "SYNCING")
        val battery = ble?.batteryPct?.value
        val lastBattery = ble?.lastBatteryReadAt?.takeIf { it > 0L }
        val lastSync = sync?.lastCompletedAt?.takeIf { it > 0L }
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val freshAudio = rawAge in 0.0..600.0

        return card {
            addView(meta("Capture loop"))
            addView(cardTitle(if (connected) "Pendant is in range" else "Bring the pendant back into range"))
            addView(bodyText(statusExplanation(service != null, connected, rawAge, sync?.lastError)))
            addView(statusLoop(
                listOf(
                    "Pendant" to connected,
                    "Phone" to (service != null),
                    "Capture" to isListening(),
                    "Audio" to freshAudio,
                )
            ))
            addView(statusGrid(
                listOf(
                    "Phone" to if (service != null) "service running" else "service stopped",
                    "Battery" to when {
                        battery != null && lastBattery != null -> "$battery% read ${agoMillis(System.currentTimeMillis() - lastBattery)}"
                        battery != null -> "$battery%"
                        else -> "not reported yet"
                    },
                    "Button" to buttonStatusLine(service, ble),
                    "Last audio" to if (rawAge >= 0.0) shortAgeSeconds(rawAge) else "unknown",
                    "Last sync" to when {
                        sync?.active == true -> "${sync.filesDone}/${sync.filesTotal} files"
                        lastSync != null -> agoMillis(System.currentTimeMillis() - lastSync)
                        else -> "not completed yet"
                    },
                    "Auto sync" to autoSyncLine(service),
                )
            ))
            addView(provenanceText(nextBestAction(service != null, connected, rawAge, sync?.lastError)))
            addView(scrollButtonRow {
                addView(pillButton("Sync Now", Variant.PRIMARY) { syncNow() })
                addView(pillButton(if (isListening()) "Pause Capture" else "Live Capture", if (isListening()) Variant.DANGER else Variant.QUIET) {
                    if (isListening()) stopLiveMode() else startLiveMode()
                })
                addView(pillButton("Arm Button", Variant.QUIET) { armPendantButton() })
                addView(pillButton("Reconnect Pendant", Variant.QUIET) { reconnectPendant() })
                addView(pillButton("Voice", Variant.QUIET) { reconnectAudio() })
                addView(pillButton("Privacy", Variant.QUIET) { showPrivacySheet() })
            })
        }
    }

    private fun capturedFilesCard(): View {
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val uploader = service?.agentUploader
        val snap = snapshot
        val raw = snap?.status?.optJSONObject("raw_frames")
        val latest = snap?.status?.optJSONObject("latest_memory")
        val memories = snap?.memories?.optJSONArray("memories") ?: JSONArray()
        val sync = ble?.storageSync?.value
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val bytesToday = raw?.optLong("bytes_today", 0L) ?: 0L
        val backendStreaming = raw?.optBoolean("is_streaming", false) == true
        val latestTitle = latest?.cleanString("title").orEmpty()
        val latestWhen = latest?.cleanString("started_at").orEmpty()
        val lastCompletedAt = sync?.lastCompletedAt?.takeIf { it > 0L }
        val syncError = sync?.lastError
        val uploadError = uploader?.lastError

        val fileLine = when {
            sync?.active == true -> "${sync.filesDone}/${sync.filesTotal} files"
            lastCompletedAt != null -> "last ${agoMillis(System.currentTimeMillis() - lastCompletedAt)}"
            syncError?.isNotBlank() == true -> "needs retry"
            service != null -> "tap Sync Now"
            else -> "service stopped"
        }
        val uploadLine = uploader?.let { "${it.posted()} ok / ${it.failed()} failed" } ?: "service stopped"
        val uploadHealth = uploader?.let {
            when {
                it.dropped() > 0L -> "${it.dropped()} dropped"
                it.lastStatus > 0 -> "HTTP ${it.lastStatus}, batch ${it.lastBatchSize}"
                else -> "waiting"
            }
        } ?: "not active"
        val backendLine = when {
            backendStreaming -> "streaming now"
            bytesToday > 0L && rawAge >= 0.0 -> "${formatBytes(bytesToday)} today, ${shortAgeSeconds(rawAge)}"
            bytesToday > 0L -> "${formatBytes(bytesToday)} today"
            rawAge >= 0.0 -> "last ${shortAgeSeconds(rawAge)}"
            else -> "unknown"
        }

        return card {
            addView(meta("Last recording"))
            addView(cardTitle(when {
                sync?.active == true -> "Pulling the latest pendant files"
                rawAge > 600.0 -> "The latest recording may still be on the pendant"
                memories.length() > 0 -> "Latest recording is becoming memories"
                else -> "Ready to pull the latest recording"
            }))
            addView(bodyText("This firmware stores voice-like audio when BLE is disconnected and SD is ready. Hourly auto sync and Sync Now reconnect, pull stored files, upload frames, then Chronicle turns them into memories after backend processing."))
            addView(statusGrid(
                listOf(
                    "Pendant files" to fileLine,
                    "Auto sync" to autoSyncLine(service),
                    "Upload" to uploadLine,
                    "Upload health" to uploadHealth,
                    "Phone local" to localStoreLine(),
                    "Last recording" to backendLine,
                )
            ))
            when {
                syncError?.isNotBlank() == true -> addView(provenanceText("Pendant file sync: $syncError"))
                uploadError?.isNotBlank() == true -> addView(provenanceText("Upload path: $uploadError"))
                latestTitle.isNotBlank() -> addView(provenanceText("Latest memory: $latestTitle${if (latestWhen.isNotBlank()) " | ${ago(latestWhen)}" else ""}"))
                rawAge > 600.0 -> addView(provenanceText("No recent backend audio. The next useful move is Sync Now, then wait for memory processing."))
                else -> addView(provenanceText("Memories can lag behind raw capture. This card shows each handoff: pendant file, phone upload, backend audio, memory."))
            }
            addView(scrollButtonRow {
                addView(pillButton("Sync Now", Variant.PRIMARY) { syncNow() })
                addView(pillButton("Reconnect Pendant", Variant.QUIET) { reconnectPendant() })
                addView(pillButton("Ask Recent Capture", Variant.QUIET) {
                    sendChat("What did the pendant capture recently, and which memories prove it?")
                })
            })
        }
    }

    private fun phoneLocalStorageCard(): View {
        val st = storeStatus
        val sync = PendantForegroundService.instance?.bleClient?.storageSync?.value
        val missingTimestampFiles = sync?.missingTimestampFiles ?: 0
        return card {
            addView(meta("Phone local audio"))
            addView(cardTitle(when {
                st == null -> "Checking phone storage"
                st.queueCount > 0 -> "${st.queueCount} local segments need reconciliation"
                st.storedBytes > 0L -> "Phone has durable audio files"
                else -> "No phone-local audio indexed yet"
            }))
            addView(bodyText("This is the Android-side safety net: raw pendant audio saved under the app before cleanup or while live capture is running. These files can exist before, during, or after backend memories."))
            if (st == null) {
                addView(provenanceText("AudioStore status is loading from the foreground service. Open Debug if this stays blank."))
            } else {
                val now = System.currentTimeMillis()
                val oldestText = st.oldestStart?.let { agoMillis(now - it) } ?: "unknown"
                val newestText = st.newestEnd?.let { agoMillis(now - it) } ?: "unknown"
                addView(statusGrid(
                    listOf(
                        "Stored" to "${formatBytes(st.storedBytes)} / ${formatDuration(st.storedMs / 1000.0)}",
                        "Queue" to "${st.queueCount} segments",
                        "Oldest" to oldestText,
                        "Newest" to newestText,
                        "Storage time" to if (missingTimestampFiles > 0) "$missingTimestampFiles files unverified" else "not flagged",
                        "Current" to "${st.currentFrames} frames / ${formatBytes(st.currentBytes)}",
                        "Segment age" to formatDuration(st.currentSegmentAgeMs / 1000.0),
                    )
                ))
                if (st.queueCount > 0) {
                    addView(provenanceText("These segments are still marked pending on the phone. Some may already be visible on the backend, but the app has not reconciled a durable backend ack yet, so they should stay visible here."))
                } else if (st.storedBytes > 0L) {
                    addView(provenanceText("No phone-local backlog is marked pending. Stored files remain available until retention cleanup."))
                }
                if (missingTimestampFiles > 0) {
                    addView(provenanceText("The pendant reported ts=0 for $missingTimestampFiles synced file(s). The app must treat those displayed times as sync/upload times, not proven recorded times."))
                }
            }
            addView(scrollButtonRow {
                addView(pillButton("Refresh", Variant.QUIET) { maybePollStoreStatus(force = true) })
                addView(pillButton("Debug", Variant.QUIET) {
                    startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                })
            })
        }
    }

    private fun timestampAuditCard(): View {
        val snap = snapshot
        val latestMemory = latestMemory()
        val latestTranscript = latestTranscriptChunk()
        val raw = snap?.status?.optJSONObject("raw_frames")
        val audio = snap?.audio ?: JSONObject()
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val rawBytes = audio.optLong("raw_bytes", raw?.optLong("bytes_today", 0L) ?: 0L)
        val rawLastMtime = raw?.optDouble("last_mtime", -1.0)?.takeIf { it > 0.0 }?.let {
            Instant.ofEpochMilli((it * 1000.0).toLong())
        }
        val transcriptRecordedAt = transcriptRecordedInstant(latestTranscript)
        val transcriptMtime = parseIsoInstant(latestTranscript?.cleanString("mtime").orEmpty())
        val memoryRecordedAt = memoryRecordedInstant(latestMemory)
        val memoryCreatedInstant = parseIsoInstant(memoryCreatedAt(latestMemory))
        val missingTimestampFiles = PendantForegroundService.instance?.bleClient?.storageSync?.value?.missingTimestampFiles ?: 0

        return card {
            addView(meta("Timestamp audit"))
            addView(cardTitle("Recorded time is not memory-created time"))
            addView(bodyText("This table keeps the capture timeline separate: recorded, uploaded, transcribed, memory-created, and displayed."))
            addView(statusGrid(
                listOf(
                    "Recorded" to when {
                        missingTimestampFiles > 0 -> "$missingTimestampFiles offline file(s) unknown"
                        memoryRecordedAt != null -> memoryTimestampWarning(memoryRecordedAt)
                        transcriptRecordedAt != null -> evidenceTimeLine(transcriptRecordedAt)
                        else -> "not exposed"
                    },
                    "Uploaded" to when {
                        rawLastMtime != null -> evidenceTimeLine(rawLastMtime)
                        rawAge >= 0.0 -> shortAgeSeconds(rawAge)
                        rawBytes > 0L -> "${formatBytes(rawBytes)} visible"
                        else -> "not visible"
                    },
                    "Transcribed" to when {
                        transcriptMtime != null -> evidenceTimeLine(transcriptMtime)
                        latestTranscript != null -> "chunk time unknown"
                        else -> "not visible"
                    },
                    "Memory created" to (memoryCreatedInstant?.let { evidenceTimeLine(it) } ?: "not exposed"),
                    "Displayed" to snapshotFreshnessLine(),
                    "Device zone" to ZoneId.systemDefault().id,
                )
            ))
            addView(provenanceText("Backend timestamps with offsets are converted to device-local time. Raw transcript filenames are interpreted as UTC capture starts. Offline storage files with ts=0 show sync/upload time, not proven spoken time."))
        }
    }

    private fun backendProcessingCard(): View {
        val snap = snapshot
        val raw = snap?.status?.optJSONObject("raw_frames")
        val latest = snap?.status?.optJSONObject("latest_memory")
        val audio = snap?.audio ?: JSONObject()
        val rawTranscripts = snap?.rawTranscripts ?: JSONObject()
        val transcriptItems = rawTranscripts.optJSONArray("items") ?: JSONArray()
        val latestChunk = transcriptItems.optJSONObject(0)
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val rawBytes = audio.optLong("raw_bytes", raw?.optLong("bytes_today", 0L) ?: 0L)
        val rawChunks = audio.optInt("raw_chunks", 0)
        val decodedWavs = audio.optInt("decoded_wavs", 0)
        val decodedBytes = audio.optLong("decoded_bytes", 0L)
        val transcriptCount = rawTranscripts.optInt("count", transcriptItems.length())
        val hours = audio.optDouble("estimated_hours", 0.0)
        val latestTitle = latest?.cleanString("title").orEmpty()
        val latestWhen = latest?.cleanString("started_at").orEmpty()
        val audioError = audio.cleanString("_error")
        val rawError = rawTranscripts.cleanString("_error")
        val stageTitle = backendStageTitle(rawBytes, decodedWavs, transcriptCount, latestTitle)
        val transcriptEvidence = transcriptCount > 0 || latestChunk != null

        return card {
            addView(meta("Backend processing"))
            addView(cardTitle(stageTitle))
            addView(bodyText("Memories are the last step. Raw audio, decoded WAVs, and transcript chunks can show up first; that means the recording exists and Chronicle is still working it forward."))
            addView(statusGrid(
                listOf(
                    "Raw audio" to when {
                        rawBytes > 0L -> "${formatBytes(rawBytes)} / $rawChunks chunks"
                        transcriptEvidence -> "present via transcript"
                        else -> "waiting"
                    },
                    "Decoded" to when {
                        decodedWavs > 0 -> "$decodedWavs WAVs${if (decodedBytes > 0L) " / ${formatBytes(decodedBytes)}" else ""}"
                        transcriptEvidence -> "complete enough for STT"
                        else -> "waiting"
                    },
                    "Transcripts" to if (transcriptCount > 0) "$transcriptCount chunks" else "waiting",
                    "Latest memory" to latestMemoryLine(latestTitle, latestWhen),
                    "Last raw" to if (rawAge >= 0.0) shortAgeSeconds(rawAge) else "unknown",
                    "Airtime" to formatHours(hours),
                )
            ))
            backendAudioHistoryView()?.let { addView(it) }
            if (latestChunk != null) {
                val preview = latestChunk.cleanString("first_text")
                    .ifBlank { latestChunk.cleanString("last_text") }
                    .ifBlank { "(no words in latest chunk)" }
                val whenText = latestChunk.cleanString("mtime").let { if (it.isBlank()) "time unknown" else ago(it) }
                val duration = latestChunk.optDouble("duration", -1.0)
                val durationText = if (duration > 0.0) " / ${formatDuration(duration)}" else ""
                addView(provenanceText("Latest transcript: $whenText$durationText | ${preview.take(160)}"))
            } else if (audioError.isNotBlank() || rawError.isNotBlank()) {
                addView(provenanceText(listOf(audioError, rawError).filter { it.isNotBlank() }.joinToString(" | ")))
            } else {
                addView(provenanceText("No transcript chunks returned yet. Watch Raw audio and Decoded first; transcript chunks usually appear before final memories."))
            }
            addView(scrollButtonRow {
                addView(pillButton("Reload", Variant.QUIET) { reloadData() })
                addView(pillButton("Raw chunks", Variant.QUIET) { openChroniclePath("/pendant/api/v3/raw") })
                addView(pillButton("Audio stats", Variant.QUIET) { openChroniclePath("/pendant/api/v3/audio") })
            })
        }
    }

    private fun storageTransferCard(): View? {
        val service = PendantForegroundService.instance ?: return null
        val ble = service.bleClient ?: return null
        val sync = ble.storageSync.value
        if (!sync.active) return null

        return card {
            addView(meta("File transfer"))
            transferTitleText = cardTitle(transferTitle(sync)).also { addView(it) }
            transferDetailText = bodyText(transferDetail(sync)).also { addView(it) }
            transferProgress = ProgressBar(
                this@MainActivity,
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                isIndeterminate = sync.filesTotal <= 0
                max = TRANSFER_PROGRESS_MAX
                progress = transferProgressValue(sync)
                progressTintList = ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(CARD_BORDER)
                indeterminateTintList = ColorStateList.valueOf(ACCENT)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    8.dp(),
                ).apply { setMargins(0, 16.dp(), 0, 12.dp()) }
            }.also { addView(it) }
            transferStatsText = provenanceText(transferStats(sync)).also { addView(it) }
            addView(scrollButtonRow {
                addView(pillButton("Debug", Variant.QUIET) {
                    startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                })
                addView(pillButton("Reconnect Pendant", Variant.QUIET) { reconnectPendant() })
            })
        }
    }

    private fun updateTransferCard() {
        val sync = PendantForegroundService.instance?.bleClient?.storageSync?.value ?: return
        transferTitleText?.text = transferTitle(sync)
        transferDetailText?.text = transferDetail(sync)
        transferStatsText?.text = transferStats(sync)
        transferProgress?.apply {
            isIndeterminate = sync.active && sync.filesTotal <= 0
            progress = transferProgressValue(sync)
        }
    }

    private fun transferTitle(sync: OmiBleClient.StorageSyncStatus): String =
        when {
            sync.cleanupActive -> "Cleaning up pendant storage"
            sync.filesTotal <= 0 -> "Listing pendant files"
            sync.filesDone >= sync.filesTotal -> "Finishing storage cleanup"
            else -> "Transferring file ${sync.filesDone + 1} of ${sync.filesTotal}"
        }

    private fun transferDetail(sync: OmiBleClient.StorageSyncStatus): String =
        when {
            sync.cleanupActive -> {
                val index = sync.cleanupLastIndex?.let { " index=$it" }.orEmpty()
                "The phone has a durable local copy and is asking the pendant to delete the synced file$index."
            }
            sync.filesTotal <= 0 -> "The phone is asking the pendant what offline files are available."
            sync.filesDone >= sync.filesTotal -> "The phone has read the listed files and is waiting for cleanup to settle."
            else -> "The phone is pulling audio from pendant storage, saving a durable local copy, then uploading frames for backend processing."
        }

    private fun transferStats(sync: OmiBleClient.StorageSyncStatus): String {
        val fileText = if (sync.filesTotal > 0) {
            "${sync.filesDone}/${sync.filesTotal} files"
        } else {
            "listing files"
        }
        val err = sync.lastError?.takeIf { it.isNotBlank() }?.let { " | last error: $it" }.orEmpty()
        val bytesText = if (sync.bytesTotal > 0L) {
            "${formatBytes(sync.bytesDone)} of ${formatBytes(sync.bytesTotal)}"
        } else {
            "${formatBytes(sync.bytesDone)} read"
        }
        val cleanup = if (
            sync.cleanupActive ||
            sync.cleanupDeleted > 0 ||
            sync.cleanupFailed > 0 ||
            sync.cleanupLastStatus?.isNotBlank() == true
        ) {
            val last = sync.cleanupLastStatus?.takeIf { it.isNotBlank() }?.let { ", last $it" }.orEmpty()
            " | cleanup deleted=${sync.cleanupDeleted} failed=${sync.cleanupFailed}$last"
        } else {
            ""
        }
        return "$fileText | $bytesText | ${sync.framesDone} frames$cleanup$err"
    }

    private fun transferProgressValue(sync: OmiBleClient.StorageSyncStatus): Int =
        if (sync.filesTotal > 0 && sync.filesDone >= sync.filesTotal) {
            TRANSFER_PROGRESS_MAX
        } else if (sync.bytesTotal > 0L) {
            ((sync.bytesDone.toDouble() / sync.bytesTotal.toDouble()) * TRANSFER_PROGRESS_MAX)
                .roundToInt()
                .coerceIn(0, TRANSFER_PROGRESS_MAX)
        } else if (sync.filesTotal > 0) {
            ((sync.filesDone.toDouble() / sync.filesTotal.toDouble()) * TRANSFER_PROGRESS_MAX)
                .roundToInt()
                .coerceIn(0, TRANSFER_PROGRESS_MAX)
        } else {
            0
        }

    private fun memoryCard(memory: JSONObject): View {
        val structured = memory.optJSONObject("structured") ?: JSONObject()
        val title = structured.cleanString("title", "Untitled memory")
        val overview = structured.cleanString("overview", "No overview yet.")
        val category = structured.cleanString("category", "Memory")
        val time = memoryStartedAt(memory).let { if (it.isBlank()) "recorded time unknown" else "recorded ${evidenceTimeLine(it)}" }
        val entities = memory.optJSONArray("entities") ?: JSONArray()
        val id = memory.cleanString("id")

        return card {
            addView(meta("$category | $time"))
            addView(cardTitle(title))
            addView(bodyText(overview))
            if (entities.length() > 0) addView(entityRow(entities))
            addView(provenanceText(memorySourceSummary(memory)))
            addView(buttonRow {
                addView(pillButton("Open memory", Variant.QUIET) {
                    showConversation(id, title)
                })
                addView(pillButton("Ask about this", Variant.PRIMARY) {
                    sendChat("What should I remember from this memory: $title?")
                })
            })
        }
    }

    private fun actionCard(item: JSONObject): View {
        val convId = item.cleanString("conv_id")
        val index = item.optInt("index", -1)
        val done = item.optBoolean("done", false)
        val title = item.cleanString("conv_title", "Source memory")

        return card {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            val check = CheckBox(this@MainActivity).apply {
                isChecked = done
                buttonTintList = ColorStateList.valueOf(ACCENT)
                setOnClickListener {
                    isEnabled = false
                    toggleAction(convId, index)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 12.dp(), 0) }
            }
            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(meta("TODO | $title"))
                addView(itemTitle(item.cleanString("text", "TODO")))
                val due = item.cleanString("due")
                if (due.isNotBlank()) addView(bodyText("Due ${ago(due)}"))
            }
            row.addView(check)
            row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row)
            addView(scrollButtonRow {
                addView(pillButton(if (done) "Reopen" else "Mark Done", Variant.PRIMARY) {
                    toggleAction(convId, index)
                })
                addView(pillButton("Ask Why", Variant.QUIET) {
                    sendChat("Why is this my TODO: ${item.cleanString("text", "TODO")}")
                })
                addView(pillButton("Open memory", Variant.QUIET) { showConversation(convId, title) })
            })
        }
    }

    private fun approvalCard(approval: JSONObject): View =
        card {
            val id = approval.cleanString("job_id").ifBlank { approval.cleanString("id") }
            addView(meta("Scout | ${approval.cleanString("intent_skill", "Approval")}"))
            addView(itemTitle(approval.cleanString("raw_text", "Approval request")))
            addView(bodyText("Approve this only if you want Scout to do the work. If you are doing it yourself, make it a TODO instead."))
            addView(scrollButtonRow {
                addView(pillButton("Approve", Variant.PRIMARY) { decideApproval(id, true) })
                addView(pillButton("Reject", Variant.DANGER) { decideApproval(id, false) })
                addView(pillButton("Ask Why", Variant.QUIET) {
                    sendChat("Why does this approval need my attention: ${approval.cleanString("raw_text", "Approval request")}")
                })
            })
        }

    private fun chatCard(): View =
        card {
            addView(meta("Ambient ask"))
            addView(cardTitle("Ask the day, not a bot"))
            addView(bodyText("Chronicle answers from recent memories, live snippets, TODOs, Scout approvals, capture state, health context, and the knowledge graph. Source-backed answers show their receipts."))
            addView(promptChipRow())
            val input = EditText(this@MainActivity).apply {
                hint = "Ask Chronicle about what just happened"
                setHintTextColor(MUTED)
                setTextColor(TEXT)
                scaledTextSize = 14f
                minLines = 2
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                background = rounded(BG_SOFT, CARD_BORDER, 12.dp().toFloat())
                setPadding(20.dp(), 16.dp(), 20.dp(), 16.dp())
                if (lastChatQuestion.isNotEmpty()) {
                    setText(lastChatQuestion)
                    setSelection(lastChatQuestion.length)
                }
            }
            addView(input)
            addView(buttonRow {
                addView(pillButton("Send", Variant.PRIMARY) {
                    val text = input.text.toString().trim()
                    if (text.isNotBlank()) sendChat(text)
                })
            })
            if (chatLoading) {
                addView(provenanceText("Checking source memories, live transcript, and open loops..."))
            }
            val answer = lastChatAnswer
            if (answer != null) {
                addView(answerSurface(answer))
                val sources = chatSources(answer)
                if (sources.length() > 0) {
                    addView(sectionLabel("Sources"))
                    for (i in 0 until minOf(sources.length(), 4)) {
                        val source = sources.optJSONObject(i) ?: continue
                        addView(sourceReference(source))
                    }
                }
            }
        }

    private fun translationCard(): View =
        card {
            addView(meta("Translation"))
            addView(cardTitle("Live translation"))
            addView(bodyText("Mode: ${translationSummary(compact = false)}"))
            addView(buttonRow {
                addView(pillButton("Auto", Variant.QUIET) { setTranslationMode("auto") })
                addView(pillButton("On", Variant.TRANSLATE) { setTranslationMode("on") })
                addView(pillButton("Off", Variant.DANGER) { setTranslationMode("off") })
            })
        }

    private fun liveTranscriptCard(segments: JSONArray): View =
        card {
            addView(meta(if (isListening()) "Live transcript" else "Transcript buffer"))
            addView(cardTitle(if (isListening()) "Capture in progress" else "Live capture is idle"))
            liveStreamStatusText = bodyText(liveStreamLine()).also { addView(it) }
            liveTranscriptContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12.dp(), 0, 0)
            }
            addView(liveTranscriptContainer)
            liveTranscriptContainer?.let { renderTranscriptSegments(it, segments) }
        }

    private fun renderTranscriptSegments(container: LinearLayout, segments: JSONArray) {
        container.removeAllViews()
        val error = lastLiveError
        if (error != null) {
            container.addView(provenanceText("Live transcript refresh failed: $error"))
        }
        if (segments.length() == 0) {
            container.addView(stateCard(
                eyebrow = if (isListening()) "Live capture" else "Idle",
                title = if (isListening()) "Waiting for speech" else "No live transcript yet",
                text = if (isListening()) {
                    "The companion is awake for this live moment. Transcript snippets will appear here as Chronicle receives them."
                } else {
                    "Start Live when you want the pendant, voice bridge, and transcript stream awake."
                },
                primaryLabel = if (isListening()) "Reconnect Voice" else "Start Live",
                primaryAction = { if (isListening()) reconnectAudio() else startLiveMode() },
            ))
            return
        }

        val start = maxOf(0, segments.length() - 18)
        for (i in start until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val text = segmentText(segment)
            if (text.isBlank()) continue
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                background = rounded(BG_SOFT, CARD_BORDER, 10.dp().toFloat())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 0, 10.dp()) }
                val speaker = segment.cleanString("speaker")
                    .ifBlank { segment.cleanString("speaker_name") }
                    .ifBlank { if (segment.optBoolean("is_final", true)) "Transcript" else "Partial" }
                val time = segment.cleanString("started_at")
                    .ifBlank { segment.cleanString("timestamp") }
                    .ifBlank { segment.cleanString("time") }
                val isFinal = segment.optBoolean("is_final", true)
                addView(meta(if (time.isBlank()) {
                    if (isFinal) speaker else "$speaker | partial"
                } else {
                    "$speaker | ${ago(time)}${if (isFinal) "" else " | partial"}"
                }))
                addView(bodyText(text))
            })
        }
    }

    private fun showConversation(id: String, fallbackTitle: String) {
        if (id.isBlank()) {
            toast("This item does not include a source memory id.")
            return
        }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetMessage("Opening memory..."))
        dialog.show()
        lifecycleScope.launch {
            runCatching { api.conversation(id) }
                .onSuccess { dialog.setContentView(conversationSheet(it, fallbackTitle)) }
                .onFailure { dialog.setContentView(sheetMessage("Could not open memory: ${it.message}")) }
        }
    }

    private fun conversationSheet(conversation: JSONObject, fallbackTitle: String): View {
        val structured = conversation.optJSONObject("structured") ?: JSONObject()
        val title = structured.cleanString("title", fallbackTitle)
        val overview = structured.cleanString("overview", "")
        val segments = conversation.optJSONArray("segments") ?: JSONArray()

        val wrap = sheetContainer()
        wrap.addView(meta("Memory detail"))
        wrap.addView(cardTitle(title))
        if (overview.isNotBlank()) wrap.addView(bodyText(overview))
        wrap.addView(provenanceText(memorySourceSummary(conversation)))
        wrap.addView(scrollButtonRow {
            addView(pillButton("Ask about this", Variant.PRIMARY) {
                sendChat("What should I remember from this memory: $title?")
            })
            addView(pillButton("Open Ask", Variant.QUIET) { selectScreen(Screen.ASK) })
        })
        if (segments.length() == 0) {
            wrap.addView(message("No transcript segments returned."))
        } else {
            wrap.addView(sectionLabel("Transcript"))
            for (i in 0 until minOf(segments.length(), 24)) {
                val segment = segments.optJSONObject(i) ?: continue
                wrap.addView(TextView(this).apply {
                    val speaker = segment.cleanString("speaker")
                    val textBody = segment.cleanString("text", "")
                    text = if (speaker.isBlank()) textBody else "$speaker: $textBody"
                    scaledTextSize = 14f
                    setTextColor(TEXT)
                    setLineSpacing(4f, 1.05f)
                    setPadding(0, 10.dp(), 0, 12.dp())
                })
            }
        }
        return ScrollView(this).apply { addView(wrap) }
    }

    private fun showSettingsSheet() {
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val wrap = sheetContainer()
        wrap.addView(meta("Settings"))
        wrap.addView(cardTitle("Pendant service"))
        wrap.addView(bodyText("Low-power mode keeps BLE and audio-out idle until you sync or start live mode."))
        wrap.addView(bodyText("BLE: ${ble?.state?.value?.name ?: "idle"} | Frames: ${ble?.frameCount() ?: 0}"))
        wrap.addView(scrollButtonRow {
            addView(pillButton("Sync Now", Variant.PRIMARY) { syncNow() })
            addView(pillButton(if (isListening()) "Pause Capture" else "Start Live", if (isListening()) Variant.DANGER else Variant.QUIET) {
                if (isListening()) stopLiveMode() else startLiveMode()
            })
        })
        wrap.addView(scrollButtonRow {
            addView(pillButton("Reconnect Pendant", Variant.QUIET) { reconnectPendant() })
            addView(pillButton("Reconnect Voice", Variant.QUIET) { reconnectAudio() })
        })
        wrap.addView(scrollButtonRow {
            addView(pillButton("Privacy", Variant.QUIET) { showPrivacySheet() })
        })
        wrap.addView(scrollButtonRow {
            addView(pillButton("Auto", Variant.QUIET) { setTranslationMode("auto") })
            addView(pillButton("On", Variant.TRANSLATE) { setTranslationMode("on") })
            addView(pillButton("Off", Variant.DANGER) { setTranslationMode("off") })
        })
        wrap.addView(scrollButtonRow {
            addView(pillButton("Web App", Variant.QUIET) { openChroniclePath("/pendant/v3") })
            addView(pillButton("Web Chat", Variant.QUIET) { openChroniclePath("/pendant/v3/chat") })
            addView(pillButton("Web Status", Variant.QUIET) { openChroniclePath("/pendant/v3/status") })
        })
        var dialog: BottomSheetDialog? = null
        wrap.addView(cardTitle("Text size"))
        wrap.addView(scrollButtonRow {
            val scale = getFontScale()
            addView(pillButton("Small", if (scale == 0.85f) Variant.PRIMARY else Variant.QUIET) {
                dialog?.dismiss()
                setFontScale(0.85f)
            })
            addView(pillButton("Normal", if (scale == 1.0f) Variant.PRIMARY else Variant.QUIET) {
                dialog?.dismiss()
                setFontScale(1.0f)
            })
            addView(pillButton("Large", if (scale == 1.15f) Variant.PRIMARY else Variant.QUIET) {
                dialog?.dismiss()
                setFontScale(1.15f)
            })
            addView(pillButton("Huge", if (scale == 1.3f) Variant.PRIMARY else Variant.QUIET) {
                dialog?.dismiss()
                setFontScale(1.3f)
            })
        })
        wrap.addView(scrollButtonRow {
            addView(pillButton("Reload", Variant.QUIET) { reloadData() })
            addView(pillButton("Diagnostics", Variant.QUIET) {
                dialog?.dismiss()
                selectScreen(Screen.PROCESSING)
            })
            addView(pillButton("Debug", Variant.QUIET) {
                startActivity(Intent(this@MainActivity, DebugActivity::class.java))
            })
            addView(pillButton("Stop Service", Variant.DANGER) {
                PendantForegroundService.stop(this@MainActivity)
                toast("Pendant service stopped.")
                handler.postDelayed({ renderContent() }, 500)
            })
        })

        dialog = BottomSheetDialog(this).apply {
            setContentView(ScrollView(this@MainActivity).apply { addView(wrap) })
            show()
        }
    }

    private fun showPrivacySheet() {
        val wrap = sheetContainer()
        wrap.addView(meta("Privacy"))
        wrap.addView(cardTitle(if (isListening()) "Live capture is active" else "Live capture is paused"))
        wrap.addView(bodyText("Use Pause Capture for the immediate privacy action available in the native app. Stored-memory redaction, private mode, and deletion need backend contracts before they can be exposed as production controls."))
        wrap.addView(scrollButtonRow {
            addView(pillButton("Pause Capture", Variant.DANGER) { stopLiveMode() })
            addView(pillButton("Processing", Variant.QUIET) { selectScreen(Screen.PROCESSING) })
        })

        BottomSheetDialog(this).apply {
            setContentView(ScrollView(this@MainActivity).apply { addView(wrap) })
            show()
        }
    }

    private fun showMemoryControls(id: String, title: String) {
        val wrap = sheetContainer()
        wrap.addView(meta("Correction loop"))
        wrap.addView(cardTitle(title.ifBlank { "Memory controls" }))
        wrap.addView(bodyText("Memory edit, redaction, and feedback controls are hidden until the backend exposes production endpoints. For now, ask Chronicle about the memory or inspect its source."))
        wrap.addView(scrollButtonRow {
            addView(pillButton("Ask about this", Variant.PRIMARY) {
                sendChat("What should I correct or verify about this memory: $title?")
            })
            addView(pillButton("Open memory", Variant.QUIET) { showConversation(id, title) })
        })

        BottomSheetDialog(this).apply {
            setContentView(ScrollView(this@MainActivity).apply { addView(wrap) })
            show()
        }
    }

    private fun showTranslationSheet() {
        val wrap = sheetContainer()
        wrap.addView(meta("Translate"))
        wrap.addView(cardTitle("Translation controls"))
        wrap.addView(bodyText("Current: ${translationSummary(compact = false)}"))
        wrap.addView(scrollButtonRow {
            addView(pillButton("Auto", Variant.QUIET) { setTranslationMode("auto") })
            addView(pillButton("On", Variant.TRANSLATE) { setTranslationMode("on") })
            addView(pillButton("Off", Variant.DANGER) { setTranslationMode("off") })
        })

        BottomSheetDialog(this).apply {
            setContentView(ScrollView(this@MainActivity).apply { addView(wrap) })
            show()
        }
    }

    private fun toggleAction(convId: String, index: Int) {
        if (convId.isBlank() || index < 0) {
            toast("Action item is missing its source id.")
            return
        }
        val previousActionItems = snapshot?.actionItems?.let { JSONObject(it.toString()) }
        setActionDoneLocally(convId, index, done = true)
        toast("Done.")
        lifecycleScope.launch {
            runCatching { api.toggleAction(convId, index) }
                .onSuccess {
                    refreshActionItemsOnly()
                }
                .onFailure {
                    previousActionItems?.let { restoreActionItems(it) }
                    toast("Could not update action: ${it.message}")
                }
        }
    }

    private fun setActionDoneLocally(convId: String, index: Int, done: Boolean) {
        val current = snapshot ?: return
        val existing = current.actionItems
        val items = existing.optJSONArray("items") ?: JSONArray()
        val nextItems = JSONArray()
        var touched = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val sameItem = item.cleanString("conv_id") == convId && item.optInt("index", -1) == index
            if (sameItem) {
                touched = true
                if (!done) {
                    nextItems.put(JSONObject(item.toString()).put("done", false))
                }
            } else {
                nextItems.put(item)
            }
        }
        if (!touched) return
        val nextActionItems = JSONObject(existing.toString())
            .put("items", nextItems)
            .put("count", nextItems.length())
        snapshot = current.copy(actionItems = nextActionItems)
        snapshot?.let { cache.save(it) }
        renderContent()
    }

    private fun restoreActionItems(actionItems: JSONObject) {
        val current = snapshot ?: return
        snapshot = current.copy(actionItems = actionItems)
        snapshot?.let { cache.save(it) }
        renderContent()
    }

    private fun refreshActionItemsOnly() {
        lifecycleScope.launch {
            runCatching { api.actionItems(completed = false) }
                .onSuccess { restoreActionItems(it) }
                .onFailure {
                    Log.w(tag, "action items refresh failed: ${it.message}", it)
                }
        }
    }

    private fun decideApproval(id: String, approve: Boolean) {
        if (id.isBlank()) {
            toast("Approval is missing an id.")
            return
        }
        val previousApprovals = snapshot?.approvals?.let { JSONObject(it.toString()) }
        setApprovalDecidedLocally(id, approve)
        toast(if (approve) "Approved." else "Rejected.")
        lifecycleScope.launch {
            runCatching { api.decideApproval(id, approve) }
                .onSuccess {
                    refreshApprovalsOnly()
                }
                .onFailure {
                    previousApprovals?.let { restoreApprovals(it) }
                    toast("Approval failed: ${it.message}")
                }
        }
    }

    private fun setApprovalDecidedLocally(id: String, approve: Boolean) {
        val current = snapshot ?: return
        val existing = current.approvals
        val pending = existing.optJSONArray("pending") ?: JSONArray()
        val recent = existing.optJSONArray("recent") ?: JSONArray()
        val nextPending = JSONArray()
        val nextRecent = JSONArray()
        var moved: JSONObject? = null
        val now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        for (i in 0 until pending.length()) {
            val approval = pending.optJSONObject(i) ?: continue
            val approvalId = approval.cleanString("job_id").ifBlank { approval.cleanString("id") }
            if (approvalId == id) {
                moved = JSONObject(approval.toString())
                    .put("status", if (approve) "approved" else "rejected")
                    .put("decided_locally", true)
                    .put("decided_at", now)
            } else {
                nextPending.put(approval)
            }
        }

        moved?.let { nextRecent.put(it) }
        for (i in 0 until minOf(recent.length(), 7)) {
            recent.optJSONObject(i)?.let { nextRecent.put(it) }
        }
        if (moved == null) return

        val nextApprovals = JSONObject(existing.toString())
            .put("pending", nextPending)
            .put("recent", nextRecent)
        snapshot = current.copy(approvals = nextApprovals)
        snapshot?.let { cache.save(it) }
        renderContent()
    }

    private fun restoreApprovals(approvals: JSONObject) {
        val current = snapshot ?: return
        snapshot = current.copy(approvals = approvals)
        snapshot?.let { cache.save(it) }
        renderContent()
    }

    private fun refreshApprovalsOnly() {
        lifecycleScope.launch {
            runCatching { api.approvals() }
                .onSuccess { restoreApprovals(it) }
                .onFailure {
                    Log.w(tag, "approvals refresh failed: ${it.message}", it)
                }
        }
    }

    private fun startLiveMode() {
        askPermissionsThen {
            PendantForegroundService.startLive(this)
            updateListeningSnapshot(on = true)
            showLiveScreen()
            toast("Live capture started. Chronicle will nudge when something needs attention.")
            maybePollLiveTranscript(force = true)
            lifecycleScope.launch {
                runCatching { api.setListening(true) }
                    .onFailure { toast("Phone capture is active; cloud confirmation is still catching up.") }
                maybePollLiveTranscript(force = true)
                handler.postDelayed({ reloadData() }, 1_500)
            }
        }
    }

    private fun stopLiveMode() {
        PendantForegroundService.stopLive(this)
        updateListeningSnapshot(on = false)
        renderContent()
        toast("Live capture paused.")
        lifecycleScope.launch {
            runCatching { api.setListening(false) }
                .onFailure { toast("Phone capture is paused; cloud state is still catching up.") }
            maybePollLiveTranscript(force = true)
            handler.postDelayed({ reloadData() }, 1_000)
        }
    }

    private fun updateListeningSnapshot(on: Boolean) {
        val snap = snapshot ?: return
        val now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val listening = JSONObject(snap.listening.toString())
            .put("on", on)
            .put("phone_requested", true)
            .put("updated_at", now)
        val live = JSONObject(snap.live.toString())
            .put("phone_requested", true)
            .put("updated_at", now)
        if (!on) {
            live.put("is_listening", false)
        }
        snapshot = snap.copy(live = live, listening = listening)
        snapshot?.let { cache.save(it) }
        if (on) lastLiveError = null
    }

    private fun showLiveScreen() {
        if (selectedScreen != Screen.CAPTURE) {
            selectScreen(Screen.CAPTURE)
        } else {
            renderContent()
        }
    }

    private fun syncNow() {
        askPermissionsThen {
            PendantForegroundService.syncNow(this)
            toast("Sync requested.")
            renderContent()
            handler.postDelayed({ reloadData() }, 1_500)
        }
    }

    private fun proveRecording() {
        askPermissionsThen {
            PendantForegroundService.proveRecording(this)
            toast("Recording proof started. Talk for at least 15 seconds.")
            renderContent()
        }
    }

    private fun reconnectPendant() {
        askPermissionsThen {
            PendantForegroundService.reconnectPendant(this)
            toast("Pendant reconnect requested. Button watch will arm when connected.")
            handler.postDelayed({ reloadData() }, 1_500)
        }
    }

    private fun armPendantButton() {
        askPermissionsThen {
            PendantForegroundService.armButton(this)
            toast("Button link opened briefly. Single tap starts live capture; double tap syncs. Long press may power the pendant off.")
            handler.postDelayed({ renderContent() }, 1_000)
        }
    }

    private fun reconnectAudio() {
        if (PendantForegroundService.instance?.isLiveMode != true) {
            toast("Start live capture before reconnecting voice.")
            return
        }
        PendantForegroundService.reconnectAudio(this)
        toast("Voice reconnect requested.")
        handler.postDelayed({ reloadData() }, 1_000)
    }

    private fun setTranslationMode(mode: String) {
        val target = snapshot?.translation?.cleanString("target_lang", "en") ?: "en"
        val previousTranslation = snapshot?.translation?.let { JSONObject(it.toString()) }
        val optimistic = JSONObject(previousTranslation?.toString() ?: "{}")
            .put("manual_override", mode)
            .put("target_lang", target)
            .put("engaged", when (mode) {
                "on" -> true
                "off" -> false
                else -> previousTranslation?.optBoolean("engaged", false) ?: false
            })
        updateTranslationSnapshot(optimistic)
        lifecycleScope.launch {
            runCatching { api.setTranslationMode(mode, target) }
                .onSuccess {
                    updateTranslationSnapshot(it)
                    toast("Translate ${modeLabel(mode)}.")
                }
                .onFailure {
                    previousTranslation?.let { previous -> updateTranslationSnapshot(previous) }
                    toast("Translate update failed: ${it.message}")
                }
        }
    }

    private fun updateTranslationSnapshot(update: JSONObject) {
        val current = snapshot ?: return
        val next = JSONObject(current.translation.toString())
        val keys = update.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            next.put(key, update.opt(key))
        }
        snapshot = current.copy(translation = next)
        snapshot?.let { cache.save(it) }
        renderContent()
    }

    private fun maybePollStoreStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStorePollAt < STORE_POLL_MS) return
        if (storePollInFlight) return
        val store = PendantForegroundService.instance?.audioStore ?: return

        storePollInFlight = true
        lastStorePollAt = now
        lifecycleScope.launch {
            runCatching { store.status() }
                .onSuccess {
                    storeStatus = it
                    if (force) renderContent() else renderHeader()
                }
                .onFailure { Log.w(tag, "AudioStore status failed: ${it.message}") }
            storePollInFlight = false
        }
    }

    private fun sendChat(message: String) {
        selectedScreen = Screen.ASK
        lastChatQuestion = message
        lastChatAnswer = null
        chatLoading = true
        renderContent()
        lifecycleScope.launch {
            runCatching { api.chat(message, askContext()) }
                .onSuccess {
                    lastChatAnswer = it
                    chatLoading = false
                    renderContent()
                }
                .onFailure {
                    chatLoading = false
                    lastChatAnswer = JSONObject()
                        .put("answer", "Chronicle could not answer because the chat endpoint failed: ${it.message}")
                    renderContent()
                }
        }
    }

    private fun maybePollLiveTranscript(force: Boolean = false) {
        if (!force && !shouldPollLiveTranscript()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastLivePollAt < LIVE_POLL_MS) return
        if (livePollInFlight) return

        livePollInFlight = true
        lastLivePollAt = now
        lifecycleScope.launch {
            runCatching { api.liveState() }
                .onSuccess { (live, listening) ->
                    val errorText = listOf(live.cleanString("_error"), listening.cleanString("_error"))
                        .filter { it.isNotBlank() }
                        .joinToString("; ")
                    lastLiveError = errorText.takeIf { it.isNotBlank() }
                    snapshot = snapshot?.copy(live = live, listening = listening)
                    if (selectedScreen == Screen.CAPTURE || selectedScreen == Screen.TODAY) {
                        updateLiveTranscriptOnly()
                    } else {
                        renderHeader()
                    }
                }
                .onFailure {
                    lastLiveError = it.message ?: "Live refresh failed"
                    if (selectedScreen == Screen.CAPTURE || selectedScreen == Screen.TODAY) updateLiveTranscriptOnly()
                }
            livePollInFlight = false
        }
    }

    private fun shouldPollLiveTranscript(): Boolean =
        selectedScreen == Screen.CAPTURE || selectedScreen == Screen.TODAY || isListening()

    private fun updateLiveTranscriptOnly() {
        renderHeader()
        liveStreamStatusText?.text = liveStreamLine()
        val live = snapshot?.live ?: JSONObject()
        liveTranscriptContainer?.let { renderTranscriptSegments(it, liveSegments(live)) }
    }

    private fun snapshotStateBanner(): View? =
        when {
            lastError != null && snapshot != null -> stateCard(
                eyebrow = "Offline cache",
                title = "Showing the last saved Chronicle state",
                text = "The app could not refresh from ${apiBase()}. You can keep reviewing cached memories while reconnecting.",
                primaryLabel = "Reload",
                primaryAction = { reloadData() },
            )
            lastError != null -> stateCard(
                eyebrow = "Disconnected",
                title = "Chronicle API is unreachable",
                text = lastError ?: "Network error",
                primaryLabel = "Retry",
                primaryAction = { reloadData(showBlank = true) },
            )
            isRefreshing && snapshot != null -> stateCard(
                eyebrow = "Refreshing",
                title = "Updating in the background",
                text = "Cached data is visible while Chronicle fetches the newest memories, TODOs, Scout approvals, and device state.",
            )
            else -> null
        }

    private fun stateCard(
        eyebrow: String,
        title: String,
        text: String,
        primaryLabel: String? = null,
        primaryAction: (() -> Unit)? = null,
        secondaryLabel: String? = null,
        secondaryAction: (() -> Unit)? = null,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 20.dp(), 24.dp(), 20.dp())
            background = rounded(BG_SOFT, CARD_BORDER, 12.dp().toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 12.dp(), 0, 16.dp()) }
            addView(meta(eyebrow))
            addView(TextView(this@MainActivity).apply {
                this.text = title
                scaledTextSize = 16f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 6.dp(), 0, 5.dp())
            })
            addView(bodyText(text))
            if (primaryLabel != null && primaryAction != null) {
                addView(scrollButtonRow {
                    addView(pillButton(primaryLabel, Variant.PRIMARY, primaryAction))
                    if (secondaryLabel != null && secondaryAction != null) {
                        addView(pillButton(secondaryLabel, Variant.QUIET, secondaryAction))
                    }
                })
            }
        }

    private fun statusLoop(items: List<Pair<String, Boolean>>): View {
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 12.dp(), 0, 2.dp())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        items.forEach { (label, active) ->
            row.addView(loopPill(label, active))
        }
        scroller.addView(row)
        return scroller
    }

    private fun loopPill(label: String, active: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 10.dp(), 14.dp(), 10.dp())
            background = rounded(if (active) GOOD_DARK else CHIP_BG, if (active) GOOD else CARD_BORDER, 999f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 10.dp(), 0) }
            addView(View(this@MainActivity).apply {
                background = rounded(if (active) GOOD else MUTED, if (active) GOOD else MUTED, 999f)
            }, LinearLayout.LayoutParams(10.dp(), 10.dp()).apply { setMargins(0, 0, 10.dp(), 0) })
            addView(TextView(this@MainActivity).apply {
                text = label
                scaledTextSize = 11f
                setTextColor(if (active) TEXT else MUTED)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
        }

    private fun answerSurface(response: JSONObject): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 18.dp(), 20.dp(), 18.dp())
            background = rounded(CONTROL_BG, CONTROL_BORDER, 12.dp().toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 18.dp(), 0, 0) }
            addView(meta(if (lastChatQuestion.isBlank()) "Answer" else "Answer | $lastChatQuestion"))
            addView(bodyText(chatAnswerText(response)).apply {
                scaledTextSize = 14f
                setTextColor(TEXT)
            })
            addView(provenanceText(chatTrustLine(response)))
        }

    private fun card(fill: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            background = rounded(CARD, CARD_BORDER, 12.dp().toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 8.dp(), 0, 12.dp()) }
            fill()
        }

    private fun quickControls(): View {
        val listening = isListening()

        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
            background = rounded(CONTROL_BG, CONTROL_BORDER, 8.dp().toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 16.dp(), 0, 6.dp()) }
        }

        band.addView(meta("Quick controls"))
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 10.dp(), 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            commandTile(
                title = "Sync",
                subtitle = "now",
                background = ACCENT,
                foreground = BG,
                stroke = ACCENT,
            ) { syncNow() }
        )
        row.addView(
            commandTile(
                title = "Proof",
                subtitle = "recording",
                background = CHIP_BG,
                foreground = TEXT,
                stroke = CARD_BORDER,
            ) { proveRecording() }
        )
        row.addView(
            commandTile(
                title = if (listening) "Pause" else "Live",
                subtitle = "capture",
                background = if (listening) GOOD_DARK else TEXT,
                foreground = if (listening) GOOD else BG,
                stroke = if (listening) GOOD else TEXT,
            ) {
                if (listening) stopLiveMode() else startLiveMode()
            }
        )
        row.addView(
            commandTile(
                title = "Button",
                subtitle = "arm",
                background = CHIP_BG,
                foreground = TEXT,
                stroke = CARD_BORDER,
            ) { armPendantButton() }
        )
        row.addView(
            commandTile(
                title = "Translate",
                subtitle = translationSummary(compact = true).lowercase(),
                background = if (translationActive()) TRANSLATE_DARK else CHIP_BG,
                foreground = if (translationActive()) TRANSLATE else TEXT,
                stroke = if (translationActive()) TRANSLATE else CARD_BORDER,
            ) { showTranslationSheet() }
        )
        row.addView(
            commandTile(
                title = "Pendant",
                subtitle = "reconnect",
                background = CHIP_BG,
                foreground = TEXT,
                stroke = CARD_BORDER,
            ) { reconnectPendant() }
        )
        row.addView(
            commandTile(
                title = "Voice",
                subtitle = "reconnect",
                background = CHIP_BG,
                foreground = TEXT,
                stroke = CARD_BORDER,
            ) { reconnectAudio() }
        )

        scroller.addView(row)
        band.addView(scroller)
        return band
    }

    private fun commandTile(
        title: String,
        subtitle: String,
        background: Int,
        foreground: Int,
        stroke: Int,
        onClick: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = 92.dp()
            setPadding(10.dp(), 12.dp(), 10.dp(), 12.dp())
            this.background = rounded(background, stroke, 10.dp().toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).apply {
                text = title
                gravity = Gravity.CENTER
                scaledTextSize = 14f
                setTextColor(foreground)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                gravity = Gravity.CENTER
                scaledTextSize = 11f
                setTextColor(foreground)
                includeFontPadding = false
                maxLines = 1
                alpha = 0.82f
                setPadding(0, 6.dp(), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(
                128.dp(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 10.dp(), 0) }
        }

    private fun memoryToolsCard(memories: JSONArray): View =
        card {
            addView(meta("Retrieve"))
            addView(cardTitle("Find the thread"))
            addView(bodyText("Use Ask for native retrieval until a dedicated semantic-search endpoint is exposed. These prompts search recent memories through Chronicle's context-backed chat path."))
            addView(chipScroller(listOf("People", "Topics", "Decisions", "Promises", "Open loops", "TODOs")) { label ->
                sendChat("Show me recent memories related to $label.")
            })
            val uncertain = countUncertainMemories(memories)
            if (uncertain > 0) {
                addView(provenanceText("$uncertain memories look low-confidence or need review."))
            }
        }

    private fun memoryArchiveIntroCard(memories: JSONArray): View =
        card {
            addView(meta("Memory / knowledge"))
            addView(cardTitle("${memories.length()} finished memories"))
            addView(bodyText("This is the finished artifact layer: what Chronicle remembered, what it can cite, and what you can ask about later. Open Processing only when you need the raw capture proof behind it."))
            addView(scrollButtonRow {
                addView(pillButton("Ask Memory", Variant.PRIMARY) {
                    sendChat("What should I remember from my latest memories?")
                })
                addView(pillButton("Search", Variant.QUIET) {
                    sendChat("Search my recent memories for the most important thread I should revisit.")
                })
                addView(pillButton("Map", Variant.QUIET) { selectScreen(Screen.MAP) })
                addView(pillButton("Open Processing", Variant.QUIET) { selectScreen(Screen.PROCESSING) })
            })
        }

    private fun todoScopeCard(todoCount: Int, scoutCount: Int): View =
        card {
            addView(meta("Inbox Summary"))
            addView(cardTitle("Your Workspace Inbox"))
            addView(bodyText("A unified view of items requiring action. Complete your TODOs and review Scout's pending approvals below."))
            addView(statusGrid(
                listOf(
                    "Your TODOs" to todoCount.toString(),
                    "Scout approvals" to scoutCount.toString(),
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Ask Inbox Summary", Variant.QUIET) {
                    sendChat("Summarize my open TODOs and pending Scout approvals.")
                })
            })
        }

    private fun webScreensCard(): View =
        card {
            addView(meta("Web app screens"))
            addView(cardTitle("Every web surface has a pendant path"))
            addView(bodyText("Use the native screens first. These shortcuts keep the full web app, live transcript, chat, status, raw transcript, and audio consoles one tap away when you need the browser version."))
            addView(scrollButtonRow {
                addView(pillButton("Web Home", Variant.QUIET) { openChroniclePath("/pendant/v3") })
                addView(pillButton("Live", Variant.QUIET) { openChroniclePath("/pendant/v3/live") })
                addView(pillButton("Status", Variant.QUIET) { openChroniclePath("/pendant/v3/status") })
                addView(pillButton("Chat", Variant.QUIET) { openChroniclePath("/pendant/v3/chat") })
                addView(pillButton("Raw", Variant.QUIET) { openChroniclePath("/pendant/v3/raw") })
                addView(pillButton("Audio", Variant.QUIET) { openChroniclePath("/pendant/v3/audio") })
            })
        }

    private fun insightCard(insight: JSONObject): View =
        card {
            addView(meta(insight.cleanString("headline", "Chronicle insight")))
            addView(cardTitle("Pattern from recent memories"))
            addView(bodyText(insight.cleanString("body")))
            val entities = insight.optJSONArray("entities") ?: JSONArray()
            if (entities.length() > 0) {
                val labels = mutableListOf<String>()
                for (i in 0 until minOf(entities.length(), 8)) {
                    val label = entities.optString(i, "").trim()
                    if (label.isNotBlank()) labels += label
                }
                if (labels.isNotEmpty()) {
                    addView(chipScroller(labels) { entity ->
                        sendChat("What context do I have around $entity?")
                    })
                }
            }
            addView(scrollButtonRow {
                addView(pillButton("Explore Context", Variant.PRIMARY) {
                    sendChat("Explore the context behind this insight: ${insight.cleanString("body")}")
                })
                addView(pillButton("Dismiss", Variant.QUIET) {
                    dismissedInsightKey = insightKey(insight)
                    toast("Insight hidden until Chronicle returns a new one.")
                    renderContent()
                })
            })
        }

    private fun insightKey(insight: JSONObject): String =
        insight.cleanString("id")
            .ifBlank { insight.cleanString("headline") + "|" + insight.cleanString("body").take(160) }

    private fun graphNodeCard(node: JSONObject): View =
        card {
            val label = node.cleanString("label")
                .ifBlank { node.cleanString("name") }
                .ifBlank { node.cleanString("id", "Unknown entity") }
            val type = node.cleanString("type", "topic")
            val mentions = node.optInt("mention_count", 0)
            val lastSeen = node.cleanString("last_seen")
            addView(meta(type))
            addView(cardTitle(label))
            addView(bodyText(when {
                mentions > 1 && lastSeen.isNotBlank() -> "$mentions mentions. Last seen ${ago(lastSeen)}."
                mentions > 1 -> "$mentions mentions in the current graph window."
                lastSeen.isNotBlank() -> "Mentioned once. Last seen ${ago(lastSeen)}."
                else -> "Mentioned once in the current graph window."
            }))
            addView(scrollButtonRow {
                addView(pillButton("Ask", Variant.PRIMARY) {
                    sendChat("What do my memories say about $label?")
                })
                addView(pillButton("Related", Variant.QUIET) {
                    sendChat("What is related to $label in my knowledge graph?")
                })
            })
        }

    private fun graphEdgeCard(edge: JSONObject): View =
        card {
            val source = edge.cleanString("source", "source")
            val target = edge.cleanString("target", "target")
            val rels = edge.optJSONArray("relationships") ?: JSONArray()
            val relationships = jsonStrings(rels, 3).joinToString(", ")
            val weight = edge.optInt("weight", 1)
            addView(meta("Relationship"))
            addView(cardTitle("$source -> $target"))
            addView(bodyText(if (relationships.isBlank()) {
                "Linked $weight time${if (weight == 1) "" else "s"} in recent memories."
            } else {
                "$relationships. Linked $weight time${if (weight == 1) "" else "s"} in recent memories."
            }))
            addView(scrollButtonRow {
                addView(pillButton("Ask Link", Variant.PRIMARY) {
                    sendChat("Explain the relationship between $source and $target from my memories.")
                })
            })
        }

    private fun graphTypeCounts(nodes: JSONArray): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val type = node.cleanString("type", "topic")
            counts[type] = (counts[type] ?: 0) + 1
        }
        return counts
    }

    private fun jsonStrings(values: JSONArray, limit: Int): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until minOf(values.length(), limit)) {
            val value = values.optString(i, "").trim()
            if (value.isNotBlank()) out += value
        }
        return out
    }

    private fun reviewInboxCard(): View {
        val snap = snapshot
        val memories = snap?.memories?.optJSONArray("memories") ?: JSONArray()
        val actions = snap?.actionItems?.optJSONArray("items") ?: JSONArray()
        val approvals = snap?.approvals?.optJSONArray("pending") ?: JSONArray()
        val uncertain = countUncertainMemories(memories)

        return card {
            addView(meta("Review inbox"))
            addView(cardTitle("Needs attention"))
            addView(statusGrid(
                listOf(
                    "Uncertain" to uncertain.toString(),
                    "TODOs" to actions.length().toString(),
                    "Scout" to approvals.length().toString(),
                    "Live" to if (isListening()) "capture" else "idle",
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Review TODOs", Variant.QUIET) {
                    selectScreen(Screen.ACTIONS)
                })
                addView(pillButton("Open Scout", Variant.QUIET) {
                    selectScreen(Screen.APPROVALS)
                })
            })
        }
    }

    private fun scoutQueueCard(): View {
        val pending = snapshot?.approvals?.optJSONArray("pending") ?: JSONArray()
        val recent = snapshot?.approvals?.optJSONArray("recent") ?: JSONArray()

        return card {
            addView(meta("Scout note"))
            addView(cardTitle(if (pending.length() > 0) "Scout has agent work waiting" else "Scout has no pending agent work"))
            addView(bodyText("Scout is agent tasks plus approvals. If Scout does the work, you approve it here first. If you do the work, it belongs in TODOs."))
            addView(statusGrid(
                listOf(
                    "Approvals" to pending.length().toString(),
                    "Recent" to recent.length().toString(),
                    "Mode" to "approve first",
                    "Owner" to "Scout",
                )
            ))
            addView(scrollButtonRow {
                addView(pillButton("Review", Variant.PRIMARY) { selectScreen(Screen.APPROVALS) })
                addView(pillButton("Open TODOs", Variant.QUIET) { selectScreen(Screen.ACTIONS) })
                addView(pillButton("Ask Why", Variant.QUIET) {
                    sendChat("What is Scout asking to do, and why does it need approval?")
                })
                addView(pillButton("Reload", Variant.QUIET) { reloadData() })
            })
        }
    }

    private fun companionNudgesCard(): View {
        val snap = snapshot
        val memories = snap?.memories?.optJSONArray("memories") ?: JSONArray()
        val actions = snap?.actionItems?.optJSONArray("items") ?: JSONArray()
        val approvals = snap?.approvals?.optJSONArray("pending") ?: JSONArray()
        val rawAge = snap?.status?.optJSONObject("raw_frames")?.optDouble("age_s", -1.0) ?: -1.0
        val uncertain = countUncertainMemories(memories)
        val nudges = mutableListOf<String>()

        if (approvals.length() > 0) {
            nudges += "Scout: ${approvals.length()} agent tasks waiting for approval."
        }
        if (actions.length() > 0) {
            nudges += "TODOs: ${actions.length()} follow-ups waiting for you."
        }
        if (uncertain > 0) {
            nudges += "$uncertain memory capture${if (uncertain == 1) "" else "s"} look uncertain and could use review."
        }
        if (isListening()) {
            nudges += "Live capture is awake. Pause it from the top controls when the moment gets private."
        } else if (rawAge > 600.0) {
            nudges += "No fresh pendant audio for ${shortAgeSeconds(rawAge)}. Sync when you want the companion caught up."
        }
        if (nudges.isEmpty()) {
            nudges += "Nothing urgent. Chronicle will stay quiet until there is a useful nudge."
        }

        return card {
            addView(meta("Companion nudges"))
            addView(cardTitle("Small prompts, not a dashboard"))
            addView(bodyText("Chronicle should feel like the pendant's Android companion: quiet by default, helpful when something needs your attention."))
            nudges.take(3).forEach { addView(provenanceText(it)) }
            addView(chipScroller(
                listOf(
                    "What needs a nudge?",
                    "What am I forgetting?",
                    "Draft the reply",
                )
            ) { prompt -> sendChat(prompt) })
        }
    }

    private fun promptChipRow(): View =
        chipScroller(
            listOf(
                "What did I agree to?",
                "Summarize the last thread",
                "What needs follow-up?",
                "What am I forgetting?",
                "What patterns stand out?",
                "Draft the reply",
                "What changed today?",
            )
        ) { prompt -> sendChat(prompt) }

    private fun chipScroller(labels: List<String>, onClick: (String) -> Unit): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 12.dp(), 0, 2.dp())
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            labels.forEach { label ->
                row.addView(pillButton(label, Variant.QUIET) { onClick(label) })
            }
            addView(row)
        }

    private fun sourceReference(source: JSONObject): View {
        val title = source.cleanString("title")
            .ifBlank { source.cleanString("memory_title") }
            .ifBlank { source.cleanString("conv_title", "Source memory") }
        val whenText = source.cleanString("started_at")
            .ifBlank { source.cleanString("timestamp") }
            .let { if (it.isBlank()) "time unknown" else ago(it) }
        val snippet = source.cleanString("transcript_snippet")
            .ifBlank { source.cleanString("snippet") }
            .ifBlank { source.cleanString("text") }
        val speaker = source.cleanString("speaker")
            .ifBlank { source.cleanString("entity") }
        val id = source.cleanString("conversation_id")
            .ifBlank { source.cleanString("conv_id") }
            .ifBlank { source.cleanString("id") }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = rounded(BG_SOFT, CARD_BORDER, 10.dp().toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 10.dp()) }
            addView(meta("Source | $whenText"))
            addView(TextView(this@MainActivity).apply {
                text = title
                scaledTextSize = 13f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 5.dp(), 0, 2.dp())
            })
            if (snippet.isNotBlank()) {
                addView(bodyText(if (speaker.isBlank()) snippet else "$speaker: $snippet"))
            }
            if (id.isNotBlank()) {
                addView(buttonRow {
                    addView(pillButton("Open source", Variant.QUIET) { showConversation(id, title) })
                })
            }
        }
    }

    private fun statusGrid(items: List<Pair<String, String>>): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10.dp(), 0, 0)
        }
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            pair.forEach { (label, value) ->
                row.addView(statusCell(label, value), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row)
        }
        return grid
    }

    private fun backendAudioHistoryView(): View? {
        val days = snapshot?.audioHistory?.optJSONArray("days") ?: return null
        if (days.length() == 0) return null
        val rows = mutableListOf<Pair<String, String>>()
        for (i in 0 until days.length()) {
            val day = days.optJSONObject(i) ?: continue
            val rawBytes = day.optLong("raw_bytes", 0L)
            val rawChunks = day.optInt("raw_chunks", 0)
            val decodedWavs = day.optInt("decoded_wavs", 0)
            val date = day.cleanString("date", "unknown")
            val value = if (rawBytes > 0L || rawChunks > 0 || decodedWavs > 0) {
                "${formatBytes(rawBytes)} / $rawChunks raw / $decodedWavs WAV"
            } else {
                "no backend audio"
            }
            rows += date to value
        }
        if (rows.isEmpty()) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp(), 0, 0)
            addView(sectionLabel("Backend audio by UTC day"))
            addView(statusGrid(rows))
        }
    }

    private fun localStoreLine(): String {
        val st = storeStatus ?: return "checking"
        val queue = if (st.queueCount > 0) ", ${st.queueCount} pending" else ""
        return "${formatBytes(st.storedBytes)}$queue"
    }

    private fun statusCell(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 16.dp(), 18.dp(), 16.dp())
            background = rounded(BG_SOFT, CARD_BORDER, 14.dp().toFloat())
            addView(TextView(this@MainActivity).apply {
                text = label.uppercase()
                scaledTextSize = 11f
                letterSpacing = 0.06f
                setTextColor(ACCENT)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                scaledTextSize = 14f
                setTextColor(TEXT)
                setPadding(0, 8.dp(), 0, 0)
                maxLines = 2
            })
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { setMargins(0, 10.dp(), 12.dp(), 10.dp()) }
        }

    private fun provenanceText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            scaledTextSize = 13f
            setTextColor(MUTED)
            setLineSpacing(3f, 1.05f)
            background = rounded(BG_SOFT, CARD_BORDER, 14.dp().toFloat())
            setPadding(18.dp(), 16.dp(), 18.dp(), 16.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 18.dp(), 0, 0) }
        }

    private fun memorySourceSummary(memory: JSONObject): String {
        val id = memory.cleanString("id").take(8).ifBlank { "unknown" }
        val started = memory.cleanString("started_at")
        val provider = memory.cleanString("provider")
        val confidence = memory.optJSONObject("structured")?.optDouble("confidence", -1.0) ?: memory.optDouble("confidence", -1.0)
        val parts = mutableListOf("Source memory $id")
        if (started.isNotBlank()) parts += ago(started)
        if (provider.isNotBlank()) parts += provider
        if (confidence >= 0.0) parts += "confidence ${(confidence * 100.0).roundToInt()}%"
        return parts.joinToString(" | ")
    }

    private fun chatAnswerText(response: JSONObject): String =
        response.cleanString("answer")
            .ifBlank { response.cleanString("response") }
            .ifBlank { response.cleanString("text") }
            .ifBlank { response.optJSONObject("result")?.cleanString("answer").orEmpty() }
            .ifBlank { "Chronicle returned no answer text." }

    private fun chatSources(response: JSONObject): JSONArray =
        response.optJSONArray("cited_conversations")
            ?: response.optJSONArray("sources")
            ?: response.optJSONArray("citations")
            ?: response.optJSONObject("result")?.optJSONArray("cited_conversations")
            ?: JSONArray()

    private fun chatTrustLine(response: JSONObject): String {
        val sources = chatSources(response)
        val uncertainty = response.cleanString("uncertainty")
            .ifBlank { response.cleanString("confidence") }
        return when {
            sources.length() > 0 && uncertainty.isNotBlank() -> "Backed by ${sources.length()} source memories. Uncertainty: $uncertainty"
            sources.length() > 0 -> "Backed by ${sources.length()} source memories. Treat unsourced suggestions as inference."
            else -> "No source memories were returned. Treat this as an inferred suggestion, not a remembered fact."
        }
    }

    private fun askContext(): JSONObject {
        val snap = snapshot ?: return JSONObject()
        val memories = JSONArray()
        val memoryList = snap.memories.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until minOf(memoryList.length(), 8)) {
            val memory = memoryList.optJSONObject(i) ?: continue
            val structured = memory.optJSONObject("structured") ?: JSONObject()
            memories.put(JSONObject()
                .put("id", memory.cleanString("id"))
                .put("title", structured.cleanString("title"))
                .put("overview", structured.cleanString("overview"))
                .put("started_at", memory.cleanString("started_at")))
        }

        val live = JSONArray()
        val segments = liveSegments(snap.live)
        for (i in 0 until minOf(segments.length(), 8)) {
            val segment = segments.optJSONObject(i) ?: continue
            live.put(JSONObject()
                .put("speaker", segment.cleanString("speaker"))
                .put("text", segmentText(segment)))
        }

        val actions = JSONArray()
        val actionItems = snap.actionItems.optJSONArray("items") ?: JSONArray()
        for (i in 0 until minOf(actionItems.length(), 10)) {
            val item = actionItems.optJSONObject(i) ?: continue
            actions.put(JSONObject()
                .put("conv_id", item.cleanString("conv_id"))
                .put("title", item.cleanString("conv_title"))
                .put("text", item.cleanString("text"))
                .put("due", item.cleanString("due")))
        }

        val graphNodes = JSONArray()
        val nodes = snap.graph.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until minOf(nodes.length(), 12)) {
            val node = nodes.optJSONObject(i) ?: continue
            graphNodes.put(JSONObject()
                .put("id", node.cleanString("id"))
                .put("label", node.cleanString("label"))
                .put("type", node.cleanString("type"))
                .put("mention_count", node.optInt("mention_count", 0)))
        }

        return JSONObject()
            .put("listening", isListening())
            .put("recent_memories", memories)
            .put("live_segments", live)
            .put("pending_actions", actions)
            .put("pending_approvals", snap.approvals.optJSONArray("pending") ?: JSONArray())
            .put("health_today", snap.health.optJSONObject("today") ?: JSONObject())
            .put("knowledge_graph_entities", graphNodes)
            .put("current_insight", snap.insights)
    }

    private fun countUncertainMemories(memories: JSONArray): Int {
        var count = 0
        for (i in 0 until memories.length()) {
            val memory = memories.optJSONObject(i) ?: continue
            val structured = memory.optJSONObject("structured") ?: JSONObject()
            val confidence = structured.optDouble("confidence", memory.optDouble("confidence", 1.0))
            if (confidence < 0.72 ||
                structured.optBoolean("uncertain", false) ||
                memory.optBoolean("uncertain", false) ||
                structured.cleanString("needs_review").isNotBlank()
            ) {
                count++
            }
        }
        return count
    }

    private fun buttonRow(fill: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 18.dp(), 0, 0)
            fill()
        }

    private fun scrollButtonRow(fill: LinearLayout.() -> Unit): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 18.dp(), 0, 0)
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                fill()
            }
            addView(row)
        }

    private fun entityRow(entities: JSONArray): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 18.dp(), 0, 0)
            }
            for (i in 0 until minOf(entities.length(), 6)) {
                val entity = entities.optJSONObject(i) ?: continue
                val label = entity.cleanString("name")
                if (label.isNotBlank()) row.addView(chip(label))
            }
            addView(row)
        }

    private fun metricRow(today: JSONObject): View {
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        today.keys().forEach { key ->
            val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
            row.addView(chip("$label ${today.opt(key)}"))
        }
        scroller.addView(row)
        return scroller
    }

    private fun pillButton(label: String, variant: Variant, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            scaledTextSize = 13f
            cornerRadius = 10.dp()
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
            setTextColor(variant.fg())
            backgroundTintList = ColorStateList.valueOf(variant.bg())
            strokeColor = ColorStateList.valueOf(variant.stroke())
            strokeWidth = 1.dp()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 10.dp(), 0) }
        }

    private fun miniButton(label: String, variant: Variant, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            scaledTextSize = 12f
            cornerRadius = 9.dp()
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
            setTextColor(variant.fg())
            backgroundTintList = ColorStateList.valueOf(variant.bg())
            strokeColor = ColorStateList.valueOf(variant.stroke())
            strokeWidth = 1.dp()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 10.dp(), 0) }
        }

    private fun chip(text: String): TextView =
        TextView(this).apply {
            this.text = text
            scaledTextSize = 12f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(CHIP_BG, CARD_BORDER, 999f)
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 10.dp(), 0) }
        }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            scaledTextSize = 14f
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2.dp(), 12.dp(), 0, 8.dp())
        }

    private fun compactTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text.ifBlank { "No title returned." }
            scaledTextSize = 14f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(1f, 1.02f)
            setPadding(0, 4.dp(), 0, 3.dp())
        }

    private fun compactBody(text: String): TextView =
        TextView(this).apply {
            this.text = text.ifBlank { "No detail returned." }
            scaledTextSize = 13f
            setTextColor(MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(1.5f, 1.02f)
        }

    private fun hairline(): View =
        View(this).apply {
            setBackgroundColor(CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1,
            ).apply { setMargins(0, 0, 0, 8.dp()) }
        }

    private var TextView.scaledTextSize: Float
        get() = this.textSize / (resources.displayMetrics.scaledDensity * getFontScale())
        set(value) {
            this.textSize = value * getFontScale()
        }

    private fun getFontScale(): Float {
        val prefs = getSharedPreferences("pendant_prefs", MODE_PRIVATE)
        return prefs.getFloat("font_scale", 1.0f)
    }

    private fun setFontScale(scale: Float) {
        val prefs = getSharedPreferences("pendant_prefs", MODE_PRIVATE)
        prefs.edit().putFloat("font_scale", scale).apply()
        toast("Text size updated to ${scale}x")
        renderContent()
    }

    private fun meta(text: String): TextView =
        TextView(this).apply {
            this.text = text.uppercase()
            scaledTextSize = 11f
            letterSpacing = 0.06f
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun cardTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            scaledTextSize = 17f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(1.5f, 1.02f)
            setPadding(0, 4.dp(), 0, 4.dp())
        }

    private fun itemTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text.ifBlank { "No title returned." }
            scaledTextSize = 15f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(2f, 1.03f)
            setPadding(0, 4.dp(), 0, 4.dp())
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text.ifBlank { "No detail returned." }
            scaledTextSize = 13f
            setTextColor(MUTED)
            setLineSpacing(2f, 1.03f)
        }

    private fun message(text: String): TextView =
        TextView(this).apply {
            this.text = text
            scaledTextSize = 14f
            setTextColor(MUTED)
            setPadding(0, 12.dp(), 0, 8.dp())
        }

    private fun sheetContainer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 36.dp())
            setBackgroundColor(BG)
        }

    private fun sheetMessage(text: String): View =
        sheetContainer().apply { addView(message(text)) }

    private fun showLoading() {
        body.removeAllViews()
        body.addView(header())
        body.addView(stateCard(
            eyebrow = "Loading",
            title = "Syncing Chronicle state",
            text = "Memories, TODOs, live state, Scout approvals, and health context are loading from the backend.",
        ))
    }

    private fun rounded(color: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(1.dp(), stroke)
        }

    private fun navTint(): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(ACCENT, MUTED),
        )

    private fun screenTitle(): String =
        when (selectedScreen) {
            Screen.TODAY -> "Today"
            Screen.CAPTURE -> "Capture"
            Screen.TRANSLATE -> "Translate"
            Screen.ACTIONS -> "Inbox"
            Screen.APPROVALS -> "Scout"
            Screen.MEMORIES -> "Memories"
            Screen.MAP -> "Knowledge Map"
            Screen.HEALTH -> "Health"
            Screen.ASK -> "Ask"
            Screen.PROCESSING -> "Processing"
        }

    private fun maybeRequestHealthConnect() {
        if (!healthReader.init()) {
            Log.i(tag, "Health Connect unavailable; skipping permission request")
            return
        }
        lifecycleScope.launch {
            if (!healthReader.hasAllPermissions()) {
                healthReader.launchPermissionRequest(hcPermissionLauncher)
            }
        }
    }

    private fun askPermissionsThen(action: () -> Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms += Manifest.permission.ACTIVITY_RECOGNITION
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            permissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startPendantService() {
        PendantForegroundService.start(this)
        startStatusTicker()
    }

    private fun startStatusTicker() {
        handler.removeCallbacks(statusTicker)
        handler.post(statusTicker)
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            reloadData()
        }
        startStatusTicker()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusTicker)
        super.onDestroy()
    }

    private fun apiBase(): String {
        val raw = URL(BuildConfig.AGENT_URL)
        return "${raw.protocol}://${raw.host}:8772"
    }

    private fun audioOutHttpBase(): String {
        val raw = URI(BuildConfig.AUDIO_OUT_WS_URL)
        val scheme = if (raw.scheme == "wss") "https" else "http"
        val port = if (raw.port > 0) ":${raw.port}" else ""
        return "$scheme://${raw.host}$port"
    }

    private fun openChroniclePath(path: String) {
        val target = apiBase().trimEnd('/') + path
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)))
        }.onFailure {
            toast("Could not open $path")
        }
    }

    private fun isListening(): Boolean =
        snapshot?.live?.optBoolean("is_listening", false) == true ||
            PendantForegroundService.instance?.isLiveMode == true

    private fun cloudCaptureRequested(): Boolean =
        snapshot?.listening?.optBoolean("on", false) == true

    private fun captureRequestedButNotLive(): Boolean =
        cloudCaptureRequested() && !isListening()

    private fun companionModeHeadline(): String {
        val service = PendantForegroundService.instance
        val sync = service?.bleClient?.storageSync?.value
        val rawAge = snapshot?.status?.optJSONObject("raw_frames")?.optDouble("age_s", -1.0) ?: -1.0
        return when {
            sync?.active == true -> "Syncing files"
            service?.isLiveMode == true -> "Live capture"
            isListening() -> "Live capture"
            cloudCaptureRequested() -> "Capture requested"
            translationActive() -> "Translating"
            rawAge in 0.0..600.0 -> "Recently heard"
            service != null -> "Companion ready"
            else -> "Companion paused"
        }
    }

    private fun pendantHealthLine(
        service: PendantForegroundService?,
        bleState: String,
        connected: Boolean,
        syncing: Boolean,
    ): String =
        when {
            service == null -> "service stopped"
            syncing -> "syncing"
            isListening() && connected -> "recording live"
            cloudCaptureRequested() -> "requested only"
            connected -> "connected"
            else -> "idle/offline"
        }.let { "$it (${bleState.lowercase()})" }

    private fun captureModeLine(connected: Boolean, rawAgeSeconds: Double): String =
        when {
            PendantForegroundService.instance?.isLiveMode == true && connected -> "live capture"
            PendantForegroundService.instance?.isLiveMode == true -> "live waking"
            snapshot?.live?.optBoolean("is_listening", false) == true -> "backend live"
            cloudCaptureRequested() -> "requested only"
            rawAgeSeconds in 0.0..600.0 -> "recent audio"
            !connected && PendantForegroundService.instance != null -> "likely offline"
            connected -> "connected quiet"
            else -> "unknown"
        }

    private fun statusExplanation(
        serviceRunning: Boolean,
        pendantConnected: Boolean,
        rawAgeSeconds: Double,
        syncError: String?,
    ): String =
        when {
            !serviceRunning -> "The Android companion service is stopped, so the phone cannot pull pendant files, stream live transcript, or post nudges."
            syncError?.isNotBlank() == true -> "The last pendant sync reported: $syncError"
            isListening() && pendantConnected -> "Live capture is active. BLE and voice are intentionally connected."
            isListening() -> "Live capture is active from backend evidence. Reconnect the pendant if transcript or audio goes quiet."
            cloudCaptureRequested() -> "Cloud capture is requested, but phone live capture is not active. Start Live to record now, or Sync Now for stored pendant files."
            pendantConnected -> "The pendant is connected for an active phone task. Offline storage is safest after BLE disconnects."
            rawAgeSeconds > 600.0 -> "No raw audio has reached Chronicle for ${shortAgeSeconds(rawAgeSeconds)}. The pendant may still have stored files if BLE was disconnected; hourly auto sync will pull them, or tap Sync Now."
            rawAgeSeconds >= 0.0 -> "Chronicle received raw audio ${shortAgeSeconds(rawAgeSeconds)}. Memories can lag while backend processing catches up."
            else -> "Hourly auto sync pulls stored pendant files. Tap Sync Now to check immediately, or Reconnect Pendant if sync status stays empty."
        }

    private fun nextBestAction(
        serviceRunning: Boolean,
        pendantConnected: Boolean,
        rawAgeSeconds: Double,
        syncError: String?,
    ): String =
        when {
            !serviceRunning -> "Next: Sync Now starts the companion service and tries to pull stored files."
            syncError?.isNotBlank() == true -> "Next: reconnect the pendant, then sync again."
            isListening() && rawAgeSeconds < 0.0 -> "Next: speak for a moment or reconnect voice if the transcript stays empty."
            isListening() -> "Next: keep talking. Pause Capture when the private moment starts."
            cloudCaptureRequested() -> "Next: Start Live if you want current capture; the cloud flag alone does not record audio."
            !pendantConnected -> "Next: wait for hourly auto sync, tap Sync Now, or Button Arm for a brief tap window."
            rawAgeSeconds > 600.0 -> "Next: hourly auto sync or Sync Now pulls any stored pendant files; memories may appear after processing."
            rawAgeSeconds >= 0.0 -> "Next: review today's memories or ask Chronicle what changed."
            else -> "Next: auto sync will check offline audio hourly; Sync Now checks immediately."
        }

    private fun buttonStatusLine(service: PendantForegroundService?, ble: OmiBleClient?): String {
        val lastAction = service?.lastButtonAction
        val lastActionAt = service?.lastButtonActionAt?.takeIf { it > 0L }
        if (lastAction != null && lastActionAt != null) {
            val shortAction = lastAction
                .removePrefix("Button armed: ")
                .removePrefix("Button link opening: ")
                .removePrefix("Button link closed: ")
            return "$shortAction ${agoMillis(System.currentTimeMillis() - lastActionAt)}"
        }

        val status = ble?.buttonStatus?.value
        if (status != null && status.receivedAt > 0L) {
            return "${status.event.label} ${agoMillis(System.currentTimeMillis() - status.receivedAt)}"
        }
        if (status?.subscribed == true) return "button link open"

        return when (ble?.state?.value) {
            OmiBleClient.State.SCANNING,
            OmiBleClient.State.CONNECTING,
            OmiBleClient.State.DISCOVERING -> "arming"
            OmiBleClient.State.READY,
            OmiBleClient.State.SUBSCRIBED,
            OmiBleClient.State.SYNCING -> "connected"
            else -> if (service != null) "tap Arm Button" else "service stopped"
        }
    }

    private fun autoSyncLine(service: PendantForegroundService?): String {
        if (service == null) return "service stopped"
        val sync = service.bleClient?.storageSync?.value
        if (sync?.active == true) return "syncing now"
        val now = System.currentTimeMillis()
        val next = service.nextAutoSyncAt
        val skip = service.lastAutoSyncSkipReason
        return when {
            skip?.isNotBlank() == true && next > now -> "retry ${formatDuration((next - now) / 1000.0)}"
            next > now -> "next ${formatDuration((next - now) / 1000.0)}"
            service.lastAutoSyncRequestedAt > 0L -> "last ${agoMillis(now - service.lastAutoSyncRequestedAt)}"
            else -> "arming"
        }
    }

    private fun pipelineHeadline(): String {
        val stages = pipelineStages()
        val memory = stages.lastOrNull()
        if (memory?.state == "Memory ready") return "Memory ready"
        return stages.lastOrNull { it.active }?.state ?: "Waiting for audio"
    }

    private fun pipelineTimelineCard(): View =
        card {
            addView(meta("Evidence spine"))
            addView(cardTitle("Pendant -> Phone -> Backend -> Transcript -> Memory"))
            addView(bodyText("Use this to answer: can I trust the pendant, where is the audio, and what should happen next? The companion cockpit stays one level up."))
            pipelineStages().forEach { stage ->
                addView(pipelineStep(stage))
            }
            addView(provenanceText(backendLagLine()))
        }

    private fun pipelineStep(stage: PipelineStage): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            background = rounded(BG_SOFT, if (stage.active) CONTROL_BORDER else CARD_BORDER, 10.dp().toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 12.dp(), 0, 0) }
            addView(View(this@MainActivity).apply {
                background = rounded(if (stage.active) ACCENT else MUTED, if (stage.active) ACCENT else MUTED, 999f)
            }, LinearLayout.LayoutParams(12.dp(), 12.dp()).apply { setMargins(0, 6.dp(), 14.dp(), 0) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(meta(stage.label))
                addView(TextView(this@MainActivity).apply {
                    text = stage.state
                    scaledTextSize = 14f
                    setTextColor(TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 4.dp(), 0, 2.dp())
                })
                addView(bodyText(stage.detail).apply { scaledTextSize = 12f })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun pipelineStages(): List<PipelineStage> {
        val snap = snapshot
        val service = PendantForegroundService.instance
        val ble = service?.bleClient
        val sync = ble?.storageSync?.value
        val uploader = service?.agentUploader
        val raw = snap?.status?.optJSONObject("raw_frames")
        val latest = snap?.status?.optJSONObject("latest_memory")
        val audio = snap?.audio ?: JSONObject()
        val rawTranscripts = snap?.rawTranscripts ?: JSONObject()
        val transcriptItems = rawTranscripts.optJSONArray("items") ?: JSONArray()
        val memories = snap?.memories?.optJSONArray("memories") ?: JSONArray()
        val bleState = ble?.state?.value?.name ?: "IDLE"
        val connected = bleState in setOf("READY", "SUBSCRIBED", "SYNCING")
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val rawBytes = audio.optLong("raw_bytes", raw?.optLong("bytes_today", 0L) ?: 0L)
        val rawChunks = audio.optInt("raw_chunks", 0)
        val decodedWavs = audio.optInt("decoded_wavs", 0)
        val transcriptCount = rawTranscripts.optInt("count", transcriptItems.length())
        val latestTitle = latest?.cleanString("title").orEmpty()
        val hasMemory = latestTitle.isNotBlank() || memories.length() > 0
        val lastSync = sync?.lastCompletedAt?.takeIf { it > 0L }

        val pendantState = when {
            sync?.active == true && sync.filesTotal <= 0 -> "Checking storage"
            sync?.active == true && sync.filesDone < sync.filesTotal -> "On pendant"
            sync?.active == true -> "Cleaning up"
            lastSync != null && (sync?.filesTotal ?: 0) > 0 -> "Synced"
            lastSync != null -> "No offline files listed"
            isListening() -> "Recording live"
            connected -> "Connected"
            else -> "Idle or offline"
        }
        val pendantDetail = when {
            sync?.active == true && sync.cleanupActive -> {
                val last = sync.cleanupLastStatus?.takeIf { it.isNotBlank() }?.let { " Last status: $it." }.orEmpty()
                "Safe cleanup is running after durable phone-local persistence. Deleted ${sync.cleanupDeleted}, failed ${sync.cleanupFailed}.$last"
            }
            sync?.active == true && sync.filesTotal <= 0 -> "The phone is asking the pendant whether offline files exist."
            sync?.active == true -> "${sync.filesDone}/${sync.filesTotal} files, ${formatBytes(sync.bytesDone)} of ${formatBytes(sync.bytesTotal)} transferred."
            lastSync != null && (sync?.filesTotal ?: 0) > 0 -> "Last sync finished ${agoMillis(System.currentTimeMillis() - lastSync)} with ${sync?.filesDone}/${sync?.filesTotal} files handled."
            lastSync != null -> "The last storage check found no files waiting on the pendant."
            connected -> "BLE is connected for live capture, button watch, or an active phone task."
            else -> "Offline storage can still be on the pendant; auto sync checks hourly, or tap Sync Now to check immediately."
        }

        val phoneState = when {
            sync?.active == true -> "Transferring"
            (uploader?.posted() ?: 0L) > 0L -> "Uploading"
            service != null -> "Ready"
            else -> "Service stopped"
        }
        val phoneDetail = when {
            sync?.active == true -> "Saving a durable phone-local copy before pendant cleanup."
            uploader != null -> "${uploader.posted()} frames posted, ${uploader.failed()} failed, batch ${uploader.lastBatchSize}."
            service != null -> "Companion service is running and ready for hourly sync, capture, translate, and nudge."
            else -> "Start with Sync Now or Live Capture to wake the Android companion service."
        }

        val backendState = when {
            rawBytes > 0L || rawAge >= 0.0 -> "Uploaded"
            transcriptCount > 0 || decodedWavs > 0 -> "Uploaded"
            (uploader?.posted() ?: 0L) > 0L -> "Receiving"
            else -> "Waiting"
        }
        val backendDetail = when {
            rawBytes > 0L -> "${formatBytes(rawBytes)} raw audio across $rawChunks chunks. Last raw audio ${if (rawAge >= 0.0) shortAgeSeconds(rawAge) else "age unknown"}."
            rawAge >= 0.0 -> "Backend saw raw audio ${shortAgeSeconds(rawAge)}."
            transcriptCount > 0 -> "$transcriptCount transcript chunks exist, so raw audio already reached backend processing."
            decodedWavs > 0 -> "$decodedWavs decoded WAV files exist, so raw audio already reached backend processing."
            else -> "No backend raw audio is visible in the current Chronicle snapshot."
        }

        val transcriptState = when {
            transcriptCount > 0 -> "Transcript ready"
            decodedWavs > 0 -> "Processing"
            rawBytes > 0L || rawAge >= 0.0 -> "Processing"
            else -> "Waiting"
        }
        val transcriptDetail = when {
            transcriptCount > 0 -> "$transcriptCount transcript chunks returned from /pendant/api/v3/raw."
            decodedWavs > 0 -> "$decodedWavs decoded WAV files exist; STT is next."
            rawBytes > 0L || rawAge >= 0.0 -> "Raw audio exists; decoding and STT may still be catching up."
            else -> "Transcript chunks appear after raw audio is decoded."
        }

        val memoryState = when {
            hasMemory -> "Memory ready"
            transcriptCount > 0 || decodedWavs > 0 || rawBytes > 0L || rawAge >= 0.0 -> "Memory processing"
            else -> "Waiting"
        }
        val memoryDetail = when {
            latestTitle.isNotBlank() -> "Latest: $latestTitle."
            memories.length() > 0 -> "${memories.length()} final memories returned in the recent window."
            transcriptCount > 0 -> "Transcript is ready; final memory creation is still processing."
            rawBytes > 0L || rawAge >= 0.0 -> "Do not treat missing memory as missing recording. Memory is the final stage."
            else -> "Final cards appear after backend processing creates memories."
        }

        return listOf(
            PipelineStage("Pendant", pendantState, pendantDetail, pendantState !in setOf("Idle or offline", "No offline files listed")),
            PipelineStage("Phone", phoneState, phoneDetail, phoneState != "Service stopped"),
            PipelineStage("Backend", backendState, backendDetail, backendState != "Waiting"),
            PipelineStage("Transcript", transcriptState, transcriptDetail, transcriptState != "Waiting"),
            PipelineStage("Memory", memoryState, memoryDetail, memoryState == "Memory ready"),
        )
    }

    private fun backendLagLine(): String {
        val snap = snapshot ?: return "Chronicle state has not loaded yet."
        val raw = snap.status.optJSONObject("raw_frames")
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        val audio = snap.audio
        val rawTranscripts = snap.rawTranscripts
        val transcriptItems = rawTranscripts.optJSONArray("items") ?: JSONArray()
        val transcriptCount = rawTranscripts.optInt("count", transcriptItems.length())
        val latestTitle = snap.status.optJSONObject("latest_memory")?.cleanString("title").orEmpty()
        val rawBytes = audio.optLong("raw_bytes", raw?.optLong("bytes_today", 0L) ?: 0L)

        return when {
            rawAge >= 0.0 && transcriptCount > 0 && latestTitle.isBlank() ->
                "Uploaded ${shortAgeSeconds(rawAge)}, transcript ready, memory still processing."
            rawAge >= 0.0 && transcriptCount == 0 && rawBytes > 0L ->
                "Uploaded ${shortAgeSeconds(rawAge)}, decoding or STT still processing."
            rawAge >= 0.0 && latestTitle.isNotBlank() ->
                "Uploaded ${shortAgeSeconds(rawAge)} and latest memory is ready."
            rawBytes > 0L ->
                "Backend has raw audio, but the latest raw age was not reported."
            transcriptCount > 0 ->
                "Transcript chunks are visible even though audio stats are missing; capture reached backend processing."
            else ->
                "No backend raw audio is visible yet. Sync Now checks pendant storage without assuming failure."
        }
    }

    private fun memoryPendingCard(): View {
        val snap = snapshot
        val processing = snap?.let { hasProcessingEvidence(it) } == true
        return stateCard(
            eyebrow = if (processing) "Memory stage" else "Ready",
            title = if (processing) "Processing before memory" else "No final memory yet",
            text = if (processing) {
                "The finished artifact is not ready yet, but Processing has upstream evidence. Keep using the companion; the memory card should appear after Chronicle finishes."
            } else {
                "Start Live for connected capture or use Sync Now to check immediately. Hourly auto sync checks for offline files while the companion service is running. Memories are outcomes, not proof that capture worked."
            },
            primaryLabel = if (processing) "Open Processing" else "Sync Now",
            primaryAction = { if (processing) selectScreen(Screen.PROCESSING) else syncNow() },
            secondaryLabel = if (isListening()) "Pause Capture" else "Start Live",
            secondaryAction = { if (isListening()) stopLiveMode() else startLiveMode() },
        )
    }

    private fun translationStatusCard(): View {
        val state = snapshot?.translation ?: JSONObject()
        val mode = state.cleanString("manual_override", "auto")
        val target = state.cleanString("target_lang", "en").uppercase()
        val detected = state.cleanString("last_detected_lang").uppercase().ifBlank { "none yet" }
        val audioOut = state.cleanString("audio_out_status")
            .ifBlank { state.cleanString("current_audio_out_status") }
            .ifBlank { if (audioOutActive()) "active" else "idle" }
        val error = state.cleanString("_error")

        return card {
            addView(meta("Translate"))
            addView(cardTitle("Mode ${modeLabel(mode)}"))
            addView(bodyText("Translation is part of live capture. It should feel like Capture with another output path, not a separate debug mode."))
            addView(statusGrid(
                listOf(
                    "Mode" to modeLabel(mode),
                    "Target" to target,
                    "Detected" to detected,
                    "Audio out" to audioOut,
                    "Live" to if (isListening()) "capture active" else "idle",
                    "State" to if (translationActive()) "engaged" else "standing by",
                )
            ))
            if (error.isNotBlank()) {
                addView(provenanceText("Translation state endpoint: $error"))
            }
        }
    }

    private fun translationSnippetsCard(): View {
        val state = snapshot?.translation ?: JSONObject()
        val snippets = state.optJSONArray("translated_snippets")
            ?: state.optJSONArray("snippets")
            ?: state.optJSONArray("recent")
            ?: JSONArray()

        return card {
            addView(meta("Recent translated snippets"))
            addView(cardTitle(if (snippets.length() > 0) "Translation receipts" else "No translated snippets yet"))
            if (snippets.length() == 0) {
                addView(bodyText("Translated snippets will appear here when live capture hears speech that needs output in ${state.cleanString("target_lang", "en").uppercase()}." ))
                addView(provenanceText("Translation is a capture mode. Start Live, keep Translate Auto or On, then watch this surface for recent translated output."))
            } else {
                for (i in 0 until minOf(snippets.length(), 5)) {
                    val snippet = snippets.optJSONObject(i) ?: continue
                    val source = snippet.cleanString("source")
                        .ifBlank { snippet.cleanString("text") }
                        .ifBlank { snippet.cleanString("original") }
                    val translated = snippet.cleanString("translated")
                        .ifBlank { snippet.cleanString("translation") }
                        .ifBlank { snippet.cleanString("output") }
                    val whenText = snippet.cleanString("created_at")
                        .ifBlank { snippet.cleanString("timestamp") }
                        .let { if (it.isBlank()) "recent" else ago(it) }
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                        background = rounded(BG_SOFT, CARD_BORDER, 8.dp().toFloat())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { setMargins(0, 8.dp(), 0, 0) }
                        addView(meta(whenText))
                        if (source.isNotBlank()) addView(bodyText(source))
                        addView(bodyText(translated.ifBlank { "No translated text returned." }).apply {
                            setTextColor(TEXT)
                        })
                    })
                }
            }
        }
    }

    private fun audioOutActive(): Boolean {
        val state = snapshot?.translation ?: return isListening() && translationActive()
        val explicit = state.optBoolean("audio_out_active", false) ||
            state.optBoolean("is_playing", false) ||
            state.optBoolean("tts_active", false)
        val status = state.cleanString("audio_out_status")
            .ifBlank { state.cleanString("current_audio_out_status") }
            .lowercase()
        return explicit || status in setOf("active", "playing", "connected", "streaming")
    }

    private fun hasProcessingEvidence(snap: ChronicleSnapshot): Boolean {
        val raw = snap.status.optJSONObject("raw_frames")
        val audio = snap.audio
        val rawTranscripts = snap.rawTranscripts
        val transcriptItems = rawTranscripts.optJSONArray("items") ?: JSONArray()
        val rawAge = raw?.optDouble("age_s", -1.0) ?: -1.0
        return rawAge >= 0.0 ||
            audio.optLong("raw_bytes", 0L) > 0L ||
            audio.optInt("decoded_wavs", 0) > 0 ||
            rawTranscripts.optInt("count", transcriptItems.length()) > 0
    }

    private fun liveStreamLine(): String {
        val segmentCount = liveSegments(snapshot?.live ?: JSONObject()).length()
        val polled = if (lastLivePollAt > 0L) "Last checked ${agoMillis(System.currentTimeMillis() - lastLivePollAt)}" else "Not checked yet"
        val error = lastLiveError
        return when {
            error != null -> "Live transcript is trying to reconnect. $polled."
            isListening() -> "Streaming transcript from Chronicle every 2s. $segmentCount snippets in view. $polled."
            selectedScreen == Screen.CAPTURE -> "Live transcript will stream here when you start Live capture."
            else -> "Live transcript is idle."
        }
    }

    private fun backendStageTitle(
        rawBytes: Long,
        decodedWavs: Int,
        transcriptCount: Int,
        latestTitle: String,
    ): String =
        when {
            latestTitle.isNotBlank() -> "Memory has been created"
            transcriptCount > 0 -> "Transcript chunks are ready"
            decodedWavs > 0 -> "Audio decoded; STT is next"
            rawBytes > 0L -> "Backend has raw audio"
            else -> "Waiting for backend audio"
        }

    private fun latestMemoryLine(title: String, startedAt: String): String =
        when {
            title.isNotBlank() && startedAt.isNotBlank() -> "${title.take(24)} / source unverified"
            title.isNotBlank() -> title.take(32)
            startedAt.isNotBlank() -> memoryTimestampWarning(startedAt)
            else -> "not created yet"
        }

    private fun liveSegments(live: JSONObject): JSONArray {
        val direct = live.optJSONArray("segments")
            ?: live.optJSONArray("transcript_segments")
            ?: live.optJSONArray("snippets")
        if (direct != null) return direct

        val text = live.cleanString("partial_transcript")
            .ifBlank { live.cleanString("partial") }
            .ifBlank { live.cleanString("transcript") }
            .ifBlank { live.cleanString("text") }
        return if (text.isBlank()) {
            JSONArray()
        } else {
            JSONArray().put(JSONObject()
                .put("speaker", "Live")
                .put("text", text)
                .put("is_final", false))
        }
    }

    private fun segmentText(segment: JSONObject): String =
        segment.cleanString("text")
            .ifBlank { segment.cleanString("transcript") }
            .ifBlank { segment.cleanString("partial") }
            .ifBlank { segment.cleanString("utterance") }

    private fun translationActive(): Boolean {
        val state = snapshot?.translation ?: return false
        return state.cleanString("manual_override", "auto") == "on" ||
            state.optBoolean("engaged", false)
    }

    private fun translationSummary(compact: Boolean): String {
        val state = snapshot?.translation ?: return "Auto"
        val mode = state.cleanString("manual_override", "auto")
        val target = state.cleanString("target_lang", "en").uppercase()
        val detected = state.cleanString("last_detected_lang").uppercase()
        val engaged = state.optBoolean("engaged", false)
        return when {
            compact && mode == "on" -> "On"
            compact && mode == "off" -> "Off"
            compact && engaged -> "Active"
            compact -> "Auto"
            mode == "on" -> "forced on to $target"
            mode == "off" -> "forced off"
            engaged && detected.isNotBlank() -> "auto active from $detected to $target"
            engaged -> "auto active to $target"
            else -> "auto to $target"
        }
    }

    private fun modeLabel(mode: String): String =
        when (mode) {
            "on" -> "forced on"
            "off" -> "forced off"
            else -> "auto"
        }

    private fun memoriesForLocalDate(memories: JSONArray, date: LocalDate): List<JSONObject> =
        jsonObjects(memories).filter { memory ->
            memoryRecordedInstant(memory)?.atZone(ZoneId.systemDefault())?.toLocalDate() == date
        }

    private fun jsonObjects(array: JSONArray): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { items += it }
        }
        return items
    }

    private fun isMemoryRecordedToday(memory: JSONObject?): Boolean =
        isLocalDate(memoryRecordedInstant(memory), LocalDate.now(ZoneId.systemDefault()))

    private fun memoryRecordedInstant(memory: JSONObject?): Instant? =
        parseIsoInstant(memoryStartedAt(memory))

    private fun latestTranscriptChunk(): JSONObject? {
        val items = snapshot?.rawTranscripts?.optJSONArray("items") ?: return null
        return items.optJSONObject(0)
    }

    private fun latestTodayTranscriptChunk(): JSONObject? {
        val items = snapshot?.rawTranscripts?.optJSONArray("items") ?: return null
        val today = LocalDate.now(ZoneId.systemDefault())
        return jsonObjects(items).firstOrNull { chunk ->
            isLocalDate(transcriptRecordedInstant(chunk), today) ||
                isLocalDate(parseIsoInstant(chunk.cleanString("mtime")), today)
        }
    }

    private fun transcriptRecordedInstant(chunk: JSONObject?): Instant? {
        val file = chunk?.cleanString("file").orEmpty()
        val match = Regex("""(\d{4}-\d{2}-\d{2})/(\d{6})""").find(file) ?: return null
        val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
        val time = match.groupValues[2]
        return runCatching {
            date.atTime(
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                time.substring(4, 6).toInt(),
            ).atZone(ZoneOffset.UTC).toInstant()
        }.getOrNull()
    }

    private fun parseIsoInstant(iso: String): Instant? {
        if (iso.isBlank()) return null
        return runCatching {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        }.getOrNull()
    }

    private fun isLocalDate(instant: Instant?, date: LocalDate): Boolean =
        instant?.atZone(ZoneId.systemDefault())?.toLocalDate() == date

    private fun evidenceTimeLine(iso: String): String =
        parseIsoInstant(iso)?.let { evidenceTimeLine(it) } ?: "not exposed"

    private fun memoryTimestampWarning(iso: String): String =
        parseIsoInstant(iso)?.let { memoryTimestampWarning(it) } ?: "memory timestamp not exposed"

    private fun memoryTimestampWarning(instant: Instant): String {
        val zone = ZoneId.systemDefault()
        val local = instant.atZone(zone)
        val today = LocalDate.now(zone)
        val day = when (local.toLocalDate()) {
            today -> ""
            today.minusDays(1) -> "yesterday"
            else -> local.format(DateTimeFormatter.ofPattern("MMM d"))
        }
        val whenText = listOf(day, local.format(DateTimeFormatter.ofPattern("h:mm a")))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return "memory ts $whenText; unverified"
    }

    private fun evidenceTimeLine(instant: Instant): String {
        val zone = ZoneId.systemDefault()
        val local = instant.atZone(zone)
        val today = LocalDate.now(zone)
        val day = when (local.toLocalDate()) {
            today -> if (local.hour < 12) "this morning" else "today"
            today.minusDays(1) -> "yesterday"
            else -> local.format(DateTimeFormatter.ofPattern("MMM d"))
        }
        return "$day ${local.format(DateTimeFormatter.ofPattern("h:mm a"))} (${agoInstant(instant)})"
    }

    private fun snapshotFreshnessLine(): String =
        when {
            isRefreshing && snapshot != null -> "refreshing"
            lastRefreshAt > 0L -> "updated ${agoMillis(System.currentTimeMillis() - lastRefreshAt)}"
            cacheLoadedAt > 0L -> "cached ${agoMillis(System.currentTimeMillis() - cacheLoadedAt)}"
            snapshot != null -> "loaded"
            else -> "not loaded"
        }

    private fun agoInstant(instant: Instant): String {
        val mins = ((System.currentTimeMillis() - instant.toEpochMilli()) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    private fun ago(iso: String): String {
        if (iso.isBlank()) return "recent"
        return try {
            val t = OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            val mins = ((System.currentTimeMillis() - t.toEpochMilli()) / 60_000).coerceAtLeast(0)
            when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 1440 -> "${mins / 60}h ago"
                else -> "${mins / 1440}d ago"
            }
        } catch (_: Throwable) {
            iso
        }
    }

    private fun shortAgeSeconds(seconds: Double): String =
        when {
            seconds < 60.0 -> "${seconds.roundToInt()}s ago"
            seconds < 3600.0 -> "${(seconds / 60.0).roundToInt()}m ago"
            else -> "${(seconds / 3600.0).roundToInt()}h ago"
        }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024L -> "${bytes} B"
            bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
            else -> {
                val tenths = (bytes / (1024.0 * 1024.0) * 10.0).roundToInt()
                "${tenths / 10}.${tenths % 10} MB"
            }
        }

    private fun formatHours(hours: Double): String =
        when {
            hours <= 0.0 -> "0h"
            hours < 1.0 -> "${(hours * 60.0).roundToInt()}m"
            else -> {
                val tenths = (hours * 10.0).roundToInt()
                "${tenths / 10}.${tenths % 10}h"
            }
        }

    private fun formatDuration(seconds: Double): String =
        when {
            seconds < 60.0 -> "${seconds.roundToInt()}s"
            seconds < 3600.0 -> "${(seconds / 60.0).roundToInt()}m"
            else -> "${(seconds / 3600.0).roundToInt()}h"
        }

    private fun agoMillis(ms: Long): String {
        val seconds = (ms / 1_000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> "${seconds}s ago"
            seconds < 3600L -> "${seconds / 60L}m ago"
            seconds < 86_400L -> "${seconds / 3600L}h ago"
            else -> "${seconds / 86_400L}d ago"
        }
    }

    private fun JSONObject.cleanString(key: String, fallback: String = ""): String {
        if (!has(key) || isNull(key)) return fallback
        val value = optString(key, fallback).trim()
        return if (value == "null") fallback else value
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private data class PipelineStage(
        val label: String,
        val state: String,
        val detail: String,
        val active: Boolean,
    )

    private enum class Screen(val label: String) {
        TODAY("Today"),
        CAPTURE("Capture"),
        TRANSLATE("Translate"),
        ACTIONS("Inbox"),
        APPROVALS("Scout"),
        MEMORIES("Memories"),
        MAP("Map"),
        HEALTH("Health"),
        ASK("Ask"),
        PROCESSING("Process"),
    }

    private enum class Variant {
        PRIMARY,
        QUIET,
        TRANSLATE,
        DANGER,
    }

    private fun Variant.bg(): Int =
        when (this) {
            Variant.PRIMARY -> ACCENT
            Variant.QUIET -> CHIP_BG
            Variant.TRANSLATE -> TRANSLATE
            Variant.DANGER -> DANGER_BG
        }

    private fun Variant.fg(): Int =
        when (this) {
            Variant.PRIMARY -> BG
            Variant.QUIET -> TEXT
            Variant.TRANSLATE -> BG
            Variant.DANGER -> DANGER
        }

    private fun Variant.stroke(): Int =
        when (this) {
            Variant.PRIMARY -> ACCENT
            Variant.QUIET -> CARD_BORDER
            Variant.TRANSLATE -> TRANSLATE
            Variant.DANGER -> DANGER
        }

    companion object {
        private const val LIVE_POLL_MS = 10_000L
        private const val STORE_POLL_MS = 10_000L
        private const val TRANSFER_PROGRESS_MAX = 1_000
        const val BG = 0xFF000000.toInt()
        const val BG_SOFT = 0xFF121212.toInt()
        const val NAV = 0xFF1F1F25.toInt()
        const val CARD = 0xFF1F1F25.toInt()
        const val CARD_BORDER = 0xFF35343B.toInt()
        const val CHIP_BG = 0xFF2E2E36.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFFBBBCC2.toInt()
        const val ACCENT = 0xFF9F7AEA.toInt()
        const val GOOD = 0xFF43A047.toInt()
        const val GOOD_DARK = 0xFF1B5E20.toInt()
        const val TRANSLATE = 0xFF9F7AEA.toInt()
        const val TRANSLATE_DARK = 0xFF311B92.toInt()
        const val DANGER = 0xFFC62828.toInt()
        const val DANGER_BG = 0xFF2C0B0B.toInt()
        const val CONTROL_BG = 0xFF1F1F25.toInt()
        const val CONTROL_BORDER = 0xFF35343B.toInt()
    }
}
