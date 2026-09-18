package id.dotcode.braille.ocr.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * Vibration feedback that means the same thing on every phone. Flagship phones can play
 * crisp primitives, mid-range ones can vary strength, and the cheapest can only switch the
 * motor on and off - so every pattern is defined three ways and the best available is used.
 *
 * Position-based haptics (left/right) are not used: almost no phone has more than one motor.
 * Direction is carried by speech and by a panned tone instead.
 */
class Haptics(context: Context, private val enabled: () -> Boolean) {

    enum class Level { RICH, AMPLITUDE, BASIC, NONE }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    val level: Level = when {
        vibrator?.hasVibrator() != true -> Level.NONE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsPrimitives() -> Level.RICH
        vibrator.hasAmplitudeControl() -> Level.AMPLITUDE
        else -> Level.BASIC
    }

    private val rich: Boolean get() = level == Level.RICH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** One light tap: a word moved, a step completed. */
    fun tick() {
        if (rich) {
            vibrate(primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f))
        } else if (level == Level.AMPLITUDE) {
            vibrate(VibrationEffect.createOneShot(18, 90))
        } else {
            vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Aiming feedback: [closeness] 0..1, stronger and crisper as the page lines up. */
    fun aim(closeness: Float) {
        val strength = closeness.coerceIn(0f, 1f)
        if (rich) {
            vibrate(primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.3f + 0.7f * strength))
        } else if (level == Level.AMPLITUDE) {
            vibrate(VibrationEffect.createOneShot(15, (60 + 195 * strength).toInt().coerceIn(1, 255)))
        } else {
            vibrate(VibrationEffect.createOneShot(if (strength > 0.66f) 30L else 12L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Two quick taps: the scan worked, the photo was taken, the device is connected. */
    fun success() {
        if (rich) {
            vibrate(doubleClick())
        } else if (level == Level.AMPLITUDE) {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 45), intArrayOf(0, 160, 0, 220), -1))
        } else {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 45), -1))
        }
    }

    /** One long buzz: something went wrong and the student has to do something. */
    fun failure() {
        if (rich) {
            vibrate(primitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f))
        } else if (level == Level.AMPLITUDE) {
            vibrate(VibrationEffect.createOneShot(320, 200))
        } else {
            vibrate(VibrationEffect.createOneShot(320, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        if (!enabled() || level == Level.NONE) return
        runCatching { vibrator?.vibrate(effect) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun primitive(id: Int, scale: Float): VibrationEffect =
        VibrationEffect.startComposition().addPrimitive(id, scale.coerceIn(0f, 1f)).compose()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doubleClick(): VibrationEffect =
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 90)
            .compose()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun supportsPrimitives(): Boolean =
        vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
        ) == true
}
