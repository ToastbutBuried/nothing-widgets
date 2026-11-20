package dev.toast.nothingwidgets

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: start / stop the visualizer. */
class VizTile : TileService() {
    override fun onStartListening() = show(VisualizerService.active)

    private fun show(on: Boolean) { qsTile?.apply { state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() } }

    override fun onClick() {
        if (VisualizerService.active) {
            startService(Intent(this, VisualizerService::class.java).setAction(VisualizerService.ACTION_STOP)); show(false); return
        }
        // a microphone-type service may only start while the app is visible: go through a see-through activity
        val i = Intent(this, VizStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}

/** Invisible: starts the visualizer while on screen for a moment, then closes. */
class VizStartActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!VisualizerService.active) startForegroundService(Intent(this, VisualizerService::class.java))
        } else startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

/** Quick Settings tile: artwork widget between cover and lyrics. */
class ArtTile : TileService() {
    override fun onStartListening() = show()

    private fun show() {
        qsTile?.apply {
            state = if (Prefs.artMode(this@ArtTile) == 1) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (Prefs.artMode(this@ArtTile) == 1) "Lyrics" else "Cover"
            updateTile()
        }
    }

    override fun onClick() {
        val m = 1 - Prefs.artMode(this)
        MediaListenerService.instance?.setArtMode(m) ?: run { Prefs.setArtMode(this, m); ArtWidgetProvider.draw(this, ArtWidgetProvider.ids(this)) }
        show()
    }
}
