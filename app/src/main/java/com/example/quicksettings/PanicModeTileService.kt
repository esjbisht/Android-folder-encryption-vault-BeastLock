package com.example.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.R
import com.example.security.QuickTileCommand
import com.example.security.VaultQuickActionBus

@RequiresApi(Build.VERSION_CODES.N)
class PanicModeTileService : TileService() {

  override fun onTileAdded() {
    super.onTileAdded()
    refreshTile()
  }

  override fun onStartListening() {
    super.onStartListening()
    refreshTile()
  }

  override fun onClick() {
    super.onClick()
    VaultQuickActionBus.dispatch(QuickTileCommand.PANIC_MODE)
    VaultQuickActionBus.updateVaultState(unlocked = false)
    refreshTile(forceSealed = true)

    if (VaultQuickActionBus.panicExitToDeviceHome) {
      val homeIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pendingIntent = PendingIntent.getActivity(
          this,
          1002,
          homeIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { startActivityAndCollapse(pendingIntent) }
      } else {
        @Suppress("DEPRECATION")
        runCatching { startActivityAndCollapse(homeIntent) }
      }
    }
  }

  private fun refreshTile(forceSealed: Boolean = false) {
    val tile = qsTile ?: return
    val isUnlocked = !forceSealed && VaultQuickActionBus.isVaultUnlocked.value
    tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_panic_mode)
    tile.label = getString(R.string.qs_tile_panic_mode)
    tile.state = if (isUnlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.contentDescription = if (isUnlocked) {
      "BeastLock Panic Mode armed. Tap to immediately seal vault and exit to home."
    } else {
      "BeastLock Panic Mode standby. Vault is sealed."
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      tile.subtitle = if (isUnlocked) "Tap to Trigger Panic" else "Vault Sealed"
    }
    tile.updateTile()
  }
}
