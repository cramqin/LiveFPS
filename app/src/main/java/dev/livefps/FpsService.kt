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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
        const val DEFAULT_INTERVAL_MS = 1000L

        @Volatile var running = false
        @Volatile var pollingIntervalMs = DEFAULT_INTERVAL_MS
        const val ACTION_START = "dev.livefps.START"
        const val ACTION_STOP = "dev.livefps.STOP"
        private const val CHANNEL = "fps"
        private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

        fun updateInterval(context: Context, newIntervalMs: Long) {
            pollingIntervalMs = newIntervalMs
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_INTERVAL_MS, newIntervalMs)
                .apply()
        }

        fun getSavedInterval(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
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

    private var currentStatusBarTop = 0
    private var savedUserY = -1
    private var isShadeShifted = false

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
            }
            registerReceiver(screenReceiver, filter)
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

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        currentStatusBarTop = if (isLandscape) 0 else systemStatusBarHeight

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
            y = (currentStatusBarTop - 20 * dp).toInt().coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        savedUserY = lp.y

        val screenWidth: Int
        val screenHeight: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val d = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(d)
            screenWidth = d.widthPixels
            screenHeight = d.heightPixels
        }

        // Drag to move with screen boundary and zero-margin freedom
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val width = v.width.takeIf { it > 0 } ?: (60 * dp).toInt()
                    val height = v.height.takeIf { it > 0 } ?: (30 * dp).toInt()
                    val maxX = screenWidth - width
                    val maxY = screenHeight - height
                    val minY = 0 // Zero forced margin: can sit right flush against or under status bar icons

                    val targetX = (startX + (e.rawX - touchX).toInt()).coerceIn(0, maxX.coerceAtLeast(0))
                    val targetY = (startY + (e.rawY - touchY).toInt()).coerceIn(minY, maxY.coerceAtLeast(minY))

                    lp.x = targetX
                    lp.y = targetY
                    savedUserY = targetY
                    isShadeShifted = false
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savedUserY = lp.y
                    isShadeShifted = false
                }
            }
            true
        }

        // Handle notification shade pull-down & dismissal in fullscreen
        tv.setOnApplyWindowInsetsListener { v, insets ->
            val isLandscapeMode = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val statusBarInsets = insets.getInsets(WindowInsets.Type.statusBars())
            val isStatusBarVisible = if (Build.VERSION.SDK_INT >= 30) {
                insets.isVisible(WindowInsets.Type.statusBars())
            } else {
                statusBarInsets.top > 0
            }
            val newStatusBarTop = if (isStatusBarVisible) statusBarInsets.top else 0
            currentStatusBarTop = newStatusBarTop

            if (isLandscapeMode) {
                if (savedUserY < 0) {
                    savedUserY = lp.y
                }
                if (newStatusBarTop > 0) {
                    // Fullscreen landscape: shade pulled down, shift down temporarily if overlapping
                    if (savedUserY < newStatusBarTop) {
                        lp.y = newStatusBarTop
                        isShadeShifted = true
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                } else {
                    // Fullscreen landscape: shade hidden again, restore saved position
                    if (isShadeShifted) {
                        lp.y = savedUserY
                        isShadeShifted = false
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
            }
            insets
        }

        wm.addView(tv, lp)
        view = tv
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.let { v ->
            val dm = resources.displayMetrics
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
            val width = v.width.takeIf { it > 0 } ?: (60 * dm.density).toInt()
            val height = v.height.takeIf { it > 0 } ?: (30 * dm.density).toInt()
            val maxX = (dm.widthPixels - width).coerceAtLeast(0)
            val maxY = (dm.heightPixels - height).coerceAtLeast(0)

            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape) {
                currentStatusBarTop = 0
            } else {
                val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
                currentStatusBarTop = if (resId > 0) resources.getDimensionPixelSize(resId) else (28 * dm.density).toInt()
            }

            val minY = 0
            lp.x = lp.x.coerceIn(0, maxX)
            lp.y = lp.y.coerceIn(minY, maxY.coerceAtLeast(minY))
            savedUserY = lp.y
            isShadeShifted = false
            runCatching { wm.updateViewLayout(v, lp) }
        }
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
                sh("dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1")
                var last = SystemClock.elapsedRealtimeNanos()

                while (alive) {
                    // Battery Saver: If screen is turned off, pause polling completely
                    if (!screenOn) {
                        sh("dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1")
                        synchronized(pauseLock) {
                            while (!screenOn && alive) {
                                pauseLock.wait()
                            }
                        }
                        if (!alive) break
                        // Resumed after screen on: re-enable and reset timestamp
                        sh("dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1")
                        sh("dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1")
                        last = SystemClock.elapsedRealtimeNanos()
                    }

                    val currentInterval = pollingIntervalMs.coerceAtLeast(100L)
                    Thread.sleep(currentInterval)
                    if (!screenOn || !alive) continue

                    val out = sh(
                        "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | grep -m1 totalFrames; " +
                            "dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1"
                    )
                    val now = SystemClock.elapsedRealtimeNanos()
                    val secs = (now - last) / 1e9
                    last = now
                    val frames = out.firstNotNullOfOrNull {
                        FRAMES_RE.find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    val fpsVal = if (frames != null && secs > 0) frames / secs else null
                    val label = if (frames == null) "no root" else "%.0f fps".format(fpsVal ?: 0.0)

                    // Avoid redundant layout/draw invalidations if text is unchanged
                    if (label != lastOverlayLabel) {
                        lastOverlayLabel = label
                        view?.post { view?.text = label }
                    }

                    FpsState.currentFps = fpsVal
                    FpsState.currentLabel = label
                    mainHandler.post {
                        FpsState.listener?.invoke(fpsVal, label)
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
                // Leave SurfaceFlinger the way we found it.
                runCatching {
                    w?.write("dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1\nexit\n")
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
