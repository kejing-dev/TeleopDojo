package io.embodit.teleopdojo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.ui.TdUiState
import io.embodit.teleopdojo.ui.debug.LocalProbePanel
import io.embodit.teleopdojo.ui.debug.Probe
import io.embodit.teleopdojo.ui.scene.TdScene
import io.embodit.teleopdojo.ui.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView holding the workcell, the UR5e and the drill objects ([TdScene]) and the
 * two small attachment panels left on the Stage: "打开面板" on its post at the table corner (and "平视前方" while the window
 * waits for a level head) and the tip tag over the table. All other 2D content is the system main window. No taps or
 * drags on the Stage (D21; the S02 marker is placed with the controller ray + trigger).
 */
@Composable
internal fun TdStage(state: TdUiState, scene: TdScene) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        attachments = {
            AttachmentPanel(PanelIds.OPEN) { PoolPanel(PanelIds.OPEN, scene) { inView -> OpenChip(inView) } }
            AttachmentPanel(PanelIds.TIP) { PoolPanel(PanelIds.TIP, scene) { inView -> TipTag(if (inView) state else null) } }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments, context) },
    )
}

/** Root of a pooled panel: content composes once the panel is in view (Spatial Ruler finding 3); debug logs presses. */
@Composable
private fun PoolPanel(id: String, scene: TdScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val watcher =
        Modifier.pointerInput(id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    if (Probe.enabled) {
                        val p = event.changes.firstOrNull()?.position
                        TdLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
