package io.ak1.drawbox.input

private val isMacOs: Boolean by lazy {
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
}

internal actual fun shiftWheelDeltaScale(): Float = if (isMacOs) 10f else 1f
