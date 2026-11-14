package com.example.security

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import com.example.quicksettings.LockVaultTileService
import com.example.quicksettings.PanicModeTileService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QuickTileCommand {
  LOCK_VAULT,
  PANIC_MODE,
}

/**
 * In-process command and lock-state bridge connecting BeastLock's Quick Settings tiles
 * with the active VaultViewModel session.
 */
object VaultQuickActionBus {
  private val _isVaultUnlocked = MutableStateFlow(false)
  val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

  @Volatile
  var panicExitToDeviceHome: Boolean = true

  private val _commands = MutableSharedFlow<QuickTileCommand>(extraBufferCapacity = 8)
  val commands: SharedFlow<QuickTileCommand> = _commands.asSharedFlow()

  fun updateVaultState(
    unlocked: Boolean,
    panicExitToHome: Boolean = this.panicExitToDeviceHome,
    context: Context? = null,
  ) {
    val changed = _isVaultUnlocked.value != unlocked
    _isVaultUnlocked.value = unlocked
    panicExitToDeviceHome = panicExitToHome
    if (changed && context != null) {
      requestTileRefresh(context)
    }
  }

  fun dispatch(command: QuickTileCommand) {
    _commands.tryEmit(command)
  }

  fun requestTileRefresh(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      runCatching {
        TileService.requestListeningState(
          context,
          ComponentName(context, LockVaultTileService::class.java),
        )
        TileService.requestListeningState(
          context,
          ComponentName(context, PanicModeTileService::class.java),
        )
      }
    }
  }
}
