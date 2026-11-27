package dev.livefps.hook

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
 * Injects a real-time FPS readout into HyperOS Status Bar using NetworkSpeedView.
 *
 * Direct container injection for both Left and Right side:
 * - Left side (near Clock): PhoneStatusBarViewController.startSideContainer or clock.parent
 * - Right side (near Battery / System icons): PhoneStatusBarViewController.endSideContainer or battery.parent
 * - Zero conflict with HyperCeiler (does not hijack StatusBarIconList or IconManager slots)
 * - Real-time switching (left/right) and toggle (on/off) via broadcast `dev.livefps.ACTION_CONFIG_CHANGED`
 * - Dynamic color synchronization with MiuiClock (dark mode / light mode adaptive)
 * - Fixed 2.0s polling rate (independent of overlay polling rate)
 * - Monotonic SurfaceFlinger timestats delta sampling (zero conflict with overlay)
 */
object StatusBarFpsHook {

    private const val TAG = "LiveFPS"
    private const val ACTION_CONFIG_CHANGED = "dev.livefps.ACTION_CONFIG_CHANGED"
    private const val ACTION_FPS_UPDATE = "dev.livefps.ACTION_FPS_UPDATE"
    private const val MSG_WORKER_TICK = 200021
    private const val STATUSBAR_POLL_INTERVAL_MS = 2000L // Fixed 2.0s rate matching HyperCeiler

    private const val TAG_LEFT = "livefps_statusbar_left"
    private const val TAG_RIGHT = "livefps_statusbar_right"
    private const val TAG_NETWORK_SPEED_NUMBER = "network_speed_number"
    private const val TAG_NETWORK_SPEED_UNIT = "network_speed_unit"

    private const val FIELD_CONTAINER = "mContainer"
    private const val FIELD_NETWORK_SPEED_NUMBER_TEXT = "mNetworkSpeedNumberText"
    private const val FIELD_NETWORK_SPEED_UNIT_TEXT = "mNetworkSpeedUnitText"
    private const val FIELD_VISIBLE_BY_CONTROLLER = "mVisibleByController"
    private const val FIELD_M_CLOCK_VIEW = "mClockView"

    private const val METHOD_SET_VISIBILITY_BY_CONTROLLER = "setVisibilityByController"
    private const val METHOD_SET_BLOCKED = "setBlocked"
    private const val METHOD_SET_NETWORK_SPEED = "setNetworkSpeed"
    private const val METHOD_ADD_DARK_RECEIVER = "addDarkReceiver"

    private const val PKG_SYSTEMUI = "com.android.systemui"
    private const val ID_CLOCK = "clock"
    private const val ID_BATTERY = "battery"
    private const val STYLE_NETWORK_SPEED_NUMBER = "TextAppearance.StatusBar.NetWorkSpeedNumber"
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
    @Volatile private var statusBarMarginLeft = 3
    @Volatile private var statusBarMarginRight = 3
    @Volatile private var statusBarVerticalOffset = 0

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastTimestatsEnableTime = 0L
    @Volatile private var lastOverlaySyncTime = 0L

    private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

    fun init(module: XposedModule, classLoader: ClassLoader) {
        Log.i(TAG, "StatusBarFpsHook initializing (direct container injection)")

        // 1. Read initial preferences
        loadInitialPrefs(module)

        // 2. Early broadcast receiver registration via Application.onCreate
        setupApplicationHook(module, classLoader)

        // 3. Find NetworkSpeedView class
        val nsvCls = findClass("com.android.systemui.statusbar.views.NetworkSpeedView", classLoader)
        if (nsvCls == null) {
            Log.e(TAG, "NetworkSpeedView class not found in SystemUI")
            return
        }

        // 4. Setup NetworkSpeedView hook behaviors (getSlot, setVisibleState, dark tint)
        setupNetworkSpeedViewHooks(module, nsvCls)

        // 5. Setup Status Bar containers (Left near clock)
        setupStatusBarLeftContainers(module, nsvCls, classLoader)

        // 6. Setup Status Bar right container via IconManager (placed directly to left of HyperCeiler)
        setupIconManagerHooks(module, nsvCls, classLoader)

        // 7. Setup MiuiClock color sync
        setupClockColorSync(module, classLoader)

        // 8. Start background sampling worker (fixed 2.0s interval, autonomous timestats)
        startSamplingWorker()
    }

    private fun loadInitialPrefs(module: XposedModule) {
        runCatching {
            val remotePrefs = module.getRemotePreferences("livefps_prefs")
            if (remotePrefs != null) {
                isEnabled = remotePrefs.getBoolean("statusbar_enabled", false)
                currentPosition = remotePrefs.getString("statusbar_position", "right") ?: "right"
                statusBarFontSize = remotePrefs.getFloat("statusbar_font_size", 11f)
                statusBarBold = remotePrefs.getBoolean("statusbar_bold", true)
                statusBarMarginLeft = remotePrefs.getInt("statusbar_margin_left", 3)
                statusBarMarginRight = remotePrefs.getInt("statusbar_margin_right", 3)
                statusBarVerticalOffset = remotePrefs.getInt("statusbar_vertical_offset", 0)
                if (isEnabled) lastTimestatsEnableTime = 0L
                Log.i(TAG, "Loaded remote prefs: isEnabled=$isEnabled, pos=$currentPosition, size=$statusBarFontSize, bold=$statusBarBold")
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
                        if (isEnabled && !prevEnabled) {
                            lastTimestatsEnableTime = 0L
                        }
                        Log.i(TAG, "Broadcast received: isEnabled=$isEnabled, pos=$currentPosition, size=$statusBarFontSize, bold=$statusBarBold")
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
                            val ackIntent = Intent("dev.livefps.ACTION_STATUSBAR_ACK").apply {
                                setPackage("dev.livefps")
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

    private fun setupNetworkSpeedViewHooks(module: XposedModule, nsvCls: Class<*>) {
        // getSlot -> "livefps_slot" for our own views
        for (m in nsvCls.declaredMethods.filter { it.name == "getSlot" && it.parameterTypes.isEmpty() }) {
            hookMethod(module, m) { chain ->
                val nsView = chain.thisObject as? View
                if (nsView?.tag == TAG_LEFT || nsView?.tag == TAG_RIGHT) {
                    "livefps_slot"
                } else {
                    chain.proceed()
                }
            }
        }

        // setVisibleState -> keep number text visible when state != 2
        for (m in nsvCls.declaredMethods.filter { it.name == "setVisibleState" }) {
            hookMethod(module, m) { chain ->
                val nsView = chain.thisObject as? View
                if (nsView?.tag == TAG_LEFT || nsView?.tag == TAG_RIGHT) {
                    val state = chain.args.getOrNull(0) as? Int ?: 0
                    val visible = state != 2
                    val number = (getField(nsView, FIELD_NETWORK_SPEED_NUMBER_TEXT) as? TextView) ?: (nsView as? TextView)
                    number?.visibility = if (visible) View.VISIBLE else View.GONE
                    null
                } else {
                    chain.proceed()
                }
            }
        }

        // Dark tint changes -> sync color with clock
        for (m in nsvCls.declaredMethods.filter { it.name in listOf("onDarkChanged", "onLightDarkTintChanged", "onDarkChangedWithContrast") }) {
            hookMethod(module, m) { chain ->
                val res = chain.proceed()
                val nsView = chain.thisObject as? View
                if (nsView?.tag == TAG_LEFT || nsView?.tag == TAG_RIGHT) {
                    val tint = chain.args.filterIsInstance<Int>().firstOrNull()
                    val number = (getField(nsView, FIELD_NETWORK_SPEED_NUMBER_TEXT) as? TextView) ?: (nsView as? TextView)
                    if (tint != null && number != null) {
                        number.setTextColor(tint)
                    }
                    syncColorWithClock(nsView)
                }
                res
            }
        }
    }

    private fun setupStatusBarLeftContainers(module: XposedModule, nsvCls: Class<*>, classLoader: ClassLoader) {
        // 1. PhoneStatusBarViewController.onViewAttached
        val controllerCls = findClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader)
        if (controllerCls != null) {
            for (m in controllerCls.declaredMethods.filter { it.name == "onViewAttached" && it.parameterTypes.isEmpty() }) {
                hookMethod(module, m) { chain ->
                    val res = chain.proceed()
                    val controller = chain.thisObject
                    val clockView = (getField(controller, "clock") as? View) ?: (getField(controller, FIELD_M_CLOCK_VIEW) as? View)
                    val startSide = getField(controller, "startSideContainer") as? ViewGroup
                    val darkDispatcher = getField(controller, "darkIconDispatcher")
                    val context = clockView?.context ?: startSide?.context

                    if (context != null) {
                        registerReceiverWithContext(context)
                        val leftContainer = startSide ?: (clockView?.parent as? ViewGroup)
                        injectToLeftContainer(leftViews, nsvCls, context, leftContainer, clockView, darkDispatcher, classLoader)
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
                        injectToLeftContainer(leftViews, nsvCls, context, leftContainer, clockView, null, classLoader)
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
                        injectToLeftContainer(leftViews, nsvCls, context, leftContainer, clockView, null, classLoader)
                    }
                    res
                }
            }
        }
    }

    private fun injectToLeftContainer(
        targetList: CopyOnWriteArrayList<View>,
        nsvCls: Class<*>,
        context: Context,
        targetContainer: ViewGroup?,
        clockView: View?,
        providedDarkDispatcher: Any?,
        classLoader: ClassLoader
    ) {
        val container = targetContainer ?: return
        val existing = container.findViewWithTag<View>(TAG_LEFT)
        if (existing != null) {
            if (!targetList.contains(existing)) targetList.add(existing)
            syncColorWithClock(existing)
            return
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val iconView = createStatusbarTextIcon(nsvCls, context, lp).apply { tag = TAG_LEFT }

        val index = if (clockView != null && clockView.parent === container) {
            (container.indexOfChild(clockView) + 1).coerceIn(0, container.childCount)
        } else {
            container.childCount
        }
        container.addView(iconView, index)
        targetList.add(iconView)
        syncColorWithClock(iconView)
        Log.i(TAG, "Injected $TAG_LEFT into left container $container at index $index")

        val darkDispatcher = providedDarkDispatcher ?: runCatching {
            val darkCls = findClass("com.android.systemui.plugins.DarkIconDispatcher", classLoader)
            val depCls = findClass("com.android.systemui.Dependency", classLoader)
            if (darkCls != null && depCls != null) {
                depCls.getMethod("get", Class::class.java).invoke(null, darkCls)
            } else null
        }.getOrNull()

        if (darkDispatcher != null) {
            runCatching { callMethod(darkDispatcher, METHOD_ADD_DARK_RECEIVER, iconView) }
        }
        updateVisibilities()
    }

    private fun setupIconManagerHooks(module: XposedModule, nsvCls: Class<*>, classLoader: ClassLoader) {
        val iconManagerClasses = listOf(
            "com.android.systemui.statusbar.phone.ui.IconManager",
            "com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager"
        )

        for (clsName in iconManagerClasses) {
            val imCls = findClass(clsName, classLoader) ?: continue
            Log.i(TAG, "Found IconManager class: $clsName")

            // 1. Hook constructors
            for (ctor in imCls.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    module.hook(ctor).intercept { chain ->
                        val res = chain.proceed()
                        attachRightFpsToIconManager(chain.thisObject, nsvCls, classLoader)
                        res
                    }
                }
            }

            // 2. Hook onIconAdded & addHolder
            for (mName in listOf("onIconAdded", "addHolder")) {
                for (m in imCls.declaredMethods.filter { it.name == mName }) {
                    hookMethod(module, m) { chain ->
                        val res = chain.proceed()
                        attachRightFpsToIconManager(chain.thisObject, nsvCls, classLoader)
                        res
                    }
                }
            }
        }
    }

    private fun attachRightFpsToIconManager(iconManager: Any, nsvCls: Class<*>, classLoader: ClassLoader) {
        val group = getField(iconManager, "mGroup") as? ViewGroup ?: return
        val location = getField(iconManager, "mLocation")
        // Exclude Quick Settings / Control Center icon manager
        if (location != null && location.toString().contains("QS")) return

        val context = group.context ?: return
        registerReceiverWithContext(context)

        var fpsView = group.findViewWithTag<View>(TAG_RIGHT)
        if (fpsView == null) {
            val lp = (callMethod(iconManager, "onCreateLayoutParams") as? ViewGroup.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            fpsView = createStatusbarTextIcon(nsvCls, context, lp).apply { tag = TAG_RIGHT }
            val targetIndex = computeRightTargetIndex(group, null)
            group.addView(fpsView, targetIndex)
            if (!rightViews.contains(fpsView)) rightViews.add(fpsView)
            Log.i(TAG, "Attached LiveFPS to IconManager.mGroup at index $targetIndex of ${group.childCount}")

            runCatching {
                val darkCls = findClass("com.android.systemui.plugins.DarkIconDispatcher", classLoader)
                val depCls = findClass("com.android.systemui.Dependency", classLoader)
                if (darkCls != null && depCls != null) {
                    val darkDispatcher = depCls.getMethod("get", Class::class.java).invoke(null, darkCls)
                    if (darkDispatcher != null) {
                        callMethod(darkDispatcher, METHOD_ADD_DARK_RECEIVER, fpsView)
                    }
                }
            }
        } else {
            if (!rightViews.contains(fpsView)) rightViews.add(fpsView)
            val currentIdx = group.indexOfChild(fpsView)
            val expectedIdx = computeRightTargetIndex(group, fpsView)
            if (expectedIdx != -1 && currentIdx != expectedIdx) {
                group.removeView(fpsView)
                val newTarget = computeRightTargetIndex(group, null)
                group.addView(fpsView, newTarget.coerceIn(0, group.childCount))
                Log.i(TAG, "Re-aligned LiveFPS in IconManager.mGroup from $currentIdx to $newTarget")
            }
        }

        syncColorWithClock(fpsView)
        updateVisibilities()
    }

    private fun computeRightTargetIndex(group: ViewGroup, selfView: View? = null): Int {
        // 1. HyperCeiler battery info view uses tag "slot_text_icon"
        val hyperView = group.findViewWithTag<View>("slot_text_icon")
        if (hyperView != null) {
            val hIdx = group.indexOfChild(hyperView)
            return if (selfView != null && group.indexOfChild(selfView) < hIdx) {
                hIdx - 1
            } else {
                hIdx
            }
        }

        // 2. Fallback: place right after network_speed view if present
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c !== selfView && (c.tag == "network_speed" || c.javaClass.simpleName == "NetworkSpeedView")) {
                val netIdx = group.indexOfChild(c)
                return if (selfView != null && group.indexOfChild(selfView) <= netIdx) netIdx else netIdx + 1
            }
        }

        // 3. Fallback: beginning of mGroup (index 0)
        return 0
    }

    private fun createStatusbarTextIcon(
        nsvCls: Class<*>,
        context: Context,
        lp: ViewGroup.LayoutParams
    ): View {
        val constructors = nsvCls.declaredConstructors
        val constructor = constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java }
            ?: constructors.firstOrNull { it.parameterTypes.size == 2 }
            ?: constructors.first()
        constructor.isAccessible = true

        val iconView = if (constructor.parameterTypes.size == 1) {
            constructor.newInstance(context) as ViewGroup
        } else {
            constructor.newInstance(context, null) as ViewGroup
        }

        iconView.layoutParams = lp

        val number = TextView(context).apply {
            tag = TAG_NETWORK_SPEED_NUMBER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            isSingleLine = true
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            alpha = 1.0f
        }
        iconView.addView(number)

        val unit = TextView(context).apply {
            tag = TAG_NETWORK_SPEED_UNIT
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0)
            visibility = View.GONE
            isSingleLine = true
        }
        iconView.addView(unit)

        setField(iconView, FIELD_CONTAINER, number)
        setField(iconView, FIELD_NETWORK_SPEED_NUMBER_TEXT, number)
        setField(iconView, FIELD_NETWORK_SPEED_UNIT_TEXT, unit)
        setField(iconView, FIELD_VISIBLE_BY_CONTROLLER, true)

        runCatching {
            iconView.javaClass.declaredMethods.filter { it.name.startsWith("updateResources") }.forEach {
                it.isAccessible = true
                it.invoke(iconView)
            }
        }

        runCatching { callMethod(iconView, METHOD_SET_BLOCKED, false) }

        val res = context.resources
        val clockStyleId = res.getIdentifier(STYLE_CLOCK, "style", PKG_SYSTEMUI)
        val netStyleId = res.getIdentifier(STYLE_NETWORK_SPEED_NUMBER, "style", PKG_SYSTEMUI)
        val styleId = if (clockStyleId != 0) clockStyleId else netStyleId
        if (styleId != 0) {
            runCatching { number.setTextAppearance(styleId) }
        }
        applyStyleToView(iconView)
        syncColorWithClock(iconView)

        return iconView
    }

    private fun applyStyleAndLayout() {
        mainHandler.post {
            for (v in leftViews) applyStyleToView(v)
            for (v in rightViews) applyStyleToView(v)
        }
    }

    private fun applyStyleToView(iconView: View) {
        val number = (getField(iconView, FIELD_NETWORK_SPEED_NUMBER_TEXT) as? TextView) ?: (iconView as? TextView)
        val density = iconView.resources?.displayMetrics?.density ?: 2.75f

        if (number != null) {
            number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, statusBarFontSize)
            number.typeface = if (statusBarBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            number.translationY = statusBarVerticalOffset * density
        }

        val lp = iconView.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val leftPx = (statusBarMarginLeft * density).toInt()
            val rightPx = (statusBarMarginRight * density).toInt()
            if (lp.leftMargin != leftPx || lp.rightMargin != rightPx) {
                lp.leftMargin = leftPx
                lp.rightMargin = rightPx
                iconView.layoutParams = lp
            }
        }
        iconView.requestLayout()
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

    private fun syncColorWithClock(iconView: View, clockView: TextView? = null) {
        val clock = clockView ?: findCachedClock(iconView)
        val number = (getField(iconView, FIELD_NETWORK_SPEED_NUMBER_TEXT) as? TextView) ?: (iconView as? TextView)
        if (number != null) {
            number.typeface = if (statusBarBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (clock != null) {
                val colors = clock.textColors
                if (colors != null) {
                    number.setTextColor(colors)
                } else {
                    number.setTextColor(clock.currentTextColor)
                }
                val clockAlpha = clock.alpha
                if (Math.abs(number.alpha - clockAlpha) > 0.01f) {
                    number.alpha = clockAlpha
                }
                if (Math.abs(iconView.alpha - clockAlpha) > 0.01f) {
                    iconView.alpha = clockAlpha
                }
            } else {
                number.setTextColor(Color.WHITE)
                number.alpha = 1.0f
                iconView.alpha = 1.0f
            }
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
            leftViews.removeAll { !it.isAttachedToWindow }
            rightViews.removeAll { !it.isAttachedToWindow }

            for (tv in leftViews) {
                val show = isEnabled && currentPosition == "left"
                runCatching {
                    callMethod(tv, METHOD_SET_VISIBILITY_BY_CONTROLLER, show)
                }.onFailure {
                    tv.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
            for (tv in rightViews) {
                val show = isEnabled && currentPosition == "right"
                runCatching {
                    callMethod(tv, METHOD_SET_VISIBILITY_BY_CONTROLLER, show)
                }.onFailure {
                    tv.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun updateFpsText(fpsText: String) {
        val activeList = if (currentPosition == "left") leftViews else rightViews
        for (tv in activeList) {
            runCatching {
                callMethod(tv, METHOD_SET_NETWORK_SPEED, fpsText, "")
            }.onFailure {
                val num = (getField(tv, FIELD_NETWORK_SPEED_NUMBER_TEXT) as? TextView) ?: (tv as? TextView)
                num?.text = fpsText
            }
            applyStyleToView(tv)
            syncColorWithClock(tv, null)
        }
    }

    private fun startSamplingWorker() {
        if (workerThread != null) return
        val thread = HandlerThread("LiveFpsStatusBarWorker").apply { start() }
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
