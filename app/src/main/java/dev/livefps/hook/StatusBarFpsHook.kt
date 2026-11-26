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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI Hook:
 * Injects a real-time FPS readout directly into HyperOS/Android Status Bar.
 *
 * Modeled after HyperCeiler's status bar injection:
 * - Hooks PhoneStatusBarViewController (startSideContainer & endSideContainer)
 * - Hooks MiuiPhoneStatusBarView / PhoneStatusBarView onAttachedToWindow
 * - Hooks HomeStatusBarViewBinderImpl.bind (HyperOS 2 pipeline)
 * - Left side (near Clock) & Right side (system icons area / battery)
 * - Uses native UID 1000 (SystemUI) DUMP permission for SurfaceFlinger TimeStats
 * - Battery-friendly: pauses sampling when screen is off (isInteractive)
 * - Fixed default 2.0s (2000ms) polling rate matching HyperCeiler (independent of overlay)
 * - Real-time changes via broadcast: position (left/right) and toggle (on/off)
 *   without restarting SystemUI!
 */
object StatusBarFpsHook {

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

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        XposedBridge.log("[LiveFPS] StatusBarFpsHook.init in ${lpparam.packageName}")

        // 1. Read initial config from XSharedPreferences
        loadInitialPrefs()

        // 2. Setup Status Bar views (Left and Right injection)
        setupStatusBarViews(classLoader)

        // 3. Setup Clock theme / dark mode syncing
        setupClockColorSync(classLoader)

        // 4. Register broadcast receiver for real-time config updates (Android 14+ safe)
        setupBroadcastReceiver(classLoader)

        // 5. Start background sampling worker
        startSamplingWorker()
    }

    private fun loadInitialPrefs() {
        runCatching {
            val xPrefs = XSharedPreferences("dev.livefps", "livefps_prefs")
            xPrefs.makeWorldReadable()
            xPrefs.reload()
            isEnabled = xPrefs.getBoolean("statusbar_enabled", false)
            currentPosition = xPrefs.getString("statusbar_position", "right") ?: "right"
            XposedBridge.log("[LiveFPS] Initial prefs: isEnabled=$isEnabled, position=$currentPosition")
        }.onFailure {
            XposedBridge.log("[LiveFPS] Failed to load initial prefs: $it")
        }
    }

    private fun setupBroadcastReceiver(classLoader: ClassLoader) {
        val appCls = XposedHelpers.findClassIfExists("android.app.Application", classLoader) ?: return
        XposedBridge.hookAllMethods(appCls, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                registerReceiverWithContext(context)
            }
        })
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
                    XposedBridge.log("[LiveFPS] Broadcast received: isEnabled=$isEnabled, pos=$currentPosition")
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
            XposedBridge.log("[LiveFPS] Registered broadcast receiver for $ACTION_CONFIG_CHANGED")
        }.onFailure {
            XposedBridge.log("[LiveFPS] Failed to register broadcast receiver: $it")
        }
    }

    private fun updateVisibilities() {
        XposedBridge.log("[LiveFPS] updateVisibilities: isEnabled=$isEnabled, pos=$currentPosition, leftViews=${leftViews.size}, rightViews=${rightViews.size}")
        mainHandler.post {
            for (tv in leftViews) {
                tv.visibility = if (isEnabled && currentPosition == "left") View.VISIBLE else View.GONE
            }
            for (tv in rightViews) {
                tv.visibility = if (isEnabled && currentPosition == "right") View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupStatusBarViews(classLoader: ClassLoader) {
        // 1. Hook PhoneStatusBarViewController.onViewAttached
        val controllerCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader)
        if (controllerCls != null) {
            runCatching {
                XposedBridge.hookAllMethods(controllerCls, "onViewAttached", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("[LiveFPS] PhoneStatusBarViewController.onViewAttached invoked")
                        val controller = param.thisObject
                        val clockView = runCatching { XposedHelpers.getObjectField(controller, "clock") as? TextView }.getOrNull()
                        val startSide = runCatching { XposedHelpers.getObjectField(controller, "startSideContainer") as? ViewGroup }.getOrNull()
                        val endSide = runCatching { XposedHelpers.getObjectField(controller, "endSideContainer") as? ViewGroup }.getOrNull()
                        val batteryView = runCatching { XposedHelpers.getObjectField(controller, "battery") as? View }.getOrNull()
                        val rootView = runCatching { XposedHelpers.getObjectField(controller, "mView") as? ViewGroup }.getOrNull()

                        injectFromController(rootView, clockView, startSide, endSide, batteryView)
                    }
                })
                XposedBridge.log("[LiveFPS] Hooked PhoneStatusBarViewController.onViewAttached")
            }.onFailure { XposedBridge.log("[LiveFPS] Hook PhoneStatusBarViewController failed: $it") }
        }

        // 2. Hook MiuiPhoneStatusBarView / PhoneStatusBarView onAttachedToWindow
        val barViewNames = listOf(
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
            "com.android.systemui.statusbar.phone.PhoneStatusBarView"
        )
        for (clsName in barViewNames) {
            val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
            runCatching {
                XposedBridge.hookAllMethods(cls, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        XposedBridge.log("[LiveFPS] $clsName.onAttachedToWindow invoked")
                        injectFromView(view)
                    }
                })
                XposedBridge.log("[LiveFPS] Hooked $clsName.onAttachedToWindow")
            }.onFailure { XposedBridge.log("[LiveFPS] Hook $clsName failed: $it") }
        }

        // 3. Hook HomeStatusBarViewBinderImpl.bind (HyperOS 2)
        val binderCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl", classLoader)
        if (binderCls != null) {
            runCatching {
                XposedBridge.hookAllMethods(binderCls, "bind", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.args.getOrNull(0) as? ViewGroup ?: return
                        XposedBridge.log("[LiveFPS] HomeStatusBarViewBinderImpl.bind invoked with ${view.javaClass.name}")
                        injectFromView(view)
                    }
                })
                XposedBridge.log("[LiveFPS] Hooked HomeStatusBarViewBinderImpl.bind")
            }.onFailure { XposedBridge.log("[LiveFPS] Hook HomeStatusBarViewBinderImpl failed: $it") }
        }
    }

    private fun injectFromController(
        rootView: ViewGroup?,
        clockView: TextView?,
        startSide: ViewGroup?,
        endSide: ViewGroup?,
        batteryView: View?
    ) {
        val context = rootView?.context ?: clockView?.context ?: startSide?.context ?: return
        clockView?.let { globalClock = WeakReference(it) }
        registerReceiverWithContext(context)
        val leftContainer = startSide ?: (clockView?.parent as? ViewGroup)
        val rightContainer = endSide ?: (batteryView?.parent as? ViewGroup)
        injectIndicators(context, leftContainer, rightContainer, clockView, batteryView)
    }

    private fun injectFromView(root: ViewGroup) {
        val context = root.context
        val res = root.resources
        registerReceiverWithContext(context)

        // Search for Clock
        val clockId = res.getIdentifier("clock", "id", "com.android.systemui")
        val clockView = if (clockId != 0) root.findViewById<TextView>(clockId) else null
        clockView?.let { globalClock = WeakReference(it) }

        // Search for Left container
        val leftField = runCatching { XposedHelpers.getObjectField(root, "mStatusBarLeftContainer") as? ViewGroup }.getOrNull()
        val leftId = res.getIdentifier("start_side_container", "id", "com.android.systemui")
        val leftById = if (leftId != 0) root.findViewById<ViewGroup>(leftId) else null
        val leftContainer = leftField ?: leftById ?: (clockView?.parent as? ViewGroup)

        // Search for Right container
        val sysAreaField = runCatching { XposedHelpers.getObjectField(root, "mSystemIconArea") as? ViewGroup }.getOrNull()
        val r1 = res.getIdentifier("end_side_container", "id", "com.android.systemui")
        val r2 = res.getIdentifier("system_icons", "id", "com.android.systemui")
        val r3 = res.getIdentifier("system_icon_area", "id", "com.android.systemui")
        val r4 = res.getIdentifier("status_bar_icons", "id", "com.android.systemui")
        val r5 = res.getIdentifier("statusIcons", "id", "com.android.systemui")

        val rightById = (if (r1 != 0) root.findViewById<ViewGroup>(r1) else null)
            ?: (if (r2 != 0) root.findViewById<ViewGroup>(r2) else null)
            ?: (if (r3 != 0) root.findViewById<ViewGroup>(r3) else null)
            ?: (if (r4 != 0) root.findViewById<ViewGroup>(r4) else null)
            ?: (if (r5 != 0) root.findViewById<ViewGroup>(r5) else null)

        val batteryId = res.getIdentifier("battery", "id", "com.android.systemui")
        val batteryView = if (batteryId != 0) root.findViewById<View>(batteryId) else null
        val rightContainer = sysAreaField ?: rightById ?: (batteryView?.parent as? ViewGroup)

        injectIndicators(context, leftContainer, rightContainer, clockView, batteryView)
    }

    private fun injectIndicators(
        context: Context,
        leftContainer: ViewGroup?,
        rightContainer: ViewGroup?,
        clockView: TextView?,
        batteryView: View?
    ) {
        XposedBridge.log("[LiveFPS] injectIndicators: left=$leftContainer, right=$rightContainer, clock=$clockView, battery=$batteryView")

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
            XposedBridge.log("[LiveFPS] Added tvLeft at index $index into $leftContainer")
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
            XposedBridge.log("[LiveFPS] Added tvRight at index $index into $rightContainer")
        }

        updateVisibilities()
    }

    private fun setupClockColorSync(classLoader: ClassLoader) {
        val miuiClockCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.views.MiuiClock", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.views.MiuiStatusBarClock", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.policy.Clock", classLoader)

        miuiClockCls?.let { cls ->
            val methods = cls.declaredMethods.filter {
                it.name in listOf("setTextColor", "setTextColorDark", "updateClockColor", "onDarkChanged", "setTextDark")
            }
            for (m in methods) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clock = param.thisObject as? TextView ?: return
                        globalClock = WeakReference(clock)
                        mainHandler.post {
                            for (v in leftViews) syncColorWithClock(v, clock)
                            for (v in rightViews) syncColorWithClock(v, clock)
                        }
                    }
                })
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

                    // Clear counter
                    ProcessBuilder("dumpsys", "SurfaceFlinger", "--timestats", "-clear").start().waitFor()

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
                } catch (_: Exception) {
                }
            }
        }
        workerHandler?.sendEmptyMessage(MSG_WORKER_TICK)
    }
}
