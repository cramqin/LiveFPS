package com.cramqin.hyperfps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
        const val KEY_OVERLAY_FONT = "overlay_font"
        const val KEY_OVERLAY_CUSTOM_FONT_PATH = "overlay_custom_font_path"
        const val KEY_OVERLAY_BOLD = "overlay_bold"
        const val KEY_OVERLAY_FONT_SIZE = "overlay_font_size"
        const val KEY_OVERLAY_COLOR = "overlay_color"
        const val KEY_OVERLAY_BG_STYLE = "overlay_bg_style"
        const val KEY_OVERLAY_SHAPE = "overlay_shape"
        const val KEY_OVERLAY_PADDING = "overlay_padding"
    }

    private lateinit var fpsNumberView: TextView
    private lateinit var fpsUnitView: TextView
    private lateinit var fpsStatusPill: TextView
    private lateinit var actionButton: Button
    private lateinit var permissionBanner: LinearLayout
    // Status Bar Card Views
    private lateinit var statusBarToggleBtn: Button
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

        val restartUiBtn = TextView(this).apply {
            text = "🔄 Restart UI"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            background = roundedBox(Color.parseColor("#252528"), 8f, Color.parseColor("#38383A"), 1f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setOnClickListener { promptRestartSystemUi() }
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
        val input = EditText(this).apply {
            hint = "#00E676"
            setText(overlayColor)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Hex Color")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.matches(Regex("""^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"""))) {
                    overlayColor = txt
                    saveOverlayPrefs()
                } else {
                    Toast.makeText(this, "Invalid hex (e.g. #00E676)", Toast.LENGTH_SHORT).show()
                }
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

        // Live Preview Box
        val previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBox(Color.parseColor("#141416"), 14f, Color.parseColor("#262629"), 1f)
            setPadding(dp(16f), dp(18f), dp(16f), dp(18f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(14f)
            layoutParams = lp
        }

        overlayPreviewView = TextView(this).apply {
            text = "120 fps"
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
            text = "HEX"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBox(Color.parseColor("#2C2C2E"), 6f, Color.parseColor("#38383A"), 1f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
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

    override fun onResume() {
        super.onResume()
        FpsState.listener = { fps, label ->
            runOnUiThread { updateFpsDisplay(fps, label) }
        }
        updateFpsDisplay(FpsState.currentFps, FpsState.currentLabel)
        refreshUi()
        dispatchStatusBarConfig()
    }

    override fun onPause() {
        FpsState.listener = null
        super.onPause()
    }

    @SuppressLint("SetTextI18n")
    private fun updateFpsDisplay(fps: Double?, label: String) {
        if (fps != null) {
            fpsNumberView.text = "%.0f".format(fps)
            fpsNumberView.setTextColor(Color.parseColor("#00E676"))
            fpsUnitView.setTextColor(Color.parseColor("#00E676"))
            fpsStatusPill.text = "🟢 Active • Updating every 2s"
            fpsStatusPill.setTextColor(Color.parseColor("#00E676"))
            fpsStatusPill.background = roundedBox(Color.parseColor("#132A1C"), 10f, Color.parseColor("#00E676"), 1f)
        } else if (FpsService.running) {
            fpsNumberView.text = label.replace(" fps", "")
            if (label == "no root") {
                fpsNumberView.setTextColor(Color.parseColor("#FF5252"))
                fpsUnitView.setTextColor(Color.parseColor("#FF5252"))
                fpsStatusPill.text = "🔴 Root access denied (check su / KernelSU)"
                fpsStatusPill.setTextColor(Color.parseColor("#FF5252"))
                fpsStatusPill.background = roundedBox(Color.parseColor("#321313"), 10f, Color.parseColor("#FF5252"), 1f)
            } else {
                fpsNumberView.setTextColor(Color.parseColor("#FFD600"))
                fpsUnitView.setTextColor(Color.parseColor("#FFD600"))
                fpsStatusPill.text = "🟡 Sampling SurfaceFlinger..."
                fpsStatusPill.setTextColor(Color.parseColor("#FFD600"))
                fpsStatusPill.background = roundedBox(Color.parseColor("#322B13"), 10f, Color.parseColor("#FFD600"), 1f)
            }
        } else {
            fpsNumberView.text = "--"
            fpsNumberView.setTextColor(Color.parseColor("#555555"))
            fpsUnitView.setTextColor(Color.parseColor("#555555"))
            fpsStatusPill.text = "⚪ Idle • Tap Start below"
            fpsStatusPill.setTextColor(Color.parseColor("#8E8E93"))
            fpsStatusPill.background = roundedBox(Color.parseColor("#252528"), 10f, Color.parseColor("#38383A"), 1f)
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
