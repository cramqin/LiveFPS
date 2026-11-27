package com.cramqin.hyperfps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.BufferedWriter

class MainActivity : Activity() {

    companion object {
        const val PREFS_NAME = "hyperfps_prefs"
        const val KEY_STATUSBAR_ENABLED = "statusbar_enabled"
        const val KEY_STATUSBAR_POSITION = "statusbar_position"
        const val KEY_STATUSBAR_FONT_SIZE = "statusbar_font_size"
        const val KEY_STATUSBAR_BOLD = "statusbar_bold"
        const val KEY_STATUSBAR_MARGIN_LEFT = "statusbar_margin_left"
        const val KEY_STATUSBAR_MARGIN_RIGHT = "statusbar_margin_right"
        const val KEY_STATUSBAR_VERTICAL_OFFSET = "statusbar_vertical_offset"
        const val KEY_STATUSBAR_FONT = "statusbar_font"
        const val KEY_STATUSBAR_CUSTOM_FONT_PATH = "statusbar_custom_font_path"
        const val ACTION_CONFIG_CHANGED = "com.cramqin.hyperfps.ACTION_CONFIG_CHANGED"
        const val ACTION_PING = "com.cramqin.hyperfps.ACTION_PING"
        const val ACTION_PONG = "com.cramqin.hyperfps.ACTION_PONG"
        const val KEY_OVERLAY_FONT = "overlay_font"
        const val KEY_OVERLAY_CUSTOM_FONT_PATH = "overlay_custom_font_path"
        const val KEY_OVERLAY_BOLD = "overlay_bold"
        const val KEY_OVERLAY_FONT_SIZE = "overlay_font_size"
        const val KEY_OVERLAY_COLOR = "overlay_color"
        const val KEY_OVERLAY_BG_STYLE = "overlay_bg_style"
        const val KEY_OVERLAY_SHAPE = "overlay_shape"
        const val KEY_OVERLAY_PADDING = "overlay_padding"
        private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")
    }

    private lateinit var fpsNumberView: TextView
    private lateinit var fpsUnitView: TextView
    private lateinit var fpsStatusPill: TextView
    private lateinit var actionButton: Button
    private lateinit var permissionBanner: LinearLayout
    private lateinit var restartUiBtn: TextView
    // Status Bar Card Views
    private lateinit var statusBarToggleBtn: Button
    private lateinit var statusBarWarningView: TextView
    private lateinit var statusBarPreviewLeftView: TextView
    private lateinit var statusBarPreviewRightView: TextView
    private lateinit var posLeftBtn: TextView
    private lateinit var posRightBtn: TextView

    private lateinit var fontSizeValView: TextView
    private lateinit var boldToggleBtn: TextView
    private lateinit var marginLeftValView: TextView
    private lateinit var marginRightValView: TextView
    private lateinit var vertOffsetValView: TextView

    private var isStatusBarEnabled = false
    private var statusBarPosition = "right"
    private var statusBarFontSize = 11f
    private var statusBarBold = true
    private var statusBarMarginLeft = 1
    private var statusBarMarginRight = 1
    private var statusBarVerticalOffset = 0
    private var statusBarFont = "default"
    private var statusBarCustomFontPath = ""
    private val statusBarFontButtons = mutableMapOf<String, TextView>()

    // Capability & In-App Polling State
    @Volatile private var hasRoot = false
    @Volatile private var rootChecked = false
    @Volatile private var isLsposedActive = false
    @Volatile private var inAppPollingActive = false
    private var inAppWorker: Thread? = null
    private var pongReceiverRegistered = false

    // Overlay Appearance State
    private var overlayFont = "mono"
    private var overlayCustomFontPath = ""
    private var overlayBold = true
    private var overlayFontSize = 13f
    private var overlayColor = "#00E676"
    private var overlayBgStyle = "semi_dark"
    private var overlayShape = "rounded"
    private var overlayPadding = "normal"

    private lateinit var overlayPreviewView: TextView
    private lateinit var overlayFontSizeValView: TextView
    private lateinit var overlayBoldToggleBtn: TextView
    private val overlayFontButtons = mutableMapOf<String, TextView>()
    private val overlayBgButtons = mutableMapOf<String, TextView>()
    private val overlayShapeButtons = mutableMapOf<String, TextView>()
    private val overlayPaddingButtons = mutableMapOf<String, TextView>()

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBox(
        bgColor: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark window decoration
        window.statusBarColor = Color.parseColor("#121212")
        window.navigationBarColor = Color.parseColor("#121212")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isStatusBarEnabled = prefs.getBoolean(KEY_STATUSBAR_ENABLED, false)
        statusBarPosition = prefs.getString(KEY_STATUSBAR_POSITION, "right") ?: "right"
        statusBarFontSize = prefs.getFloat(KEY_STATUSBAR_FONT_SIZE, 11f)
        statusBarBold = prefs.getBoolean(KEY_STATUSBAR_BOLD, true)
        statusBarMarginLeft = prefs.getInt(KEY_STATUSBAR_MARGIN_LEFT, 1)
        statusBarMarginRight = prefs.getInt(KEY_STATUSBAR_MARGIN_RIGHT, 1)
        statusBarVerticalOffset = prefs.getInt(KEY_STATUSBAR_VERTICAL_OFFSET, 0)
        statusBarFont = prefs.getString(KEY_STATUSBAR_FONT, "default") ?: "default"
        statusBarCustomFontPath = prefs.getString(KEY_STATUSBAR_CUSTOM_FONT_PATH, "") ?: ""

        overlayFont = prefs.getString(KEY_OVERLAY_FONT, "mono") ?: "mono"
        overlayCustomFontPath = prefs.getString(KEY_OVERLAY_CUSTOM_FONT_PATH, "") ?: ""
        overlayBold = prefs.getBoolean(KEY_OVERLAY_BOLD, true)
        overlayFontSize = prefs.getFloat(KEY_OVERLAY_FONT_SIZE, 13f)
        overlayColor = prefs.getString(KEY_OVERLAY_COLOR, "#00E676") ?: "#00E676"
        overlayBgStyle = prefs.getString(KEY_OVERLAY_BG_STYLE, "semi_dark") ?: "semi_dark"
        overlayShape = prefs.getString(KEY_OVERLAY_SHAPE, "rounded") ?: "rounded"
        overlayPadding = prefs.getString(KEY_OVERLAY_PADDING, "normal") ?: "normal"

        val windowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val statusBarScrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        windowLayout.addView(statusBarScrim)

        val root = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        windowLayout.addView(root)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(content)

        fun applyInsets(topInset: Int, bottomInset: Int) {
            val effectiveTop = if (topInset > 0) topInset else dp(36f)
            statusBarScrim.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, effectiveTop)
            content.setPadding(
                dp(20f),
                dp(12f),
                dp(20f),
                bottomInset + dp(28f)
            )
        }

        val initialTop = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            ).top
        }.getOrDefault(0)

        val initialBottom = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.navigationBars()
            ).bottom
        }.getOrDefault(0)

        applyInsets(initialTop, initialBottom)

        windowLayout.setOnApplyWindowInsetsListener { _, insets ->
            val statusBar = insets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            val navBar = insets.getInsets(WindowInsets.Type.navigationBars())
            applyInsets(statusBar.top, navBar.bottom)
            insets
        }

        // App Header
        content.addView(buildHeader())

        // 1. Hero Card: Live In-App FPS Display
        content.addView(buildHeroFpsCard())

        // 2. Permission warning banner & Floating Overlay Action Card
        permissionBanner = buildPermissionBanner()
        content.addView(permissionBanner)
        content.addView(buildActionCard())
        content.addView(buildOverlayAppearanceCard())

        // 3. Status Bar (LSPosed) Independent Card
        content.addView(buildStatusBarCard())

        // 5. Info Card
        content.addView(buildInfoCard())

        setContentView(windowLayout)

        dispatchStatusBarConfig()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun buildHeader(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(20f)
            layoutParams = lp
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "HyperFPS"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        topRow.addView(title)

        val badge = TextView(this).apply {
            text = "ROOT • LSPOSED"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E676"))
            background = roundedBox(Color.parseColor("#1B382B"), 6f, Color.parseColor("#00E676"), 1f)
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(10f)
            layoutParams = lp
        }
        topRow.addView(badge)

        val spacer = View(this).apply {
            val lp = LinearLayout.LayoutParams(0, 1, 1f)
            layoutParams = lp
        }
        topRow.addView(spacer)

        restartUiBtn = TextView(this).apply {
            text = "🔄 Restart UI"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            background = roundedBox(Color.parseColor("#252528"), 8f, Color.parseColor("#38383A"), 1f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            isEnabled = false
            alpha = 0.35f
            setOnClickListener {
                if (hasRoot) {
                    promptRestartSystemUi()
                } else {
                    Toast.makeText(this@MainActivity, "Root access required", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topRow.addView(restartUiBtn)

        layout.addView(topRow)

        val subtitle = TextView(this).apply {
            text = "Real on-screen refresh rate via SurfaceFlinger"
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4f)
            layoutParams = lp
        }
        layout.addView(subtitle)

        return layout
    }

    private fun promptRestartSystemUi() {
        if (!hasRoot) return
        AlertDialog.Builder(this)
            .setTitle("Restart SystemUI?")
            .setMessage("This will restart Android SystemUI to reload status bar changes. The screen will flicker briefly.")
            .setPositiveButton("Restart") { _, _ ->
                Toast.makeText(this, "Restarting SystemUI...", Toast.LENGTH_SHORT).show()
                Thread {
                    runCatching {
                        val p = ProcessBuilder("su", "-c", "pkill -f com.android.systemui").start()
                        p.waitFor()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildHeroFpsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#1C1C1E"), 20f, Color.parseColor("#2C2C2E"), 1f)
            setPadding(dp(22f), dp(20f), dp(22f), dp(20f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16f)
            layoutParams = lp
        }

        val label = TextView(this).apply {
            text = "LIVE ON-SCREEN FPS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        card.addView(label)

        val fpsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6f)
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }

        fpsNumberView = TextView(this).apply {
            text = "--"
            textSize = 64f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            includeFontPadding = false
        }
        fpsRow.addView(fpsNumberView)

        fpsUnitView = TextView(this).apply {
            text = "FPS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E676"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(10f)
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }
        fpsRow.addView(fpsUnitView)
        card.addView(fpsRow)

        fpsStatusPill = TextView(this).apply {
            text = "⚪ Idle • Tap Start below"
            textSize = 12f
            setTextColor(Color.parseColor("#E0E0E0"))
            background = roundedBox(Color.parseColor("#252528"), 10f, Color.parseColor("#38383A"), 1f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        card.addView(fpsStatusPill)

        return card
    }



    private fun buildStatusBarCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#1C1C1E"), 20f, Color.parseColor("#2C2C2E"), 1f)
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16f)
            layoutParams = lp
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "STATUS BAR (LSPOSED)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        headerRow.addView(title)
        card.addView(headerRow)

        val desc = TextView(this).apply {
            text = "Displays FPS directly inside HyperOS/Android status bar (works in Settings, lockscreen, full-screen apps)"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(2f)
            lp.bottomMargin = dp(12f)
            layoutParams = lp
        }
        card.addView(desc)

        // Warning banner when not rooted or not active in LSPosed
        statusBarWarningView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#FFB74D"))
            background = roundedBox(Color.parseColor("#2A1D0A"), 10f, Color.parseColor("#FF9800"), 1f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
            visibility = View.GONE
        }
        card.addView(statusBarWarningView)

        // Toggle Status Bar Button
        statusBarToggleBtn = Button(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48f)
            )
            lp.bottomMargin = dp(12f)
            layoutParams = lp
            setOnClickListener {
                if (!hasRoot || !isLsposedActive) return@setOnClickListener
                isStatusBarEnabled = !isStatusBarEnabled
                savePrefsAndWorldReadable {
                    putBoolean(KEY_STATUSBAR_ENABLED, isStatusBarEnabled)
                }
                refreshStatusBarUi()
                dispatchStatusBarConfig()
            }
        }
        card.addView(statusBarToggleBtn)

        // Position Row (Left vs Right)
        val posLabel = TextView(this).apply {
            text = "STATUS BAR POSITION"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(6f)
            layoutParams = lp
        }
        card.addView(posLabel)

        val posRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        posLeftBtn = TextView(this).apply {
            text = "Left (Clock Area)"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(4f)
            layoutParams = lp
            setOnClickListener { setPosition("left") }
        }
        posRow.addView(posLeftBtn)

        posRightBtn = TextView(this).apply {
            text = "Right (Icons Area)"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(4f)
            layoutParams = lp
            setOnClickListener { setPosition("right") }
        }
        posRow.addView(posRightBtn)
        card.addView(posRow)

        // Live Status Bar Preview
        card.addView(buildSubHeader("LIVE STATUS BAR PREVIEW"))
        val sbPreviewStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBox(Color.parseColor("#0A0C10"), 10f, Color.parseColor("#2A2E38"), 1f)
            setPadding(dp(12f), 0, dp(12f), 0)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34f)
            )
            lp.bottomMargin = dp(4f)
            layoutParams = lp
        }

        val simClock = TextView(this).apply {
            text = "10:34"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }
        sbPreviewStrip.addView(simClock)

        statusBarPreviewLeftView = TextView(this).apply {
            text = "120"
            setTextColor(Color.WHITE)
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        sbPreviewStrip.addView(statusBarPreviewLeftView)

        val simNotifs = TextView(this).apply {
            text = " ● ●"
            textSize = 8f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        sbPreviewStrip.addView(simNotifs)

        val sbSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        sbPreviewStrip.addView(sbSpacer)

        statusBarPreviewRightView = TextView(this).apply {
            text = "120"
            setTextColor(Color.WHITE)
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        sbPreviewStrip.addView(statusBarPreviewRightView)

        val simIcons = TextView(this).apply {
            text = " 0.00 K/s  📶 🔋"
            textSize = 10f
            setTextColor(Color.parseColor("#C7C7CC"))
            includeFontPadding = false
        }
        sbPreviewStrip.addView(simIcons)
        card.addView(sbPreviewStrip)

        // Customization Section Header with Reset
        val customSectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(16f)
            lp.bottomMargin = dp(6f)
            layoutParams = lp
        }

        val customSectionTitle = TextView(this).apply {
            text = "APPEARANCE & PLACEMENT"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        customSectionHeader.addView(customSectionTitle)

        val resetBtn = TextView(this).apply {
            text = "RESET"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            setOnClickListener {
                statusBarFontSize = 11f
                statusBarBold = true
                statusBarMarginLeft = 1
                statusBarMarginRight = 1
                statusBarVerticalOffset = 0
                statusBarFont = "default"
                statusBarCustomFontPath = ""
                savePrefsAndWorldReadable {
                    putFloat(KEY_STATUSBAR_FONT_SIZE, statusBarFontSize)
                    putBoolean(KEY_STATUSBAR_BOLD, statusBarBold)
                    putInt(KEY_STATUSBAR_MARGIN_LEFT, statusBarMarginLeft)
                    putInt(KEY_STATUSBAR_MARGIN_RIGHT, statusBarMarginRight)
                    putInt(KEY_STATUSBAR_VERTICAL_OFFSET, statusBarVerticalOffset)
                    putString(KEY_STATUSBAR_FONT, statusBarFont)
                    putString(KEY_STATUSBAR_CUSTOM_FONT_PATH, statusBarCustomFontPath)
                }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        }
        customSectionHeader.addView(resetBtn)
        card.addView(customSectionHeader)

        val customBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#141416"), 12f, Color.parseColor("#262629"), 1f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        // 1. Font Style
        customBox.addView(buildSubHeader("FONT STYLE & TYPEFACE"))
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8f)
            layoutParams = lp
        }
        val fontOptions = listOf(
            "default" to "Default",
            "mono" to "Mono",
            "sans" to "Sans",
            "serif" to "Serif",
            "custom" to "Custom"
        )
        fontOptions.forEach { (key, label) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dp(3f), dp(7f), dp(3f), dp(7f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(3f)
                layoutParams = lp
                setOnClickListener {
                    if (key == "custom") {
                        promptCustomStatusBarFont()
                    } else {
                        statusBarFont = key
                        savePrefsAndWorldReadable {
                            putString(KEY_STATUSBAR_FONT, statusBarFont)
                        }
                        refreshAppearanceUi()
                        dispatchStatusBarConfig()
                    }
                }
            }
            statusBarFontButtons[key] = btn
            fontRow.addView(btn)
        }
        customBox.addView(fontRow)

        // 2. Font Size
        val (fontSizeRow, fontSizeVal) = buildStepperRow("Font Size", {
            if (statusBarFontSize > 8f) {
                statusBarFontSize -= 1f
                savePrefsAndWorldReadable { putFloat(KEY_STATUSBAR_FONT_SIZE, statusBarFontSize) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        }, {
            if (statusBarFontSize < 24f) {
                statusBarFontSize += 1f
                savePrefsAndWorldReadable { putFloat(KEY_STATUSBAR_FONT_SIZE, statusBarFontSize) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        })
        fontSizeValView = fontSizeVal
        customBox.addView(fontSizeRow)

        // 3. Bold Toggle
        val (boldRow, boldBtn) = buildBoldToggleRow()
        boldToggleBtn = boldBtn
        customBox.addView(boldRow)

        // 3. Margin Left
        val (marginLeftRow, marginLeftVal) = buildStepperRow("Left Margin", {
            if (statusBarMarginLeft > -10) {
                statusBarMarginLeft -= 1
                savePrefsAndWorldReadable { putInt(KEY_STATUSBAR_MARGIN_LEFT, statusBarMarginLeft) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        }, {
            if (statusBarMarginLeft < 30) {
                statusBarMarginLeft += 1
                savePrefsAndWorldReadable { putInt(KEY_STATUSBAR_MARGIN_LEFT, statusBarMarginLeft) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        })
        marginLeftValView = marginLeftVal
        customBox.addView(marginLeftRow)

        // 4. Margin Right
        val (marginRightRow, marginRightVal) = buildStepperRow("Right Margin", {
            if (statusBarMarginRight > -10) {
                statusBarMarginRight -= 1
                savePrefsAndWorldReadable { putInt(KEY_STATUSBAR_MARGIN_RIGHT, statusBarMarginRight) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        }, {
            if (statusBarMarginRight < 30) {
                statusBarMarginRight += 1
                savePrefsAndWorldReadable { putInt(KEY_STATUSBAR_MARGIN_RIGHT, statusBarMarginRight) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        })
        marginRightValView = marginRightVal
        customBox.addView(marginRightRow)

        // 5. Vertical Offset
        val (vertOffsetRow, vertOffsetVal) = buildStepperRow("Vertical Offset", {
            if (statusBarVerticalOffset > -15) {
                statusBarVerticalOffset -= 1
                savePrefsAndWorldReadable { putInt(KEY_STATUSBAR_VERTICAL_OFFSET, statusBarVerticalOffset) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        }, {
            if (statusBarVerticalOffset < 15) {
                statusBarVerticalOffset += 1
                savePrefsAndWorldReadable { putInt(KEY_STATUSBAR_VERTICAL_OFFSET, statusBarVerticalOffset) }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        })
        vertOffsetValView = vertOffsetVal
        customBox.addView(vertOffsetRow)

        card.addView(customBox)

        refreshStatusBarUi()
        refreshAppearanceUi()

        return card
    }

    private fun buildStepperRow(
        title: String,
        onDec: () -> Unit,
        onInc: () -> Unit
    ): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        val label = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.parseColor("#E0E0E0"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        row.addView(label)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val decBtn = TextView(this).apply {
            text = "－"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#3A3A3C"), 1f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setOnClickListener { onDec() }
        }
        controls.addView(decBtn)

        val valView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00E676"))
            val lp = LinearLayout.LayoutParams(dp(56f), ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        }
        controls.addView(valView)

        val incBtn = TextView(this).apply {
            text = "＋"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#3A3A3C"), 1f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setOnClickListener { onInc() }
        }
        controls.addView(incBtn)

        row.addView(controls)
        return Pair(row, valView)
    }

    private fun buildBoldToggleRow(): Pair<View, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        val label = TextView(this).apply {
            text = "Font Weight"
            textSize = 12f
            setTextColor(Color.parseColor("#E0E0E0"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        row.addView(label)

        val btn = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(5f), dp(14f), dp(5f))
            setOnClickListener {
                statusBarBold = !statusBarBold
                savePrefsAndWorldReadable {
                    putBoolean(KEY_STATUSBAR_BOLD, statusBarBold)
                }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
            }
        }
        row.addView(btn)
        return Pair(row, btn)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshAppearanceUi() {
        if (!::fontSizeValView.isInitialized) return
        fontSizeValView.text = "%.0f sp".format(statusBarFontSize)
        if (statusBarBold) {
            boldToggleBtn.text = "BOLD"
            boldToggleBtn.background = roundedBox(Color.parseColor("#00E676"), 8f)
            boldToggleBtn.setTextColor(Color.parseColor("#121212"))
            boldToggleBtn.typeface = Typeface.DEFAULT_BOLD
        } else {
            boldToggleBtn.text = "NORMAL"
            boldToggleBtn.background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#38383A"), 1f)
            boldToggleBtn.setTextColor(Color.parseColor("#CCCCCC"))
            boldToggleBtn.typeface = Typeface.DEFAULT
        }
        marginLeftValView.text = "${statusBarMarginLeft} dp"
        marginRightValView.text = "${statusBarMarginRight} dp"
        vertOffsetValView.text = if (statusBarVerticalOffset > 0) "+${statusBarVerticalOffset} dp" else "${statusBarVerticalOffset} dp"

        statusBarFontButtons.forEach { (key, btn) ->
            if (key == statusBarFont) {
                btn.background = roundedBox(Color.parseColor("#00E676"), 8f)
                btn.setTextColor(Color.parseColor("#121212"))
                btn.typeface = Typeface.DEFAULT_BOLD
                if (key == "custom" && statusBarCustomFontPath.isNotBlank()) {
                    val fName = java.io.File(statusBarCustomFontPath).name
                    btn.text = if (fName.length > 7) fName.take(6) + "…" else fName
                } else {
                    btn.text = when (key) {
                        "mono" -> "Mono"
                        "sans" -> "Sans"
                        "serif" -> "Serif"
                        "custom" -> "Custom"
                        else -> "Default"
                    }
                }
            } else {
                btn.background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#38383A"), 1f)
                btn.setTextColor(Color.parseColor("#CCCCCC"))
                btn.typeface = Typeface.DEFAULT
                btn.text = when (key) {
                    "mono" -> "Mono"
                    "sans" -> "Sans"
                    "serif" -> "Serif"
                    "custom" -> "Custom"
                    else -> "Default"
                }
            }
        }

        if (::statusBarPreviewLeftView.isInitialized && ::statusBarPreviewRightView.isInitialized) {
            val density = resources.displayMetrics.density
            val leftPx = (statusBarMarginLeft * density).toInt()
            val rightPx = (statusBarMarginRight * density).toInt()
            val baseTypeface = if (statusBarFont == "custom" && statusBarCustomFontPath.isNotBlank()) {
                runCatching {
                    val f = java.io.File(statusBarCustomFontPath)
                    if (f.exists() && f.canRead()) Typeface.createFromFile(f) else null
                }.getOrNull() ?: Typeface.DEFAULT
            } else {
                when (statusBarFont) {
                    "mono", "monospace" -> Typeface.MONOSPACE
                    "sans", "sans-serif" -> Typeface.SANS_SERIF
                    "serif" -> Typeface.SERIF
                    else -> Typeface.DEFAULT
                }
            }
            val resolvedTf = Typeface.create(baseTypeface, if (statusBarBold) Typeface.BOLD else Typeface.NORMAL)

            listOf(statusBarPreviewLeftView, statusBarPreviewRightView).forEach { pv ->
                pv.textSize = statusBarFontSize
                pv.typeface = resolvedTf
                pv.translationY = statusBarVerticalOffset * density
                pv.setPaddingRelative(leftPx, 0, rightPx, 0)
                pv.alpha = if (isStatusBarEnabled) 1.0f else 0.45f
            }
            statusBarPreviewLeftView.visibility = if (statusBarPosition == "left") View.VISIBLE else View.GONE
            statusBarPreviewRightView.visibility = if (statusBarPosition == "right") View.VISIBLE else View.GONE
        }
    }

    private fun promptCustomStatusBarFont() {
        val input = EditText(this).apply {
            hint = "/sdcard/Fonts/myfont.ttf"
            setText(statusBarCustomFontPath)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Status Bar Font")
            .setMessage("Enter path to a .ttf / .otf file on device:")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) {
                        statusBarCustomFontPath = path
                        statusBarFont = "custom"
                        savePrefsAndWorldReadable {
                            putString(KEY_STATUSBAR_CUSTOM_FONT_PATH, statusBarCustomFontPath)
                            putString(KEY_STATUSBAR_FONT, statusBarFont)
                        }
                        refreshAppearanceUi()
                        dispatchStatusBarConfig()
                        Toast.makeText(this, "Loaded: ${file.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Unreadable or file does not exist: $path", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                statusBarCustomFontPath = ""
                statusBarFont = "default"
                savePrefsAndWorldReadable {
                    putString(KEY_STATUSBAR_CUSTOM_FONT_PATH, "")
                    putString(KEY_STATUSBAR_FONT, "default")
                }
                refreshAppearanceUi()
                dispatchStatusBarConfig()
                Toast.makeText(this, "Custom font cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setPosition(pos: String) {
        statusBarPosition = pos
        savePrefsAndWorldReadable {
            putString(KEY_STATUSBAR_POSITION, pos)
        }
        refreshStatusBarUi()
        refreshAppearanceUi()
        dispatchStatusBarConfig()
    }

    private fun savePrefsAndWorldReadable(block: android.content.SharedPreferences.Editor.() -> Unit) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply(block).apply()
        runCatching {
            val file = java.io.File(applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (file.exists()) {
                file.setReadable(true, false)
                file.parentFile?.setExecutable(true, false)
                file.parentFile?.setReadable(true, false)
            }
        }
    }

    private fun refreshStatusBarUi() {
        if (isStatusBarEnabled) {
            statusBarToggleBtn.text = "Status Bar FPS: ENABLED"
            statusBarToggleBtn.setTextColor(Color.parseColor("#121212"))
            statusBarToggleBtn.background = roundedBox(Color.parseColor("#00E676"), 12f)
        } else {
            statusBarToggleBtn.text = "Status Bar FPS: DISABLED"
            statusBarToggleBtn.setTextColor(Color.WHITE)
            statusBarToggleBtn.background = roundedBox(Color.parseColor("#2A2A2E"), 12f, Color.parseColor("#38383A"), 1f)
        }

        if (statusBarPosition == "left") {
            posLeftBtn.background = roundedBox(Color.parseColor("#00E676"), 10f)
            posLeftBtn.setTextColor(Color.parseColor("#121212"))
            posLeftBtn.typeface = Typeface.DEFAULT_BOLD

            posRightBtn.background = roundedBox(Color.parseColor("#2A2A2E"), 10f, Color.parseColor("#38383A"), 1f)
            posRightBtn.setTextColor(Color.parseColor("#CCCCCC"))
            posRightBtn.typeface = Typeface.DEFAULT
        } else {
            posRightBtn.background = roundedBox(Color.parseColor("#00E676"), 10f)
            posRightBtn.setTextColor(Color.parseColor("#121212"))
            posRightBtn.typeface = Typeface.DEFAULT_BOLD

            posLeftBtn.background = roundedBox(Color.parseColor("#2A2A2E"), 10f, Color.parseColor("#38383A"), 1f)
            posLeftBtn.setTextColor(Color.parseColor("#CCCCCC"))
            posLeftBtn.typeface = Typeface.DEFAULT
        }
    }

    private fun dispatchStatusBarConfig() {
        val intent = Intent(ACTION_CONFIG_CHANGED).apply {
            putExtra("enabled", isStatusBarEnabled)
            putExtra("position", statusBarPosition)
            putExtra("interval_ms", 2000L) // Fixed 2.0s polling rate for status bar
            putExtra("font_size", statusBarFontSize)
            putExtra("bold", statusBarBold)
            putExtra("margin_left", statusBarMarginLeft)
            putExtra("margin_right", statusBarMarginRight)
            putExtra("vertical_offset", statusBarVerticalOffset)
            putExtra("font", statusBarFont)
            putExtra("custom_font_path", statusBarCustomFontPath)
        }
        sendBroadcast(intent)
    }

    private fun buildPermissionBanner(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#2A1D0A"), 16f, Color.parseColor("#FF9800"), 1f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16f)
            layoutParams = lp

            val warnTitle = TextView(this@MainActivity).apply {
                text = "⚠️ Overlay Permission Required"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFA726"))
            }
            addView(warnTitle)

            val warnDesc = TextView(this@MainActivity).apply {
                text = "Enable \"Display over other apps\" to show the floating FPS overlay."
                textSize = 12f
                setTextColor(Color.parseColor("#FFE082"))
                val dLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                dLp.topMargin = dp(4f)
                dLp.bottomMargin = dp(10f)
                layoutParams = dLp
            }
            addView(warnDesc)

            val grantBtn = Button(this@MainActivity).apply {
                text = "Grant Overlay Permission"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                background = roundedBox(Color.parseColor("#FFA726"), 10f)
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
            addView(grantBtn)
        }
    }

    private fun buildActionCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#1C1C1E"), 20f, Color.parseColor("#2C2C2E"), 1f)
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16f)
            layoutParams = lp
        }

        val title = TextView(this).apply {
            text = "FLOATING OVERLAY CONTROLS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }
        card.addView(title)

        actionButton = Button(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54f)
            )
            layoutParams = lp
            setOnClickListener { toggle() }
        }
        card.addView(actionButton)

        return card
    }

    private fun buildSubHeader(title: String): View {
        return TextView(this).apply {
            text = title
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10f)
            lp.bottomMargin = dp(6f)
            layoutParams = lp
        }
    }

    private fun promptCustomColor() {
        val currentInt = runCatching { Color.parseColor(overlayColor) }.getOrDefault(Color.parseColor("#00E676"))
        val hsv = FloatArray(3)
        Color.colorToHSV(currentInt, hsv)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(8f))
        }

        val previewSwatch = TextView(this).apply {
            text = "120 fps  •  #${Integer.toHexString(currentInt).uppercase().padStart(8, '0').takeLast(6)}"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(currentInt)
            background = roundedBox(Color.parseColor("#141820"), 12f, currentInt, 1.5f)
            setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(16f)
            layoutParams = lp
        }
        container.addView(previewSwatch)

        val hexInput = EditText(this).apply {
            hint = "#00E676"
            setText("#${Integer.toHexString(currentInt).uppercase().padStart(8, '0').takeLast(6)}")
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
        }

        var updatingFromSliders = false
        var updatingFromHex = false

        fun updatePreviewFromHsv() {
            val c = Color.HSVToColor(hsv)
            val hex = "#%06X".format(0xFFFFFF and c)
            previewSwatch.text = "120 fps  •  $hex"
            previewSwatch.setTextColor(c)
            previewSwatch.background = roundedBox(Color.parseColor("#141820"), 12f, c, 1.5f)
            if (!updatingFromHex) {
                updatingFromSliders = true
                hexInput.setText(hex)
                updatingFromSliders = false
            }
        }

        fun addSlider(label: String, max: Int, initial: Int, trackColors: IntArray?, onChange: (Int) -> Unit): SeekBar {
            val lbl = TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#8E8E93"))
            }
            container.addView(lbl)

            val sb = SeekBar(this).apply {
                this.max = max
                progress = initial
                if (trackColors != null) {
                    progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, trackColors).apply {
                        cornerRadius = dp(6f).toFloat()
                    }
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(4f)
                lp.bottomMargin = dp(12f)
                layoutParams = lp
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser && !updatingFromHex) {
                            onChange(p)
                            updatePreviewFromHsv()
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            container.addView(sb)
            return sb
        }

        val hueColors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        val hueBar = addSlider("HUE", 360, hsv[0].toInt(), hueColors) { hsv[0] = it.toFloat() }
        val satBar = addSlider("SATURATION", 100, (hsv[1] * 100).toInt(), null) { hsv[1] = it / 100f }
        val valBar = addSlider("BRIGHTNESS", 100, (hsv[2] * 100).toInt(), null) { hsv[2] = it / 100f }

        val hexLabel = TextView(this).apply {
            text = "HEX CODE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E8E93"))
        }
        container.addView(hexLabel)
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromSliders) return
                val txt = s?.toString()?.trim() ?: return
                if (txt.matches(Regex("""^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"""))) {
                    updatingFromHex = true
                    val parsed = Color.parseColor(txt)
                    Color.colorToHSV(parsed, hsv)
                    hueBar.progress = hsv[0].toInt()
                    satBar.progress = (hsv[1] * 100).toInt()
                    valBar.progress = (hsv[2] * 100).toInt()
                    updatePreviewFromHsv()
                    updatingFromHex = false
                }
            }
        })
        container.addView(hexInput)

        AlertDialog.Builder(this)
            .setTitle("Pick Overlay Color")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val txt = hexInput.text.toString().trim()
                val finalHex = if (txt.matches(Regex("""^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"""))) {
                    txt.uppercase()
                } else {
                    "#%06X".format(0xFFFFFF and Color.HSVToColor(hsv))
                }
                overlayColor = finalHex
                saveOverlayPrefs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildOverlayAppearanceCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#1C1C1E"), 20f, Color.parseColor("#2C2C2E"), 1f)
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(16f)
            layoutParams = lp
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }

        val title = TextView(this).apply {
            text = "OVERLAY APPEARANCE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        headerRow.addView(title)

        val resetBtn = TextView(this).apply {
            text = "RESET"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            setOnClickListener {
                overlayFont = "mono"
                overlayCustomFontPath = ""
                overlayBold = true
                overlayFontSize = 13f
                overlayColor = "#00E676"
                overlayBgStyle = "semi_dark"
                overlayShape = "rounded"
                overlayPadding = "normal"
                saveOverlayPrefs()
            }
        }
        headerRow.addView(resetBtn)
        card.addView(headerRow)

        // Realistic Simulated App/Game Backdrop for Overlay Preview
        val previewContainer = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1E3A5F"), Color.parseColor("#162438"), Color.parseColor("#12151E"))
            ).apply {
                cornerRadius = dp(14f).toFloat()
                setStroke(dp(1f), Color.parseColor("#2E3B4E"))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88f)
            )
            lp.bottomMargin = dp(14f)
            layoutParams = lp
        }

        val simWatermark = TextView(this).apply {
            text = "SIMULATED APP SCREEN"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#4DFFFFFF"))
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(10f), dp(6f))
            }
            layoutParams = lp
        }
        previewContainer.addView(simWatermark)

        overlayPreviewView = TextView(this).apply {
            text = "120 fps"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        previewContainer.addView(overlayPreviewView)
        card.addView(previewContainer)

        val customBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#141416"), 12f, Color.parseColor("#262629"), 1f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        // 1. Font Style
        customBox.addView(buildSubHeader("FONT STYLE & TYPEFACE"))
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8f)
            layoutParams = lp
        }
        val overlayFontOptions = listOf(
            "default" to "Default",
            "mono" to "Mono",
            "sans" to "Sans",
            "serif" to "Serif",
            "custom" to "Custom"
        )
        overlayFontOptions.forEach { (key, label) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dp(3f), dp(7f), dp(3f), dp(7f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(3f)
                layoutParams = lp
                setOnClickListener {
                    if (key == "custom") {
                        promptCustomOverlayFont()
                    } else {
                        overlayFont = key
                        saveOverlayPrefs()
                    }
                }
            }
            overlayFontButtons[key] = btn
            fontRow.addView(btn)
        }
        customBox.addView(fontRow)

        // 2. Font Weight (Bold Toggle)
        customBox.addView(buildSubHeader("FONT WEIGHT"))
        val overlayBoldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val overlayBoldLabel = TextView(this).apply {
            text = "Font Weight"
            textSize = 12f
            setTextColor(Color.parseColor("#E0E0E0"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        overlayBoldRow.addView(overlayBoldLabel)

        overlayBoldToggleBtn = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(5f), dp(14f), dp(5f))
            setOnClickListener {
                overlayBold = !overlayBold
                saveOverlayPrefs()
            }
        }
        overlayBoldRow.addView(overlayBoldToggleBtn)
        customBox.addView(overlayBoldRow)

        // 2. Font Size Stepper
        val (fontSizeRow, fontSizeVal) = buildStepperRow("Font Size", {
            if (overlayFontSize > 9f) {
                overlayFontSize -= 1f
                saveOverlayPrefs()
            }
        }, {
            if (overlayFontSize < 24f) {
                overlayFontSize += 1f
                saveOverlayPrefs()
            }
        })
        overlayFontSizeValView = fontSizeVal
        customBox.addView(fontSizeRow)

        // 3. Text Color
        customBox.addView(buildSubHeader("TEXT COLOR"))
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8f)
            layoutParams = lp
        }
        val colors = listOf(
            "#00E676" to "Green",
            "#00E5FF" to "Cyan",
            "#FFFFFF" to "White",
            "#FFD600" to "Gold",
            "#FF1744" to "Red",
            "#B388FF" to "Purple"
        )
        colors.forEach { (hex, _) ->
            val dot = View(this).apply {
                val size = dp(28f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(8f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                    if (overlayColor.equals(hex, ignoreCase = true)) {
                        setStroke(dp(3f), Color.WHITE)
                    }
                }
                setOnClickListener {
                    overlayColor = hex
                    saveOverlayPrefs()
                }
            }
            colorRow.addView(dot)
        }

        val customColorBtn = TextView(this).apply {
            text = "🎨 Pick"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#38383A"), 1f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setOnClickListener { promptCustomColor() }
        }
        colorRow.addView(customColorBtn)
        customBox.addView(colorRow)

        // 4. Background Style
        customBox.addView(buildSubHeader("BACKGROUND STYLE"))
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8f)
            layoutParams = lp
        }
        listOf(
            "semi_dark" to "Semi-Dark",
            "translucent" to "Glass",
            "solid" to "Solid",
            "transparent" to "None"
        ).forEach { (key, label) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dp(3f), dp(8f), dp(3f), dp(8f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(3f)
                layoutParams = lp
                setOnClickListener {
                    overlayBgStyle = key
                    saveOverlayPrefs()
                }
            }
            overlayBgButtons[key] = btn
            bgRow.addView(btn)
        }
        customBox.addView(bgRow)

        // 5. Shape / Corner Radius
        customBox.addView(buildSubHeader("SHAPE & CORNERS"))
        val shapeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8f)
            layoutParams = lp
        }
        listOf(
            "pill" to "Capsule",
            "rounded" to "Round (8dp)",
            "soft" to "Soft (4dp)",
            "rect" to "Sharp (0dp)"
        ).forEach { (key, label) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dp(3f), dp(8f), dp(3f), dp(8f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(3f)
                layoutParams = lp
                setOnClickListener {
                    overlayShape = key
                    saveOverlayPrefs()
                }
            }
            overlayShapeButtons[key] = btn
            shapeRow.addView(btn)
        }
        customBox.addView(shapeRow)

        // 6. Padding / Size
        customBox.addView(buildSubHeader("SIZE & PADDING"))
        val padRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        listOf(
            "compact" to "Compact",
            "normal" to "Normal",
            "relaxed" to "Relaxed"
        ).forEach { (key, label) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = dp(3f)
                layoutParams = lp
                setOnClickListener {
                    overlayPadding = key
                    saveOverlayPrefs()
                }
            }
            overlayPaddingButtons[key] = btn
            padRow.addView(btn)
        }
        customBox.addView(padRow)

        card.addView(customBox)
        refreshOverlayAppearanceUi()
        return card
    }

    private fun refreshOverlayAppearanceUi() {
        if (!::overlayPreviewView.isInitialized) return
        val dm = resources.displayMetrics
        val dp = dm.density

        overlayFontSizeValView.text = "%.0f sp".format(overlayFontSize)

        if (::overlayBoldToggleBtn.isInitialized) {
            if (overlayBold) {
                overlayBoldToggleBtn.text = "BOLD"
                overlayBoldToggleBtn.background = roundedBox(Color.parseColor("#00E676"), 8f)
                overlayBoldToggleBtn.setTextColor(Color.parseColor("#121212"))
                overlayBoldToggleBtn.typeface = Typeface.DEFAULT_BOLD
            } else {
                overlayBoldToggleBtn.text = "NORMAL"
                overlayBoldToggleBtn.background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#38383A"), 1f)
                overlayBoldToggleBtn.setTextColor(Color.parseColor("#CCCCCC"))
                overlayBoldToggleBtn.typeface = Typeface.DEFAULT
            }
        }

        overlayPreviewView.textSize = overlayFontSize
        val baseTypeface = if (overlayFont == "custom" && overlayCustomFontPath.isNotBlank()) {
            runCatching {
                val f = java.io.File(overlayCustomFontPath)
                if (f.exists() && f.canRead()) Typeface.createFromFile(f) else null
            }.getOrNull() ?: Typeface.MONOSPACE
        } else {
            when (overlayFont) {
                "sans", "sans_bold", "sans-serif" -> Typeface.SANS_SERIF
                "serif" -> Typeface.SERIF
                "default" -> Typeface.DEFAULT
                else -> Typeface.MONOSPACE
            }
        }
        overlayPreviewView.typeface = Typeface.create(baseTypeface, if (overlayBold) Typeface.BOLD else Typeface.NORMAL)
        val textColor = runCatching { Color.parseColor(overlayColor) }.getOrDefault(Color.parseColor("#00E676"))
        overlayPreviewView.setTextColor(textColor)

        val bgColor = when (overlayBgStyle) {
            "solid" -> 0xFF121212.toInt()
            "translucent" -> 0x66121212.toInt()
            "transparent" -> 0x00000000
            else -> 0xCC121212.toInt()
        }

        val cornerRadius = when (overlayShape) {
            "pill" -> 999f * dp
            "soft" -> 4f * dp
            "rect" -> 0f
            else -> 8f * dp
        }

        val (padH, padV) = when (overlayPadding) {
            "compact" -> Pair(6f, 2f)
            "relaxed" -> Pair(14f, 7f)
            else -> Pair(10f, 5f)
        }

        if (overlayBgStyle == "transparent") {
            overlayPreviewView.background = null
            overlayPreviewView.setShadowLayer(6f, 2f, 2f, Color.BLACK)
        } else {
            overlayPreviewView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            overlayPreviewView.background = GradientDrawable().apply {
                setColor(bgColor)
                this.cornerRadius = cornerRadius
                setStroke((1 * dp).toInt(), 0x33FFFFFF.toInt())
            }
        }
        overlayPreviewView.setPadding((padH * dp).toInt(), (padV * dp).toInt(), (padH * dp).toInt(), (padV * dp).toInt())

        fun highlightGroup(map: Map<String, TextView>, selectedKey: String) {
            map.forEach { (key, btn) ->
                if (key == selectedKey) {
                    btn.background = roundedBox(Color.parseColor("#00E676"), 8f)
                    btn.setTextColor(Color.parseColor("#121212"))
                    btn.typeface = Typeface.DEFAULT_BOLD
                } else {
                    btn.background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#38383A"), 1f)
                    btn.setTextColor(Color.parseColor("#CCCCCC"))
                    btn.typeface = Typeface.DEFAULT
                }
            }
        }

        overlayFontButtons.forEach { (key, btn) ->
            if (key == overlayFont) {
                btn.background = roundedBox(Color.parseColor("#00E676"), 8f)
                btn.setTextColor(Color.parseColor("#121212"))
                btn.typeface = Typeface.DEFAULT_BOLD
                if (key == "custom" && overlayCustomFontPath.isNotBlank()) {
                    val fName = java.io.File(overlayCustomFontPath).name
                    btn.text = if (fName.length > 7) fName.take(6) + "…" else fName
                } else {
                    btn.text = when (key) {
                        "mono" -> "Mono"
                        "sans" -> "Sans"
                        "serif" -> "Serif"
                        "custom" -> "Custom"
                        else -> "Default"
                    }
                }
            } else {
                btn.background = roundedBox(Color.parseColor("#2C2C2E"), 8f, Color.parseColor("#38383A"), 1f)
                btn.setTextColor(Color.parseColor("#CCCCCC"))
                btn.typeface = Typeface.DEFAULT
                btn.text = when (key) {
                    "mono" -> "Mono"
                    "sans" -> "Sans"
                    "serif" -> "Serif"
                    "custom" -> "Custom"
                    else -> "Default"
                }
            }
        }

        highlightGroup(overlayBgButtons, overlayBgStyle)
        highlightGroup(overlayShapeButtons, overlayShape)
        highlightGroup(overlayPaddingButtons, overlayPadding)
    }

    private fun promptCustomOverlayFont() {
        val input = EditText(this).apply {
            hint = "/sdcard/Fonts/myfont.ttf"
            setText(overlayCustomFontPath)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Overlay Font")
            .setMessage("Enter path to a .ttf / .otf file on device:")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) {
                        overlayCustomFontPath = path
                        overlayFont = "custom"
                        saveOverlayPrefs()
                        Toast.makeText(this, "Loaded: ${file.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Unreadable or file does not exist: $path", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                overlayCustomFontPath = ""
                overlayFont = "mono"
                saveOverlayPrefs()
                Toast.makeText(this, "Custom font cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveOverlayPrefs() {
        savePrefsAndWorldReadable {
            putString(KEY_OVERLAY_FONT, overlayFont)
            putString(KEY_OVERLAY_CUSTOM_FONT_PATH, overlayCustomFontPath)
            putBoolean(KEY_OVERLAY_BOLD, overlayBold)
            putFloat(KEY_OVERLAY_FONT_SIZE, overlayFontSize)
            putString(KEY_OVERLAY_COLOR, overlayColor)
            putString(KEY_OVERLAY_BG_STYLE, overlayBgStyle)
            putString(KEY_OVERLAY_SHAPE, overlayShape)
            putString(KEY_OVERLAY_PADDING, overlayPadding)
        }
        refreshOverlayAppearanceUi()
        dispatchOverlayConfig()
    }

    private fun dispatchOverlayConfig() {
        val intent = Intent(FpsService.ACTION_OVERLAY_CONFIG_CHANGED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildInfoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.parseColor("#161618"), 16f, Color.parseColor("#252528"), 1f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        val infoTitle = TextView(this).apply {
            text = "💡 Independent Controls & Features"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        card.addView(infoTitle)

        val infoText = TextView(this).apply {
            text = "• Floating Overlay and Status Bar operate 100% independently.\n" +
                   "• Status Bar uses LSPosed in SystemUI (works in Settings, lockscreen & games).\n" +
                   "• Floating overlay is privileged via system_server hook to stay visible in Settings.\n" +
                   "• On LTPO / VRR displays, true presented FPS drops to 1-3 fps at idle."
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6f)
            layoutParams = lp
        }
        card.addView(infoText)

        return card
    }

    private val pongReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PONG) {
                isLsposedActive = true
                runOnUiThread { refreshCapabilityUi() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pongReceiverRegistered) {
            val filter = IntentFilter(ACTION_PONG)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pongReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pongReceiver, filter)
            }
            pongReceiverRegistered = true
        }
        isLsposedActive = false
        sendBroadcast(Intent(ACTION_PING).setPackage("com.android.systemui"))

        FpsState.listener = { fps, label ->
            runOnUiThread {
                if (label == "no root") {
                    hasRoot = false
                    rootChecked = true
                    refreshCapabilityUi()
                } else if (fps != null) {
                    hasRoot = true
                    rootChecked = true
                    refreshCapabilityUi()
                }
                updateFpsDisplay(fps, label)
            }
        }
        updateFpsDisplay(FpsState.currentFps, FpsState.currentLabel)
        refreshUi()
        refreshCapabilityUi()
        dispatchStatusBarConfig()
        startInAppFpsPoller()
    }

    override fun onPause() {
        stopInAppFpsPoller()
        FpsState.listener = null
        if (pongReceiverRegistered) {
            runCatching { unregisterReceiver(pongReceiver) }
            pongReceiverRegistered = false
        }
        super.onPause()
    }

    private fun startInAppFpsPoller() {
        if (inAppPollingActive) return
        inAppPollingActive = true
        inAppWorker = Thread({
            var proc: Process? = null
            var w: BufferedWriter? = null
            var r: BufferedReader? = null
            var lastTime = SystemClock.elapsedRealtimeNanos()
            var lastFrames = -1L

            fun sh(cmd: String): List<String> {
                val out = ArrayList<String>()
                w!!.write("$cmd\necho __END__\n")
                w!!.flush()
                while (true) {
                    val line = r!!.readLine() ?: break
                    if (line == "__END__") break
                    out += line
                }
                return out
            }

            try {
                while (inAppPollingActive) {
                    if (FpsService.running) {
                        if (!rootChecked) {
                            val checkProc = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                            val out = checkProc.inputStream.bufferedReader().readText()
                            checkProc.waitFor()
                            hasRoot = out.contains("uid=0")
                            rootChecked = true
                            runOnUiThread { refreshCapabilityUi() }
                        }
                        Thread.sleep(1000)
                        continue
                    }

                    if (proc == null) {
                        proc = ProcessBuilder("su").redirectErrorStream(true).start()
                        w = proc.outputStream.bufferedWriter()
                        r = proc.inputStream.bufferedReader()
                        val idLines = sh("id")
                        if (!idLines.any { it.contains("uid=0") }) {
                            throw SecurityException("su denied")
                        }
                        hasRoot = true
                        rootChecked = true
                        runOnUiThread { refreshCapabilityUi() }
                        sh("dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1")
                        lastTime = SystemClock.elapsedRealtimeNanos()
                        lastFrames = -1L
                    }

                    Thread.sleep(1200)
                    if (!inAppPollingActive || FpsService.running) continue

                    val lines = sh("dumpsys SurfaceFlinger --timestats -dump")
                    val now = SystemClock.elapsedRealtimeNanos()
                    var currentFrames: Long? = null
                    for (line in lines) {
                        val m = FRAMES_RE.find(line)
                        if (m != null) {
                            currentFrames = m.groupValues[1].toLongOrNull()
                            break
                        }
                    }

                    if (currentFrames != null && lastFrames >= 0) {
                        val dt = (now - lastTime) / 1e9
                        val df = currentFrames - lastFrames
                        if (dt > 0 && df >= 0) {
                            val fpsVal = df / dt
                            runOnUiThread {
                                if (!FpsService.running) {
                                    updateFpsDisplay(fpsVal, "%.0f fps".format(fpsVal))
                                }
                            }
                        }
                    }
                    if (currentFrames != null) {
                        lastFrames = currentFrames
                    }
                    lastTime = now
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
                hasRoot = false
                rootChecked = true
                runOnUiThread {
                    refreshCapabilityUi()
                    if (!FpsService.running) {
                        updateFpsDisplay(null, "no root")
                    }
                }
            } finally {
                runCatching {
                    w?.write("exit\n")
                    w?.flush()
                    proc?.waitFor()
                }
                proc?.destroy()
            }
        }, "hyperfps-inapp-reader").apply { start() }
    }

    private fun stopInAppFpsPoller() {
        inAppPollingActive = false
        inAppWorker?.interrupt()
        inAppWorker = null
    }

    private fun refreshCapabilityUi() {
        if (::restartUiBtn.isInitialized) {
            restartUiBtn.isEnabled = hasRoot
            restartUiBtn.alpha = if (hasRoot) 1.0f else 0.35f
        }

        if (::statusBarToggleBtn.isInitialized && ::statusBarWarningView.isInitialized) {
            val canUseStatusBar = hasRoot && isLsposedActive
            statusBarToggleBtn.isEnabled = canUseStatusBar
            statusBarToggleBtn.alpha = if (canUseStatusBar) 1.0f else 0.45f

            if (!rootChecked) {
                statusBarWarningView.visibility = View.GONE
            } else if (!hasRoot) {
                statusBarWarningView.text = "⚠️ Root access denied. Status Bar FPS and Restart UI are disabled."
                statusBarWarningView.visibility = View.VISIBLE
            } else if (!isLsposedActive) {
                statusBarWarningView.text = "⚠️ Not activated in LSPosed. Enable HyperFPS for System UI & Restart UI."
                statusBarWarningView.visibility = View.VISIBLE
            } else {
                statusBarWarningView.visibility = View.GONE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFpsDisplay(fps: Double?, label: String) {
        if (fps != null) {
            fpsNumberView.text = "%.0f".format(fps)
            fpsNumberView.setTextColor(Color.parseColor("#00E676"))
            fpsUnitView.setTextColor(Color.parseColor("#00E676"))
            fpsStatusPill.text = if (FpsService.running) "🟢 Overlay Active • SurfaceFlinger" else "🟢 Live • SurfaceFlinger"
            fpsStatusPill.setTextColor(Color.parseColor("#00E676"))
            fpsStatusPill.background = roundedBox(Color.parseColor("#132A1C"), 10f, Color.parseColor("#00E676"), 1f)
        } else if (label == "no root") {
            fpsNumberView.text = "no root"
            fpsNumberView.setTextColor(Color.parseColor("#FF5252"))
            fpsUnitView.setTextColor(Color.parseColor("#FF5252"))
            fpsStatusPill.text = "🔴 Root access denied (check su / KernelSU)"
            fpsStatusPill.setTextColor(Color.parseColor("#FF5252"))
            fpsStatusPill.background = roundedBox(Color.parseColor("#321313"), 10f, Color.parseColor("#FF5252"), 1f)
        } else {
            fpsNumberView.text = "--"
            fpsNumberView.setTextColor(Color.parseColor("#FFD600"))
            fpsUnitView.setTextColor(Color.parseColor("#FFD600"))
            fpsStatusPill.text = "🟡 Sampling SurfaceFlinger..."
            fpsStatusPill.setTextColor(Color.parseColor("#FFD600"))
            fpsStatusPill.background = roundedBox(Color.parseColor("#322B13"), 10f, Color.parseColor("#FFD600"), 1f)
        }
    }

    private fun toggle() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val svc = Intent(this, FpsService::class.java)
        if (FpsService.running) {
            stopService(svc)
        } else {
            startForegroundService(svc)
        }
        actionButton.postDelayed({
            refreshUi()
            FpsTileService.requestUpdate(this)
        }, 300)
    }

    private fun refreshUi() {
        val hasOverlay = Settings.canDrawOverlays(this)
        permissionBanner.visibility = if (hasOverlay) View.GONE else View.VISIBLE

        if (FpsService.running) {
            actionButton.text = "Stop Floating Overlay"
            actionButton.setTextColor(Color.WHITE)
            actionButton.background = roundedBox(Color.parseColor("#D32F2F"), 14f)
        } else {
            actionButton.text = if (hasOverlay) "Start Floating Overlay" else "Grant Permission & Start"
            actionButton.setTextColor(Color.parseColor("#121212"))
            actionButton.background = roundedBox(Color.parseColor("#00E676"), 14f)
        }
    }
}
