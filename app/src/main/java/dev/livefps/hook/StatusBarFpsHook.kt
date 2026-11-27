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
 * SystemUI Hook (libxposed API 102) matching HyperCeiler's architecture:
 * Injects a real-time FPS readout into HyperOS Status Bar using NetworkSpeedView.
 *
 * Supports:
 * - Right side (near Battery & Network Speed): hooks StatusBarIconList + IconManager + NetworkSpeedController
 * - Left side (near Clock): hooks PhoneStatusBarViewController + MiuiCollapsedStatusBarFragment + MiuiPhoneStatusBarView
 * - Real-time switching & toggle via broadcast `dev.livefps.ACTION_CONFIG_CHANGED` without SystemUI restart
 * - Color synchronization with MiuiClock (dark mode / light mode adaptive)
 * - Fixed 2.0s polling rate (matching HyperCeiler)
 * - Monotonic SurfaceFlinger timestats delta sampling (zero conflict with overlay)
 */
object StatusBarFpsHook {

    private const val TAG = "LiveFPS"
    private const val SLOT_NAME = "livefps_slot"
    private const val SLOT_NETWORK_SPEED = "network_speed"
    private const val ICON_TYPE = 91
    private const val ACTION_CONFIG_CHANGED = "dev.livefps.ACTION_CONFIG_CHANGED"
    private const val MSG_WORKER_TICK = 200021
    private const val STATUSBAR_POLL_INTERVAL_MS = 2000L // 2.0s rate matching HyperCeiler

    private const val TAG_SLOT_TEXT_ICON = "livefps_statusbar_icon"
    private const val TAG_NETWORK_SPEED_NUMBER = "network_speed_number"
    private const val TAG_NETWORK_SPEED_UNIT = "network_speed_unit"
    private const val FIELD_CONTAINER = "mContainer"
    private const val FIELD_NETWORK_SPEED_NUMBER_TEXT = "mNetworkSpeedNumberText"
    private const val FIELD_NETWORK_SPEED_UNIT_TEXT = "mNetworkSpeedUnitText"
    private const val FIELD_VISIBLE_BY_CONTROLLER = "mVisibleByController"
    private const val FIELD_TYPE = "type"
    private const val FIELD_M_TYPE = "mType"
    private const val FIELD_M_CONTEXT = "mContext"
    private const val FIELD_M_GROUP = "mGroup"
    private const val FIELD_M_CLOCK_VIEW = "mClockView"

    private const val METHOD_SET_VISIBILITY_BY_CONTROLLER = "setVisibilityByController"
    private const val METHOD_SET_ICON = "setIcon"
    private const val METHOD_GET_SLOT_INDEX = "getSlotIndex"
    private const val METHOD_ON_CREATE_LAYOUT_PARAMS = "onCreateLayoutParams"
    private const val METHOD_SET_BLOCKED = "setBlocked"
    private const val METHOD_SET_NETWORK_SPEED = "setNetworkSpeed"
    private const val METHOD_ADD_DARK_RECEIVER = "addDarkReceiver"

    private const val PKG_SYSTEMUI = "com.android.systemui"
    private const val ID_CLOCK = "clock"
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
        Log.i(TAG, "StatusBarFpsHook initializing (matching HyperCeiler architecture)")

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

        // 5. Setup Right Side hooks (StatusBarIconList + IconManager + NetworkSpeedController)
        setupRightSideHooks(module, nsvCls, classLoader)

        // 6. Setup Left Side hooks (PhoneStatusBarViewController + MiuiCollapsedStatusBarFragment + MiuiPhoneStatusBarView)
        setupLeftSideHooks(module, nsvCls, classLoader)

        // 7. Setup MiuiClock color sync
        setupClockColorSync(module, classLoader)

        // 8. Start background sampling worker (fixed 2.0s interval)
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
        // getSlot -> SLOT_NAME
        for (m in nsvCls.declaredMethods.filter { it.name == "getSlot" && it.parameterTypes.isEmpty() }) {
            hookMethod(module, m) { chain ->
                val nsView = chain.thisObject as? View
                if (nsView?.tag == TAG_SLOT_TEXT_ICON) {
                    SLOT_NAME
                } else {
                    chain.proceed()
                }
            }
        }

        // setVisibleState -> keep number text visible when state != 2
        for (m in nsvCls.declaredMethods.filter { it.name == "setVisibleState" }) {
            hookMethod(module, m) { chain ->
                val nsView = chain.thisObject as? View
                if (nsView?.tag == TAG_SLOT_TEXT_ICON) {
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
                if (nsView?.tag == TAG_SLOT_TEXT_ICON) {
                    syncColorWithClock(nsView)
                }
                res
            }
        }
    }

    private fun setupRightSideHooks(module: XposedModule, nsvCls: Class<*>, classLoader: ClassLoader) {
        // 1. StatusBarIconList: insert SLOT_NAME after SLOT_NETWORK_SPEED
        val sbiListCls = findClass("com.android.systemui.statusbar.phone.ui.StatusBarIconList", classLoader)
            ?: findClass("com.android.systemui.statusbar.phone.StatusBarIconList", classLoader)
        if (sbiListCls != null) {
            for (ctor in sbiListCls.declaredConstructors.filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == Array<String>::class.java }) {
                hookConstructor(module, ctor) { chain ->
                    @Suppress("UNCHECKED_CAST")
                    val slots = chain.args.getOrNull(0) as? Array<String>
                    if (slots != null) {
                        val slotList = ArrayList(slots.toList())
                        if (!slotList.contains(SLOT_NAME)) {
                            val netSpeedIndex = slotList.indexOf(SLOT_NETWORK_SPEED)
                            if (netSpeedIndex >= 0) {
                                slotList.add(netSpeedIndex + 1, SLOT_NAME)
                            } else {
                                slotList.add(SLOT_NAME)
                            }
                            chain.args[0] = slotList.toTypedArray()
                        }
                    }
                    chain.proceed()
                }
            }
        }

        // 2. NetworkSpeedController: register icon to mStatusBarIconController
        val nscCls = findClass("com.android.systemui.statusbar.policy.NetworkSpeedController", classLoader)
        if (nscCls != null) {
            for (ctor in nscCls.declaredConstructors) {
                hookConstructor(module, ctor) { chain ->
                    val res = chain.proceed()
                    val iconController = getField(chain.thisObject, "mStatusBarIconController")
                    if (iconController != null) {
                        registerIconToController(iconController, classLoader)
                    }
                    res
                }
            }
        }

        // 3. StatusBarIconControllerImpl.setIcon: enforce ICON_TYPE for our slot
        val sbicImplCls = findClass("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl", classLoader)
            ?: findClass("com.android.systemui.statusbar.phone.StatusBarIconControllerImpl", classLoader)
        if (sbicImplCls != null) {
            for (m in sbicImplCls.declaredMethods.filter { it.name == METHOD_SET_ICON && it.parameterTypes.size == 2 }) {
                hookMethod(module, m) { chain ->
                    val slotName = chain.args.getOrNull(0) as? String
                    if (slotName == SLOT_NAME) {
                        val iconHolder = chain.args.getOrNull(1)
                        if (iconHolder != null) {
                            setField(iconHolder, FIELD_TYPE, ICON_TYPE)
                            setField(iconHolder, FIELD_M_TYPE, ICON_TYPE)
                        }
                    }
                    chain.proceed()
                }
            }
        }

        // 4. IconManager.addHolder: instantiate and add NetworkSpeedView to right container group
        val iconManagerCls = findClass("com.android.systemui.statusbar.phone.ui.IconManager", classLoader)
            ?: findClass("com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager", classLoader)
        if (iconManagerCls != null) {
            for (m in iconManagerCls.declaredMethods.filter { it.name == "addHolder" && it.parameterTypes.size == 4 }) {
                hookMethod(module, m) { chain ->
                    val iconHolder = chain.args.getOrNull(3)
                    val type = if (iconHolder != null) {
                        (getField(iconHolder, FIELD_TYPE) as? Int) ?: (getField(iconHolder, FIELD_M_TYPE) as? Int) ?: -1
                    } else -1

                    if (type == ICON_TYPE) {
                        val iconManager = chain.thisObject
                        val context = getField(iconManager, FIELD_M_CONTEXT) as? Context ?: return@hookMethod chain.proceed()
                        registerReceiverWithContext(context)
                        val lp = (runCatching { callMethod(iconManager, METHOD_ON_CREATE_LAYOUT_PARAMS) as? LinearLayout.LayoutParams }.getOrNull())
                            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        val group = getField(iconManager, FIELD_M_GROUP) as? ViewGroup ?: return@hookMethod chain.proceed()
                        val existing = group.findViewWithTag<View>(TAG_SLOT_TEXT_ICON)
                        if (existing != null) {
                            if (!rightViews.contains(existing)) rightViews.add(existing)
                            return@hookMethod existing
                        } else {
                            val iconView = createStatusbarTextIcon(nsvCls, context, lp, true)
                            val index = (chain.args.getOrNull(0) as? Int ?: 0).coerceIn(0, group.childCount)
                            group.addView(iconView, index)
                            rightViews.add(iconView)
                            Log.i(TAG, "Added right-side NetworkSpeedView into group at index $index")
                            updateVisibilities()
                            return@hookMethod iconView
                        }
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun registerIconToController(iconController: Any, classLoader: ClassLoader) {
        runCatching {
            callMethod(iconController, METHOD_SET_ICON, arrayOf(String::class.java, Int::class.javaPrimitiveType), arrayOf(SLOT_NAME, 0))
        }.onFailure {
            runCatching {
                val slotIndex = callMethod(iconController, METHOD_GET_SLOT_INDEX, arrayOf(String::class.java), arrayOf(SLOT_NAME)) as? Int ?: 0
                val sbHolderCls = findClass("com.android.systemui.statusbar.phone.StatusBarIconHolder", classLoader)
                if (sbHolderCls != null) {
                    val ctor = sbHolderCls.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() } ?: sbHolderCls.declaredConstructors.first()
                    ctor.isAccessible = true
                    val holder = ctor.newInstance()
                    setField(holder, FIELD_TYPE, ICON_TYPE)
                    setField(holder, FIELD_M_TYPE, ICON_TYPE)
                    callMethod(iconController, METHOD_SET_ICON, arrayOf(Int::class.javaPrimitiveType, sbHolderCls), arrayOf(slotIndex, holder))
                }
            }
        }
    }

    private fun setupLeftSideHooks(module: XposedModule, nsvCls: Class<*>, classLoader: ClassLoader) {
        // 1. PhoneStatusBarViewController.onViewAttached
        val controllerCls = findClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader)
        if (controllerCls != null) {
            for (m in controllerCls.declaredMethods.filter { it.name == "onViewAttached" && it.parameterTypes.isEmpty() }) {
                hookMethod(module, m) { chain ->
                    val res = chain.proceed()
                    val controller = chain.thisObject
                    val clockView = (getField(controller, "clock") as? View) ?: (getField(controller, FIELD_M_CLOCK_VIEW) as? View)
                    val startSideContainer = getField(controller, "startSideContainer") as? ViewGroup
                    val darkDispatcher = getField(controller, "darkIconDispatcher")
                    val context = clockView?.context ?: startSideContainer?.context
                    if (context != null) {
                        registerReceiverWithContext(context)
                        injectToLeftContainer(nsvCls, context, clockView, startSideContainer, darkDispatcher, classLoader)
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
                        val clockId = context.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                        val clockView = (getField(fragment, FIELD_M_CLOCK_VIEW) as? View) ?: if (clockId != 0 && rootView != null) rootView.findViewById(clockId) else null
                        val container = clockView?.parent as? ViewGroup
                        injectToLeftContainer(nsvCls, context, clockView, container, null, classLoader)
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
                        registerReceiverWithContext(view.context)
                        val clockId = view.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                        val clockView = if (clockId != 0) view.findViewById<View>(clockId) else null
                        val container = clockView?.parent as? ViewGroup
                        if (container != null) {
                            injectToLeftContainer(nsvCls, view.context, clockView, container, null, classLoader)
                        }
                    }
                    res
                }
            }
        }
    }

    private fun injectToLeftContainer(
        nsvCls: Class<*>,
        context: Context,
        clockView: View?,
        targetContainer: ViewGroup?,
        providedDarkDispatcher: Any?,
        classLoader: ClassLoader
    ) {
        val container = targetContainer ?: (clockView?.parent as? ViewGroup) ?: return
        val existing = container.findViewWithTag<View>(TAG_SLOT_TEXT_ICON)
        if (existing != null) {
            if (!leftViews.contains(existing)) leftViews.add(existing)
            syncColorWithClock(existing, clockView as? TextView)
            return
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val iconView = createStatusbarTextIcon(nsvCls, context, lp, false)
        val index = if (clockView != null && clockView.parent === container) {
            (container.indexOfChild(clockView) + 1).coerceIn(0, container.childCount)
        } else {
            container.childCount
        }
        container.addView(iconView, index)
        leftViews.add(iconView)
        syncColorWithClock(iconView, clockView as? TextView)
        Log.i(TAG, "Added left-side NetworkSpeedView into container at index $index")

        val darkDispatcher = providedDarkDispatcher ?: runCatching {
            val darkCls = findClass("com.android.systemui.plugins.DarkIconDispatcher", classLoader)
            val depCls = findClass("com.android.systemui.Dependency", classLoader)
            if (darkCls != null && depCls != null) {
                depCls.getMethod("get", Class::class.java).invoke(null, darkCls)
            } else null
        }.getOrNull()

        if (darkDispatcher != null) {
            runCatching {
                callMethod(darkDispatcher, METHOD_ADD_DARK_RECEIVER, arrayOf(View::class.java), arrayOf(iconView))
            }
        }
        updateVisibilities()
    }

    private fun createStatusbarTextIcon(
        nsvCls: Class<*>,
        context: Context,
        lp: ViewGroup.LayoutParams,
        fromController: Boolean
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

        iconView.tag = TAG_SLOT_TEXT_ICON
        iconView.layoutParams = lp

        val number = TextView(context).apply {
            tag = TAG_NETWORK_SPEED_NUMBER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
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

        initStatusbarTextIcon(context, lp, iconView, fromController)
        return iconView
    }

    private fun initStatusbarTextIcon(
        context: Context,
        lp: ViewGroup.LayoutParams,
        iconView: View,
        fromController: Boolean
    ) {
        if (!fromController) {
            runCatching { callMethod(iconView, METHOD_SET_BLOCKED, arrayOf(Boolean::class.javaPrimitiveType), arrayOf(false)) }
        }
        val iconTextView = (getField(iconView, FIELD_NETWORK_SPEED_NUMBER_TEXT) as? TextView)
            ?: (iconView as? TextView) ?: return

        val res = context.resources
        val styleId = res.getIdentifier(STYLE_NETWORK_SPEED_NUMBER, "style", PKG_SYSTEMUI)
            .takeIf { it != 0 }
            ?: res.getIdentifier(STYLE_CLOCK, "style", PKG_SYSTEMUI)
        if (styleId != 0) {
            runCatching { iconTextView.setTextAppearance(styleId) }
        }
        syncColorWithClock(iconView)
        iconTextView.isSingleLine = true
        val dp = res.displayMetrics.density
        iconTextView.setPaddingRelative((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
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
                    callMethod(tv, METHOD_SET_VISIBILITY_BY_CONTROLLER, arrayOf(Boolean::class.javaPrimitiveType), arrayOf(show))
                }.onFailure {
                    tv.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
            for (tv in rightViews) {
                val show = isEnabled && currentPosition == "right"
                runCatching {
                    callMethod(tv, METHOD_SET_VISIBILITY_BY_CONTROLLER, arrayOf(Boolean::class.javaPrimitiveType), arrayOf(show))
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
                callMethod(tv, METHOD_SET_NETWORK_SPEED, arrayOf(String::class.java, String::class.java), arrayOf(fpsText, ""))
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

    private fun hookConstructor(
        module: XposedModule,
        constructor: Constructor<*>,
        callback: (chain: XposedInterface.Chain) -> Any?
    ) {
        runCatching {
            constructor.isAccessible = true
            module.hook(constructor).intercept { chain ->
                callback(chain)
            }
        }.onFailure {
            Log.w(TAG, "Failed hooking constructor ${constructor.declaringClass.name}: ${it.message}")
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
        paramTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray()
    ): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val m = runCatching { cls.getDeclaredMethod(methodName, *paramTypes).apply { isAccessible = true } }.getOrNull()
            if (m != null) return m.invoke(obj, *args)
            cls = cls.superclass
        }
        return null
    }
}
