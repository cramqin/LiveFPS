package dev.livefps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * FPS source: SurfaceFlinger TimeStats (root). Every configured polling interval
 * we read `totalFrames` (frames actually presented to the display) and clear
 * the counter, so frames / elapsed seconds = real on-screen FPS.
 * On LTPO / VRR displays this drops to 1-3 fps at idle, matching the developer-options overlay.
 */
class FpsService : Service() {

    companion object {
        const val PREFS_NAME = "livefps_prefs"
        const val KEY_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 1000L

        @Volatile var running = false
        @Volatile var pollingIntervalMs = DEFAULT_INTERVAL_MS
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
        startWorker()
        return START_STICKY
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
        worker?.interrupt()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
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
        val dp = resources.displayMetrics.density
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (80 * dp).toInt()
        }

        // Drag to move.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(v, lp)
                }
            }
            true
        }

        wm.addView(tv, lp)
        view = tv
    }

    private fun startWorker() {
        alive = true
        FpsState.isRunning = true
        worker = Thread({
            var proc: Process? = null
            var w: BufferedWriter? = null
            var r: BufferedReader? = null

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
                    val currentInterval = pollingIntervalMs.coerceAtLeast(100L)
                    Thread.sleep(currentInterval)
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

                    view?.post { view?.text = label }

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
            }
        }, "fps-reader").apply { start() }
    }
}
