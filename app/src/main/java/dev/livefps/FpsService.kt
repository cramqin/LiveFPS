package dev.livefps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Shared state for in-app UI and outside observers.
 */
object FpsState {
    @Volatile var currentFps: Double? = null
    @Volatile var currentLabel: String = "-- fps"
    @Volatile var isRunning: Boolean = false

    // UI listener callback (dispatched on main thread)
    var listener: ((fps: Double?, label: String) -> Unit)? = null
}

/**
 * Foreground service that draws a small draggable FPS box over other apps
 * and streams real-time FPS data to the in-app UI.
 *
 * Positioning & Rotation Features:
 * 1. Portrait mode: sticks right next to the status bar with 0 gap/margin.
 * 2. Fullscreen / Landscape rotation: allows positioning at the very top (y = 0)
 *    when status bar is hidden.
 * 3. Notification shade auto-offset: when notification shade / status bar is swiped down
 *    over the overlay, it temporarily shifts down to avoid overlap, and automatically
 *    restores back to its exact saved top position when the shade is closed.
 * 4. Screen rotation clamping: preserves boundary bounds across device orientation changes.
 * 5. Quick Settings Tile synchronization.
 */
class FpsService : Service() {

    companion object {
        const val PREFS_NAME = "livefps_prefs"
        const val KEY_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 2000L
        const val ACTION_FPS_UPDATE = "dev.livefps.ACTION_FPS_UPDATE"
        const val ACTION_STATUSBAR_ACK = "dev.livefps.ACTION_STATUSBAR_ACK"

        @Volatile var running = false
        @Volatile var pollingIntervalMs = DEFAULT_INTERVAL_MS
        const val ACTION_START = "dev.livefps.START"
        const val ACTION_STOP = "dev.livefps.STOP"
        private const val CHANNEL = "fps"
        private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

        fun updateInterval(context: Context, newIntervalMs: Long = DEFAULT_INTERVAL_MS) {
            pollingIntervalMs = DEFAULT_INTERVAL_MS
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
                .apply()
        }

        fun getSavedInterval(context: Context): Long {
            return DEFAULT_INTERVAL_MS
        }
    }

    private lateinit var wm: WindowManager
    private var view: TextView? = null
    private var worker: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var alive = false

    private val pauseLock = Object()
    @Volatile private var screenOn = true
    private var receiverRegistered = false

    @Volatile private var lastStatusBarAckFps: String? = null
    @Volatile private var lastStatusBarAckTime = 0L

    private var userPortraitY = -1
    private var userLandscapeY = -1
    @Volatile private var isUserInteracting = false

    private val insetsDebounceHandler = Handler(Looper.getMainLooper())
    private val adjustPositionRunnable = Runnable {
        if (!alive || isUserInteracting) return@Runnable
        val v = view ?: return@Runnable
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
        val dm = resources.displayMetrics
        val dp = dm.density
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val systemStatusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else (28 * dp).toInt()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val insets = if (Build.VERSION.SDK_INT >= 30) {
            v.rootWindowInsets ?: wm.currentWindowMetrics.windowInsets
        } else null

        val isStatusBarVisible = if (Build.VERSION.SDK_INT >= 30 && insets != null) {
            insets.isVisible(WindowInsets.Type.statusBars())
        } else true

        val insetsTop = if (Build.VERSION.SDK_INT >= 30 && insets != null) {
            insets.getInsets(WindowInsets.Type.statusBars()).top
        } else 0

        val statusBarHeight = if (insetsTop > 0) insetsTop else systemStatusBarHeight

        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds
        } else {
            val d = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(d)
            Rect(0, 0, d.widthPixels, d.heightPixels)
        }
        val screenW = bounds.width()
        val screenH = bounds.height()
        val width = v.width.takeIf { it > 0 } ?: (60 * dp).toInt()
        val height = v.height.takeIf { it > 0 } ?: (30 * dp).toInt()

        val snugMargin = (statusBarHeight - (10 * dp).toInt()).coerceAtLeast((24 * dp).toInt())
        val minY = if (isLandscape) {
            if (isStatusBarVisible) snugMargin else 0
        } else {
            snugMargin
        }

        val maxX = (screenW - width).coerceAtLeast(0)
        val maxY = (screenH - height).coerceAtLeast(minY)

        val targetX = lp.x.coerceIn(0, maxX)
        val targetY = if (isLandscape) {
            val preferred = if (userLandscapeY >= 0) userLandscapeY else minY
            preferred.coerceIn(minY, maxY)
        } else {
            val preferred = if (userPortraitY >= 0) userPortraitY else snugMargin
            preferred.coerceIn(minY, maxY)
        }

        if (lp.x != targetX || lp.y != targetY) {
            lp.x = targetX
            lp.y = targetY
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    synchronized(pauseLock) {
                        pauseLock.notifyAll()
                    }
                }
                ACTION_STATUSBAR_ACK -> {
                    val ackFps = intent.getStringExtra("fps")
                    if (!ackFps.isNullOrEmpty()) {
                        lastStatusBarAckFps = ackFps
                        lastStatusBarAckTime = SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        pollingIntervalMs = getSavedInterval(this)
        startInForeground()
        if (Settings.canDrawOverlays(this)) {
            addOverlay()
        }
        registerScreenReceiver()
        startWorker()
        FpsTileService.requestUpdate(this)
        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(ACTION_STATUSBAR_ACK)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onDestroy() {
        alive = false
        running = false
        FpsState.isRunning = false
        FpsState.currentFps = null
        FpsState.currentLabel = "-- fps"
        mainHandler.post {
            FpsState.listener?.invoke(null, "-- fps")
        }

        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }

        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        insetsDebounceHandler.removeCallbacks(adjustPositionRunnable)
        worker?.interrupt()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        FpsTileService.requestUpdate(this)
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FPS overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FpsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("LiveFPS running")
            .setContentText("Monitoring on-screen refresh rate")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun addOverlay() {
        wm = getSystemService(WindowManager::class.java)
        val dm = resources.displayMetrics
        val dp = dm.density

        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val systemStatusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else (28 * dp).toInt()
        val snugMargin = (systemStatusBarHeight - (10 * dp).toInt()).coerceAtLeast((24 * dp).toInt())

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isStatusBarVisible = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.isVisible(WindowInsets.Type.statusBars())
        } else true
        val initialMinY = if (isLandscape) {
            if (isStatusBarVisible) snugMargin else 0
        } else {
            snugMargin
        }

        if (userPortraitY < 0) userPortraitY = snugMargin
        if (userLandscapeY < 0) userLandscapeY = initialMinY

        val initialY = if (isLandscape) {
            userLandscapeY.coerceAtLeast(initialMinY)
        } else {
            userPortraitY.coerceAtLeast(initialMinY)
        }

        val tv = TextView(this).apply {
            text = "-- fps"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            background = GradientDrawable().apply {
                setColor(0xCC121212.toInt())
                cornerRadius = 8 * dp
                setStroke((1 * dp).toInt(), 0x33FFFFFF.toInt())
            }
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = initialY
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        // Drag to move with dynamic screen boundary and strict status bar collision
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserInteracting = true
                    insetsDebounceHandler.removeCallbacks(adjustPositionRunnable)
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val width = v.width.takeIf { it > 0 } ?: (60 * dp).toInt()
                    val height = v.height.takeIf { it > 0 } ?: (30 * dp).toInt()

                    val bounds = if (Build.VERSION.SDK_INT >= 30) {
                        wm.currentWindowMetrics.bounds
                    } else {
                        val d = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealMetrics(d)
                        Rect(0, 0, d.widthPixels, d.heightPixels)
                    }
                    val screenW = bounds.width()
                    val screenH = bounds.height()

                    val insets = if (Build.VERSION.SDK_INT >= 30) {
                        v.rootWindowInsets ?: wm.currentWindowMetrics.windowInsets
                    } else null
                    val isStatusBarVisibleNow = if (Build.VERSION.SDK_INT >= 30 && insets != null) {
                        insets.isVisible(WindowInsets.Type.statusBars())
                    } else true
                    val insetsTop = if (Build.VERSION.SDK_INT >= 30 && insets != null) {
                        insets.getInsets(WindowInsets.Type.statusBars()).top
                    } else 0
                    val currentStatusBarH = if (insetsTop > 0) insetsTop else systemStatusBarHeight
                    val currentSnugMargin = (currentStatusBarH - (10 * dp).toInt()).coerceAtLeast((24 * dp).toInt())

                    val isLandscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val minY = if (isLandscapeNow) {
                        if (isStatusBarVisibleNow) currentSnugMargin else 0
                    } else {
                        currentSnugMargin
                    }
                    val maxX = (screenW - width).coerceAtLeast(0)
                    val maxY = (screenH - height).coerceAtLeast(minY)

                    val targetX = (startX + (e.rawX - touchX).toInt()).coerceIn(0, maxX)
                    val targetY = (startY + (e.rawY - touchY).toInt()).coerceIn(minY, maxY)

                    lp.x = targetX
                    lp.y = targetY
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserInteracting = false
                    val isLandscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    if (isLandscapeNow) {
                        userLandscapeY = lp.y
                    } else {
                        userPortraitY = lp.y
                    }
                    insetsDebounceHandler.removeCallbacks(adjustPositionRunnable)
                    insetsDebounceHandler.postDelayed(adjustPositionRunnable, 150L)
                }
            }
            true
        }

        tv.setOnApplyWindowInsetsListener { _, insets ->
            insetsDebounceHandler.removeCallbacks(adjustPositionRunnable)
            insetsDebounceHandler.postDelayed(adjustPositionRunnable, 150L)
            insets
        }

        wm.addView(tv, lp)
        view = tv
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        insetsDebounceHandler.removeCallbacks(adjustPositionRunnable)
        insetsDebounceHandler.postDelayed(adjustPositionRunnable, 150L)
    }

    private fun startWorker() {
        alive = true
        FpsState.isRunning = true
        worker = Thread({
            var proc: Process? = null
            var w: BufferedWriter? = null
            var r: BufferedReader? = null
            var lastOverlayLabel = ""

            fun sh(cmd: String): List<String> {
                val out = ArrayList<String>()
                w!!.write("$cmd\necho __END__\n")
                w!!.flush()
                while (true) {
                    val line = r!!.readLine() ?: break
                    if (line == "__END__") break
                    out += line
                }
                return out
            }

            try {
                proc = ProcessBuilder("su").redirectErrorStream(true).start()
                w = proc.outputStream.bufferedWriter()
                r = proc.inputStream.bufferedReader()

                sh("dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1")
                var lastTime = SystemClock.elapsedRealtimeNanos()
                var lastFrames = -1L

                while (alive) {
                    // Battery Saver: If screen is turned off, pause polling completely
                    if (!screenOn) {
                        synchronized(pauseLock) {
                            while (!screenOn && alive) {
                                pauseLock.wait()
                            }
                        }
                        if (!alive) break
                        lastTime = SystemClock.elapsedRealtimeNanos()
                        lastFrames = -1L
                    }

                    val currentInterval = DEFAULT_INTERVAL_MS
                    Thread.sleep(currentInterval)
                    if (!screenOn || !alive) continue

                    val out = sh("dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | grep -m1 totalFrames")
                    val now = SystemClock.elapsedRealtimeNanos()
                    val secs = (now - lastTime) / 1e9
                    lastTime = now
                    val currentFrames = out.firstNotNullOfOrNull {
                        FRAMES_RE.find(it)?.groupValues?.get(1)?.toLongOrNull()
                    }
                    val fpsVal = if (currentFrames != null && lastFrames >= 0 && secs > 0) {
                        val deltaFrames = (currentFrames - lastFrames).coerceAtLeast(0)
                        deltaFrames / secs
                    } else null

                    if (currentFrames != null) {
                        lastFrames = currentFrames
                    }

                    val fpsInt = fpsVal?.let { Math.round(it).toInt() }
                    val fpsStr = fpsInt?.toString() ?: "0"
                    val label = if (currentFrames == null) "no root" else "$fpsStr fps"

                    // Avoid redundant layout/draw invalidations if text is unchanged
                    if (label != lastOverlayLabel) {
                        lastOverlayLabel = label
                        view?.post { view?.text = label }
                    }

                    FpsState.currentFps = fpsVal
                    FpsState.currentLabel = label
                    mainHandler.post {
                        FpsState.listener?.invoke(fpsVal, label)
                        if (!isUserInteracting) {
                            adjustPositionRunnable.run()
                        }
                    }

                    if (currentFrames != null && fpsVal != null) {
                        val syncIntent = Intent(ACTION_FPS_UPDATE).apply {
                            setPackage("com.android.systemui")
                            putExtra("fps", fpsStr)
                        }
                        sendBroadcast(syncIntent)

                        // Lightweight self-check: verify status bar acknowledged identical reading.
                        // If mismatched or lagging, re-dispatch immediate resync.
                        val nowMs = SystemClock.elapsedRealtime()
                        if (lastStatusBarAckFps != null && lastStatusBarAckFps != fpsStr && nowMs - lastStatusBarAckTime < 3500L) {
                            mainHandler.postDelayed({
                                if (alive) {
                                    val resync = Intent(ACTION_FPS_UPDATE).apply {
                                        setPackage("com.android.systemui")
                                        putExtra("fps", fpsStr)
                                    }
                                    sendBroadcast(resync)
                                }
                            }, 120)
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                val errLabel = "no root"
                view?.post { view?.text = errLabel }
                FpsState.currentFps = null
                FpsState.currentLabel = errLabel
                mainHandler.post {
                    FpsState.listener?.invoke(null, errLabel)
                }
            } finally {
                runCatching {
                    w?.write("exit\n")
                    w?.flush()
                    proc?.waitFor()
                }
                proc?.destroy()
                FpsState.isRunning = false
                FpsTileService.requestUpdate(this@FpsService)
            }
        }, "fps-reader").apply { start() }
    }
}
