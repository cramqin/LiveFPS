package dev.livefps

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Quick Settings Tile allowing one-tap toggle of the LiveFPS floating overlay
 * from the Android notification shade / control center.
 */
class FpsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (FpsState.isRunning) {
            val stopIntent = Intent(this, FpsService::class.java).setAction(FpsService.ACTION_STOP)
            startService(stopIntent)
            updateTileState(false)
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "LiveFPS: Overlay permission required", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }
            val startIntent = Intent(this, FpsService::class.java).setAction(FpsService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            updateTileState(true)
        }
    }

    private fun updateTileState(overrideRunning: Boolean? = null) {
        val tile = qsTile ?: return
        val isRunning = overrideRunning ?: FpsState.isRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "LiveFPS"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_fps)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (isRunning) FpsState.currentLabel else "Off"
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, FpsTileService::class.java))
            }
        }
    }
}
