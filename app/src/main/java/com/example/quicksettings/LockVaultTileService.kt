package com.example.quicksettings

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.R
import com.example.security.QuickTileCommand
import com.example.security.VaultQuickActionBus

@RequiresApi(Build.VERSION_CODES.N)
class LockVaultTileService : TileService() {

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
    if (VaultQuickActionBus.isVaultUnlocked.value) {
      VaultQuickActionBus.dispatch(QuickTileCommand.LOCK_VAULT)
      VaultQuickActionBus.updateVaultState(unlocked = false)
    }
    refreshTile(forceLocked = true)
  }

  private fun refreshTile(forceLocked: Boolean = false) {
    val tile = qsTile ?: return
    val isUnlocked = !forceLocked && VaultQuickActionBus.isVaultUnlocked.value
    tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_lock_vault)
    tile.label = getString(R.string.qs_tile_lock_vault)
    tile.state = if (isUnlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.contentDescription = if (isUnlocked) {
      "BeastLock is unlocked. Tap to lock vault immediately."
    } else {
      "BeastLock vault is locked."
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      tile.subtitle = if (isUnlocked) "Unlocked • Tap to Lock" else "Vault Locked"
    }
    tile.updateTile()
  }
}
