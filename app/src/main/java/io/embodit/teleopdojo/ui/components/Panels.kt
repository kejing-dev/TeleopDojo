package io.embodit.teleopdojo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.teleopdojo.R
import io.embodit.teleopdojo.domain.ControlMode
import io.embodit.teleopdojo.domain.Difficulty
import io.embodit.teleopdojo.domain.FreePlay
import io.embodit.teleopdojo.domain.LevelId
import io.embodit.teleopdojo.domain.MappingMode
import io.embodit.teleopdojo.domain.Oops
import io.embodit.teleopdojo.domain.Scoring
import io.embodit.teleopdojo.ui.ControllerState
import io.embodit.teleopdojo.ui.DIFFS
import io.embodit.teleopdojo.ui.Hint
import io.embodit.teleopdojo.ui.Lang
import io.embodit.teleopdojo.ui.Prefs
import io.embodit.teleopdojo.ui.Screen
import io.embodit.teleopdojo.ui.TdUiState
import io.embodit.teleopdojo.ui.Ui
import io.embodit.teleopdojo.ui.debug.probe
import io.embodit.teleopdojo.ui.theme.TdColors
import io.embodit.teleopdojo.ui.theme.TdType
import java.util.Locale
import kotlin.math.roundToInt

// ================================================================================================= building blocks

enum class KeyKind { PRIMARY, DANGER, NORMAL }

/**
 * A text key (>= 48 dp, B19) with an optional icon. Primary (遥操黄) and danger (light red) carry the dark ink #16181C;
 * normal keys use the system button colours (a tertiary fill disappears on the window glass, factory template).
 */
@Composable
internal fun Key(text: String, name: String, icon: Int? = null, kind: KeyKind = KeyKind.NORMAL, height: Dp = 48.dp, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = when (kind) {
        KeyKind.PRIMARY -> ButtonDefaults.buttonColors(containerColor = TdColors.Interaction, contentColor = TdColors.OnFill)
        KeyKind.DANGER -> ButtonDefaults.buttonColors(containerColor = TdColors.DangerFill, contentColor = TdColors.OnFill)
        KeyKind.NORMAL -> ButtonDefaults.buttonColors(containerColor = TdColors.KeyFill, contentColor = TdColors.TagInk)
    }
    val ink = when (kind) {
        KeyKind.NORMAL -> TdColors.TagInk
        else -> TdColors.OnFill
    }
    val style = if (kind == KeyKind.DANGER) TdType.button.copy(fontWeight = FontWeight.Bold) else TdType.button
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.probe(name).height(height),
        size = ButtonDefaults.Regular.copy(height = height),
        shape = CircleShape,
        colors = colors,
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, maxLines = 2, color = ink, style = style, textAlign = TextAlign.Center, onTextLayout = fits("key/$name"))
    }
}

/** A status pill on a semantic fill (content #16181C, palette.rule) with an icon and a word (D23: never an icon alone). */
@Composable
private fun Pill(text: String, bg: Color, ink: Color, icon: Int?, name: String, modifier: Modifier = Modifier) {
    Row(modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg).padding(horizontal = 12.dp).probe(name), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = TdType.label, color = ink, maxLines = 1, onTextLayout = fits("pill/$name"))
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = TdColors.Interaction,
        selectedItemContentColor = TdColors.OnFill,
    )

/**
 * Options inside one page row (design spatialUiMap.segments: SegmentControl for in-page choices, never for pages), 48 dp
 * tall; every item takes the widest label + 26 dp so nothing is cut (D13).
 */
@Composable
private fun <T> Options(options: List<Pair<T, String>>, selected: T?, name: String, minItem: Int = 64, enabled: Boolean = true, onPick: (T) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TdType.label
    val w = remember(options.map { it.second }, minItem, density) {
        options.maxOf { (_, label) -> maxOf(minItem.dp, with(density) { (measurer.measure(label, style, maxLines = 1).size.width * 1.1f).toDp() } + 26.dp) }
    }
    SegmentControl(modifier = Modifier.width(w * options.size + 8.dp).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        options.forEach { (v, label) ->
            val sel = v == selected
            SegmentItem(
                selected = sel,
                onClick = { if (enabled) onPick(v) },
                modifier = Modifier.width(w).height(40.dp).probe("${name}_$v"),
                textStyle = style,
                colors = segColors(),
                title = { Text(text = label, maxLines = 1, color = if (sel) TdColors.OnFill else PicoTheme.colorScheme.labelPrimary, style = style, onTextLayout = fits("seg/${name}_$v")) },
            )
        }
    }
}

/** A labelled row: label on the left, control on the right (48 dp, B19). */
@Composable
private fun OptRow(label: String, name: String, height: Dp = 52.dp, note: String? = null, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).probe("row_$name"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = TdType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("row/$name"))
            if (note != null) Text(text = note, style = TdType.label.copy(fontSize = 14.sp), color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, onTextLayout = fits("row/${name}_note"))
        }
        Spacer(Modifier.width(8.dp))
        control()
    }
}

@Composable
private fun Title(text: String, name: String, modifier: Modifier = Modifier) =
    Text(text = text, style = TdType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = modifier.probe(name), onTextLayout = fits("title/$name"))

@Composable
private fun Body(text: String, name: String, modifier: Modifier = Modifier, maxLines: Int = 3, align: TextAlign = TextAlign.Start) =
    Text(text = text, style = TdType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, textAlign = align, modifier = modifier.probe(name), onTextLayout = fits("body/$name"))

@Composable
private fun Small(text: String, name: String, modifier: Modifier = Modifier, maxLines: Int = 3) =
    Text(text = text, style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, modifier = modifier.probe(name), onTextLayout = fits("small/$name"))

// ================================================================================================= copy helpers

fun levelName(l: LevelId): Int = when (l) { LevelId.REACH -> R.string.lv_reach; LevelId.PICK -> R.string.lv_pick; LevelId.STACK -> R.string.lv_stack; LevelId.FREE -> R.string.tab_free }

private fun levelTag(l: LevelId): Int = when (l) { LevelId.REACH -> R.string.lv_reach_tag; LevelId.PICK -> R.string.lv_pick_tag; LevelId.STACK -> R.string.lv_stack_tag; LevelId.FREE -> R.string.lv_free_tag }

private fun levelIcon(l: LevelId): Int = when (l) { LevelId.REACH -> R.drawable.ic_visibility; LevelId.PICK -> R.drawable.ic_cube; LevelId.STACK -> R.drawable.ic_format_list_numbered; LevelId.FREE -> R.drawable.ic_shuffle }

private fun diffName(d: Difficulty): Int = when (d) { Difficulty.EASY -> R.string.d_easy; Difficulty.STANDARD -> R.string.d_standard; Difficulty.PICKY -> R.string.d_picky }

private fun introLine(l: LevelId, d: Difficulty): Int = when (l) {
    LevelId.REACH -> when (d) { Difficulty.EASY -> R.string.intro_reach_easy; Difficulty.STANDARD -> R.string.intro_reach_standard; Difficulty.PICKY -> R.string.intro_reach_picky }
    LevelId.PICK -> when (d) { Difficulty.EASY -> R.string.intro_pick_easy; Difficulty.STANDARD -> R.string.intro_pick_standard; Difficulty.PICKY -> R.string.intro_pick_picky }
    LevelId.STACK -> when (d) { Difficulty.EASY -> R.string.intro_stack_easy; Difficulty.STANDARD -> R.string.intro_stack_standard; Difficulty.PICKY -> R.string.intro_stack_picky }
    LevelId.FREE -> R.string.lv_free_tag
}

@Composable
private fun mapLabel(key: String): String =
    if (key == "follow") rs(R.string.m_follow) else rs(R.string.map_clutch_x, key.removePrefix("clutch"))

@Composable
private fun hintText(state: TdUiState): String? = when (state.hint) {
    Hint.NONE -> if (state.live.unreachable) rs(R.string.h_unreachable) else null
    Hint.HOLD_GRIP -> rs(R.string.h_hold_grip)
    Hint.REGRAB -> rs(R.string.h_regrab)
    Hint.GOT_IT -> rs(R.string.h_got_it)
    Hint.TABLE -> rs(R.string.h_table)
    Hint.JOINT -> rs(R.string.h_joint, state.live.jointLimit + 1)
    Hint.SINGULAR -> rs(R.string.h_singular)
    Hint.SLIPPED -> rs(R.string.h_slipped)
    Hint.LOST -> rs(R.string.h_lost)
    Hint.ALMOST_IN -> rs(R.string.h_almost_in)
    Hint.ALMOST_TURN -> rs(R.string.h_almost_turn)
    Hint.ALMOST_SQUEEZE -> rs(R.string.h_almost_squeeze)
    Hint.LOOSENED -> rs(R.string.h_loosened)
    Hint.PRESS_GRIP_AGAIN -> rs(R.string.h_press_grip_again)
    Hint.TOWER -> rs(R.string.h_tower)
    Hint.PLACED -> rs(R.string.h_placed)
    Hint.DROPPED -> null
}

/** The comparison sentence of S15 from TdController's "mine|other|faster s|fewer table hits" keys. */
@Composable
private fun compareText(raw: String): String {
    val f = raw.split('|')
    if (f.size < 4) return raw
    val mine = mapLabel(f[0])
    val other = mapLabel(f[1])
    val dt = f[2].toIntOrNull() ?: 0
    val dTable = f[3].toIntOrNull() ?: 0
    val a = if (dt >= 0) rs(R.string.cmp_faster, mine, other, dt) else rs(R.string.cmp_slower, mine, other, -dt)
    val b = when {
        dTable > 0 -> rs(R.string.cmp_table_less, dTable)
        dTable < 0 -> rs(R.string.cmp_table_more, -dTable)
        else -> ""
    }
    return a + b
}

private fun stars(n: Int): String = "★".repeat(n.coerceIn(0, 3)) + "☆".repeat(3 - n.coerceIn(0, 3))

// ================================================================================================= top row

/**
 * Every page's top row (design panels.main.topRow, 56 dp): the clutch pill (握住 green / 松开 grey / 暂停 orange), the
 * gripper opening, the key reminder for the teleop hand, and the mute key with its word (D15 / D23).
 */
@Composable
internal fun TopRow(state: TdUiState, onEvent: (Ui) -> Unit) {
    val paused = state.screen == Screen.PAUSED
    val clutched = state.live.clutched && !paused
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            paused -> Pill(rs(R.string.st_paused), TdColors.Paused, TdColors.OnFill, R.drawable.ic_pause, "pill_state")
            clutched -> Pill(rs(R.string.st_clutched), TdColors.Clutched, TdColors.OnFill, R.drawable.ic_pan_tool, "pill_state")
            else -> Pill(rs(R.string.st_released), TdColors.Released, TdColors.OnFill, R.drawable.ic_pan_tool, "pill_state")
        }
        Column(Modifier.weight(1f)) {
            Text(text = rs(R.string.gripper_mm, state.live.openingMm), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.probe("gripper_mm"), onTextLayout = fits("top/gripper_mm"))
            Text(text = rs(if (state.prefs.leftHanded) R.string.keys_left else R.string.keys_right), style = TdType.label.copy(fontSize = 14.sp), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.probe("keys"), onTextLayout = fits("top/keys"))
        }
        Button(
            onClick = { onEvent(Ui.SetPrefs("mute") { it.copy(muted = !it.muted) }) },
            modifier = Modifier.height(48.dp).probe("mute"),
            size = ButtonDefaults.Regular.copy(height = 48.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = TdColors.KeyFill, contentColor = TdColors.TagInk),
        ) {
            Icon(painter = painterResource(if (state.prefs.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(22.dp), tint = TdColors.TagInk)
            Spacer(Modifier.width(6.dp))
            Text(text = rs(if (state.prefs.muted) R.string.unmute else R.string.mute), style = TdType.button, color = TdColors.TagInk, maxLines = 1, onTextLayout = fits("top/mute"))
        }
    }
}

/** S04 banner: by the provider's support state (design states.S04). */
@Composable
private fun ControllerBanner(state: TdUiState) {
    val text = when (state.controller) {
        ControllerState.OK -> return
        ControllerState.PENDING -> rs(R.string.nc_pending)
        ControllerState.NOT_SUPPORTED -> rs(R.string.nc_device)
        ControllerState.NO_PERMISSION -> rs(R.string.nc_permission)
        ControllerState.NOT_FULL_SPACE -> rs(R.string.nc_fullspace)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(TdColors.TagBg).padding(horizontal = 14.dp, vertical = 10.dp).probe("banner_controller"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(R.drawable.ic_controller), contentDescription = null, modifier = Modifier.size(22.dp), tint = TdColors.TagInk)
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = TdType.body, color = TdColors.TagInk, maxLines = 2, onTextLayout = fits("banner/controller"))
    }
}

/** Pages with the system Toolbar keep its strip free (native-window-move §3.6). */
@Composable
private fun ColumnScope.ToolbarClear() = Spacer(Modifier.height(PanelSizes.TOOLBAR_CLEAR.dp).probe("toolbar_strip"))

// ================================================================================================= pages

/** 关卡 page: S01 / S05 / S03 / S06 / countdown / S07-S08 / S17 / S13 / S02 (one page that changes in place). */
@Composable
internal fun LevelsPage(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopRow(state, onEvent)
        when (state.screen) {
            Screen.STARTING -> Starting(state)
            Screen.COACH -> Coach(state, onEvent)
            Screen.HOME -> Home(state, onEvent)
            Screen.PLACING -> Placing(state, onEvent)
            else -> RoundPages(state, onEvent)
        }
    }
}

/** 随便练 page (S16): the round card, then the same round pages as the drills. */
@Composable
internal fun FreePage(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopRow(state, onEvent)
        if (state.fromFree && state.screen != Screen.HOME && state.screen != Screen.STARTING && state.screen != Screen.COACH && state.screen != Screen.PLACING) RoundPages(state, onEvent)
        else FreeHome(state, onEvent)
    }
}

@Composable
private fun ColumnScope.RoundPages(state: TdUiState, onEvent: (Ui) -> Unit) {
    when (state.screen) {
        Screen.INTRO -> Intro(state, onEvent)
        Screen.COUNTDOWN -> Countdown(state)
        Screen.LIVE -> Live(state)
        Screen.PAUSED -> Paused(state, onEvent)
        Screen.RESULT -> Result(state, onEvent)
        else -> Unit
    }
}

@Composable
private fun ColumnScope.Starting(state: TdUiState) {
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp).probe("spinner"))
        Spacer(Modifier.height(16.dp))
        Title(rs(R.string.starting), "starting")
        if (state.headWaiting) Body(rs(R.string.starting_head), "starting_head", align = TextAlign.Center)
    }
}

@Composable
private fun ColumnScope.Coach(state: TdUiState, onEvent: (Ui) -> Unit) {
    val lines = listOf(R.string.coach_1, R.string.coach_2, R.string.coach_3)
    val i = state.coachStep.coerceIn(0, 2)
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        // what this tip is about, big enough to read across the room (D23: an icon with words)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(TdColors.Interaction).probe("coach_icon"), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(listOf(R.drawable.ic_pan_tool, R.drawable.ic_controller, R.drawable.ic_replay)[i]), contentDescription = null, modifier = Modifier.size(64.dp), tint = TdColors.OnFill)
            }
        }
        Small(rs(R.string.coach_step, i + 1), "coach_step")
        Text(text = rs(lines[i]), style = TdType.title.copy(fontSize = 26.sp, lineHeight = 34.sp), color = PicoTheme.colorScheme.labelPrimary, maxLines = 3, modifier = Modifier.probe("coach_line"), onTextLayout = fits("title/coach_line"))
        Body(rs(if (state.prefs.leftHanded) R.string.coach_keys_left else R.string.coach_keys_right), "coach_keys")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Key(rs(R.string.coach_skip), "coach_skip", modifier = Modifier.weight(1f)) { onEvent(Ui.CoachSkip) }
            Key(rs(if (i >= 2) R.string.coach_done else R.string.coach_next), "coach_next", R.drawable.ic_chevron_right, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.CoachNext) }
        }
    }
}

@Composable
private fun ColumnScope.Home(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        ControllerBanner(state)
        Title(rs(R.string.home_title), "home_title")
        for (l in listOf(LevelId.REACH, LevelId.PICK, LevelId.STACK)) LevelCard(state, l) { onEvent(Ui.OpenLevel(l)) }
    }
}

/** A drill card (design spatialUiMap.levelCard: a Button as a card): pictogram, name, tag line, best result. */
@Composable
private fun LevelCard(state: TdUiState, l: LevelId, onClick: () -> Unit) {
    val best = DIFFS.mapNotNull { state.best[TdUiState.bestKey(l, it)] }.maxByOrNull { it.score }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(104.dp).probe("card_${l.name}"),
        size = ButtonDefaults.Regular.copy(height = 104.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TdColors.KeyFill, contentColor = TdColors.TagInk),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(TdColors.Interaction), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(levelIcon(l)), contentDescription = null, modifier = Modifier.size(30.dp), tint = TdColors.OnFill)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text = rs(levelName(l)), style = TdType.titleSmall, color = TdColors.TagInk, maxLines = 1, onTextLayout = fits("card/${l.name}_name"))
                Text(text = rs(levelTag(l)), style = TdType.label, color = TdColors.TagInk, maxLines = 2, onTextLayout = fits("card/${l.name}_tag"))
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = if (best == null) rs(R.string.best_none) else stars(best.stars), style = TdType.label, color = TdColors.TagInk, maxLines = 1, onTextLayout = fits("card/${l.name}_stars"))
                if (best != null) Text(text = rs(R.string.best_score, best.score), style = TdType.label, color = TdColors.TagInk, maxLines = 1, onTextLayout = fits("card/${l.name}_best"))
            }
        }
    }
}

/** S06: difficulty / mapping / scale, the control chip (read only) and 开始 (or B). */
@Composable
private fun ColumnScope.Intro(state: TdUiState, onEvent: (Ui) -> Unit) {
    val su = state.setup
    val free = su.level == LevelId.FREE
    fun setup(why: String, f: (io.embodit.teleopdojo.ui.RoundSetup) -> io.embodit.teleopdojo.ui.RoundSetup) = onEvent(Ui.Setup(why, f))
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.back), "intro_back", R.drawable.ic_chevron_left) { onEvent(if (free) Ui.ToHome else Ui.Back) }
            Spacer(Modifier.width(12.dp))
            Title(if (free) rs(R.string.free_round, state.freeRound) else rs(levelName(su.level)), "intro_title", Modifier.weight(1f))
        }
        Body(if (free) rs(R.string.lv_free_tag) else rs(introLine(su.level, su.difficulty)), "intro_line", maxLines = 2)
        if (!free) OptRow(rs(R.string.diff), "diff") {
            Options(DIFFS.map { it to rs(diffName(it)) }, su.difficulty, "diff") { d -> setup("diff") { it.copy(difficulty = d) } }
        }
        OptRow(rs(R.string.mapping), "mapping") {
            Options(listOf(MappingMode.CLUTCH to rs(R.string.m_clutch), MappingMode.FOLLOW to rs(R.string.m_follow)), su.mapping, "mapping", 88) { m -> setup("mapping") { it.copy(mapping = m) } }
        }
        val follow = su.mapping == MappingMode.FOLLOW
        OptRow(rs(R.string.scale), "scale", 64.dp, note = rs(if (follow) R.string.scale_follow else R.string.scale_note)) {
            Options(listOf(0.5f to "0.5×", 1.0f to "1.0×", 1.5f to "1.5×"), if (follow) 1.0f else su.scale, "scale", 64, enabled = !follow) { v -> setup("scale") { it.copy(scale = v) } }
        }
        val control = su.control(state.prefs)
        OptRow(rs(R.string.control), "control") {
            Pill(rs(if (su.difficulty == Difficulty.EASY && !free) R.string.c_assist else if (control == ControlMode.POSE) R.string.c_pose else R.string.c_position), TdColors.TagBg, TdColors.TagInk, null, "control_chip")
        }
        ControllerBanner(state)
        val ok = state.controller == ControllerState.OK || state.controller == ControllerState.PENDING
        Key(
            rs(if (!ok) R.string.start_disabled else if (state.prefs.leftHanded) R.string.start_y else R.string.start_b),
            "start", R.drawable.ic_play_arrow, KeyKind.PRIMARY, 56.dp, enabled = ok, modifier = Modifier.fillMaxWidth(),
        ) { onEvent(Ui.Start) }
    }
}

@Composable
private fun ColumnScope.Countdown(state: TdUiState) {
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Text(text = rs(R.string.countdown_go, state.countdown), style = TdType.title.copy(fontSize = 96.sp, lineHeight = 104.sp), color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.probe("countdown"), onTextLayout = fits("live/countdown"))
        Body(rs(R.string.countdown_hint), "countdown_hint", align = TextAlign.Center)
    }
    ToolbarClear()
}

/** S07 / S08 (+ S09-S12, S22 hints): progress, clock, hint line, oops counters. The quick keys are on the Toolbar. */
@Composable
private fun ColumnScope.Live(state: TdUiState) {
    val l = state.live
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Small(if (state.fromFree) rs(R.string.free_round, state.freeRound) else rs(levelName(state.setup.level)) + " · " + rs(diffName(state.setup.difficulty)), "live_title", maxLines = 1)
                Text(text = rs(R.string.progress, l.done, l.targets), style = TdType.title.copy(fontSize = 40.sp, lineHeight = 46.sp), color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.probe("live_progress"), onTextLayout = fits("live/progress"))
            }
            Text(text = rs(R.string.time_s, l.timeS), style = TdType.title.copy(fontSize = 32.sp, lineHeight = 38.sp), color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.probe("live_time"), onTextLayout = fits("live/time"))
        }
        val hint = hintText(state)
        Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(TdColors.TagBg).padding(horizontal = 16.dp).probe("live_hint"), contentAlignment = Alignment.CenterStart) {
            Text(text = hint ?: rs(R.string.h_regrab), style = TdType.body, color = TdColors.TagInk, maxLines = 2, onTextLayout = fits("live/hint"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((o, id) in listOf(Oops.TABLE to R.string.oops_table, Oops.JOINT to R.string.oops_joint, Oops.SINGULAR to R.string.oops_singular, Oops.SLIP to R.string.oops_slip)) {
                val n = l.oops[o] ?: 0
                Pill(rs(id, n), if (n > 0) TdColors.Paused else TdColors.Released, TdColors.OnFill, null, "oops_${o.name}")
            }
        }
    }
    ToolbarClear()
}

/** The quick keys of a round on the system Toolbar (design panels.main.toolbar). */
@Composable
internal fun RowScope.LiveToolbar(state: TdUiState, onEvent: (Ui) -> Unit) {
    if (state.screen == Screen.PAUSED) return
    Key(rs(R.string.pause), "tb_pause", R.drawable.ic_pause) { onEvent(Ui.Pause) }
    Key(rs(R.string.restart), "tb_restart", R.drawable.ic_restart_alt) { onEvent(Ui.Restart) }
    Key(rs(R.string.home_pose), "tb_home", R.drawable.ic_home) { onEvent(Ui.GoHomePose) }
    Key(rs(R.string.end), "tb_end", R.drawable.ic_stop) { onEvent(Ui.End) }
}

@Composable
private fun ColumnScope.Paused(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        val ask = state.roundConfirm
        if (ask != null) {
            // D44: restarting / leaving a running round loses it; the question opens right here
            Title(rs(if (ask == "restart") R.string.confirm_restart else R.string.confirm_home), "round_confirm")
            Body(rs(R.string.confirm_body), "round_confirm_body")
            Row(Modifier.fillMaxWidth().probe("round_confirm_row"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Key(rs(R.string.cancel), "confirm_no", height = 56.dp, modifier = Modifier.weight(1f)) { onEvent(Ui.ConfirmNo) }
                Key(rs(if (ask == "restart") R.string.restart else R.string.to_home), "confirm_yes", if (ask == "restart") R.drawable.ic_restart_alt else R.drawable.ic_home, KeyKind.DANGER, 56.dp, modifier = Modifier.weight(1f)) { onEvent(Ui.ConfirmYes) }
            }
            return@Column
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(TdColors.Paused).probe("paused_icon"), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(64.dp), tint = TdColors.OnFill)
            }
        }
        Title(rs(R.string.paused_title), "paused_title")
        Body(rs(R.string.paused_body), "paused_body")
        Key(rs(R.string.resume), "resume", R.drawable.ic_play_arrow, KeyKind.PRIMARY, 56.dp, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.Resume) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Key(rs(R.string.restart), "paused_restart", R.drawable.ic_restart_alt, modifier = Modifier.weight(1f)) { onEvent(Ui.Restart) }
            Key(rs(R.string.to_home), "paused_home", R.drawable.ic_home, modifier = Modifier.weight(1f)) { onEvent(Ui.ToHome) }
        }
    }
}

/** S13 / S15: stars, score, the metrics, the demo badge, the comparison and the next steps. */
@Composable
private fun ColumnScope.Result(state: TdUiState, onEvent: (Ui) -> Unit) {
    val r = state.result ?: return
    val m = r.metrics
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stars(r.stars), style = TdType.title.copy(fontSize = 32.sp, lineHeight = 38.sp), color = TdColors.StarInk, modifier = Modifier.probe("res_stars"), onTextLayout = fits("res/stars"))
            Spacer(Modifier.width(12.dp))
            Text(text = rs(R.string.score, r.score), style = TdType.title.copy(fontSize = 36.sp, lineHeight = 42.sp), color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f).probe("res_score"), onTextLayout = fits("res/score"))
            if (r.assisted) Pill(rs(R.string.assisted), TdColors.TagBg, TdColors.TagInk, null, "res_assisted")
        }
        val rows = listOf(
            rs(R.string.m_done) to rs(R.string.progress, m.doneCount, m.targets),
            rs(R.string.m_time) to rs(R.string.time_s, m.timeS.roundToInt()),
            rs(R.string.m_steady) to "${m.steady}",
            rs(R.string.m_path) to "${m.pathPct}%",
            rs(R.string.m_oops) to "${m.oops}",
            rs(R.string.m_regrabs) to "${m.clutches}",
            rs(R.string.m_idle) to "${m.idlePct}%",
            rs(R.string.m_flips) to "${m.gripperFlips}",
        )
        for (pair in rows.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((k, v) in pair) {
                    Row(Modifier.weight(1f).height(30.dp).probe("res_metric_$k"), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = k, style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("res/k_$k"))
                        Text(text = v, style = TdType.titleSmall, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, onTextLayout = fits("res/v_$k"))
                    }
                }
            }
        }
        if (r.demoReady) Pill(rs(R.string.demo_badge), TdColors.Done, TdColors.OnFill, R.drawable.ic_check, "res_demo")
        state.compare?.let { Body(compareText(it), "res_compare", maxLines = 2) }
        Small(rs(R.string.footnote), "res_footnote", maxLines = 3)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(rs(R.string.again), "res_again", R.drawable.ic_replay, KeyKind.PRIMARY, modifier = Modifier.weight(1f)) { onEvent(Ui.Again) }
            if (state.fromFree) Key(rs(R.string.free_next), "res_free_next", R.drawable.ic_chevron_right, modifier = Modifier.weight(1f)) { onEvent(Ui.FreeNext) }
            else Key(rs(if (r.level == LevelId.STACK) R.string.see_card else R.string.next_level), "res_next", R.drawable.ic_chevron_right, modifier = Modifier.weight(1f)) { onEvent(Ui.NextLevel) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(rs(R.string.switch_mapping), "res_switch", R.drawable.ic_swap_horiz, modifier = Modifier.weight(1f)) { onEvent(Ui.SwitchMapping) }
            Key(rs(R.string.to_home), "res_home", R.drawable.ic_home, modifier = Modifier.weight(1f)) { onEvent(Ui.ToHome) }
        }
    }
}

@Composable
private fun ColumnScope.FreeHome(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        ControllerBanner(state)
        Title(rs(R.string.free_round, state.freeRound), "free_round")
        Body(rs(R.string.lv_free_tag), "free_tag")
        Small(String.format(Locale.US, "%.1f cm · %d", FreePlay.tolerance(state.freeRound) * 100f, FreePlay.targets(state.freeRound)), "free_params")
        val busy = state.inRound || state.screen == Screen.PLACING || state.screen == Screen.STARTING
        Key(rs(R.string.free_next), "free_open", R.drawable.ic_play_arrow, KeyKind.PRIMARY, 56.dp, enabled = !busy, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.FreeNext) }
    }
}

/** S02 in the window: instructions, 移到标记处 (after the trigger set the marker) and 取消. */
@Composable
private fun ColumnScope.Placing(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        Title(rs(R.string.place_title), "place_title")
        Body(rs(R.string.place_hint), "place_hint")
        Small(rs(R.string.place_hint_2), "place_hint_2")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Key(rs(R.string.cancel), "place_cancel", modifier = Modifier.weight(1f)) { onEvent(Ui.PlaceCancel) }
            Key(rs(R.string.place_here), "place_here", R.drawable.ic_check, KeyKind.PRIMARY, enabled = state.markerSet, modifier = Modifier.weight(1f)) { onEvent(Ui.PlaceConfirm) }
        }
    }
}

// ================================================================================================= S14 scorecard

@Composable
internal fun CardPage(state: TdUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopRow(state, onEvent)
        val bests = listOf(LevelId.REACH, LevelId.PICK, LevelId.STACK).map { l -> l to DIFFS.mapNotNull { state.best[TdUiState.bestKey(l, it)] }.maxByOrNull { it.score } }
        val avg = bests.map { it.second?.stars ?: 0 }.average().toFloat()
        val title = when (Scoring.title(avg)) {
            Scoring.Title.STEADY -> R.string.card_title_steady
            Scoring.Title.SKILLED -> R.string.card_title_skilled
            Scoring.Title.GETTING_IT -> R.string.card_title_getting
            Scoring.Title.NEW -> R.string.card_title_new
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(rs(title), "card_title", Modifier.weight(1f))
                Text(text = stars(avg.roundToInt()), style = TdType.title, color = TdColors.StarInk, onTextLayout = fits("card/avg"))
            }
            Small(rs(R.string.card_rows), "card_rows_title")
            for ((l, b) in bests) {
                val demos = DIFFS.sumOf { state.best[TdUiState.bestKey(l, it)]?.demo ?: 0 }
                Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 12.dp).probe("card_row_${l.name}"), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = rs(levelName(l)), style = TdType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(110.dp), onTextLayout = fits("card/row_${l.name}"))
                    if (b == null) Text(text = rs(R.string.card_empty), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("card/row_${l.name}_empty"))
                    else {
                        Text(text = stars(b.stars) + "  " + rs(R.string.score, b.score), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("card/row_${l.name}_score"))
                        Text(text = rs(R.string.time_s, b.timeS.roundToInt()) + " · " + rs(R.string.m_steady) + " " + b.steady + " · " + rs(R.string.m_oops) + " " + b.oops, style = TdType.label.copy(fontSize = 14.sp), color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.width(200.dp), onTextLayout = fits("card/row_${l.name}_detail"))
                    }
                    if (demos > 0) {
                        Spacer(Modifier.width(6.dp))
                        Pill(rs(R.string.card_demo, demos), TdColors.Done, TdColors.OnFill, null, "card_demo_${l.name}")
                    }
                }
            }
            Small(rs(R.string.card_compare), "card_compare_title")
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = rs(R.string.card_col_map), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), onTextLayout = fits("card/col_map"))
                Text(text = rs(R.string.card_col_best), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(110.dp), onTextLayout = fits("card/col_best"))
                Text(text = rs(R.string.card_col_avg), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(130.dp), onTextLayout = fits("card/col_avg"))
            }
            for (k in listOf("clutch0.5", "clutch1.0", "clutch1.5", "follow")) {
                val s = state.mapStats[k]
                Row(Modifier.fillMaxWidth().height(32.dp).probe("card_map_$k"), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = mapLabel(k), style = TdType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("card/map_$k"))
                    Text(text = if (s == null) rs(R.string.dash) else rs(R.string.score, s.best), style = TdType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(110.dp), onTextLayout = fits("card/map_${k}_best"))
                    Text(text = if (s == null) rs(R.string.dash) else rs(R.string.time_s, s.avgTime.roundToInt()), style = TdType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(130.dp), onTextLayout = fits("card/map_${k}_avg"))
                }
            }
        }
    }
}

// ================================================================================================= S18 settings

/**
 * Settings (design panels.main.pages.settings; D26): every row fits the first screen (factory gate settings-shot), the
 * confirmations open in place (S19 / S20), 退出应用 is the last row.
 */
@Composable
internal fun SettingsPage(state: TdUiState, onEvent: (Ui) -> Unit) {
    val p = state.prefs
    fun pr(why: String, f: (Prefs) -> Prefs) = onEvent(Ui.SetPrefs(why, f))
    val busy = state.inRound
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) { Title(rs(R.string.set_title), "set_title") }
        if (state.aboutOpen) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                Title(rs(R.string.about), "about_title")
                Body(rs(R.string.about_1), "about_1", maxLines = 4)
                Body(rs(R.string.about_2), "about_2", maxLines = 4)
                Body(rs(R.string.about_3), "about_3", maxLines = 2)
                Key(rs(R.string.about_close), "about_close", R.drawable.ic_chevron_left, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.About) }
            }
            return@Column
        }
        OptRow(rs(R.string.set_lang), "lang", 50.dp) {
            Options(listOf(Lang.ZH to rs(R.string.lang_zh), Lang.EN to rs(R.string.lang_en)), if (p.lang == Lang.SYSTEM) currentLang() else p.lang, "set_lang", 96) { l -> pr("lang") { it.copy(lang = l) } }
        }
        OptRow(rs(R.string.set_hand), "hand", 50.dp) {
            Options(listOf(false to rs(R.string.hand_right), true to rs(R.string.hand_left)), p.leftHanded, "set_hand", 80, enabled = !busy) { v -> pr("hand") { it.copy(leftHanded = v) } }
        }
        OptRow(rs(R.string.set_table), "table", 50.dp) {
            Options(listOf(false to rs(R.string.table_sit), true to rs(R.string.table_stand)), p.standing, "set_table", 64, enabled = !busy) { v -> pr("table") { it.copy(standing = v, tableAdjustCm = 0) } }
            Spacer(Modifier.width(8.dp))
            IconKey(R.drawable.ic_remove, "-5 cm", "table_minus", !busy) { pr("tableAdj") { it.copy(tableAdjustCm = (it.tableAdjustCm - 5).coerceIn(-10, 10)) } }
            Text(text = rs(R.string.table_cm, (p.tableHeight * 100).roundToInt()), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp).probe("table_value"), onTextLayout = fits("set/table_value"))
            IconKey(R.drawable.ic_add, "+5 cm", "table_plus", !busy) { pr("tableAdj") { it.copy(tableAdjustCm = (it.tableAdjustCm + 5).coerceIn(-10, 10)) } }
        }
        OptRow(rs(R.string.set_control), "control_std", 50.dp) {
            Options(listOf(ControlMode.POSE to rs(R.string.c_pose), ControlMode.POSITION to rs(R.string.c_position)), p.controlStandard, "set_control", 88) { v -> pr("control") { it.copy(controlStandard = v) } }
        }
        OptRow(rs(R.string.set_env), "env", 50.dp) {
            Options(listOf(false to rs(R.string.env_mr), true to rs(R.string.env_vr)), p.vr, "set_env", 88) { v -> pr("vr") { it.copy(vr = v) } }
        }
        var vol by remember(p.volume) { mutableFloatStateOf(p.volume.toFloat()) }
        OptRow(rs(R.string.set_volume), "volume", 50.dp) {
            // gate:allow slider-height the Slider is given a 48 dp touch height right here
            Slider(value = vol, onValueChange = { vol = it }, modifier = Modifier.width(170.dp).height(48.dp).probe("volume"), valueRange = 0f..100f, onValueChangeFinished = {
                // three snaps (low / mid / high = 30 / 60 / 90, ±5) + percent (design rules.audio)
                val snapped = listOf(30f, 60f, 90f).firstOrNull { kotlin.math.abs(it - vol) <= 5f } ?: vol
                vol = snapped
                pr("volume") { it.copy(volume = snapped.roundToInt()) }
            })
            Text(text = rs(R.string.vol_pct, vol.roundToInt()), style = TdType.label, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(52.dp).padding(start = 6.dp).probe("volume_pct"), onTextLayout = fits("set/volume_pct"))
            Box(Modifier.height(48.dp).width(64.dp), contentAlignment = Alignment.Center) {
                Switch(checked = !p.muted, onCheckedChange = { on -> pr("mute") { it.copy(muted = !on) } }, modifier = Modifier.probe("mute_settings"))
            }
        }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.move_cell), "set_move", R.drawable.ic_open_with, enabled = !busy, modifier = Modifier.weight(1f)) { onEvent(Ui.PlaceStart) }
            Key(rs(if (p.leftHanded) R.string.cell_aside_left else R.string.cell_aside), "set_aside", R.drawable.ic_desk, enabled = !busy, modifier = Modifier.weight(1f)) { onEvent(Ui.WorkcellAside) }
        }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.recall), "set_recall", R.drawable.ic_my_location, modifier = Modifier.weight(1.2f)) { onEvent(Ui.RecallPanel) }
            if (!state.resetConfirm) Key(rs(R.string.reset), "set_reset", R.drawable.ic_restart_alt, enabled = !busy, modifier = Modifier.weight(1f)) { onEvent(Ui.ResetAsk) }
            Key(rs(R.string.about), "set_about", R.drawable.ic_info, modifier = Modifier.weight(0.8f)) { onEvent(Ui.About) }
        }
        Small(if (busy) rs(R.string.finish_first) else rs(R.string.recall_note), "set_note", maxLines = 1)
        if (state.resetConfirm) {
            Row(Modifier.fillMaxWidth().height(52.dp).probe("reset_confirm"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Body(rs(R.string.reset_q), "reset_q", Modifier.weight(1f), maxLines = 2)
                Key(rs(R.string.cancel), "reset_cancel") { onEvent(Ui.ResetCancel) }
                Key(rs(R.string.reset_yes), "reset_yes", null, KeyKind.DANGER) { onEvent(Ui.ResetYes) }
            }
        }
        Spacer(Modifier.weight(1f))
        // D26: quit is always the last row; the confirmation opens right here (S19), no second panel
        if (state.exitConfirm) {
            Row(Modifier.fillMaxWidth().height(56.dp).probe("exit_confirm"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Title(rs(R.string.exit_title), "exit_title", Modifier.weight(1f))
                Key(rs(R.string.cancel), "quit_cancel", height = 52.dp) { onEvent(Ui.QuitCancel) }
                Key(rs(R.string.exit_yes), "quit_yes", R.drawable.ic_logout, KeyKind.DANGER, 52.dp) { onEvent(Ui.QuitYes) }
            }
        } else Key(rs(R.string.quit), "quit", R.drawable.ic_logout, KeyKind.DANGER, 52.dp, modifier = Modifier.fillMaxWidth()) { onEvent(Ui.QuitAsk) }
    }
}

/** The language the window is showing right now (自动 follows the system). */
@Composable
private fun currentLang(): Lang = if (LocalTdResources.current.configuration.locales[0].language == "zh") Lang.ZH else Lang.EN

@Composable
private fun IconKey(icon: Int, description: String, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = TdColors.KeyFill, contentColor = TdColors.TagInk),
        size = IconButtonDefaults.iconButtonSize(width = 52.dp, height = 48.dp),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp), tint = TdColors.TagInk)
    }
}

// ================================================================================================= Stage panels

/** "打开面板" on the post at the workcell corner (S25), or "平视前方" while the window waits for a level head (D9). */
@Composable
internal fun OpenChip(show: Boolean) {
    Box(Modifier.size(PanelSizes.OPEN_W.dp, PanelSizes.OPEN_H.dp), contentAlignment = Alignment.Center) {
        if (!show) return@Box
        if (io.embodit.teleopdojo.ui.window.WindowCtl.levelHint) {
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(TdColors.TagBg).padding(horizontal = 16.dp, vertical = 6.dp).probe("look_level"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(text = rs(R.string.look_level), style = TdType.title.copy(fontSize = 24.sp, lineHeight = 30.sp), color = TdColors.TagInk, maxLines = 1, onTextLayout = fits("chip/look_level"))
                Text(text = rs(R.string.look_level_2), style = TdType.body, color = TdColors.TagInk, maxLines = 1, onTextLayout = fits("chip/look_level_2"))
            }
            return@Box
        }
        Key(rs(R.string.open_panel), "open_panel", R.drawable.ic_open_with, KeyKind.PRIMARY, 56.dp) { io.embodit.teleopdojo.ui.window.WindowCtl.reopen("chip") }
    }
}

/** The tip tag over the table (S09-S12, S22, countdown, pause, S02): opaque slate, not interactive. */
@Composable
internal fun TipTag(state: TdUiState?) {
    Box(Modifier.size(PanelSizes.TIP_W.dp, PanelSizes.TIP_H.dp), contentAlignment = Alignment.Center) {
        if (state == null) return@Box
        val text = when (state.screen) {
            Screen.COUNTDOWN -> rs(R.string.countdown_go, state.countdown)
            Screen.PAUSED -> rs(R.string.paused_title)
            Screen.PLACING -> rs(R.string.place_hint)
            else -> hintText(state) ?: return@Box
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(TdColors.TagBg).padding(horizontal = 16.dp).probe("tip"), contentAlignment = Alignment.Center) {
            Text(text = text, style = TdType.body, color = TdColors.TagInk, maxLines = 2, textAlign = TextAlign.Center, onTextLayout = fits("tip/text"))
        }
    }
}
