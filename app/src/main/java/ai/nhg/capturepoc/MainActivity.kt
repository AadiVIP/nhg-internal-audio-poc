package ai.nhg.capturepoc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_CONSENT = 7001
        private const val REQ_NOTIFICATIONS = 7002
        private const val REQ_OVERLAY = 7003

        // Colors
        private const val DARK_BG = 0xFF0D1117.toInt()
        private const val DARK_SURFACE = 0xFF161B22.toInt()
        private const val DARK_CARD = 0xFF21262D.toInt()
        private const val DARK_TEXT = 0xFFF0F6FC.toInt()
        private const val DARK_MUTED = 0xFF8B949E.toInt()
        private const val DARK_BORDER = 0xFF30363D.toInt()

        private const val LIGHT_BG = 0xFFF6F8FA.toInt()
        private const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
        private const val LIGHT_CARD = 0xFFEAEEF2.toInt()
        private const val LIGHT_TEXT = 0xFF1F2328.toInt()
        private const val LIGHT_MUTED = 0xFF656D76.toInt()
        private const val LIGHT_BORDER = 0xFFD0D7DE.toInt()

        private const val ACCENT_GREEN = 0xFF00E676.toInt()
        private const val ACCENT_GREEN_DARK = 0xFF00C853.toInt()
        private const val ACCENT_AMBER = 0xFFFFB300.toInt()
        private const val ACCENT_RED = 0xFFFF5252.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var consentInFlight = false

    private lateinit var prefs: PreferencesManager

    // Dynamic theme colors
    private var colorBg = DARK_BG
    private var colorSurface = DARK_SURFACE
    private var colorCard = DARK_CARD
    private var colorText = DARK_TEXT
    private var colorMuted = DARK_MUTED
    private var colorBorder = DARK_BORDER
    private var isDarkTheme = true

    // UI Structure
    private lateinit var rootLayout: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var recorderView: LinearLayout
    private lateinit var savedAudioView: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var tabRecorderBtn: LinearLayout
    private lateinit var tabSavedBtn: LinearLayout
    private lateinit var tabRecorderText: TextView
    private lateinit var tabSavedText: TextView
    private lateinit var tabSavedBadge: TextView

    // Top Bar
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var themeToggleBtn: ImageView
    private lateinit var settingsBtn: ImageView

    // Recorder UI elements
    private lateinit var statusBadge: TextView
    private lateinit var timerText: TextView
    private lateinit var visualizerView: AudioVisualizerView
    private lateinit var formatSelectorBtn: LinearLayout
    private lateinit var formatSelectorText: TextView
    private lateinit var recordMetricText: TextView
    private lateinit var disclaimerCard: LinearLayout

    private lateinit var recordControlsLayout: FrameLayout
    private lateinit var idleRecordBtn: LinearLayout
    private lateinit var idleRecordIcon: ImageView
    private lateinit var activeControlsRow: LinearLayout
    private lateinit var pauseResumeBtn: LinearLayout
    private lateinit var pauseResumeIcon: ImageView
    private lateinit var pauseResumeLabel: TextView
    private lateinit var stopBtn: LinearLayout
    private lateinit var stopIcon: ImageView

    // Saved Audio UI elements
    private lateinit var searchInput: EditText
    private lateinit var sortBtn: TextView
    private lateinit var selectBtn: TextView
    private lateinit var normalTopBar: LinearLayout
    private lateinit var selectionTopBar: LinearLayout
    private lateinit var selectionCloseBtn: ImageView
    private lateinit var selectionCountText: TextView
    private lateinit var selectAllBtn: TextView
    private lateinit var deleteSelectedBtn: TextView
    private lateinit var recordingsContainer: LinearLayout
    private lateinit var emptyStateView: LinearLayout
    private lateinit var recordingsScrollView: ScrollView

    private var isSelectionMode = false
    private val selectedFilePaths = mutableSetOf<String>()

    // Bottom Player Bar
    private lateinit var playerBarLayout: LinearLayout
    private lateinit var playerPlayPauseBtn: ImageView
    private lateinit var playerTitleText: TextView
    private lateinit var playerTimeText: TextView
    private lateinit var playerSeekBar: SeekBar
    private lateinit var playerCloseBtn: ImageView

    private var currentTab = 0 // 0 = Recorder, 1 = Saved Audio
    private var currentSortMode = 0 // 0 = Newest, 1 = Oldest, 2 = Duration, 3 = Name
    private var searchQuery = ""
    private var cachedRecordings = listOf<RecordingItem>()

    private val uiTicker = object : Runnable {
        override fun run() {
            refreshRecorderUi()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PreferencesManager.get(this)
        CaptureController.selectedFormat = prefs.outputFormat
        resolveTheme()

        buildMainLayout()
        requestNotificationPermissionIfNeeded()
        runSessionRecovery()
    }

    override fun onStart() {
        super.onStart()
        handler.post(uiTicker)
        loadSavedRecordings()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(uiTicker)
    }

    override fun onDestroy() {
        AudioPlayerController.stop()
        super.onDestroy()
    }

    private fun resolveTheme() {
        isDarkTheme = when (prefs.themeMode) {
            PreferencesManager.THEME_LIGHT -> false
            PreferencesManager.THEME_DARK -> true
            else -> {
                val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode != android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
        }

        colorBg = if (isDarkTheme) DARK_BG else LIGHT_BG
        colorSurface = if (isDarkTheme) DARK_SURFACE else LIGHT_SURFACE
        colorCard = if (isDarkTheme) DARK_CARD else LIGHT_CARD
        colorText = if (isDarkTheme) DARK_TEXT else LIGHT_TEXT
        colorMuted = if (isDarkTheme) DARK_MUTED else LIGHT_MUTED
        colorBorder = if (isDarkTheme) DARK_BORDER else LIGHT_BORDER

        window.statusBarColor = colorBg
        window.navigationBarColor = colorSurface
        if (Build.VERSION.SDK_INT >= 23) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (isDarkTheme) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun buildMainLayout() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            fitsSystemWindows = true
        }

        // Top App Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            setBackgroundColor(colorBg)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleText = TextView(this).apply {
            text = "NHG Internal Audio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        subtitleText = TextView(this).apply {
            text = "Internal device audio only"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        titleCol.addView(titleText)
        titleCol.addView(subtitleText)
        topBar.addView(titleCol)

        themeToggleBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            setImageResource(R.drawable.ic_theme)
            setColorFilter(colorMuted)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = circleRipple(colorSurface)
            setOnClickListener { toggleTheme() }
        }
        topBar.addView(themeToggleBtn)

        settingsBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setImageResource(R.drawable.ic_settings)
            setColorFilter(colorMuted)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = circleRipple(colorSurface)
            setOnClickListener { showSettingsDialog() }
        }
        topBar.addView(settingsBtn)

        rootLayout.addView(topBar)

        // Content Frame for Tabs
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        recorderView = buildRecorderView()
        savedAudioView = buildSavedAudioView()

        contentFrame.addView(recorderView)
        contentFrame.addView(savedAudioView)
        savedAudioView.visibility = View.GONE

        rootLayout.addView(contentFrame)

        // Bottom Player Bar (Floats above navigation)
        playerBarLayout = buildPlayerBar()
        rootLayout.addView(playerBarLayout)

        // Bottom Navigation Bar
        bottomNav = buildBottomNav()
        rootLayout.addView(bottomNav)

        setContentView(rootLayout)
    }

    private fun buildRecorderView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        // Status pill
        statusBadge = TextView(this).apply {
            text = "IDLE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorMuted)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = roundedDrawable(colorSurface, colorBorder, 16)
        }
        root.addView(statusBadge)

        // Elapsed Timer
        timerText = TextView(this).apply {
            text = "00:00.0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(timerText)

        // Audio Visualizer
        visualizerView = AudioVisualizerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
                topMargin = dp(4)
                bottomMargin = dp(16)
            }
            accentColor = if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK
        }
        root.addView(visualizerView)

        // Format selector pill
        formatSelectorBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedRipple(colorSurface, colorBorder, 20)
            setOnClickListener { showFormatSelectorDialog() }
        }
        formatSelectorText = TextView(this).apply {
            text = prefs.outputFormat.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val chevron = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(colorMuted)
        }
        formatSelectorBtn.addView(formatSelectorText)
        formatSelectorBtn.addView(chevron)
        root.addView(formatSelectorBtn)

        // Metrics text (file size, RMS, peak)
        recordMetricText = TextView(this).apply {
            text = "Ready to record internal playback audio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorMuted)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(16))
        }
        root.addView(recordMetricText)

        // Controls container
        recordControlsLayout = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(16)
            }
        }

        // Idle Record Button (Large circular button)
        idleRecordBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER)
            background = circleRipple(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            elevation = dp(6).toFloat()
            setOnClickListener { startCaptureSession() }
        }
        idleRecordIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setImageResource(R.drawable.ic_record)
            setColorFilter(if (isDarkTheme) DARK_BG else 0xFFFFFFFF.toInt())
        }
        idleRecordBtn.addView(idleRecordIcon)
        recordControlsLayout.addView(idleRecordBtn)

        // Active Controls Row (Pause/Resume + Stop)
        activeControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }

        // Pause/Resume button
        pauseResumeBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(20) }
            background = circleRipple(colorCard)
            contentDescription = "Pause recording"
            setOnClickListener {
                if (CaptureController.state == CaptureState.PAUSED) {
                    resumeCaptureSession()
                } else {
                    pauseCaptureSession()
                }
            }
        }
        pauseResumeIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_pause)
            setColorFilter(colorText)
        }
        pauseResumeLabel = TextView(this).apply {
            text = "Pause"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorMuted)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(3)
            }
        }
        pauseResumeBtn.addView(pauseResumeIcon)
        pauseResumeBtn.addView(pauseResumeLabel)
        activeControlsRow.addView(pauseResumeBtn)

        // Stop button
        stopBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(20) }
            background = circleRipple(0x22FF5252.toInt())
            contentDescription = "Stop recording"
            setOnClickListener { stopCaptureSession() }
        }
        stopIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_stop)
            setColorFilter(ACCENT_RED)
        }
        val stopLabel = TextView(this).apply {
            text = "Stop"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ACCENT_RED)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(3)
            }
        }
        stopBtn.addView(stopIcon)
        stopBtn.addView(stopLabel)
        activeControlsRow.addView(stopBtn)

        recordControlsLayout.addView(activeControlsRow)
        root.addView(recordControlsLayout)

        // Disclaimer Card
        disclaimerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(colorSurface, colorBorder, 14)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        val discTitle = TextView(this).apply {
            text = "About Internal Playback Capture"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val discBody = TextView(this).apply {
            text = "This app records audio output directly from Android playback streams (media, games). The microphone is never accessed. Note: Android allows individual apps to opt out of playback capture; calls and protected content cannot be captured."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorMuted)
            setPadding(0, dp(4), 0, 0)
        }
        disclaimerCard.addView(discTitle)
        disclaimerCard.addView(discBody)
        root.addView(disclaimerCard)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
        }
        return wrapper
    }

    private fun buildSavedAudioView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // Top Toolbar Container (Swaps normal search/sort/select with contextual selection bar)
        val toolbarContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

        // Normal top row (Search, Sort, Select)
        normalTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val searchWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = roundedDrawable(colorSurface, colorBorder, 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        }
        val searchIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            setImageResource(R.drawable.ic_search)
            setColorFilter(colorMuted)
        }
        searchInput = EditText(this).apply {
            hint = "Search recordings..."
            setHintTextColor(colorMuted)
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    searchQuery = s?.toString() ?: ""
                    renderRecordingsList()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchWrapper.addView(searchIcon)
        searchWrapper.addView(searchInput)
        normalTopBar.addView(searchWrapper)

        // Sort Button
        sortBtn = TextView(this).apply {
            text = "Sort"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRipple(colorSurface, colorBorder, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }
            setOnClickListener { showSortMenu(it) }
        }
        normalTopBar.addView(sortBtn)

        // Select Button
        selectBtn = TextView(this).apply {
            text = "Select"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRipple(colorSurface, colorBorder, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
            setOnClickListener { enterSelectionMode() }
        }
        normalTopBar.addView(selectBtn)
        toolbarContainer.addView(normalTopBar)

        // Selection Top Bar (Contextual Action Bar)
        selectionTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
            background = roundedDrawable(colorSurface, colorBorder, 12)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            visibility = View.GONE
        }

        selectionCloseBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setImageResource(R.drawable.ic_close)
            setColorFilter(colorText)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = circleRipple(colorSurface)
            contentDescription = "Exit selection mode"
            setOnClickListener { exitSelectionMode() }
        }
        selectionTopBar.addView(selectionCloseBtn)

        selectionCountText = TextView(this).apply {
            text = "0 selected"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        selectionTopBar.addView(selectionCountText)

        selectAllBtn = TextView(this).apply {
            text = "Select all"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedRipple(colorSurface, null, 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { toggleSelectAll() }
        }
        selectionTopBar.addView(selectAllBtn)

        deleteSelectedBtn = TextView(this).apply {
            text = "Delete"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ACCENT_RED)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedRipple(0x22FF5252.toInt(), ACCENT_RED, 8)
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { confirmDeleteSelected() }
        }
        selectionTopBar.addView(deleteSelectedBtn)
        toolbarContainer.addView(selectionTopBar)

        root.addView(toolbarContainer)

        // Scrollable list container
        recordingsScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }

        recordingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        recordingsScrollView.addView(recordingsContainer)
        root.addView(recordingsScrollView)

        // Empty state view
        emptyStateView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
            visibility = View.GONE
        }
        val emptyIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            setImageResource(R.drawable.ic_audio_file)
            setColorFilter(colorMuted)
        }
        val emptyTitle = TextView(this).apply {
            text = "No recordings yet"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        val emptySub = TextView(this).apply {
            text = "Captured internal playback audio will be saved and organized here."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorMuted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        val recordNowBtn = Button(this).apply {
            text = "Record Now"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (isDarkTheme) DARK_BG else 0xFFFFFFFF.toInt())
            background = roundedDrawable(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK, null, 12)
            setOnClickListener { switchTab(0) }
        }
        emptyStateView.addView(emptyIcon)
        emptyStateView.addView(emptyTitle)
        emptyStateView.addView(emptySub)
        emptyStateView.addView(recordNowBtn)
        root.addView(emptyStateView)

        return root
    }

    private fun buildPlayerBar(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedDrawable(colorCard, colorBorder, 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(8)
            }
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        playerPlayPauseBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }
            setImageResource(R.drawable.ic_play)
            setColorFilter(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = circleRipple(colorSurface)
            setOnClickListener { AudioPlayerController.togglePlayPause() }
        }
        topRow.addView(playerPlayPauseBtn)

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        playerTitleText = TextView(this).apply {
            text = "Playing audio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
        }
        playerTimeText = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorMuted)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        infoCol.addView(playerTitleText)
        infoCol.addView(playerTimeText)
        topRow.addView(infoCol)

        playerCloseBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setImageResource(R.drawable.ic_close)
            setColorFilter(colorMuted)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = circleRipple(colorSurface)
            setOnClickListener {
                AudioPlayerController.stop()
                root.visibility = View.GONE
            }
        }
        topRow.addView(playerCloseBtn)
        root.addView(topRow)

        playerSeekBar = SeekBar(this).apply {
            setPadding(dp(4), dp(8), dp(4), dp(4))
            progressTintList = ColorStateList.valueOf(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            thumbTintList = ColorStateList.valueOf(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) AudioPlayerController.seekTo(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(playerSeekBar)

        return root
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(colorSurface)
            elevation = dp(10).toFloat()
        }

        tabRecorderBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
            background = roundedRipple(colorSurface, null, 12)
            setOnClickListener { switchTab(0) }
        }
        tabRecorderText = TextView(this).apply {
            text = "Recorder"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
        }
        tabRecorderBtn.addView(tabRecorderText)
        nav.addView(tabRecorderBtn)

        tabSavedBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
            background = roundedRipple(colorSurface, null, 12)
            setOnClickListener { switchTab(1) }
        }
        tabSavedText = TextView(this).apply {
            text = "Saved Audio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setTextColor(colorMuted)
        }
        tabSavedBadge = TextView(this).apply {
            text = "0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = roundedDrawable(colorCard, null, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        tabSavedBtn.addView(tabSavedText)
        tabSavedBtn.addView(tabSavedBadge)
        nav.addView(tabSavedBtn)

        return nav
    }

    private fun switchTab(index: Int) {
        if (index == 0 && isSelectionMode) {
            exitSelectionMode()
        }
        currentTab = index
        if (index == 0) {
            recorderView.visibility = View.VISIBLE
            savedAudioView.visibility = View.GONE
            tabRecorderText.setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            tabRecorderText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            tabSavedText.setTextColor(colorMuted)
            tabSavedText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        } else {
            recorderView.visibility = View.GONE
            savedAudioView.visibility = View.VISIBLE
            tabSavedText.setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            tabSavedText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            tabRecorderText.setTextColor(colorMuted)
            tabRecorderText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            loadSavedRecordings()
        }
    }

    private fun refreshRecorderUi() {
        val state = CaptureController.state
        val isPaused = (state == CaptureState.PAUSED)
        val isActive = state.isActive

        statusBadge.text = when (state) {
            CaptureState.RECORDING -> "● RECORDING"
            CaptureState.PAUSED -> "❚❚ PAUSED"
            CaptureState.STARTING -> "STARTING..."
            CaptureState.REQUESTING_PERMISSION -> "REQUESTING CONSENT..."
            CaptureState.FINALIZING -> "FINALIZING..."
            CaptureState.COMPLETED -> "COMPLETED"
            CaptureState.COMPLETED_SILENT -> "COMPLETED (SILENT)"
            CaptureState.FAILED -> "FAILED"
            CaptureState.REVOKED -> "REVOKED"
            CaptureState.INTERRUPTED -> "INTERRUPTED"
            CaptureState.IDLE -> "IDLE"
        }

        statusBadge.setTextColor(
            when (state) {
                CaptureState.RECORDING, CaptureState.COMPLETED -> if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK
                CaptureState.PAUSED, CaptureState.STARTING, CaptureState.FINALIZING, CaptureState.REQUESTING_PERMISSION -> ACCENT_AMBER
                CaptureState.FAILED, CaptureState.REVOKED -> ACCENT_RED
                CaptureState.INTERRUPTED -> 0xFFFF9100.toInt()
                CaptureState.COMPLETED_SILENT -> ACCENT_AMBER
                CaptureState.IDLE -> colorMuted
            }
        )

        val elapsedMs = CaptureController.elapsedMs
        val totalSecs = elapsedMs / 1000L
        val tenths = (elapsedMs % 1000L) / 100L
        val mins = totalSecs / 60L
        val secs = totalSecs % 60L
        timerText.text = String.format(Locale.US, "%02d:%02d.%d", mins, secs, tenths)

        visualizerView.updateLevel(CaptureController.rmsDb, CaptureController.peakDb, state == CaptureState.RECORDING)

        val frames = CaptureController.frames
        val format = if (state.isActive) CaptureController.selectedFormat else prefs.outputFormat
        formatSelectorText.text = format.displayName

        val mb = frames * 4.0 / (1024.0 * 1024.0)
        if (state == CaptureState.RECORDING || state == CaptureState.PAUSED) {
            recordMetricText.text = String.format(
                Locale.US,
                "RMS %5.1f dBFS   Peak %5.1f dBFS   ~%.2f MB",
                CaptureController.rmsDb, CaptureController.peakDb, mb
            )
        } else if (state == CaptureState.FINALIZING) {
            recordMetricText.text = CaptureController.stateDetail
        } else if (CaptureController.lastError != null) {
            recordMetricText.text = CaptureController.lastError
        } else {
            recordMetricText.text = "Ready to record internal playback audio"
        }

        if (state == CaptureState.RECORDING || state == CaptureState.PAUSED) {
            idleRecordBtn.visibility = View.GONE
            activeControlsRow.visibility = View.VISIBLE
            formatSelectorBtn.isEnabled = false
            formatSelectorBtn.alpha = 0.5f

            if (isPaused) {
                pauseResumeIcon.setImageResource(R.drawable.ic_play)
                pauseResumeLabel.text = "Resume"
                pauseResumeIcon.setColorFilter(ACCENT_AMBER)
                pauseResumeLabel.setTextColor(ACCENT_AMBER)
                pauseResumeBtn.contentDescription = "Resume recording"
            } else {
                pauseResumeIcon.setImageResource(R.drawable.ic_pause)
                pauseResumeLabel.text = "Pause"
                pauseResumeIcon.setColorFilter(colorText)
                pauseResumeLabel.setTextColor(colorMuted)
                pauseResumeBtn.contentDescription = "Pause recording"
            }
        } else {
            idleRecordBtn.visibility = View.VISIBLE
            activeControlsRow.visibility = View.GONE
            formatSelectorBtn.isEnabled = true
            formatSelectorBtn.alpha = 1.0f
        }

        // Check if a completed file needs prompt
        if ((state == CaptureState.COMPLETED || state == CaptureState.COMPLETED_SILENT) &&
            CaptureController.lastRecordedFile != null) {
            val file = CaptureController.lastRecordedFile!!
            val dur = CaptureController.lastRecordedDurationMs
            CaptureController.lastRecordedFile = null
            showRecordingFinishedDialog(file, dur)
            loadSavedRecordings()
        }
    }

    private fun startCaptureSession() {
        if (consentInFlight || CaptureController.state.isActive) return

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }

        CaptureController.set(CaptureState.REQUESTING_PERMISSION, "Waiting for system capture consent")
        consentInFlight = true
        try {
            startActivityForResult(intent, REQ_CONSENT)
        } catch (t: Throwable) {
            consentInFlight = false
            CaptureController.set(CaptureState.FAILED, "Could not open consent dialog: ${t.message}")
        }
    }

    private fun pauseCaptureSession() {
        try {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_PAUSE))
        } catch (_: Throwable) {}
    }

    private fun resumeCaptureSession() {
        try {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_RESUME))
        } catch (_: Throwable) {}
    }

    private fun stopCaptureSession() {
        CaptureController.set(CaptureState.FINALIZING, "Finalizing audio...")
        try {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        } catch (t: Throwable) {
            CaptureController.set(CaptureState.FAILED, "Failed to contact service: ${t.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONSENT) {
            consentInFlight = false
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                    putExtra(CaptureService.EXTRA_OUTPUT_FORMAT, prefs.outputFormat.id)
                }
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                    CaptureController.set(CaptureState.STARTING, "Consent granted; service starting")
                } catch (t: Throwable) {
                    CaptureController.set(CaptureState.FAILED, "Service start failed: ${t.message}")
                }
            } else {
                CaptureController.set(CaptureState.IDLE, "Consent cancelled or denied")
            }
        } else if (requestCode == REQ_OVERLAY) {
            if (FloatingBubbleManager.canDrawOverlays(this)) {
                prefs.floatingControlsEnabled = true
                toast("Floating controls enabled")
            } else {
                prefs.floatingControlsEnabled = false
                toast("Overlay permission not granted")
            }
        }
    }

    private fun loadSavedRecordings() {
        Thread({
            val list = SavedAudioRepository.loadRecordings(applicationContext)
            runOnUiThread {
                cachedRecordings = list
                tabSavedBadge.text = list.size.toString()
                if (isSelectionMode) {
                    val existingPaths = list.map { it.file.absolutePath }.toSet()
                    selectedFilePaths.retainAll(existingPaths)
                    updateSelectionToolbar()
                }
                renderRecordingsList()
            }
        }, "nhg-load-recordings").start()
    }

    private fun getFilteredRecordings(): List<RecordingItem> {
        var filtered = cachedRecordings
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            filtered = filtered.filter { it.name.lowercase().contains(q) }
        }

        return when (currentSortMode) {
            1 -> filtered.sortedBy { it.createdAtMs }
            2 -> filtered.sortedByDescending { it.durationMs }
            3 -> filtered.sortedBy { it.name.lowercase() }
            else -> filtered.sortedByDescending { it.createdAtMs }
        }
    }

    private fun renderRecordingsList() {
        recordingsContainer.removeAllViews()

        val filtered = getFilteredRecordings()

        if (filtered.isEmpty()) {
            emptyStateView.visibility = View.VISIBLE
            recordingsScrollView.visibility = View.GONE
        } else {
            emptyStateView.visibility = View.GONE
            recordingsScrollView.visibility = View.VISIBLE

            val sdf = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
            for (item in filtered) {
                recordingsContainer.addView(buildRecordingItemRow(item, sdf))
            }
        }
        if (isSelectionMode) {
            updateSelectionToolbar()
        }
    }

    private fun buildRecordingItemRow(item: RecordingItem, sdf: SimpleDateFormat): View {
        val isSelected = selectedFilePaths.contains(item.file.absolutePath)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = if (isSelectionMode && isSelected) {
                roundedRipple(
                    if (isDarkTheme) 0x2A00E676.toInt() else 0x2200C853.toInt(),
                    if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK,
                    14
                )
            } else {
                roundedRipple(colorSurface, colorBorder, 14)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener {
                if (isSelectionMode) {
                    toggleItemSelection(item)
                } else {
                    startPlayback(item)
                }
            }
            setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(item)
                } else {
                    toggleItemSelection(item)
                }
                true
            }
        }

        // Left action button / Selection indicator
        val leftBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            if (isSelectionMode) {
                if (isSelected) {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(if (isDarkTheme) DARK_BG else 0xFFFFFFFF.toInt())
                    background = circleDrawable(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                } else {
                    setImageResource(0)
                    background = roundedDrawable(Color.TRANSPARENT, colorBorder, 20)
                }
            } else {
                val isThisPlaying = (AudioPlayerController.currentItem?.file?.absolutePath == item.file.absolutePath &&
                        AudioPlayerController.playState == AudioPlayerController.PlayState.PLAYING)
                setImageResource(if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                setColorFilter(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = circleRipple(colorCard)
            }
            setOnClickListener {
                if (isSelectionMode) {
                    toggleItemSelection(item)
                } else {
                    startPlayback(item)
                }
            }
            setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(item)
                } else {
                    toggleItemSelection(item)
                }
                true
            }
        }
        row.addView(leftBtn)

        // Metadata column
        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = false
            isFocusable = false
        }

        val nameText = TextView(this).apply {
            text = item.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
        }
        metaCol.addView(nameText)

        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        val badge = TextView(this).apply {
            text = item.format.badgeLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = roundedDrawable(if (isDarkTheme) 0x2200E676.toInt() else 0x2200C853.toInt(), null, 4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
        subRow.addView(badge)

        val durSec = item.durationMs / 1000L
        val durStr = String.format(Locale.US, "%02d:%02d", durSec / 60, durSec % 60)
        val mbStr = String.format(Locale.US, "%.1f MB", item.sizeBytes / (1024.0 * 1024.0))
        val dateStr = sdf.format(Date(item.createdAtMs))

        val detailText = TextView(this).apply {
            text = "$durStr • $mbStr • $dateStr"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorMuted)
        }
        subRow.addView(detailText)
        metaCol.addView(subRow)
        row.addView(metaCol)

        // Overflow action button (hidden in selection mode)
        val moreBtn = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setImageResource(R.drawable.ic_settings)
            setColorFilter(colorMuted)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = circleRipple(colorSurface)
            visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            setOnClickListener { showRecordingItemMenu(it, item) }
        }
        row.addView(moreBtn)

        return row
    }

    private fun enterSelectionMode(initialItem: RecordingItem? = null) {
        isSelectionMode = true
        selectedFilePaths.clear()
        if (initialItem != null) {
            selectedFilePaths.add(initialItem.file.absolutePath)
        }
        normalTopBar.visibility = View.GONE
        selectionTopBar.visibility = View.VISIBLE
        updateSelectionToolbar()
        renderRecordingsList()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedFilePaths.clear()
        normalTopBar.visibility = View.VISIBLE
        selectionTopBar.visibility = View.GONE
        renderRecordingsList()
    }

    private fun toggleItemSelection(item: RecordingItem) {
        val path = item.file.absolutePath
        if (selectedFilePaths.contains(path)) {
            selectedFilePaths.remove(path)
        } else {
            selectedFilePaths.add(path)
        }
        updateSelectionToolbar()
        renderRecordingsList()
    }

    private fun toggleSelectAll() {
        val currentItems = getFilteredRecordings()
        if (currentItems.isEmpty()) return

        val currentPaths = currentItems.map { it.file.absolutePath }.toSet()
        val allSelected = currentPaths.all { selectedFilePaths.contains(it) }

        if (allSelected) {
            selectedFilePaths.removeAll(currentPaths)
        } else {
            selectedFilePaths.addAll(currentPaths)
        }
        updateSelectionToolbar()
        renderRecordingsList()
    }

    private fun updateSelectionToolbar() {
        val count = selectedFilePaths.size
        val isFiltered = searchQuery.isNotBlank()
        val label = if (isFiltered) {
            "$count selected (filtered)"
        } else {
            "$count selected"
        }
        selectionCountText.text = label

        val currentItems = getFilteredRecordings()
        val currentPaths = currentItems.map { it.file.absolutePath }.toSet()
        val allSelected = currentItems.isNotEmpty() && currentPaths.all { selectedFilePaths.contains(it) }
        selectAllBtn.text = if (allSelected) "Deselect all" else "Select all"

        deleteSelectedBtn.isEnabled = (count > 0)
        deleteSelectedBtn.alpha = if (count > 0) 1.0f else 0.4f
    }

    private fun confirmDeleteSelected() {
        val count = selectedFilePaths.size
        if (count == 0) return

        val msg = if (count == 1) {
            "Delete 1 selected recording? This action cannot be undone."
        } else {
            "Delete $count selected recordings? This action cannot be undone."
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Recordings")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                deleteSelectedRecordings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelectedRecordings() {
        val pathsToDelete = selectedFilePaths.toSet()
        val itemsToDelete = cachedRecordings.filter { pathsToDelete.contains(it.file.absolutePath) }

        val currentPlayingPath = AudioPlayerController.currentItem?.file?.absolutePath
        if (currentPlayingPath != null && pathsToDelete.contains(currentPlayingPath)) {
            AudioPlayerController.stop()
            playerBarLayout.visibility = View.GONE
        }

        Thread({
            var successCount = 0
            var failCount = 0
            val remainingSelection = mutableSetOf<String>()

            for (item in itemsToDelete) {
                val ok = SavedAudioRepository.deleteRecording(applicationContext, item)
                if (ok) {
                    successCount++
                } else {
                    failCount++
                    remainingSelection.add(item.file.absolutePath)
                }
            }

            runOnUiThread {
                if (failCount > 0) {
                    toast("Deleted $successCount recording(s). Failed to delete $failCount recording(s).")
                    selectedFilePaths.clear()
                    selectedFilePaths.addAll(remainingSelection)
                    updateSelectionToolbar()
                } else {
                    toast("Deleted $successCount recording(s)")
                    exitSelectionMode()
                }
                loadSavedRecordings()
            }
        }, "nhg-delete-selected").start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode()
            return
        }
        super.onBackPressed()
    }

    private fun startPlayback(item: RecordingItem) {
        if (AudioPlayerController.currentItem?.file?.absolutePath == item.file.absolutePath) {
            AudioPlayerController.togglePlayPause()
            return
        }

        playerBarLayout.visibility = View.VISIBLE
        playerTitleText.text = item.name

        AudioPlayerController.play(
            item = item,
            onProgress = { posMs, totalMs, isPlaying ->
                playerSeekBar.max = totalMs
                playerSeekBar.progress = posMs
                val pSec = posMs / 1000
                val tSec = totalMs / 1000
                playerTimeText.text = String.format(Locale.US, "%02d:%02d / %02d:%02d", pSec / 60, pSec % 60, tSec / 60, tSec % 60)
                playerPlayPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                renderRecordingsList()
            },
            onCompletion = {
                playerPlayPauseBtn.setImageResource(R.drawable.ic_play)
                renderRecordingsList()
            }
        )
    }

    private fun showRecordingItemMenu(view: View, item: RecordingItem) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "Play")
        popup.menu.add(0, 2, 1, "Rename")
        popup.menu.add(0, 3, 2, "Share")
        popup.menu.add(0, 4, 3, "Delete")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> startPlayback(item)
                2 -> showRenameDialog(item)
                3 -> SavedAudioRepository.shareRecording(this, item)
                4 -> confirmDeleteRecording(item)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(item: RecordingItem) {
        val input = EditText(this).apply {
            setText(item.file.nameWithoutExtension)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Recording")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    val result = SavedAudioRepository.renameRecording(this, item, newName)
                    result.onSuccess {
                        toast("Renamed to ${it.name}")
                        loadSavedRecordings()
                    }.onFailure {
                        toast("Rename failed: ${it.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteRecording(item: RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording?")
            .setMessage("Are you sure you want to delete '${item.name}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (AudioPlayerController.currentItem?.file?.absolutePath == item.file.absolutePath) {
                    AudioPlayerController.stop()
                    playerBarLayout.visibility = View.GONE
                }
                SavedAudioRepository.deleteRecording(this, item)
                toast("Recording deleted")
                loadSavedRecordings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSortMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 0, 0, "Newest First")
        popup.menu.add(0, 1, 1, "Oldest First")
        popup.menu.add(0, 2, 2, "Duration")
        popup.menu.add(0, 3, 3, "Name (A-Z)")

        popup.setOnMenuItemClickListener { menuItem ->
            currentSortMode = menuItem.itemId
            sortBtn.text = menuItem.title
            renderRecordingsList()
            true
        }
        popup.show()
    }

    private fun showFormatSelectorDialog() {
        if (CaptureController.state.isActive) {
            toast("Cannot change format while recording")
            return
        }

        val formats = AudioOutputFormat.values()
        val items = formats.map { f ->
            if (f.isAvailable) f.displayName else "${f.displayName} (Unavailable)"
        }.toTypedArray()

        val selectedIndex = formats.indexOf(prefs.outputFormat).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select Output Format")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val chosen = formats[which]
                if (chosen.isAvailable) {
                    prefs.outputFormat = chosen
                    CaptureController.selectedFormat = chosen
                    formatSelectorText.text = chosen.displayName
                    dialog.dismiss()
                    toast("Selected format: ${chosen.displayName}")
                } else {
                    toast(chosen.description)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRecordingFinishedDialog(file: File, durationMs: Long) {
        val format = AudioOutputFormat.fromExtension(file.extension)
        val durSec = durationMs / 1000L
        val durStr = String.format(Locale.US, "%02d:%02d", durSec / 60, durSec % 60)
        val mbStr = String.format(Locale.US, "%.2f MB", file.length() / (1024.0 * 1024.0))

        val msg = "File: ${file.name}\nFormat: ${format.displayName}\nDuration: $durStr\nSize: $mbStr"

        AlertDialog.Builder(this)
            .setTitle("Recording Saved")
            .setMessage(msg)
            .setPositiveButton("Play") { _, _ ->
                switchTab(1)
                val item = cachedRecordings.firstOrNull {
                    it.file.absolutePath == file.absolutePath ||
                    it.internalFile?.absolutePath == file.absolutePath ||
                    it.id == file.nameWithoutExtension
                }
                if (item != null) startPlayback(item)
            }
            .setNeutralButton("Share") { _, _ ->
                val tempItem = RecordingItem(
                    id = file.nameWithoutExtension,
                    name = file.name,
                    file = file,
                    format = format,
                    durationMs = durationMs,
                    sizeBytes = file.length(),
                    createdAtMs = file.lastModified()
                )
                SavedAudioRepository.shareRecording(this, tempItem)
            }
            .setNegativeButton("Done") { _, _ ->
                switchTab(1)
            }
            .show()
    }

    private fun showSettingsDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // Theme Row
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val themeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val themeLabel = TextView(this).apply {
            text = "App Theme"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val themeSub = TextView(this).apply {
            text = if (isDarkTheme) "Dark theme active" else "Light theme active"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorMuted)
        }
        themeTextCol.addView(themeLabel)
        themeTextCol.addView(themeSub)
        themeRow.addView(themeTextCol)

        val themeBtn = Button(this).apply {
            text = if (isDarkTheme) "Switch to Light" else "Switch to Dark"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener {
                toggleTheme()
            }
        }
        themeRow.addView(themeBtn)
        root.addView(themeRow)

        // Floating Bubble Row
        val bubbleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val bubbleTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bubbleLabel = TextView(this).apply {
            text = "Floating Recording Controls"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorText)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bubbleSub = TextView(this).apply {
            text = "Shows a compact draggable overlay with Pause and Stop buttons while recording."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorMuted)
        }
        bubbleTextCol.addView(bubbleLabel)
        bubbleTextCol.addView(bubbleSub)
        bubbleRow.addView(bubbleTextCol)

        val bubbleSwitch = Switch(this).apply {
            isChecked = prefs.floatingControlsEnabled
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (FloatingBubbleManager.canDrawOverlays(this@MainActivity)) {
                        prefs.floatingControlsEnabled = true
                    } else {
                        this.isChecked = false
                        promptOverlayPermission()
                    }
                } else {
                    prefs.floatingControlsEnabled = false
                }
            }
        }
        bubbleRow.addView(bubbleSwitch)
        root.addView(bubbleRow)

        // Security and Privacy Info
        val secCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedDrawable(colorCard, colorBorder, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        val secTitle = TextView(this).apply {
            text = "Privacy & Security Guarantee"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (isDarkTheme) ACCENT_GREEN else ACCENT_GREEN_DARK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val secText = TextView(this).apply {
            text = "• RECORD_AUDIO: Not declared (No microphone access)\n" +
                    "• INTERNET: Not declared (No network or uploads)\n" +
                    "• Offline, private capture via Android Playback API"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorMuted)
            setPadding(0, dp(4), 0, 0)
        }
        secCard.addView(secTitle)
        secCard.addView(secText)
        root.addView(secCard)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun promptOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Floating Controls Permission")
            .setMessage("Floating recording controls allow you to pause or stop recordings while playing games or using media apps.\n\nAndroid requires the 'Display over other apps' permission to show this overlay. Would you like to grant it now?")
            .setPositiveButton("Open Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= 23) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, REQ_OVERLAY)
                    } catch (t: Throwable) {
                        toast("Could not open overlay settings: ${t.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleTheme() {
        prefs.themeMode = if (isDarkTheme) PreferencesManager.THEME_LIGHT else PreferencesManager.THEME_DARK
        recreate()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun runSessionRecovery() {
        Thread({
            try {
                SessionRecovery.recoverAndExport(applicationContext)
            } catch (_: Throwable) {}
        }, "nhg-recovery").start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(fill: Int, stroke: Int?, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun roundedRipple(fill: Int, stroke: Int?, radiusDp: Int): RippleDrawable {
        val content = roundedDrawable(fill, stroke, radiusDp)
        val mask = roundedDrawable(Color.WHITE, null, radiusDp)
        val rippleColor = ColorStateList.valueOf(if (isDarkTheme) 0x33FFFFFF.toInt() else 0x22000000.toInt())
        return RippleDrawable(rippleColor, content, mask)
    }

    private fun circleDrawable(fill: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }
    }

    private fun circleRipple(fill: Int): RippleDrawable {
        val content = circleDrawable(fill)
        val mask = circleDrawable(Color.WHITE)
        val rippleColor = ColorStateList.valueOf(if (isDarkTheme) 0x33FFFFFF.toInt() else 0x22000000.toInt())
        return RippleDrawable(rippleColor, content, mask)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
