package com.cramqin.hyperfps.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI Hook (libxposed API 102):
 * Injects a real-time FPS readout into HyperOS Status Bar using a standalone TextView.
 *
 * Direct container injection for both Left and Right side:
 * - Left side (near Clock): PhoneStatusBarViewController.startSideContainer or clock.parent
 * - Right side (near Battery / System icons): PhoneStatusBarViewController.endSideContainer or battery.parent
 * - Zero conflict with stock NetworkSpeedView or HyperCeiler (does not hook NetworkSpeedView or IconManager)
 * - Real-time switching (left/right) and toggle (on/off) via broadcast `com.cramqin.hyperfps.ACTION_CONFIG_CHANGED`
 * - Dynamic color synchronization with MiuiClock (dark mode / light mode adaptive)
 * - Fixed 2.0s polling rate (independent of overlay polling rate)
 * - Monotonic SurfaceFlinger timestats delta sampling (zero conflict with overlay)
 */
object StatusBarFpsHook {

    private const val TAG = "HyperFPS"
    private const val ACTION_CONFIG_CHANGED = "com.cramqin.hyperfps.ACTION_CONFIG_CHANGED"
    private const val ACTION_FPS_UPDATE = "com.cramqin.hyperfps.ACTION_FPS_UPDATE"
    private const val ACTION_STATUSBAR_ACK = "com.cramqin.hyperfps.ACTION_STATUSBAR_ACK"
    private const val ACTION_PING = "com.cramqin.hyperfps.ACTION_PING"
    private const val ACTION_PONG = "com.cramqin.hyperfps.ACTION_PONG"
    private const val MSG_WORKER_TICK = 200021
    private const val STATUSBAR_POLL_INTERVAL_MS = 2000L // Fixed 2.0s rate matching HyperCeiler

    private const val TAG_LEFT = "hyperfps_statusbar_left"
    private const val TAG_RIGHT = "hyperfps_statusbar_right"

    private const val FIELD_M_CLOCK_VIEW = "mClockView"

    private const val PKG_SYSTEMUI = "com.android.systemui"
    private const val ID_CLOCK = "clock"
    private const val ID_BATTERY = "battery"
    private const val STYLE_CLOCK = "TextAppearance.StatusBar.Clock"

    private val leftViews = CopyOnWriteArrayList<View>()
    private val rightViews = CopyOnWriteArrayList<View>()
    private val clockCache = Collections.synchronizedMap(WeakHashMap<View, WeakReference<TextView>>())
    @Volatile private var cachedClockRef: WeakReference<TextView>? = null
    @Volatile private var receiverRegistered = false

    @Volatile private var isEnabled = false
    @Volatile private var currentPosition = "right"
    @Volatile private var statusBarFontSize = 11f
    @Volatile private var statusBarBold = true
    @Volatile private var statusBarColor = "#FFFFFF"
    @Volatile private var statusBarMarginLeft = 1
    @Volatile private var statusBarMarginRight = 1
    @Volatile private var statusBarVerticalOffset = 0
    @Volatile private var statusBarFont = "default"
    @Volatile private var statusBarCustomFontPath = ""
    @Volatile private var cachedCustomTypeface: Typeface? = null
    @Volatile private var lastCustomFontPath: String? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastTimestatsEnableTime = 0L
    @Volatile private var lastOverlaySyncTime = 0L

    private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

    private fun resolveStatusBarTypeface(): Typeface {
        if (statusBarCustomFontPath.isNotBlank()) {
            if (statusBarCustomFontPath != lastCustomFontPath) {
                lastCustomFontPath = statusBarCustomFontPath
                cachedCustomTypeface = runCatching {
                    val f = java.io.File(statusBarCustomFontPath)
                    if (f.exists() && f.canRead()) {
                        Typeface.createFromFile(f)
                    } else null
                }.getOrNull()
            }
            val custom = cachedCustomTypeface
            if (custom != null) {
                val style = if (statusBarBold) Typeface.BOLD else Typeface.NORMAL
                return Typeface.create(custom, style)
            }
        }

        val base = when (statusBarFont.lowercase()) {
            "mono", "monospace" -> Typeface.MONOSPACE
            "sans", "sans-serif" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            else -> Typeface.DEFAULT
        }
        val style = if (statusBarBold) Typeface.BOLD else Typeface.NORMAL
        return Typeface.create(base, style)
    }

    fun init(module: XposedModule, classLoader: ClassLoader) {
        Log.i(TAG, "StatusBarFpsHook initializing (standalone TextView container injection)")

        // 1. Read initial preferences from local JSON / XML / RemotePreferences
        loadInitialPrefs(module)

        // 2. Early broadcast receiver registration via Application.onCreate
        setupApplicationHook(module, classLoader)

        // 3. Setup Status Bar containers (Left near clock, Right near battery / system icons)
        setupStatusBarContainers(module, classLoader)

        // 4. Setup MiuiClock color sync
        setupClockColorSync(module, classLoader)

        // 5. Start background sampling worker (fixed 2.0s interval, autonomous timestats)
        startSamplingWorker()
    }

    private fun loadInitialPrefs(module: XposedModule) {
        var loaded = false

        // Strategy 1: Read JSON from /data/local/tmp/hyperfps_config.json (world-readable, boot persistent)
        runCatching {
            val f = java.io.File("/data/local/tmp/hyperfps_config.json")
            if (f.exists() && f.canRead()) {
                val json = org.json.JSONObject(f.readText())
                applyJsonConfig(json)
                loaded = true
                Log.i(TAG, "Loaded initial config from /data/local/tmp/hyperfps_config.json: enabled=$isEnabled, pos=$currentPosition, col=$statusBarColor")
            }
        }

        // Strategy 2: Read JSON from app internal files dir
        if (!loaded) {
            runCatching {
                val f = java.io.File("/data/user/0/com.cramqin.hyperfps/files/hyperfps_config.json")
                if (f.exists() && f.canRead()) {
                    val json = org.json.JSONObject(f.readText())
                    applyJsonConfig(json)
                    loaded = true
                    Log.i(TAG, "Loaded initial config from app files dir: enabled=$isEnabled, pos=$currentPosition")
                }
            }
        }

        // Strategy 3: Read XML from app shared_prefs
        if (!loaded) {
            runCatching {
                val f = java.io.File("/data/user/0/com.cramqin.hyperfps/shared_prefs/hyperfps_prefs.xml")
                if (f.exists() && f.canRead()) {
                    applyXmlConfig(f.readText())
                    loaded = true
                    Log.i(TAG, "Loaded initial config from app shared_prefs XML: enabled=$isEnabled, pos=$currentPosition")
                }
            }
        }

        // Strategy 4: Xposed RemotePreferences
        runCatching {
            val remotePrefs = module.getRemotePreferences("hyperfps_prefs")
            if (remotePrefs != null) {
                if (remotePrefs.contains("statusbar_enabled")) {
                    isEnabled = remotePrefs.getBoolean("statusbar_enabled", isEnabled)
                }
                currentPosition = remotePrefs.getString("statusbar_position", currentPosition) ?: currentPosition
                statusBarFontSize = remotePrefs.getFloat("statusbar_font_size", statusBarFontSize)
                statusBarBold = remotePrefs.getBoolean("statusbar_bold", statusBarBold)
                statusBarColor = remotePrefs.getString("statusbar_color", statusBarColor) ?: statusBarColor
                statusBarMarginLeft = remotePrefs.getInt("statusbar_margin_left", statusBarMarginLeft)
                statusBarMarginRight = remotePrefs.getInt("statusbar_margin_right", statusBarMarginRight)
                statusBarVerticalOffset = remotePrefs.getInt("statusbar_vertical_offset", statusBarVerticalOffset)
                statusBarFont = remotePrefs.getString("statusbar_font", statusBarFont) ?: statusBarFont
                statusBarCustomFontPath = remotePrefs.getString("statusbar_custom_font_path", statusBarCustomFontPath) ?: statusBarCustomFontPath
                Log.i(TAG, "Merged remotePrefs: enabled=$isEnabled, pos=$currentPosition, col=$statusBarColor")
            }
        }.onFailure {
            Log.w(TAG, "Could not load remote prefs: ${it.message}")
        }

        if (isEnabled) lastTimestatsEnableTime = 0L
    }

    private fun applyJsonConfig(json: org.json.JSONObject) {
        isEnabled = json.optBoolean("statusbar_enabled", isEnabled)
        currentPosition = json.optString("statusbar_position", currentPosition)
        statusBarFontSize = json.optDouble("statusbar_font_size", statusBarFontSize.toDouble()).toFloat()
        statusBarBold = json.optBoolean("statusbar_bold", statusBarBold)
        statusBarColor = json.optString("statusbar_color", statusBarColor)
        statusBarMarginLeft = json.optInt("statusbar_margin_left", statusBarMarginLeft)
        statusBarMarginRight = json.optInt("statusbar_margin_right", statusBarMarginRight)
        statusBarVerticalOffset = json.optInt("statusbar_vertical_offset", statusBarVerticalOffset)
        statusBarFont = json.optString("statusbar_font", statusBarFont)
        statusBarCustomFontPath = json.optString("statusbar_custom_font_path", statusBarCustomFontPath)
    }

    private fun applyXmlConfig(xml: String) {
        val enabledMatch = Regex("""<boolean name="statusbar_enabled" value="(\w+)" />""").find(xml)
        if (enabledMatch != null) isEnabled = enabledMatch.groupValues[1].toBoolean()

        val posMatch = Regex("""<string name="statusbar_position">(.*?)</string>""").find(xml)
        if (posMatch != null) currentPosition = posMatch.groupValues[1]

        val sizeMatch = Regex("""<float name="statusbar_font_size" value="(.*?)" />""").find(xml)
        if (sizeMatch != null) sizeMatch.groupValues[1].toFloatOrNull()?.let { statusBarFontSize = it }

        val boldMatch = Regex("""<boolean name="statusbar_bold" value="(\w+)" />""").find(xml)
        if (boldMatch != null) statusBarBold = boldMatch.groupValues[1].toBoolean()

        val colMatch = Regex("""<string name="statusbar_color">(.*?)</string>""").find(xml)
        if (colMatch != null) statusBarColor = colMatch.groupValues[1]

        val fontMatch = Regex("""<string name="statusbar_font">(.*?)</string>""").find(xml)
        if (fontMatch != null) statusBarFont = fontMatch.groupValues[1]
    }

    private fun setupApplicationHook(module: XposedModule, classLoader: ClassLoader) {
        val appCls = findClass("android.app.Application", classLoader) ?: return
        for (m in appCls.declaredMethods.filter { it.name == "onCreate" && it.parameterTypes.isEmpty() }) {
            hookMethod(module, m) { chain ->
                val res = chain.proceed()
                (chain.thisObject as? Context)?.let { registerReceiverWithContext(it) }
                res
            }
        }
    }

    private fun registerReceiverWithContext(context: Context) {
        if (receiverRegistered) return
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val filter = IntentFilter().apply {
            addAction(ACTION_CONFIG_CHANGED)
            addAction(ACTION_FPS_UPDATE)
            addAction(ACTION_PING)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PING -> {
                        val pongIntent = Intent(ACTION_PONG).apply {
                            setPackage("com.cramqin.hyperfps")
                        }
                        runCatching { ctx?.sendBroadcast(pongIntent) }
                    }
                    ACTION_CONFIG_CHANGED -> {
                        val prevEnabled = isEnabled
                        isEnabled = intent.getBooleanExtra("enabled", isEnabled)
                        currentPosition = intent.getStringExtra("position") ?: currentPosition
                        statusBarFontSize = intent.getFloatExtra("font_size", statusBarFontSize)
                        statusBarBold = intent.getBooleanExtra("bold", statusBarBold)
                        statusBarColor = intent.getStringExtra("color") ?: statusBarColor
                        statusBarMarginLeft = intent.getIntExtra("margin_left", statusBarMarginLeft)
                        statusBarMarginRight = intent.getIntExtra("margin_right", statusBarMarginRight)
                        statusBarVerticalOffset = intent.getIntExtra("vertical_offset", statusBarVerticalOffset)
                        statusBarFont = intent.getStringExtra("font") ?: statusBarFont
                        statusBarCustomFontPath = intent.getStringExtra("custom_font_path") ?: statusBarCustomFontPath
                        if (isEnabled && !prevEnabled) {
                            lastTimestatsEnableTime = 0L
                        }
                        Log.i(TAG, "Broadcast received: isEnabled=$isEnabled, pos=$currentPosition, size=$statusBarFontSize, bold=$statusBarBold, col=$statusBarColor, font=$statusBarFont")
                        applyStyleAndLayout()
                        updateVisibilities()
                    }
                    ACTION_FPS_UPDATE -> {
                        val fps = intent.getStringExtra("fps")
                        if (!fps.isNullOrEmpty()) {
                            lastOverlaySyncTime = SystemClock.elapsedRealtime()
                            if (isEnabled) {
                                mainHandler.post { updateFpsText(fps) }
                            }
                            val ackIntent = Intent(ACTION_STATUSBAR_ACK).apply {
                                setPackage("com.cramqin.hyperfps")
                                putExtra("fps", fps)
                            }
                            runCatching { ctx?.sendBroadcast(ackIntent) }
                        }
                    }
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            Log.i(TAG, "Broadcast receiver registered for $ACTION_CONFIG_CHANGED and $ACTION_FPS_UPDATE")
        }.onFailure {
            Log.e(TAG, "Failed to register broadcast receiver", it)
        }
    }

    private fun setupStatusBarContainers(module: XposedModule, classLoader: ClassLoader) {
        // 1. PhoneStatusBarViewController.onViewAttached
        val controllerCls = findClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader)
        if (controllerCls != null) {
            for (m in controllerCls.declaredMethods.filter { it.name == "onViewAttached" && it.parameterTypes.isEmpty() }) {
                hookMethod(module, m) { chain ->
                    val res = chain.proceed()
                    val controller = chain.thisObject
                    val statusBarView = (getField(controller, "mView") as? ViewGroup)
                        ?: (getField(controller, "phoneStatusBarView") as? ViewGroup)
                        ?: (chain.thisObject as? ViewGroup)
                    val clockView = (getField(controller, "clock") as? View) ?: (getField(controller, FIELD_M_CLOCK_VIEW) as? View)
                    val startSide = getField(controller, "startSideContainer") as? ViewGroup
                    val endSide = getField(controller, "endSideContainer") as? ViewGroup
                    val context = statusBarView?.context ?: clockView?.context ?: startSide?.context ?: endSide?.context

                    if (context != null) {
                        registerReceiverWithContext(context)
                        val resIds = context.resources
                        val clockId = resIds.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                        val resolvedClock = clockView ?: if (clockId != 0 && statusBarView != null) statusBarView.findViewById(clockId) else null
                        val leftContainer = startSide ?: (resolvedClock?.parent as? ViewGroup)
                        injectToLeftContainer(leftViews, context, leftContainer, resolvedClock)

                        val statusBarScope = statusBarView ?: (leftContainer?.parent as? View) ?: leftContainer?.rootView ?: endSide?.rootView
                        val (rightContainer, anchorChild) = resolveRightTarget(statusBarScope, context, endSide)
                        injectToRightContainer(rightViews, context, rightContainer, anchorChild)
                    }
                    res
                }
            }
        }

        // 2. MiuiCollapsedStatusBarFragment / CollapsedStatusBarFragment
        for (fName in listOf("com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment", "com.android.systemui.statusbar.phone.CollapsedStatusBarFragment")) {
            val fCls = findClass(fName, classLoader) ?: continue
            for (m in fCls.declaredMethods.filter { (it.name == "initMiuiViewsOnViewCreated" || it.name == "onViewCreated") && it.parameterTypes.isNotEmpty() }) {
                hookMethod(module, m) { chain ->
                    val res = chain.proceed()
                    val fragment = chain.thisObject
                    val rootView = chain.args.getOrNull(0) as? View
                    val context = rootView?.context ?: runCatching { fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context }.getOrNull()
                    if (context != null) {
                        registerReceiverWithContext(context)
                        val resIds = context.resources
                        val clockId = resIds.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                        val clockView = (getField(fragment, FIELD_M_CLOCK_VIEW) as? View) ?: if (clockId != 0 && rootView != null) rootView.findViewById(clockId) else null
                        val leftContainer = clockView?.parent as? ViewGroup
                        injectToLeftContainer(leftViews, context, leftContainer, clockView)

                        val statusBarScope = (leftContainer?.parent as? View) ?: rootView
                        val (rightContainer, anchorChild) = resolveRightTarget(statusBarScope, context, null)
                        injectToRightContainer(rightViews, context, rightContainer, anchorChild)
                    }
                    res
                }
            }
        }

        // 3. MiuiPhoneStatusBarView / PhoneStatusBarView constructors and inflate
        for (vName in listOf("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", "com.android.systemui.statusbar.phone.PhoneStatusBarView")) {
            val vCls = findClass(vName, classLoader) ?: continue
            for (ctor in vCls.declaredConstructors) {
                hookMethod(module, ctor) { chain ->
                    val res = chain.proceed()
                    val view = chain.thisObject as? ViewGroup ?: return@hookMethod res
                    view.post {
                        val context = view.context ?: return@post
                        registerReceiverWithContext(context)
                        val resIds = context.resources
                        val clockId = resIds.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                        val clockView = if (clockId != 0) view.findViewById<View>(clockId) else null
                        val leftContainer = clockView?.parent as? ViewGroup
                        injectToLeftContainer(leftViews, context, leftContainer, clockView)

                        val (rightContainer, anchorChild) = resolveRightTarget(view, context, null)
                        injectToRightContainer(rightViews, context, rightContainer, anchorChild)
                    }
                    res
                }
            }
        }
    }

    private fun resolveRightTarget(
        scopeView: View?,
        context: Context,
        fallbackEndSide: ViewGroup?
    ): Pair<ViewGroup?, View?> {
        val res = context.resources

        // 1. Main HyperOS status bar: status_icon_area (LinearLayout).
        // Return null as anchorChild so it appends to the end of status_icon_area (right side of status bar, away from center camera punch hole)
        val statusIconAreaId = res.getIdentifier("status_icon_area", "id", PKG_SYSTEMUI)
        val statusIconArea = if (statusIconAreaId != 0) scopeView?.findViewById<ViewGroup>(statusIconAreaId) else null
        if (statusIconArea != null) {
            return Pair(statusIconArea, null)
        }

        // 2. Alternate status bar layout: status_icons_container
        val statusIconsContainerId = res.getIdentifier("status_icons_container", "id", PKG_SYSTEMUI)
        val statusIconsContainer = if (statusIconsContainerId != 0) scopeView?.findViewById<ViewGroup>(statusIconsContainerId) else null
        if (statusIconsContainer != null) {
            return Pair(statusIconsContainer, null)
        }

        // 3. Fallback via battery view: walk above MiuiStatusBatteryContainer into its LinearLayout parent
        val batteryId = res.getIdentifier(ID_BATTERY, "id", PKG_SYSTEMUI)
        val batteryView = if (batteryId != 0) scopeView?.findViewById<View>(batteryId) else null
        val batteryParent = batteryView?.parent as? ViewGroup
        if (batteryParent != null) {
            if (batteryParent.javaClass.simpleName.contains("BatteryContainer") || batteryParent !is LinearLayout) {
                val grandParent = batteryParent.parent as? ViewGroup
                if (grandParent != null) {
                    return Pair(grandParent, null)
                }
            }
            return Pair(batteryParent, null)
        }

        return Pair(fallbackEndSide, null)
    }

    private fun injectToLeftContainer(
        targetList: CopyOnWriteArrayList<View>,
        context: Context,
        targetContainer: ViewGroup?,
        clockView: View?
    ) {
        val container = targetContainer ?: return
        val existing = container.findViewWithTag<View>(TAG_LEFT)
        if (existing != null) {
            if (!targetList.contains(existing)) targetList.add(existing)
            syncColorWithClock(existing)
            return
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val fpsView = createFpsTextView(context, lp).apply { tag = TAG_LEFT }

        val index = if (clockView != null && clockView.parent === container) {
            (container.indexOfChild(clockView) + 1).coerceIn(0, container.childCount)
        } else {
            container.childCount
        }
        container.addView(fpsView, index)
        targetList.add(fpsView)
        syncColorWithClock(fpsView)
        Log.i(TAG, "Injected $TAG_LEFT into left container $container at index $index")
        updateVisibilities()
    }

    private fun injectToRightContainer(
        targetList: CopyOnWriteArrayList<View>,
        context: Context,
        targetContainer: ViewGroup?,
        anchorChild: View?
    ) {
        val container = targetContainer ?: return
        val existing = container.findViewWithTag<View>(TAG_RIGHT)
        if (existing != null) {
            if (!targetList.contains(existing)) targetList.add(existing)
            syncColorWithClock(existing)
            return
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val fpsView = createFpsTextView(context, lp).apply { tag = TAG_RIGHT }

        val index = if (anchorChild != null && anchorChild.parent === container) {
            container.indexOfChild(anchorChild).coerceIn(0, container.childCount)
        } else {
            container.childCount
        }
        container.addView(fpsView, index)
        targetList.add(fpsView)
        syncColorWithClock(fpsView)
        Log.i(TAG, "Injected $TAG_RIGHT into right container $container at index $index")
        updateVisibilities()
    }

    private fun createFpsTextView(context: Context, lp: ViewGroup.LayoutParams): TextView {
        val tv = TextView(context).apply {
            id = View.generateViewId()
            layoutParams = lp
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            typeface = resolveStatusBarTypeface()
            setTextColor(Color.WHITE)
            alpha = 1.0f
            text = "--"
        }
        val clockStyleId = context.resources.getIdentifier(STYLE_CLOCK, "style", PKG_SYSTEMUI)
        if (clockStyleId != 0) {
            runCatching { tv.setTextAppearance(clockStyleId) }
        }
        applyStyleToView(tv)
        syncColorWithClock(tv)
        return tv
    }

    private fun applyStyleAndLayout() {
        mainHandler.post {
            for (v in leftViews) applyStyleToView(v)
            for (v in rightViews) applyStyleToView(v)
        }
    }

    private fun applyStyleToView(view: View) {
        val tv = view as? TextView ?: return
        val density = tv.resources?.displayMetrics?.density ?: 2.75f
        val leftPx = (statusBarMarginLeft * density).toInt()
        val rightPx = (statusBarMarginRight * density).toInt()

        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, statusBarFontSize)
        tv.typeface = resolveStatusBarTypeface()
        tv.translationY = statusBarVerticalOffset * density
        tv.setPaddingRelative(leftPx, 0, rightPx, 0)

        val lp = tv.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null && (lp.leftMargin != leftPx || lp.rightMargin != rightPx)) {
            lp.leftMargin = leftPx
            lp.rightMargin = rightPx
            tv.layoutParams = lp
        }
        syncColorWithClock(tv)
        tv.requestLayout()
    }

    private fun setupClockColorSync(module: XposedModule, classLoader: ClassLoader) {
        val clockClasses = listOf(
            "com.android.systemui.statusbar.views.MiuiClock",
            "com.android.systemui.statusbar.views.MiuiStatusBarClock",
            "com.android.systemui.statusbar.policy.Clock"
        )
        val methods = listOf("setTextColor", "setTextColorDark", "updateClockColor", "onDarkChanged", "setTextDark", "updateTime")

        for (clsName in clockClasses) {
            val cls = findClass(clsName, classLoader) ?: continue
            for (mName in methods) {
                for (m in cls.declaredMethods.filter { it.name == mName }) {
                    hookMethod(module, m) { chain ->
                        val res = chain.proceed()
                        val clock = chain.thisObject as? TextView
                        if (clock != null) {
                            mainHandler.post {
                                cachedClockRef = WeakReference(clock)
                                ensureInjectedFromClock(clock)
                                for (v in leftViews) syncColorWithClock(v, clock)
                                for (v in rightViews) syncColorWithClock(v, clock)
                            }
                        }
                        res
                    }
                }
            }
        }
    }

    private fun ensureInjectedFromClock(clock: TextView) {
        if (!clock.isAttachedToWindow) return
        val p = clock.parent as? View
        val pName = p?.javaClass?.name ?: ""
        if (pName.contains("Header") || pName.contains("QS") || pName.contains("ConstraintLayout") || pName.contains("carrier", ignoreCase = true)) {
            return
        }
        val context = clock.context ?: return
        registerReceiverWithContext(context)

        var p: View? = clock.parent as? View
        var scope: View? = null
        for (i in 0 until 4) {
            if (p == null) break
            val (rc, _) = resolveRightTarget(p, context, null)
            if (rc != null) {
                scope = p
                break
            }
            p = p.parent as? View
        }
        if (scope == null) return

        val leftContainer = clock.parent as? ViewGroup
        if (leftContainer != null && leftContainer.findViewWithTag<View>(TAG_LEFT) == null) {
            injectToLeftContainer(leftViews, context, leftContainer, clock)
        }

        val (rightContainer, anchorChild) = resolveRightTarget(scope, context, null)
        if (rightContainer != null && rightContainer.findViewWithTag<View>(TAG_RIGHT) == null) {
            injectToRightContainer(rightViews, context, rightContainer, anchorChild)
        }
    }

    private fun syncColorWithClock(view: View, clockView: TextView? = null) {
        val tv = view as? TextView ?: return
        tv.typeface = resolveStatusBarTypeface()

        // 1. If explicit solid color is configured and not "auto", enforce it directly
        if (statusBarColor.isNotBlank() && !statusBarColor.equals("auto", ignoreCase = true)) {
            val col = runCatching { Color.parseColor(statusBarColor) }.getOrDefault(Color.WHITE)
            tv.setTextColor(col)
            tv.alpha = 1.0f
            return
        }

        // 2. Adaptive auto color with active clock
        val clock = clockView ?: findCachedClock(tv)
        if (clock != null) {
            val colors = clock.textColors
            val curColor = clock.currentTextColor
            if (colors != null) {
                tv.setTextColor(colors)
            } else if (curColor != 0) {
                tv.setTextColor(curColor)
            } else {
                tv.setTextColor(Color.WHITE)
            }
            val clockAlpha = clock.alpha
            tv.alpha = if (clockAlpha > 0.1f) clockAlpha else 1.0f
        } else {
            tv.setTextColor(Color.WHITE)
            tv.alpha = 1.0f
        }
    }

    private fun findCachedClock(iconView: View): TextView? {
        synchronized(clockCache) {
            clockCache[iconView]?.get()?.let { cached ->
                if (cached.isAttachedToWindow) return cached
                clockCache.remove(iconView)
            }
            cachedClockRef?.get()?.let { cached ->
                if (cached.isAttachedToWindow) {
                    clockCache[iconView] = WeakReference(cached)
                    return cached
                }
                cachedClockRef = null
            }
            for (lv in leftViews) {
                clockCache[lv]?.get()?.let { cached ->
                    if (cached.isAttachedToWindow) {
                        clockCache[iconView] = WeakReference(cached)
                        cachedClockRef = WeakReference(cached)
                        return cached
                    }
                }
            }

            val res = iconView.resources ?: return null
            val clockId = res.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
            if (clockId == 0) return null

            val container = iconView.parent as? ViewGroup
            var clock = container?.findViewById<TextView>(clockId)
            if (clock == null || clock.width == 0 || clock.alpha < 0.1f) {
                clock = iconView.rootView?.findViewById<TextView>(clockId)
            }
            if (clock != null && clock.width > 0 && clock.alpha > 0.1f) {
                val ref = WeakReference(clock)
                clockCache[iconView] = ref
                cachedClockRef = ref
            }
            return clock
        }
    }

    private fun updateVisibilities() {
        mainHandler.post {
            leftViews.removeAll { it.parent == null }
            rightViews.removeAll { it.parent == null }

            for (tv in leftViews) {
                val show = isEnabled && currentPosition == "left"
                tv.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    syncColorWithClock(tv)
                    tv.alpha = 1.0f
                }
            }
            for (tv in rightViews) {
                val show = isEnabled && currentPosition == "right"
                tv.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    syncColorWithClock(tv)
                    tv.alpha = 1.0f
                }
            }
        }
    }

    private fun updateFpsText(fpsText: String) {
        val activeList = if (currentPosition == "left") leftViews else rightViews
        for (tv in activeList) {
            if (tv.parent == null) continue
            (tv as? TextView)?.text = fpsText
            if (isEnabled) {
                tv.visibility = View.VISIBLE
            }
            applyStyleToView(tv)
            syncColorWithClock(tv, null)
        }
    }

    private fun startSamplingWorker() {
        if (workerThread != null) return
        val thread = HandlerThread("HyperFpsStatusBarWorker").apply { start() }
        workerThread = thread
        workerHandler = object : Handler(thread.looper) {
            private var lastFrames: Long = -1L
            private var lastNanos: Long = SystemClock.elapsedRealtimeNanos()

            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_WORKER_TICK) {
                    processTick()
                    removeMessages(MSG_WORKER_TICK)
                    sendEmptyMessageDelayed(MSG_WORKER_TICK, STATUSBAR_POLL_INTERVAL_MS)
                }
            }

            private fun processTick() {
                val sampleView = leftViews.firstOrNull() ?: rightViews.firstOrNull()
                val context = sampleView?.context
                val powerManager = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isScreenOn = powerManager?.isInteractive ?: true

                if (!isEnabled || !isScreenOn) {
                    return
                }

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastOverlaySyncTime < 3500L) {
                    // Floating overlay is running and actively broadcasting synchronized FPS every 2s.
                    // Skip redundant dumpsys to preserve resources and maintain lockstep sync.
                    return
                }

                ensureTimestatsEnabled()

                try {
                    val currentFrames = readTotalFrames()
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (currentFrames != null && lastFrames >= 0) {
                        val deltaFrames = currentFrames - lastFrames
                        val deltaSecs = (now - lastNanos) / 1e9
                        if (deltaSecs > 0 && deltaFrames >= 0) {
                            val fps = deltaFrames / deltaSecs
                            val fpsLabel = "%.0f".format(fps)
                            mainHandler.post { updateFpsText(fpsLabel) }
                        }
                    }
                    if (currentFrames != null) {
                        lastFrames = currentFrames
                    }
                    lastNanos = now
                } catch (e: Exception) {
                    Log.e(TAG, "StatusBarFpsHook worker error", e)
                }
            }
        }
        workerHandler?.sendEmptyMessage(MSG_WORKER_TICK)
    }

    private fun ensureTimestatsEnabled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTimestatsEnableTime > 20000L) {
            lastTimestatsEnableTime = now
            runCatching {
                ProcessBuilder("dumpsys", "SurfaceFlinger", "--timestats", "-enable").start().waitFor()
            }
        }
    }

    private fun readTotalFrames(): Long? {
        return try {
            val proc = ProcessBuilder("dumpsys", "SurfaceFlinger", "--timestats", "-dump").start()
            val reader = proc.inputStream.bufferedReader()
            var line: String?
            var frames: Long? = null
            while (reader.readLine().also { line = it } != null) {
                val m = FRAMES_RE.find(line!!)
                if (m != null) {
                    frames = m.groupValues[1].toLongOrNull()
                    break
                }
            }
            proc.destroy()
            frames
        } catch (_: Exception) {
            null
        }
    }

    // --- Helper Utilities ---

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    private fun hookMethod(
        module: XposedModule,
        method: Method,
        callback: (chain: XposedInterface.Chain) -> Any?
    ) {
        runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                callback(chain)
            }
        }.onFailure {
            Log.w(TAG, "Failed hooking method ${method.declaringClass.name}#${method.name}: ${it.message}")
        }
    }

    private fun getField(obj: Any, fieldName: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val f = runCatching { cls.getDeclaredField(fieldName).apply { isAccessible = true } }.getOrNull()
            if (f != null) return f.get(obj)
            cls = cls.superclass
        }
        return null
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val f = runCatching { cls.getDeclaredField(fieldName).apply { isAccessible = true } }.getOrNull()
            if (f != null) {
                f.set(obj, value)
                return
            }
            cls = cls.superclass
        }
    }

    private fun callMethod(
        obj: Any,
        methodName: String,
        vararg args: Any?
    ): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val m = runCatching {
                cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.apply { isAccessible = true }
            }.getOrNull()
            if (m != null) return runCatching { m.invoke(obj, *args) }.getOrNull()
            cls = cls.superclass
        }
        return null
    }
}
