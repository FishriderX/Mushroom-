package com.example.pikminhelper.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.pikminhelper.HelperPrefs

class HelperTileService : TileService() {
    private val prefs by lazy { HelperPrefs(this) }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        prefs.enabled = !prefs.enabled
        refresh()
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (prefs.enabled) "蘑菇助手 ON" else "蘑菇助手 OFF"
            updateTile()
        }
    }
}
