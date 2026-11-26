package dev.livefps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var fpsNumberView: TextView
    private lateinit var fpsUnitView: TextView
    private lateinit var fpsStatusPill: TextView
    private lateinit var actionButton: Button
    private lateinit var permissionBanner: LinearLayout
    private val rateButtons = ArrayList<TextView>()

    private var selectedIntervalMs = FpsService.DEFAULT_INTERVAL_MS

    private val pollingRates = listOf(
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

        selectedIntervalMs = FpsService.getSavedInterval(this)

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

        // 3. Permission warning banner (if needed)
        permissionBanner = buildPermissionBanner()
        content.addView(permissionBanner)

        // 4. Action Card (Start / Stop)
        content.addView(buildActionCard())

        // 5. Info Card
        content.addView(buildInfoCard())

        setContentView(root)

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
            text = "ROOT"
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
            text = "SurfaceFlinger sampling frequency"
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
        pollingRates.forEachIndexed { index, pair ->
            val ms = pair.first
            val labelText = pair.second
            val btn = TextView(this).apply {
                text = labelText
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) lp.marginStart = dp(8f)
                layoutParams = lp
                setOnClickListener { selectPollingRate(ms) }
            }
            rateButtons.add(btn)
            row.addView(btn)
        }
        card.addView(row)
        updatePollingButtonsStyle()

        return card
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
            text = "💡 How LiveFPS Works"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        card.addView(infoTitle)

        val infoText = TextView(this).apply {
            text = "Unlike standard FPS tools that poll Choreographer, LiveFPS queries SurfaceFlinger TimeStats via root (su). On LTPO / VRR displays, it displays the true panel refresh rate (dropping down to 1-3 fps when idle)."
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
    }

    private fun updatePollingButtonsStyle() {
        pollingRates.forEachIndexed { index, pair ->
            val ms = pair.first
            val btn = rateButtons.getOrNull(index) ?: return@forEachIndexed
            if (ms == selectedIntervalMs) {
                btn.background = roundedBox(Color.parseColor("#00E676"), 12f)
                btn.setTextColor(Color.parseColor("#121212"))
                btn.typeface = Typeface.DEFAULT_BOLD
            } else {
                btn.background = roundedBox(Color.parseColor("#2A2A2E"), 12f, Color.parseColor("#38383A"), 1f)
                btn.setTextColor(Color.parseColor("#CCCCCC"))
                btn.typeface = Typeface.DEFAULT
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateFpsDisplay(fps: Double?, label: String) {
        if (fps != null) {
            fpsNumberView.text = "%.0f".format(fps)
            fpsNumberView.setTextColor(Color.parseColor("#00E676"))
            fpsUnitView.setTextColor(Color.parseColor("#00E676"))
            fpsStatusPill.text = "🟢 Active • Updating every ${selectedIntervalMs}ms"
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
            actionButton.text = "Stop FPS Overlay"
            actionButton.setTextColor(Color.WHITE)
            actionButton.background = roundedBox(Color.parseColor("#D32F2F"), 14f)
        } else {
            actionButton.text = if (hasOverlay) "Start FPS Overlay" else "Grant Permission & Start"
            actionButton.setTextColor(Color.parseColor("#121212"))
            actionButton.background = roundedBox(Color.parseColor("#00E676"), 14f)
        }
    }
}
