package io.embodit.teleopdojo.ui.theme

import androidx.compose.ui.graphics.Color

/** The design's colours (design-package `palette`, `entities3d`). The only file with colour literals. */
object TdColors {
    // semantic (palette.semantic); content on a semantic fill is #16181C (palette.rule)
    val Clutched = Color(0xFF5FD38A) // design-style: fixed-figma-color 握住
    val Released = Color(0xFF9A9A96) // design-style: fixed-figma-color 松开
    val Paused = Color(0xFFFFA62B) // design-style: fixed-figma-color 暂停
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color 撞桌 / 到头
    val Alert = Color(0xFFFFA62B) // design-style: fixed-figma-color 快奇异
    val Target = Color(0xFF3FD0E0) // design-style: fixed-figma-color 光球未亮
    val Done = Color(0xFF5FD38A) // design-style: fixed-figma-color 已亮 / 放稳
    val Tether = Color(0xFFFFC72C) // design-style: fixed-figma-color 控制线
    // stars on the window glass: the tether yellow is ~1.5:1 there, this dark amber keeps the star shape (>= 3:1, large)
    val StarInk = Color(0xFF6B4400) // design-style: fixed-figma-color stars on glass
    val OnFill = Color(0xFF16181C) // design-style: fixed-figma-color ink on semantic fills

    // ColorScheme roles (palette.theme: vibrant + the semantic set)
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color primary key (遥操黄)
    val Passable = Clutched
    // danger keys (退出 / 清掉): a lighter red so the bold 20 sp label stays >= 4.5:1 with OnFill
    val DangerFill = Color(0xFFFF8A80) // design-style: fixed-figma-color danger key fill

    // pills and tags: opaque dark slate with light text (readable over any room)
    val TagBg = Color(0xFF1E2329) // design-style: fixed-figma-color tag background
    val TagInk = Color(0xFFE8EDF2) // design-style: fixed-figma-color tag text
    // normal keys, drill cards, steppers, mute: opaque slate + TagInk. The system button fill over the window glass let the
    // dark labelPrimary read 1.7-3.7:1 on the emulator (v0.1.0 contrast table)
    val KeyFill = Color(0xFF353B43) // design-style: fixed-figma-color normal key fill

    // 3D (entities3d)
    val ArmWhite = Color(0xFFE4E6E8) // design-style: fixed-figma-color UR5e links
    val JointGrey = Color(0xFF5A6068) // design-style: fixed-figma-color joint drums
    val UrBlue = Color(0xFF2E7DD1) // design-style: fixed-figma-color joint band
    val GripperBody = Color(0xFFD7DADD) // design-style: fixed-figma-color gripper A grey-white
    val PadRubber = Color(0xFF3A3D41) // design-style: fixed-figma-color finger pads
    val Ghost = Color(0xFFBFC6CC) // design-style: fixed-figma-color target ghost
    val TableTop = Color(0xFFC9CCCF) // design-style: fixed-figma-color table top (light matte grey)
    val TableLeg = Color(0xFF6E737A) // design-style: fixed-figma-color legs / post / shelves
    val GridLine = Color(0xFF2B3036) // design-style: fixed-figma-color 1 cm grid
    val StripeInk = Color(0xFF16181C) // design-style: fixed-figma-color safety stripe black
    val BlockRed = Color(0xFFD9483B) // design-style: fixed-figma-color block red
    val BlockBlue = Color(0xFF2F6FCC) // design-style: fixed-figma-color block blue
    val BlockYellow = Color(0xFFE8B52A) // design-style: fixed-figma-color block yellow
    val TrayGrey = Color(0xFF7D838A) // design-style: fixed-figma-color tray rim
    val FrameWhite = Color(0xFFFFFFFF) // design-style: fixed-figma-color outlines / dashes
    val DomeWall = Color(0xFF8A8F95) // design-style: fixed-figma-color workshop walls (baked, unlit)
    val DomeFloor = Color(0xFF9FA3A6) // design-style: fixed-figma-color concrete floor
    val DomeCeiling = Color(0xFF4A4E54) // design-style: fixed-figma-color workshop ceiling (fallback when the baked texture is missing)
}
