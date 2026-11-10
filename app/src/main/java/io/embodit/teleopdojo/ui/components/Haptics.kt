package io.embodit.teleopdojo.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.pico.spatial.ui.platform.HandController
import com.pico.spatial.ui.platform.HandControllerHapticType
import com.pico.spatial.ui.platform.LocalHandControllerHaptic
import com.pico.spatial.ui.platform.SpatialHandControllerHaptic
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.ui.TdController

/**
 * Controller haptics (design rules.haptics, SDK 6.1 controller-haptic-feedback Method 3): the instance only exists inside
 * a PicoTheme composable, so the window hands it over here; the frame system's events call it on the main thread, on
 * the teleop hand. Grab / light = Press; table = (200, 160, 60); joint limit = (80, 120, 25) at most once a second.
 */
object Haptics {
    private var haptic: SpatialHandControllerHaptic? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastJointMs = 0L

    @Composable
    fun Bind() {
        val h = LocalHandControllerHaptic.current
        DisposableEffect(h) {
            haptic = h
            TdController.haptic = { kind -> main.post { play(kind) } }
            onDispose { if (haptic === h) haptic = null }
        }
    }

    private fun play(kind: String) {
        val h = haptic ?: return
        val hand = if (TdController.state.value.prefs.leftHanded) HandController.Left else HandController.Right
        val type = when (kind) {
            "table" -> HandControllerHapticType.custom(200, 160, 60)
            "joint" -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastJointMs < 1000) return
                lastJointMs = now
                HandControllerHapticType.custom(80, 120, 25)
            }
            else -> HandControllerHapticType.Press
        }
        runCatching { h.feedback(type, hand) }.onFailure { TdLog.w("HAPTIC $kind failed: $it") }
    }
}
