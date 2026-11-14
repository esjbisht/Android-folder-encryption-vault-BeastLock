package com.example.security

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

enum class PanicSensitivity(
  val thresholdG: Float,
  val label: String,
  val description: String,
) {
  SENSITIVE(
    thresholdG = 2.1f,
    label = "Sensitive",
    description = "Triggers on a quick wrist flick",
  ),
  STANDARD(
    thresholdG = 2.7f,
    label = "Standard",
    description = "Balanced for deliberate shakes",
  ),
  FIRM(
    thresholdG = 3.4f,
    label = "Firm",
    description = "Requires a vigorous shake",
  );

  companion object {
    fun fromName(name: String?): PanicSensitivity {
      return entries.firstOrNull { it.name == name } ?: STANDARD
    }
  }
}

/**
 * Pure-Kotlin accelerometer shake detector normalized by Earth gravity (`g`).
 * Requires [requiredShakes] distinct acceleration peaks spaced at least [minPeakSpacingMs]
 * apart within [shakeWindowMs], followed by a [cooldownMs] lockout to avoid duplicate triggers.
 */
class ShakeDetector(
  private var sensitivity: PanicSensitivity = PanicSensitivity.STANDARD,
  private val requiredShakes: Int = 2,
  private val minPeakSpacingMs: Long = 120L,
  private val shakeWindowMs: Long = 700L,
  private val cooldownMs: Long = 1500L,
  private val onShakeDetected: () -> Unit,
) {
  private var shakeCount = 0
  private var windowStartMs = 0L
  private var lastPeakMs = 0L
  private var lastTriggerMs = 0L

  fun updateSensitivity(newSensitivity: PanicSensitivity) {
    sensitivity = newSensitivity
  }

  fun reset() {
    shakeCount = 0
    windowStartMs = 0L
    lastPeakMs = 0L
  }

  fun onAccelerationSample(
    x: Float,
    y: Float,
    z: Float,
    timestampMs: Long,
  ): Boolean {
    if (lastTriggerMs > 0L && timestampMs - lastTriggerMs < cooldownMs) {
      return false
    }

    val gX = x / SensorManager.GRAVITY_EARTH
    val gY = y / SensorManager.GRAVITY_EARTH
    val gZ = z / SensorManager.GRAVITY_EARTH
    val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

    if (gForce < sensitivity.thresholdG) {
      if (shakeCount > 0 && timestampMs - windowStartMs > shakeWindowMs) {
        reset()
      }
      return false
    }

    if (shakeCount > 0 && timestampMs - windowStartMs > shakeWindowMs) {
      reset()
    }

    if (lastPeakMs > 0L && timestampMs - lastPeakMs < minPeakSpacingMs) {
      return false
    }

    if (shakeCount == 0) {
      windowStartMs = timestampMs
    }
    lastPeakMs = timestampMs
    shakeCount++

    if (shakeCount >= requiredShakes) {
      reset()
      lastTriggerMs = timestampMs
      onShakeDetected()
      return true
    }

    return false
  }
}

/**
 * Lifecycle-scoped accelerometer listener for BeastLock Panic Mode.
 * Uses [SensorManager.SENSOR_DELAY_UI] which requires no extra sensor permissions.
 */
class PanicShakeMonitor(
  context: Context,
  initialSensitivity: PanicSensitivity,
  onPanicShake: () -> Unit,
) : SensorEventListener {

  private val sensorManager = context.applicationContext
    .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private val shakeDetector = ShakeDetector(
    sensitivity = initialSensitivity,
    onShakeDetected = onPanicShake,
  )

  private var isRegistered = false

  val hasAccelerometer: Boolean
    get() = accelerometer != null

  fun updateSensitivity(sensitivity: PanicSensitivity) {
    shakeDetector.updateSensitivity(sensitivity)
  }

  fun start() {
    val accel = accelerometer ?: return
    if (!isRegistered) {
      shakeDetector.reset()
      isRegistered = sensorManager?.registerListener(
        this,
        accel,
        SensorManager.SENSOR_DELAY_UI,
      ) == true
    }
  }

  fun stop() {
    if (isRegistered) {
      sensorManager?.unregisterListener(this)
      isRegistered = false
    }
    shakeDetector.reset()
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
    val values = event.values
    if (values.size < 3) return
    shakeDetector.onAccelerationSample(
      x = values[0],
      y = values[1],
      z = values[2],
      timestampMs = System.currentTimeMillis(),
    )
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
