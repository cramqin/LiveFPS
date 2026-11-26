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
 * Modeled after HyperCeiler's BatteryDetailIndicator:
 * - Left side (near Clock) & Right side (inside system_icon_area) injection points
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

    @Volatile private var isEnabled = false
    @Volatile private var currentPosition = "right"

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

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
        }
    }

    private fun setupBroadcastReceiver(classLoader: ClassLoader) {
        val appCls = XposedHelpers.findClassIfExists("android.app.Application", classLoader) ?: return
        XposedBridge.hookAllMethods(appCls, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                val filter = IntentFilter(ACTION_CONFIG_CHANGED)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action == ACTION_CONFIG_CHANGED) {
                            isEnabled = intent.getBooleanExtra("enabled", isEnabled)
                            currentPosition = intent.getStringExtra("position") ?: currentPosition
                            updateVisibilities()
                        }
                    }
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, filter)
                    }
                }
            }
        })
    }

    private fun updateVisibilities() {
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
        // Hook MiuiCollapsedStatusBarFragment / CollapsedStatusBarFragment
        val fragmentCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.CollapsedStatusBarFragment", classLoader)

        fragmentCls?.let { cls ->
            val methods = cls.declaredMethods.filter {
                (it.name == "initMiuiViewsOnViewCreated" || it.name == "onViewCreated") && it.parameterTypes.isNotEmpty()
            }
            for (m in methods) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = (param.args.getOrNull(0) as? View)
                            ?: runCatching { XposedHelpers.callMethod(param.thisObject, "getView") as? View }.getOrNull()
                            ?: return
                        injectViews(root)
                    }
                })
            }
        }

        // Hook MiuiPhoneStatusBarView / PhoneStatusBarView
        val barViewCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.PhoneStatusBarView", classLoader)

        barViewCls?.let { cls ->
            val methods = cls.declaredMethods.filter {
                it.name in listOf("onFinishInflate", "onAttachedToWindow") && it.parameterTypes.isEmpty()
            }
            for (m in methods) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        injectViews(view)
                    }
                })
            }
        }
    }

    private fun injectViews(root: View) {
        val context = root.context
        val res = root.resources

        // 1. Left container (near Clock)
        val clockId = res.getIdentifier("clock", "id", "com.android.systemui")
        val clockView = if (clockId != 0) root.findViewById<View>(clockId) else null
        val leftContainer = (clockView?.parent as? ViewGroup) ?: (root as? ViewGroup)

        if (leftContainer != null && leftContainer.findViewWithTag<View>(TAG_LEFT) == null) {
            val tvLeft = createTextIndicatorView(context).apply { tag = TAG_LEFT }
            leftViews.add(tvLeft)
            val index = if (clockView != null) {
                (leftContainer.indexOfChild(clockView) + 1).coerceIn(0, leftContainer.childCount)
            } else {
                leftContainer.childCount
            }
            leftContainer.addView(tvLeft, index)
            syncColorWithClock(tvLeft, clockView as? TextView)
        }

        // 2. Right container (system_icon_area or statusIcons)
        val sysIconAreaId = res.getIdentifier("system_icon_area", "id", "com.android.systemui")
        val sysIconArea = if (sysIconAreaId != 0) root.findViewById<ViewGroup>(sysIconAreaId) else null

        val statusIconsId = res.getIdentifier("statusIcons", "id", "com.android.systemui")
        val statusIcons = if (statusIconsId != 0) root.findViewById<ViewGroup>(statusIconsId) else null

        val rightContainer = sysIconArea ?: (statusIcons?.parent as? ViewGroup) ?: statusIcons

        if (rightContainer != null && rightContainer.findViewWithTag<View>(TAG_RIGHT) == null) {
            val tvRight = createTextIndicatorView(context).apply { tag = TAG_RIGHT }
            rightViews.add(tvRight)
            rightContainer.addView(tvRight, 0)
            syncColorWithClock(tvRight, clockView as? TextView)
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
        return TextView(context).apply {
            text = "--"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
    }

    private fun syncColorWithClock(iconView: TextView, clockView: TextView?) {
        val clock = clockView ?: findCachedClock(iconView) ?: return
        clock.textColors?.let { iconView.setTextColor(it) }
        iconView.alpha = clock.alpha
    }

    private fun findCachedClock(iconView: View): TextView? {
        val container = iconView.parent as? ViewGroup ?: return null
        clockCache[iconView]?.get()?.let { return it }
        val clockId = container.resources.getIdentifier("clock", "id", "com.android.systemui")
        val clock = if (clockId != 0) container.findViewById<TextView>(clockId) else null
        if (clock != null) {
            clockCache[iconView] = WeakReference(clock)
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
