package dev.livefps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Single screen: one button to start/stop the overlay. */
class MainActivity : Activity() {

    private lateinit var button: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 15f }
        button = Button(this).apply { setOnClickListener { toggle() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(button)
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggle() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val svc = Intent(this, FpsService::class.java)
        if (FpsService.running) stopService(svc) else startForegroundService(svc)
        button.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        status.text = when {
            !Settings.canDrawOverlays(this) -> "Allow \"Display over other apps\" first.\nRoot is also required."
            FpsService.running -> "Overlay running. Drag the box to move it."
            else -> "Overlay stopped."
        }
        button.text = when {
            !Settings.canDrawOverlays(this) -> "Grant overlay permission"
            FpsService.running -> "Stop overlay"
            else -> "Start overlay"
        }
    }
}
