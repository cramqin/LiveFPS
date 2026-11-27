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
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI Hook (libxposed API 102):
 * Injects a real-time FPS readout directly into HyperOS / Android Status Bar.
 *
 * Supported Status Bar containers:
 * - PhoneStatusBarViewController (startSideContainer & endSideContainer)
 * - MiuiCollapsedStatusBarFragment / CollapsedStatusBarFragment (views near clock & battery)
 * - MiuiPhoneStatusBarView / PhoneStatusBarView onAttachedToWindow & onFinishInflate
 * - HomeStatusBarViewBinderImpl.bind (HyperOS 2 pipeline)
 *
 * Features:
 * - Left side (near Clock) & Right side (system icons area / near Battery)
 * - Fixed default 2.0s (2000ms) polling rate matching HyperCeiler (independent of overlay)
 * - Real-time changes via broadcast: position (left/right) and toggle (on/off) without restarting SystemUI!
 * - Color synchronization with MiuiClock (dark mode / light mode adaptive)
 * - Zero battery drain when disabled or when screen is off (isInteractive)
 */
object StatusBarFpsHook {

    private const val TAG = "LiveFPS"
    private const val ACTION_CONFIG_CHANGED = "dev.livefps.ACTION_CONFIG_CHANGED"
    private const val TAG_LEFT = "livefps_statusbar_left"
    private const val TAG_RIGHT = "livefps_statusbar_right"
    private const val MSG_WORKER_TICK = 101
    private const val STATUSBAR_POLL_INTERVAL_MS = 2000L // Fixed 2.0s rate matching HyperCeiler

    private val leftViews = CopyOnWriteArrayList<TextView>()
    private val rightViews = CopyOnWriteArrayList<TextView>()
    private val clockCache = Collections.synchronizedMap(WeakHashMap<View, WeakReference<TextView>>())
    @Volatile private var globalClock: WeakReference<TextView>? = null
    @Volatile private var receiverRegistered = false

    @Volatile private var isEnabled = false
    @Volatile private var currentPosition = "right"

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

    fun init(module: XposedModule, classLoader: ClassLoader) {
        Log.i(TAG, "StatusBarFpsHook.init initializing")

        // 1. Load initial prefs from remote preferences
        loadInitialPrefs(module)

        // 2. Hook Application.onCreate for early broadcast receiver registration
        setupApplicationHook(module, classLoader)

        // 3. Setup Status Bar views (ViewController, Fragments, BarViews, Binders)
        setupStatusBarViews(module, classLoader)

        // 4. Setup Clock theme / dark mode syncing
        setupClockColorSync(module, classLoader)

        // 5. Start background sampling worker
        startSamplingWorker()
    }

    private fun loadInitialPrefs(module: XposedModule) {
        runCatching {
            val remotePrefs = module.getRemotePreferences("livefps_prefs")
            if (remotePrefs != null) {
                isEnabled = remotePrefs.getBoolean("statusbar_enabled", false)
                currentPosition = remotePrefs.getString("statusbar_position", "right") ?: "right"
                Log.i(TAG, "Loaded remote prefs: isEnabled=$isEnabled, position=$currentPosition")
            }
        }.onFailure {
            Log.w(TAG, "Could not load remote prefs: ${it.message}")
        }
    }

    private fun setupApplicationHook(module: XposedModule, classLoader: ClassLoader) {
        val appCls = findClass("android.app.Application", classLoader) ?: return
        hookAllMethods(module, appCls, "onCreate") { chain, _ ->
            val context = chain.thisObject as? Context ?: return@hookAllMethods
            registerReceiverWithContext(context)
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
            Log.i(TAG, "Registered broadcast receiver for $ACTION_CONFIG_CHANGED")
        }.onFailure {
            Log.e(TAG, "Failed to register broadcast receiver", it)
        }
    }

    private fun updateVisibilities() {
        Log.i(TAG, "updateVisibilities: isEnabled=$isEnabled, pos=$currentPosition, left=${leftViews.size}, right=${rightViews.size}")
        mainHandler.post {
            leftViews.removeAll { !it.isAttachedToWindow }
            rightViews.removeAll { !it.isAttachedToWindow }

            for (tv in leftViews) {
                tv.visibility = if (isEnabled && currentPosition == "left") View.VISIBLE else View.GONE
            }
            for (tv in rightViews) {
                tv.visibility = if (isEnabled && currentPosition == "right") View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupStatusBarViews(module: XposedModule, classLoader: ClassLoader) {
        // 1. PhoneStatusBarViewController.onViewAttached
        val controllerCls = findClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader)
        if (controllerCls != null) {
            hookAllMethods(module, controllerCls, "onViewAttached") { chain, _ ->
                val controller = chain.thisObject
                val clockView = (getField(controller, "clock") as? TextView)
                    ?: (getField(controller, "mClockView") as? TextView)
                val startSide = getField(controller, "startSideContainer") as? ViewGroup
                val endSide = getField(controller, "endSideContainer") as? ViewGroup
                val batteryView = getField(controller, "battery") as? View
                val rootView = getField(controller, "mView") as? ViewGroup

                val context = rootView?.context ?: clockView?.context ?: startSide?.context ?: return@hookAllMethods
                clockView?.let { globalClock = WeakReference(it) }
                registerReceiverWithContext(context)

                val leftContainer = startSide ?: (clockView?.parent as? ViewGroup)
                val rightContainer = endSide ?: (batteryView?.parent as? ViewGroup)
                injectIndicators(context, leftContainer, rightContainer, clockView, batteryView)
            }
        }

        // 2. CollapsedStatusBarFragment / MiuiCollapsedStatusBarFragment (HyperOS & AOSP)
        val fragmentNames = listOf(
            "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment",
            "com.android.systemui.statusbar.phone.CollapsedStatusBarFragment"
        )
        for (fName in fragmentNames) {
            val fCls = findClass(fName, classLoader) ?: continue
            for (mName in listOf("initMiuiViewsOnViewCreated", "onViewCreated")) {
                hookAllMethods(module, fCls, mName) { chain, _ ->
                    val fragment = chain.thisObject
                    val rootView = (chain.args.firstOrNull() as? View)
                        ?: runCatching { fragment.javaClass.getMethod("getView").invoke(fragment) as? View }.getOrNull()
                    val context = rootView?.context
                        ?: runCatching { fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context }.getOrNull()
                        ?: return@hookAllMethods

                    registerReceiverWithContext(context)
                    val res = context.resources
                    val clockId = res.getIdentifier("clock", "id", "com.android.systemui")
                    val clockView = (getField(fragment, "mClockView") as? TextView)
                        ?: (if (clockId != 0 && rootView != null) rootView.findViewById(clockId) else null)
                    clockView?.let { globalClock = WeakReference(it) }

                    val batteryId = res.getIdentifier("battery", "id", "com.android.systemui")
                    val batteryView = if (batteryId != 0 && rootView != null) rootView.findViewById<View>(batteryId) else null

                    val leftContainer = clockView?.parent as? ViewGroup
                    val rightContainer = (batteryView?.parent as? ViewGroup)
                        ?: (rootView as? ViewGroup)?.let { findRightContainer(it) }

                    injectIndicators(context, leftContainer, rightContainer, clockView, batteryView)
                }
            }
        }

        // 3. MiuiPhoneStatusBarView / PhoneStatusBarView onAttachedToWindow & onFinishInflate
        val barViewNames = listOf(
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
            "com.android.systemui.statusbar.phone.PhoneStatusBarView"
        )
        for (clsName in barViewNames) {
            val cls = findClass(clsName, classLoader) ?: continue
            for (mName in listOf("onAttachedToWindow", "onFinishInflate")) {
                hookAllMethods(module, cls, mName) { chain, _ ->
                    val view = chain.thisObject as? ViewGroup ?: return@hookAllMethods
                    injectFromView(view)
                }
            }
        }

        // 4. HomeStatusBarViewBinderImpl.bind (HyperOS 2 pipeline)
        val binderCls = findClass("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl", classLoader)
        if (binderCls != null) {
            hookAllMethods(module, binderCls, "bind") { chain, _ ->
                val view = chain.args.firstOrNull() as? ViewGroup ?: return@hookAllMethods
                injectFromView(view)
            }
        }
    }

    private fun injectFromView(root: ViewGroup) {
        val context = root.context
        val res = root.resources
        registerReceiverWithContext(context)

        // Find Clock
        val clockId = res.getIdentifier("clock", "id", "com.android.systemui")
        val clockView = if (clockId != 0) root.findViewById<TextView>(clockId) else null
        clockView?.let { globalClock = WeakReference(it) }

        // Find Left container
        val leftField = getField(root, "mStatusBarLeftContainer") as? ViewGroup
        val leftId = res.getIdentifier("start_side_container", "id", "com.android.systemui")
        val leftById = if (leftId != 0) root.findViewById<ViewGroup>(leftId) else null
        val leftContainer = leftField ?: leftById ?: (clockView?.parent as? ViewGroup)

        // Find Right container & Battery
        val batteryId = res.getIdentifier("battery", "id", "com.android.systemui")
        val batteryView = if (batteryId != 0) root.findViewById<View>(batteryId) else null
        val rightContainer = findRightContainer(root) ?: (batteryView?.parent as? ViewGroup)

        injectIndicators(context, leftContainer, rightContainer, clockView, batteryView)
    }

    private fun findRightContainer(root: ViewGroup): ViewGroup? {
        val sysAreaField = getField(root, "mSystemIconArea") as? ViewGroup
        if (sysAreaField != null) return sysAreaField

        val res = root.resources
        for (name in listOf("end_side_container", "system_icons", "system_icon_area", "status_bar_icons", "statusIcons")) {
            val id = res.getIdentifier(name, "id", "com.android.systemui")
            if (id != 0) {
                val v = root.findViewById<ViewGroup>(id)
                if (v != null) return v
            }
        }
        return null
    }

    private fun injectIndicators(
        context: Context,
        leftContainer: ViewGroup?,
        rightContainer: ViewGroup?,
        clockView: TextView?,
        batteryView: View?
    ) {
        Log.i(TAG, "injectIndicators: left=$leftContainer, right=$rightContainer, clock=$clockView, battery=$batteryView")

        if (leftContainer != null && leftContainer.findViewWithTag<View>(TAG_LEFT) == null) {
            val tvLeft = createTextIndicatorView(context).apply { tag = TAG_LEFT }
            leftViews.add(tvLeft)
            val index = if (clockView != null && clockView.parent === leftContainer) {
                (leftContainer.indexOfChild(clockView) + 1).coerceIn(0, leftContainer.childCount)
            } else {
                leftContainer.childCount
            }
            leftContainer.addView(tvLeft, index)
            syncColorWithClock(tvLeft, clockView)
            Log.i(TAG, "Added tvLeft at index $index into $leftContainer")
        }

        if (rightContainer != null && rightContainer.findViewWithTag<View>(TAG_RIGHT) == null) {
            val tvRight = createTextIndicatorView(context).apply { tag = TAG_RIGHT }
            rightViews.add(tvRight)
            val index = if (batteryView != null && batteryView.parent === rightContainer) {
                rightContainer.indexOfChild(batteryView)
            } else {
                0
            }
            rightContainer.addView(tvRight, index)
            syncColorWithClock(tvRight, clockView)
            Log.i(TAG, "Added tvRight at index $index into $rightContainer")
        }

        updateVisibilities()
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
                hookAllMethods(module, cls, mName) { chain, _ ->
                    val clock = chain.thisObject as? TextView ?: return@hookAllMethods
                    globalClock = WeakReference(clock)
                    mainHandler.post {
                        for (v in leftViews) syncColorWithClock(v, clock)
                        for (v in rightViews) syncColorWithClock(v, clock)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createTextIndicatorView(context: Context): TextView {
        val dp = context.resources.displayMetrics.density
        val res = context.resources
        val styleId = res.getIdentifier("TextAppearance.StatusBar.NetWorkSpeedNumber", "style", "com.android.systemui")
            .takeIf { it != 0 }
            ?: res.getIdentifier("TextAppearance.StatusBar.Clock", "style", "com.android.systemui")

        return TextView(context).apply {
            text = "--"
            if (styleId != 0) {
                runCatching { setTextAppearance(styleId) }
            }
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = if (isEnabled) View.VISIBLE else View.GONE
        }
    }

    private fun syncColorWithClock(iconView: TextView, clockView: TextView?) {
        val clock = clockView
            ?: globalClock?.get()
            ?: findCachedClock(iconView)
        if (clock != null) {
            globalClock = WeakReference(clock)
            clock.textColors?.let { iconView.setTextColor(it) }
            iconView.alpha = clock.alpha
        } else {
            iconView.setTextColor(Color.WHITE)
        }
    }

    private fun findCachedClock(iconView: View): TextView? {
        clockCache[iconView]?.get()?.let { return it }
        val clockId = iconView.resources.getIdentifier("clock", "id", "com.android.systemui")
        if (clockId == 0) return null
        val container = iconView.parent as? ViewGroup
        var clock = container?.findViewById<TextView>(clockId)
        if (clock == null) {
            clock = iconView.rootView?.findViewById<TextView>(clockId)
        }
        if (clock != null) {
            clockCache[iconView] = WeakReference(clock)
            globalClock = WeakReference(clock)
        }
        return clock
    }

    private fun startSamplingWorker() {
        if (workerThread != null) return
        val thread = HandlerThread("LiveFpsStatusBarWorker").apply { start() }
        workerThread = thread
        workerHandler = object : Handler(thread.looper) {
            private var lastNanos = SystemClock.elapsedRealtimeNanos()

            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_WORKER_TICK) {
                    processTick()
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
                    // SystemUI (UID 1000) has native DUMP permission!
                    val proc = ProcessBuilder("dumpsys", "SurfaceFlinger", "--timestats", "-dump").start()
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    var frames: Int? = null
                    while (reader.readLine().also { line = it } != null) {
                        val m = FRAMES_RE.find(line!!)
                        if (m != null) {
                            frames = m.groupValues[1].toIntOrNull()
                            break
                        }
                    }
                    proc.destroy()

                    // Clear counter for next interval
                    runCatching {
                        ProcessBuilder("dumpsys", "SurfaceFlinger", "--timestats", "-clear").start().waitFor()
                    }

                    val now = SystemClock.elapsedRealtimeNanos()
                    val secs = (now - lastNanos) / 1e9
                    lastNanos = now

                    val fps = if (frames != null && secs > 0) frames / secs else null
                    val fpsLabel = if (fps != null) "%.0f".format(fps) else "--"

                    mainHandler.post {
                        val activeList = if (currentPosition == "left") leftViews else rightViews
                        for (v in activeList) {
                            if (v.text != fpsLabel) {
                                v.text = fpsLabel
                            }
                            syncColorWithClock(v, null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "processTick error", e)
                }
            }
        }
        workerHandler?.sendEmptyMessage(MSG_WORKER_TICK)
    }

    // --- Reflection and Hook Utilities ---

    private fun findClass(className: String, classLoader: ClassLoader): Class<*>? = runCatching {
        Class.forName(className, false, classLoader)
    }.getOrNull()

    private fun hookAllMethods(
        module: XposedModule,
        clazz: Class<*>,
        methodName: String,
        after: (chain: XposedInterface.Chain, result: Any?) -> Unit
    ) {
        val targets = clazz.declaredMethods.filter { it.name == methodName }
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val res = chain.proceed()
                    runCatching { after(chain, res) }.onFailure { Log.e(TAG, "Hook error in ${m.name}", it) }
                    res
                }
                Log.d(TAG, "Hooked method: ${clazz.name}#${m.name}")
            }.onFailure {
                Log.w(TAG, "Failed hooking method ${clazz.name}#${m.name}: ${it.message}")
            }
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
}
