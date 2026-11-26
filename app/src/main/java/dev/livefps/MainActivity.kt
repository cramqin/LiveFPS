package dev.livefps

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
        const val PREFS_NAME = "livefps_prefs"
        const val KEY_STATUSBAR_ENABLED = "statusbar_enabled"
        const val KEY_STATUSBAR_POSITION = "statusbar_position"
        const val ACTION_CONFIG_CHANGED = "dev.livefps.ACTION_CONFIG_CHANGED"
    }

    private lateinit var fpsNumberView: TextView
    private lateinit var fpsUnitView: TextView
    private lateinit var fpsStatusPill: TextView
    private lateinit var actionButton: Button
    private lateinit var permissionBanner: LinearLayout
    private val rateButtons = ArrayList<TextView>()
    private var customRateButton: TextView? = null

    // Status Bar Card Views
    private lateinit var statusBarToggleBtn: Button
    private lateinit var posLeftBtn: TextView
    private lateinit var posRightBtn: TextView

    private var selectedIntervalMs = FpsService.DEFAULT_INTERVAL_MS
    private var isStatusBarEnabled = false
    private var statusBarPosition = "right"

    private val presetRates = listOf(
        Pair(250L, "250ms\n4 Hz"),
        Pair(500L, "500ms\n2 Hz"),
        Pair(1000L, "1.0s\n1 Hz"),
        Pair(2000L, "2.0s\n0.5 Hz")
    )

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
        selectedIntervalMs = FpsService.getSavedInterval(this)
        isStatusBarEnabled = prefs.getBoolean(KEY_STATUSBAR_ENABLED, false)
        statusBarPosition = prefs.getString(KEY_STATUSBAR_POSITION, "right") ?: "right"

        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(content)

        fun applyInsets(topInset: Int, bottomInset: Int) {
            val effectiveTop = if (topInset > 0) topInset else dp(36f)
            content.setPadding(
                dp(20f),
                effectiveTop + dp(16f),
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

        root.setOnApplyWindowInsetsListener { _, insets ->
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

        // 2. Polling Rate Selector Card
        content.addView(buildPollingRateCard())

        // 3. Status Bar (LSPosed) Independent Card
        content.addView(buildStatusBarCard())

        // 4. Permission warning banner (if needed for overlay)
        permissionBanner = buildPermissionBanner()
        content.addView(permissionBanner)

        // 5. Floating Overlay Action Card (Start / Stop)
        content.addView(buildActionCard())

        // 6. Info Card
        content.addView(buildInfoCard())

        setContentView(root)

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
            text = "LiveFPS"
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

    private fun buildPollingRateCard(): View {
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

        val title = TextView(this).apply {
            text = "POLLING RATE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        card.addView(title)

        val desc = TextView(this).apply {
            text = "SurfaceFlinger sampling frequency (Presets or Custom seconds)"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(2f)
            lp.bottomMargin = dp(12f)
            layoutParams = lp
        }
        card.addView(desc)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }

        rateButtons.clear()
        presetRates.forEachIndexed { index, pair ->
            val ms = pair.first
            val labelText = pair.second
            val btn = TextView(this).apply {
                text = labelText
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) lp.marginStart = dp(6f)
                layoutParams = lp
                setOnClickListener { selectPollingRate(ms) }
            }
            rateButtons.add(btn)
            row.addView(btn)
        }

        val customBtn = TextView(this).apply {
            text = formatCustomButtonText()
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(6f)
            layoutParams = lp
            setOnClickListener { showCustomPollingDialog() }
        }
        customRateButton = customBtn
        row.addView(customBtn)

        card.addView(row)
        updatePollingButtonsStyle()

        return card
    }

    private fun formatCustomButtonText(): String {
        val isPreset = presetRates.any { it.first == selectedIntervalMs }
        return if (isPreset) {
            "Custom\n..."
        } else {
            val sec = selectedIntervalMs / 1000f
            "%.2fs\nCustom".format(sec)
        }
    }

    private fun showCustomPollingDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(8f))
        }

        val currentSec = "%.2f".format(selectedIntervalMs / 1000f)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(currentSec)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "e.g. 1.5"
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Custom Polling Rate")
            .setMessage("Enter sampling interval in seconds (0.1s to 10.0s):")
            .setView(container)
            .setPositiveButton("Set") { _, _ ->
                val secVal = input.text.toString().toDoubleOrNull()
                if (secVal != null && secVal > 0) {
                    val ms = (secVal * 1000).toLong().coerceIn(100L, 30000L)
                    selectPollingRate(ms)
                } else {
                    Toast.makeText(this, "Please enter a valid number of seconds", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_STATUSBAR_ENABLED, isStatusBarEnabled)
                    .apply()
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
        refreshStatusBarUi()

        return card
    }

    private fun setPosition(pos: String) {
        statusBarPosition = pos
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUSBAR_POSITION, pos)
            .apply()
        refreshStatusBarUi()
        dispatchStatusBarConfig()
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
            putExtra("interval_ms", selectedIntervalMs)
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

    private fun selectPollingRate(ms: Long) {
        selectedIntervalMs = ms
        FpsService.updateInterval(this, ms)
        updatePollingButtonsStyle()
        updateFpsDisplay(FpsState.currentFps, FpsState.currentLabel)
        dispatchStatusBarConfig()
    }

    private fun updatePollingButtonsStyle() {
        var isPresetActive = false
        presetRates.forEachIndexed { index, pair ->
            val ms = pair.first
            val btn = rateButtons.getOrNull(index) ?: return@forEachIndexed
            if (ms == selectedIntervalMs) {
                isPresetActive = true
                btn.background = roundedBox(Color.parseColor("#00E676"), 12f)
                btn.setTextColor(Color.parseColor("#121212"))
                btn.typeface = Typeface.DEFAULT_BOLD
            } else {
                btn.background = roundedBox(Color.parseColor("#2A2A2E"), 12f, Color.parseColor("#38383A"), 1f)
                btn.setTextColor(Color.parseColor("#CCCCCC"))
                btn.typeface = Typeface.DEFAULT
            }
        }

        customRateButton?.let { cBtn ->
            cBtn.text = formatCustomButtonText()
            if (!isPresetActive) {
                cBtn.background = roundedBox(Color.parseColor("#00E676"), 12f)
                cBtn.setTextColor(Color.parseColor("#121212"))
                cBtn.typeface = Typeface.DEFAULT_BOLD
            } else {
                cBtn.background = roundedBox(Color.parseColor("#2A2A2E"), 12f, Color.parseColor("#38383A"), 1f)
                cBtn.setTextColor(Color.parseColor("#CCCCCC"))
                cBtn.typeface = Typeface.DEFAULT
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFpsDisplay(fps: Double?, label: String) {
        if (fps != null) {
            fpsNumberView.text = "%.0f".format(fps)
            fpsNumberView.setTextColor(Color.parseColor("#00E676"))
            fpsUnitView.setTextColor(Color.parseColor("#00E676"))
            val sec = selectedIntervalMs / 1000f
            val timingText = if (selectedIntervalMs % 1000L == 0L) "${selectedIntervalMs / 1000}s" else "%.2fs".format(sec)
            fpsStatusPill.text = "🟢 Active • Updating every $timingText"
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
        actionButton.postDelayed({ refreshUi() }, 300)
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
