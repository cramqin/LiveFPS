package dev.livefps.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.BufferedReader
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI Hook:
 * Injects a real-time FPS readout directly into HyperOS/Android Status Bar.
 *
 * Modeled after HyperCeiler's BatteryDetailIndicator:
 * - Left side & Right side injection points
 * - Uses native UID 1000 (SystemUI) DUMP permission for SurfaceFlinger TimeStats
 * - Battery-friendly: pauses sampling when screen is off (isInteractive)
 * - Real-time changes via broadcast: position (left/right) and toggle (on/off)
 *   without restarting SystemUI!
 */
object StatusBarFpsHook {

    private const val ACTION_CONFIG_CHANGED = "dev.livefps.ACTION_CONFIG_CHANGED"
    private const val SLOT_NAME = "fps_info"
    private const val SLOT_NETWORK_SPEED = "network_speed"
    private const val ICON_TYPE = 92
    private const val MSG_WORKER_TICK = 101

    private val leftViews = CopyOnWriteArrayList<TextView>()
    private val rightViews = CopyOnWriteArrayList<TextView>()
    private val clockCache = Collections.synchronizedMap(WeakHashMap<View, WeakReference<TextView>>())

    @Volatile private var isEnabled = false
    @Volatile private var currentPosition = "right"
    @Volatile private var pollingIntervalMs = 1000L

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 1. Right Side Setup (Status bar icon list & icon controller)
        setupRightSide(classLoader)

        // 2. Left Side Setup (Collapsed status bar fragment near clock)
        setupLeftSide(classLoader)

        // 3. Register broadcast receiver for real-time config updates
        setupBroadcastReceiver(classLoader)

        // 4. Start background sampling worker
        startSamplingWorker()
    }

    private fun setupBroadcastReceiver(classLoader: ClassLoader) {
        val appCls = XposedHelpers.findClassIfExists("android.app.Application", classLoader) ?: return
        XposedBridge.hookAllMethods(appCls, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                val filter = IntentFilter(ACTION_CONFIG_CHANGED)
                context.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action == ACTION_CONFIG_CHANGED) {
                            isEnabled = intent.getBooleanExtra("enabled", isEnabled)
                            currentPosition = intent.getStringExtra("position") ?: currentPosition
                            pollingIntervalMs = intent.getLongExtra("interval_ms", pollingIntervalMs).coerceAtLeast(100L)
                            updateVisibilities()
                        }
                    }
                }, filter)
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

    private fun setupRightSide(classLoader: ClassLoader) {
        val sbiListCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.ui.StatusBarIconList", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.StatusBarIconList", classLoader)

        sbiListCls?.constructors?.forEach { constructor ->
            if (constructor.parameterTypes.size == 1 && constructor.parameterTypes[0] == Array<String>::class.java) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val slots = param.args[0] as? Array<String> ?: return
                        val slotList = ArrayList(slots.toList())
                        if (!slotList.contains(SLOT_NAME)) {
                            val netSpeedIndex = slotList.indexOf(SLOT_NETWORK_SPEED)
                            if (netSpeedIndex >= 0) {
                                slotList.add(netSpeedIndex + 1, SLOT_NAME)
                            } else {
                                slotList.add(SLOT_NAME)
                            }
                            param.args[0] = slotList.toTypedArray()
                        }
                    }
                })
            }
        }

        val iconManagerCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.ui.IconManager", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager", classLoader)

        iconManagerCls?.let { cls ->
            XposedBridge.hookAllMethods(cls, "addHolder", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val iconHolder = param.args.getOrNull(3) ?: return
                    val type = runCatching { XposedHelpers.getIntField(iconHolder, "type") }
                        .getOrElse { runCatching { XposedHelpers.getIntField(iconHolder, "mType") }.getOrDefault(-1) }
                    if (type == ICON_TYPE) {
                        val mContext = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                        val mGroup = XposedHelpers.getObjectField(param.thisObject, "mGroup") as ViewGroup
                        val tv = createTextIndicatorView(mContext)
                        rightViews.add(tv)
                        val index = (param.args[0] as? Int ?: 0).coerceIn(0, mGroup.childCount)
                        mGroup.addView(tv, index)
                        param.result = tv
                        updateVisibilities()
                    }
                }
            })
        }
    }

    private fun setupLeftSide(classLoader: ClassLoader) {
        val fragmentCls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment", classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.CollapsedStatusBarFragment", classLoader)

        fragmentCls?.let { cls ->
            XposedBridge.hookAllMethods(cls, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.args.getOrNull(0) as? View ?: return
                    val context = root.context
                    val clockId = root.resources.getIdentifier("clock", "id", "com.android.systemui")
                    val clockView = if (clockId != 0) root.findViewById<View>(clockId) else null
                    val container = (clockView?.parent as? ViewGroup) ?: (root as? ViewGroup) ?: return

                    val tv = createTextIndicatorView(context)
                    leftViews.add(tv)
                    val index = if (clockView != null) (container.indexOfChild(clockView) + 1).coerceIn(0, container.childCount) else container.childCount
                    container.addView(tv, index)
                    syncColorWithClock(tv, clockView as? TextView)
                    updateVisibilities()
                }
            })
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
                    sendEmptyMessageDelayed(MSG_WORKER_TICK, pollingIntervalMs)
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
