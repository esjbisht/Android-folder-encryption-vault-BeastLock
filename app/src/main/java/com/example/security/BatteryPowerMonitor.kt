package com.example.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DevicePowerState(
  val batteryPercent: Int = 100,
  val isCharging: Boolean = false,
  val isSystemPowerSaveMode: Boolean = false,
  val isBatterySaverPaused: Boolean = false,
) {
  val isLowBatteryBelow20: Boolean get() = batteryPercent in 0 until LOW_BATTERY_THRESHOLD_PERCENT

  companion object {
    const val LOW_BATTERY_THRESHOLD_PERCENT = 20
  }
}

/**
 * Monitors device battery percentage, charging status, and Android Power Save mode.
 *
 * Applies hysteresis: Battery Saver pause activates below 20% battery or when system Power Save
 * mode is enabled, and clears only once battery rises strictly above 20% (> 20%) and Power Save
 * mode turns off.
 */
class BatteryPowerMonitor(
  private val context: Context,
) {
  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
  private val _powerState = MutableStateFlow(readCurrentSystemState(prevPaused = false))
  val powerState: StateFlow<DevicePowerState> = _powerState.asStateFlow()

  private var isRegistered = false
  private var lockOverrideForTests = false

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
      if (lockOverrideForTests) return
      refreshFromSystem(batteryIntent = if (intent?.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
    }
  }

  fun start() {
    if (isRegistered) return
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_BATTERY_CHANGED)
      addAction(Intent.ACTION_BATTERY_LOW)
      addAction(Intent.ACTION_BATTERY_OKAY)
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction(Intent.ACTION_POWER_DISCONNECTED)
      addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
    }
    ContextCompat.registerReceiver(
      context,
      receiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    isRegistered = true
    if (!lockOverrideForTests) {
      refreshFromSystem()
    }
  }

  fun stop() {
    if (!isRegistered) return
    runCatching { context.unregisterReceiver(receiver) }
    isRegistered = false
  }

  fun refreshFromSystem(batteryIntent: Intent? = null) {
    if (lockOverrideForTests) return
    _powerState.value = readCurrentSystemState(
      prevPaused = _powerState.value.isBatterySaverPaused,
      stickyIntent = batteryIntent,
    )
  }

  fun updateForTesting(
    batteryPercent: Int,
    isCharging: Boolean,
    isSystemPowerSaveMode: Boolean,
  ) {
    lockOverrideForTests = true
    val nextPaused = evaluatePauseState(
      previouslyPaused = _powerState.value.isBatterySaverPaused,
      batteryPercent = batteryPercent,
      isSystemPowerSaveMode = isSystemPowerSaveMode,
    )
    _powerState.value = DevicePowerState(
      batteryPercent = batteryPercent,
      isCharging = isCharging,
      isSystemPowerSaveMode = isSystemPowerSaveMode,
      isBatterySaverPaused = nextPaused,
    )
  }

  private fun readCurrentSystemState(
    prevPaused: Boolean,
    stickyIntent: Intent? = null,
  ): DevicePowerState {
    val intent = stickyIntent ?: runCatching {
      context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) {
      ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
    } else {
      100
    }
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
      status == BatteryManager.BATTERY_STATUS_FULL
    val powerSave = powerManager?.isPowerSaveMode == true

    val paused = evaluatePauseState(
      previouslyPaused = prevPaused,
      batteryPercent = percent,
      isSystemPowerSaveMode = powerSave,
    )
    return DevicePowerState(
      batteryPercent = percent,
      isCharging = isCharging,
      isSystemPowerSaveMode = powerSave,
      isBatterySaverPaused = paused,
    )
  }

  companion object {
    fun evaluatePauseState(
      previouslyPaused: Boolean,
      batteryPercent: Int,
      isSystemPowerSaveMode: Boolean,
    ): Boolean {
      if (isSystemPowerSaveMode || batteryPercent in 0 until DevicePowerState.LOW_BATTERY_THRESHOLD_PERCENT) {
        return true
      }
      if (previouslyPaused) {
        // Hysteresis: once paused, resume only after battery charges strictly above 20% (> 20%)
        return batteryPercent <= DevicePowerState.LOW_BATTERY_THRESHOLD_PERCENT
      }
      return false
    }
  }
}
