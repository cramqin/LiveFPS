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

        // 1. Read initial preferences
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
        runCatching {
            val remotePrefs = module.getRemotePreferences("hyperfps_prefs")
            if (remotePrefs != null) {
                isEnabled = remotePrefs.getBoolean("statusbar_enabled", false)
                currentPosition = remotePrefs.getString("statusbar_position", "right") ?: "right"
                statusBarFontSize = remotePrefs.getFloat("statusbar_font_size", 11f)
                statusBarBold = remotePrefs.getBoolean("statusbar_bold", true)
                statusBarMarginLeft = remotePrefs.getInt("statusbar_margin_left", 1)
                statusBarMarginRight = remotePrefs.getInt("statusbar_margin_right", 1)
                statusBarVerticalOffset = remotePrefs.getInt("statusbar_vertical_offset", 0)
                statusBarFont = remotePrefs.getString("statusbar_font", "default") ?: "default"
                statusBarCustomFontPath = remotePrefs.getString("statusbar_custom_font_path", "") ?: ""
                if (isEnabled) lastTimestatsEnableTime = 0L
                Log.i(TAG, "Loaded remote prefs: isEnabled=$isEnabled, pos=$currentPosition, size=$statusBarFontSize, bold=$statusBarBold, font=$statusBarFont")
            }
        }.onFailure {
            Log.w(TAG, "Could not load remote prefs: ${it.message}")
        }
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
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_CONFIG_CHANGED -> {
                        val prevEnabled = isEnabled
                        isEnabled = intent.getBooleanExtra("enabled", isEnabled)
                        currentPosition = intent.getStringExtra("position") ?: currentPosition
                        statusBarFontSize = intent.getFloatExtra("font_size", statusBarFontSize)
                        statusBarBold = intent.getBooleanExtra("bold", statusBarBold)
                        statusBarMarginLeft = intent.getIntExtra("margin_left", statusBarMarginLeft)
                        statusBarMarginRight = intent.getIntExtra("margin_right", statusBarMarginRight)
                        statusBarVerticalOffset = intent.getIntExtra("vertical_offset", statusBarVerticalOffset)
                        statusBarFont = intent.getStringExtra("font") ?: statusBarFont
                        statusBarCustomFontPath = intent.getStringExtra("custom_font_path") ?: statusBarCustomFontPath
                        if (isEnabled && !prevEnabled) {
                            lastTimestatsEnableTime = 0L
                        }
                        Log.i(TAG, "Broadcast received: isEnabled=$isEnabled, pos=$currentPosition, size=$statusBarFontSize, bold=$statusBarBold, font=$statusBarFont")
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
                    val clockView = (getField(controller, "clock") as? View) ?: (getField(controller, FIELD_M_CLOCK_VIEW) as? View)
                    val startSide = getField(controller, "startSideContainer") as? ViewGroup
                    val endSide = getField(controller, "endSideContainer") as? ViewGroup
                    val context = clockView?.context ?: startSide?.context ?: endSide?.context

                    if (context != null) {
                        registerReceiverWithContext(context)
                        val leftContainer = startSide ?: (clockView?.parent as? ViewGroup)
                        injectToLeftContainer(leftViews, context, leftContainer, clockView)

                        val rootView = leftContainer?.rootView ?: endSide?.rootView
                        val batteryId = context.resources.getIdentifier(ID_BATTERY, "id", PKG_SYSTEMUI)
                        val batteryView = if (batteryId != 0) rootView?.findViewById<View>(batteryId) else null
                        val rightContainer = (batteryView?.parent as? ViewGroup) ?: endSide
                        injectToRightContainer(rightViews, context, rightContainer)
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

                        val batteryId = resIds.getIdentifier(ID_BATTERY, "id", PKG_SYSTEMUI)
                        val batteryView = if (batteryId != 0 && rootView != null) rootView.findViewById<View>(batteryId) else null
                        val rightContainer = batteryView?.parent as? ViewGroup
                        injectToRightContainer(rightViews, context, rightContainer)
                    }
                    res
                }
            }
        }

        // 3. MiuiPhoneStatusBarView / PhoneStatusBarView
        for (vName in listOf("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", "com.android.systemui.statusbar.phone.PhoneStatusBarView")) {
            val vCls = findClass(vName, classLoader) ?: continue
            for (m in vCls.declaredMethods.filter { (it.name == "onFinishInflate" || it.name == "onAttachedToWindow") && it.parameterTypes.isEmpty() }) {
                hookMethod(module, m) { chain ->
                    val res = chain.proceed()
                    val view = chain.thisObject as? ViewGroup
                    if (view != null) {
                        val context = view.context
                        registerReceiverWithContext(context)
                        val resIds = context.resources
                        val clockId = resIds.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                        val clockView = if (clockId != 0) view.findViewById<View>(clockId) else null
                        val leftContainer = clockView?.parent as? ViewGroup
                        injectToLeftContainer(leftViews, context, leftContainer, clockView)

                        val batteryId = resIds.getIdentifier(ID_BATTERY, "id", PKG_SYSTEMUI)
                        val batteryView = if (batteryId != 0) view.findViewById<View>(batteryId) else null
                        val rightContainer = batteryView?.parent as? ViewGroup
                        injectToRightContainer(rightViews, context, rightContainer)
                    }
                    res
                }
            }
        }
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
        targetContainer: ViewGroup?
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

        container.addView(fpsView, 0)
        targetList.add(fpsView)
        syncColorWithClock(fpsView)
        Log.i(TAG, "Injected $TAG_RIGHT into right container $container at index 0")
        updateVisibilities()
    }

    private fun createFpsTextView(context: Context, lp: ViewGroup.LayoutParams): TextView {
        val tv = TextView(context).apply {
            layoutParams = lp
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            typeface = resolveStatusBarTypeface()
            setTextColor(Color.WHITE)
            alpha = 1.0f
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

    private fun syncColorWithClock(view: View, clockView: TextView? = null) {
        val tv = view as? TextView ?: return
        val clock = clockView ?: findCachedClock(tv)
        tv.typeface = resolveStatusBarTypeface()
        if (clock != null) {
            val colors = clock.textColors
            if (colors != null) {
                tv.setTextColor(colors)
            } else {
                tv.setTextColor(clock.currentTextColor)
            }
            val clockAlpha = clock.alpha
            if (Math.abs(tv.alpha - clockAlpha) > 0.01f) {
                tv.alpha = clockAlpha
            }
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
            if (clock == null) {
                clock = iconView.rootView?.findViewById<TextView>(clockId)
            }
            if (clock != null) {
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
            }
            for (tv in rightViews) {
                val show = isEnabled && currentPosition == "right"
                tv.visibility = if (show) View.VISIBLE else View.GONE
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
