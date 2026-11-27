package dev.livefps.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    @Volatile private var receiverRegistered = false

    @Volatile private var isEnabled = false
    @Volatile private var currentPosition = "right"

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // 5. Setup Status Bar containers (Left near clock & Right near battery)
        setupStatusBarContainers(module, nsvCls, classLoader)

        // 6. Setup MiuiClock color sync
        setupClockColorSync(module, classLoader)

        // 7. Start background sampling worker (fixed 2.0s interval)
        startSamplingWorker()
    }

    private fun loadInitialPrefs(module: XposedModule) {
        runCatching {
            val remotePrefs = module.getRemotePreferences("livefps_prefs")
            if (remotePrefs != null) {
                isEnabled = remotePrefs.getBoolean("statusbar_enabled", false)
                currentPosition = remotePrefs.getString("statusbar_position", "right") ?: "right"
                Log.i(TAG, "Loaded remote prefs: isEnabled=$isEnabled, pos=$currentPosition")
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
        val filter = IntentFilter(ACTION_CONFIG_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CONFIG_CHANGED) {
                    isEnabled = intent.getBooleanExtra("enabled", isEnabled)
                    currentPosition = intent.getStringExtra("position") ?: currentPosition
                    Log.i(TAG, "Broadcast received: isEnabled=$isEnabled, pos=$currentPosition")
                    updateVisibilities()
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
            Log.i(TAG, "Broadcast receiver registered for $ACTION_CONFIG_CHANGED")
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
                    syncColorWithClock(nsView)
                }
                res
            }
        }
    }

    private fun setupStatusBarContainers(module: XposedModule, nsvCls: Class<*>, classLoader: ClassLoader) {
        // 1. PhoneStatusBarViewController.onViewAttached
        val controllerCls = findClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader)
        if (controllerCls != null) {
            for (m in controllerCls.declaredMethods.filter { it.name == "onViewAttached" && it.parameterTypes.isEmpty() }) {
                hookMethod(module, m) { chain ->
                    val res = chain.proceed()
                    val controller = chain.thisObject
                    val clockView = (getField(controller, "clock") as? View) ?: (getField(controller, FIELD_M_CLOCK_VIEW) as? View)
                    val batteryView = getField(controller, "battery") as? View
                    val startSide = getField(controller, "startSideContainer") as? ViewGroup
                    val endSide = getField(controller, "endSideContainer") as? ViewGroup
                    val darkDispatcher = getField(controller, "darkIconDispatcher")
                    val context = clockView?.context ?: startSide?.context ?: batteryView?.context ?: endSide?.context

                    if (context != null) {
                        registerReceiverWithContext(context)
                        // Left side near clock
                        val leftContainer = startSide ?: (clockView?.parent as? ViewGroup)
                        injectToContainer(leftViews, nsvCls, context, leftContainer, clockView, darkDispatcher, classLoader, TAG_LEFT, true)

                        // Right side near battery
                        val rightContainer = endSide ?: (batteryView?.parent as? ViewGroup)
                        injectToContainer(rightViews, nsvCls, context, rightContainer, batteryView, darkDispatcher, classLoader, TAG_RIGHT, false)
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
                        val batteryId = resIds.getIdentifier(ID_BATTERY, "id", PKG_SYSTEMUI)
                        val batteryView = if (batteryId != 0 && rootView != null) rootView.findViewById<View>(batteryId) else null

                        // Left
                        val leftContainer = clockView?.parent as? ViewGroup
                        injectToContainer(leftViews, nsvCls, context, leftContainer, clockView, null, classLoader, TAG_LEFT, true)

                        // Right
                        val rightContainer = (batteryView?.parent as? ViewGroup) ?: (rootView as? ViewGroup)?.let { findRightContainer(it) }
                        injectToContainer(rightViews, nsvCls, context, rightContainer, batteryView, null, classLoader, TAG_RIGHT, false)
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
                        val batteryId = resIds.getIdentifier(ID_BATTERY, "id", PKG_SYSTEMUI)
                        val batteryView = if (batteryId != 0) view.findViewById<View>(batteryId) else null

                        // Left
                        val leftContainer = clockView?.parent as? ViewGroup
                        injectToContainer(leftViews, nsvCls, context, leftContainer, clockView, null, classLoader, TAG_LEFT, true)

                        // Right
                        val rightContainer = findRightContainer(view) ?: (batteryView?.parent as? ViewGroup)
                        injectToContainer(rightViews, nsvCls, context, rightContainer, batteryView, null, classLoader, TAG_RIGHT, false)
                    }
                    res
                }
            }
        }
    }

    private fun findRightContainer(root: ViewGroup): ViewGroup? {
        val sysAreaField = getField(root, "mSystemIconArea") as? ViewGroup
        if (sysAreaField != null) return sysAreaField

        val res = root.resources
        for (name in listOf("end_side_container", "system_icons", "system_icon_area", "status_bar_icons", "statusIcons")) {
            val id = res.getIdentifier(name, "id", PKG_SYSTEMUI)
            if (id != 0) {
                val v = root.findViewById<ViewGroup>(id)
                if (v != null) return v
            }
        }
        return null
    }

    private fun injectToContainer(
        targetList: CopyOnWriteArrayList<View>,
        nsvCls: Class<*>,
        context: Context,
        targetContainer: ViewGroup?,
        anchorView: View?,
        providedDarkDispatcher: Any?,
        classLoader: ClassLoader,
        viewTag: String,
        isLeft: Boolean
    ) {
        val container = targetContainer ?: return
        val existing = container.findViewWithTag<View>(viewTag)
        if (existing != null) {
            if (!targetList.contains(existing)) targetList.add(existing)
            syncColorWithClock(existing)
            return
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val iconView = createStatusbarTextIcon(nsvCls, context, lp).apply { tag = viewTag }

        val index = if (anchorView != null && anchorView.parent === container) {
            if (isLeft) {
                (container.indexOfChild(anchorView) + 1).coerceIn(0, container.childCount) // after clock
            } else {
                container.indexOfChild(anchorView).coerceIn(0, container.childCount) // right before battery
            }
        } else {
            if (isLeft) container.childCount else 0
        }
        container.addView(iconView, index)
        targetList.add(iconView)
        syncColorWithClock(iconView)
        Log.i(TAG, "Injected $viewTag into container $container at index $index")

        val darkDispatcher = providedDarkDispatcher ?: runCatching {
            val darkCls = findClass("com.android.systemui.plugins.DarkIconDispatcher", classLoader)
            val depCls = findClass("com.android.systemui.Dependency", classLoader)
            if (darkCls != null && depCls != null) {
                depCls.getMethod("get", Class::class.java).invoke(null, darkCls)
            } else null
        }.getOrNull()

        if (darkDispatcher != null) {
            runCatching {
                callMethod(darkDispatcher, METHOD_ADD_DARK_RECEIVER, iconView)
            }
        }
        updateVisibilities()
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
        val styleId = res.getIdentifier(STYLE_NETWORK_SPEED_NUMBER, "style", PKG_SYSTEMUI)
            .takeIf { it != 0 }
            ?: res.getIdentifier(STYLE_CLOCK, "style", PKG_SYSTEMUI)
        if (styleId != 0) {
            runCatching { number.setTextAppearance(styleId) }
        }
        syncColorWithClock(iconView)
        val dp = res.displayMetrics.density
        number.setPaddingRelative((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)

        return iconView
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
        if (number != null && clock != null) {
            val colors = clock.textColors
            if (colors != null) {
                number.setTextColor(colors)
            }
            val clockAlpha = clock.alpha
            if (Math.abs(iconView.alpha - clockAlpha) > 0.01f) {
                iconView.alpha = clockAlpha
            }
        }
    }

    private fun findCachedClock(iconView: View): TextView? {
        val container = iconView.parent as? ViewGroup ?: return null
        synchronized(clockCache) {
            clockCache[iconView]?.get()?.let { cached ->
                if (cached.parent === container) return cached
                clockCache.remove(iconView)
            }
            val clockId = container.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
            val clock = if (clockId != 0) container.findViewById<TextView>(clockId) else null
            if (clock != null) {
                clockCache[iconView] = WeakReference(clock)
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
