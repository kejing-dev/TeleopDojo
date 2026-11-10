package io.embodit.teleopdojo.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.teleopdojo.ui.Lang
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中 / EN), switched live (Rehearsal Hall Strings.kt): the
 * screen provides a Resources object for that locale and every panel reads its strings from it.
 */
val LocalTdResources = staticCompositionLocalOf<Resources> { error("LocalTdResources not provided") }

fun localizedResources(context: Context, lang: Lang): Resources {
    val tag =
        when (lang) {
            Lang.SYSTEM -> return context.resources
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalTdResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalTdResources.current.getString(id, *args)

/**
 * Sizes, dp. All 2D content is ONE system Planar window (profile D8 / D21, native-window-move): 600 x 620 (design 560 x
 * 520 grown so the settings page holds every row on its first screen, B19 / factory gate settings-shot; deviation
 * logged). On the Stage only the "打开面板" chip on its post and the tip tag over the table, both at scale 0.75.
 */
object PanelSizes {
    const val SCALE = 0.75f
    const val WINDOW_W = 600f
    const val WINDOW_H = 620f
    /** Bottom strip kept free on pages with the system Toolbar (it straddles the window's lower edge). */
    const val TOOLBAR_CLEAR = 72f
    const val OPEN_W = 320f
    const val OPEN_H = 88f
    const val TIP_W = 380f
    const val TIP_H = 84f
}
