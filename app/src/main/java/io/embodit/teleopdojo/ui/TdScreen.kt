package io.embodit.teleopdojo.ui

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.ui.components.LocalTdResources
import io.embodit.teleopdojo.ui.components.TdStage
import io.embodit.teleopdojo.ui.components.localizedResources
import io.embodit.teleopdojo.ui.debug.Probe
import io.embodit.teleopdojo.ui.scene.TdFrameSystem
import io.embodit.teleopdojo.ui.scene.TdScene
import io.embodit.teleopdojo.ui.window.WindowCtl

/**
 * The Stage screen: wires [TdController] (state), [TdScene] (entities, per-frame work) and [TdStage] (the chip and the
 * tip tag), and opens the main window ([io.embodit.teleopdojo.ui.window.TdWindow]).
 */
@Composable
fun TdScreen() {
    val context = LocalContext.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        TdController.start(context)
        true
    }
    val state by TdController.state.collectAsStateWithLifecycle()
    val scene = remember { TdScene() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<TdFrameSystem>()
        Probe.host = object : Probe.Host { override fun scene(): TdScene = scene }
        // background: a round pauses (S17), trackers stop; back: trackers resume, the round waits for 继续
        val observer =
            LifecycleEventObserver { _, event ->
                TdLog.i("LIFECYCLE event=$event")
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        TdController.onEvent(Ui.Background(true))
                        scene.pause()
                    }
                    Lifecycle.Event.ON_START -> {
                        scene.resume()
                        TdController.onEvent(Ui.Background(false))
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        TdLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<TdFrameSystem>()
            scene.detach()
            TdLog.i("STAGE disposed")
        }
    }

    // the main window (D8 / D21): opened by the Stage when it comes up (once the head is level, D9); "叫回面板" closes and re-opens it
    val navigator = LocalSpatialNavigator.current
    DisposableEffect(navigator) {
        WindowCtl.navigator = navigator
        onDispose { if (WindowCtl.navigator === navigator) WindowCtl.navigator = null }
    }
    LaunchedEffect(Unit) { WindowCtl.openWindow("stage") }
    LaunchedEffect(state.panelEpoch) { if (state.panelEpoch > 0) WindowCtl.recall("panel ${state.panelEpoch}") }

    val resources = remember(state.prefs.lang, context) { localizedResources(context, state.prefs.lang) }
    CompositionLocalProvider(LocalTdResources provides resources) {
        TdStage(state = state, scene = scene)
    }
}
