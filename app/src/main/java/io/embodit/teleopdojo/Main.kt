package io.embodit.teleopdojo

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.teleopdojo.ui.TdScreen
import io.embodit.teleopdojo.ui.components.PanelSizes
import io.embodit.teleopdojo.ui.theme.TdTheme
import io.embodit.teleopdojo.ui.window.TdWindow
import io.embodit.teleopdojo.ui.window.PANEL_ID

/**
 * App entry (design container STAGE_MIXED, skeleton D). The default Stage is Mixed (manifest pico.spatial.stage.style=1):
 * the course and the drone stand on your real floor. v0.1.2 (profile D8 / D21, factory snippet native-window-move):
 * all 2D content is one system Planar window opened from the Stage; the system moves / closes / minimises it.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { TdTheme { TdScreen() } }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PanelSizes.WINDOW_W.dp, height = PanelSizes.WINDOW_H.dp),
        ) { TdWindow() }
    }
