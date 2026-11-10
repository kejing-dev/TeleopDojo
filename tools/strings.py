#!/usr/bin/env python3
"""Teleop Dojo copy, both languages in one table (design content.tone: spoken, short; profile D28).

    python tools/strings.py      -> app/src/main/res/values{,-zh,-en}/strings.xml

values/ is Chinese (the default), values-zh/ the same, values-en/ English (factory rule 51: an English build for the
release). No company name anywhere (factory gate company-name).
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

S = [
    ("app_name", "遥操练习场", "Teleop Dojo"),
    ("tab_levels", "关卡", "Drills"),
    ("tab_free", "随便练", "Free play"),
    ("tab_card", "成绩卡", "Scorecard"),
    ("tab_settings", "设置", "Settings"),
    # top row
    ("st_clutched", "握住", "Holding"),
    ("st_released", "松开", "Let go"),
    ("st_paused", "暂停", "Paused"),
    ("gripper_mm", "夹爪 %1$d mm", "Gripper %1$d mm"),
    ("keys_right", "B 开始/结束 · A 暂停", "B start/end · A pause"),
    ("keys_left", "Y 开始/结束 · X 暂停", "Y start/end · X pause"),
    ("mute", "静音", "Mute"),
    ("unmute", "有声", "Sound"),
    # S01 / S04
    ("starting", "准备中…", "Getting ready…"),
    ("starting_head", "在等定位，戴好头显看一眼前面", "Waiting for tracking. Look ahead for a sec."),
    ("nc_device", "拿起手柄就能开始（没连上的话按一下手柄 Home 键）", "Pick up a controller to start (not linked? press its Home key)"),
    ("nc_permission", "去系统设置给遥操练习场开手柄权限", "Allow controller access for Teleop Dojo in system settings"),
    ("nc_fullspace", "回到全屏模式就能继续", "Go back to full space to carry on"),
    ("nc_pending", "在找手柄…", "Looking for controllers…"),
    # S05 coach
    ("coach_1", "按住侧键，它就跟着你的手。松开，它就停在那。", "Hold grip and it follows your hand. Let go and it stays put."),
    ("coach_2", "扣扳机，夹爪就合上。松开扳机，它就张开。", "Squeeze the trigger and the gripper closes. Ease off and it opens."),
    ("coach_3", "手够不着了？松开侧键，把手收回来，再按住接着走。", "Running out of arm? Let go, bring your hand back, grab again."),
    ("coach_keys_right", "按 B 开始 / 结束一局，A 暂停，右摇杆按下作废这一局。", "B starts / ends a round, A pauses, right stick click throws the round away."),
    ("coach_keys_left", "按 Y 开始 / 结束一局，X 暂停，左摇杆按下作废这一局。", "Y starts / ends a round, X pauses, left stick click throws the round away."),
    ("coach_next", "下一条", "Next"),
    ("coach_done", "知道了", "Got it"),
    ("coach_skip", "跳过", "Skip"),
    ("coach_step", "%1$d / 3", "%1$d / 3"),
    # levels
    ("lv_reach", "点灯", "Light 'em up"),
    ("lv_pick", "搬家", "Moving day"),
    ("lv_stack", "叠高高", "Tower time"),
    ("lv_reach_tag", "5 盏小灯，夹爪尖碰一下就亮", "Five little lights. Tap each with the gripper tip."),
    ("lv_pick_tag", "把方块搬进托盘，放稳就算", "Get the block into the tray. Set it down, done."),
    ("lv_stack_tag", "叠三块，站稳 2 秒", "Stack three. Hold for two seconds."),
    ("lv_free_tag", "每局随机，越练越难", "New layout every round. Gets harder as you go."),
    ("best_none", "还没玩过", "Not played yet"),
    ("best_score", "最好 %1$d 分", "Best %1$d"),
    ("home_title", "选一关练练", "Pick a drill"),
    # intro
    ("back", "返回", "Back"),
    ("diff", "难度", "Level"),
    ("d_easy", "轻松", "Easy"),
    ("d_standard", "标准", "Standard"),
    ("d_picky", "较真", "Picky"),
    ("mapping", "映射", "Mapping"),
    ("m_clutch", "离合", "Clutch"),
    ("m_follow", "跟手", "Follow"),
    ("scale", "缩放", "Scale"),
    ("scale_note", "1.5× 是 XRoboToolkit UR5e 仿真默认", "1.5× is the XRoboToolkit UR5e sim default"),
    ("scale_follow", "跟手固定 1.0×", "Follow is fixed at 1.0×"),
    ("control", "控制", "Control"),
    ("c_pose", "全姿态", "Full pose"),
    ("c_position", "只管位置", "Position only"),
    ("c_assist", "只管位置 · 辅助", "Position only · assist"),
    ("start", "开始", "Start"),
    ("start_b", "开始（或按 B）", "Start (or press B)"),
    ("start_y", "开始（或按 Y）", "Start (or press Y)"),
    ("start_disabled", "拿起手柄才能开始", "Pick up a controller to start"),
    ("intro_reach_easy", "5 个大光球，容差 2.5 cm，停一下就亮", "5 big lights, 2.5 cm slack, hold still a moment"),
    ("intro_reach_standard", "6 个光球，容差 1.5 cm，有两个在高处", "6 lights, 1.5 cm slack, two of them up high"),
    ("intro_reach_picky", "6 个光球，还要对上箭头方向（20° 内）", "6 lights, and match the arrow (within 20°)"),
    ("intro_pick_easy", "1 块 6 cm 方块，搬进大托盘", "One 6 cm block into the big tray"),
    ("intro_pick_standard", "3 块 5 cm 方块，搬进 12 cm 托盘", "Three 5 cm blocks into the 12 cm tray"),
    ("intro_pick_picky", "3 块方块各进一个格子，边要对齐（15° 内）", "Each block into its own square, edges lined up (15°)"),
    ("intro_stack_easy", "3 块 6 cm，上下差 2.5 cm 内算稳", "Three 6 cm blocks, 2.5 cm off still counts"),
    ("intro_stack_standard", "3 块 5 cm，上下差 1.5 cm 内算稳", "Three 5 cm blocks, within 1.5 cm"),
    ("intro_stack_picky", "4 块 4 cm，上下差 1 cm 内算稳", "Four 4 cm blocks, within 1 cm"),
    # free
    ("free_round", "第 %1$d 局", "Round %1$d"),
    ("free_kind_reach", "这局：点灯", "This round: lights"),
    ("free_kind_pick", "这局：搬家", "This round: moving"),
    ("free_kind_stack", "这局：叠高高", "This round: tower"),
    ("free_next", "下一局", "Next round"),
    # live
    ("progress", "%1$d / %2$d", "%1$d / %2$d"),
    ("time_s", "%1$d 秒", "%1$d s"),
    ("oops_table", "撞桌 %1$d", "Table %1$d"),
    ("oops_joint", "到头 %1$d", "Limit %1$d"),
    ("oops_singular", "快奇异 %1$d", "Singular %1$d"),
    ("oops_slip", "甩掉 %1$d", "Slipped %1$d"),
    ("countdown_go", "%1$d", "%1$d"),
    ("countdown_hint", "准备，按住侧键就开始走", "Get ready. Hold grip to move."),
    ("h_hold_grip", "按住侧键，它就跟着你的手", "Hold grip and it follows your hand"),
    ("h_regrab", "手够不着了？松开侧键，把手收回来，再按住", "Out of arm? Let go, bring your hand back, grab again"),
    ("h_got_it", "夹到了。慢点抬，别甩。", "Got it. Lift slow, no flicking."),
    ("h_table", "轻点，桌子在这", "Easy, the table's right there"),
    ("h_joint", "J%1$d 到头了", "J%1$d is at its limit"),
    ("h_singular", "腕快拧直了，换个角度", "Wrist is going straight, try another angle"),
    ("h_slipped", "甩掉了，慢点", "Flung it. Slower."),
    ("h_lost", "手柄跟丢了，臂先停着", "Lost the controller, the arm waits"),
    ("h_almost_in", "差一点，往里一点", "Almost. A bit further in."),
    ("h_almost_turn", "差一点，再转一点", "Almost. Turn a bit more."),
    ("h_almost_squeeze", "差一点，再扣紧点", "Almost. Squeeze harder."),
    ("h_loosened", "放宽一点了，再试", "Eased up a little. Try again."),
    ("h_press_grip_again", "再按住侧键接着走", "Hold grip again to carry on"),
    ("h_tower", "站住了，再撑一下", "It's standing. Hold on."),
    ("h_placed", "放稳了", "Set down"),
    ("h_unreachable", "够不着那儿，臂停在最近的地方", "Can't reach there, stopping at the closest point"),
    ("pause", "暂停", "Pause"),
    ("restart", "重来", "Restart"),
    ("home_pose", "回起始姿势", "Home pose"),
    ("end", "结束", "End"),
    # paused
    ("paused_title", "暂停了", "Paused"),
    ("paused_body", "计时停着。继续以后要重新按住侧键。", "The clock is stopped. Hold grip again after you resume."),
    ("resume", "继续", "Resume"),
    ("confirm_restart", "这局不要了，重来？", "Drop this round and restart?"),
    ("confirm_home", "这局不要了，回首页？", "Drop this round and go home?"),
    ("confirm_body", "这一局的成绩不会记下来。", "This round will not be scored."),
    ("to_home", "回首页", "Home"),
    # result
    ("score", "%1$d 分", "%1$d pts"),
    ("m_done", "完成", "Done"),
    ("m_time", "用时", "Time"),
    ("m_steady", "手稳度", "Steadiness"),
    ("m_path", "走得直", "Path"),
    ("m_oops", "小毛病", "Oops"),
    ("m_regrabs", "离合次数", "Re-grabs"),
    ("m_idle", "停顿", "Idle"),
    ("m_flips", "夹爪来回", "Gripper flips"),
    ("demo_badge", "这一把能当示范", "This one could be a demo"),
    ("assisted", "辅助模式", "Assist mode"),
    ("again", "再来", "Again"),
    ("switch_mapping", "换个映射试试", "Try another mapping"),
    ("next_level", "下一关", "Next drill"),
    ("see_card", "看成绩卡", "Scorecard"),
    ("cmp_faster", "%1$s 比 %2$s 快 %3$d 秒", "%1$s was %3$d s faster than %2$s"),
    ("cmp_slower", "%1$s 比 %2$s 慢 %3$d 秒", "%1$s was %3$d s slower than %2$s"),
    ("cmp_table_less", "，撞桌少 %1$d 次", ", %1$d fewer table hits"),
    ("cmp_table_more", "，撞桌多 %1$d 次", ", %1$d more table hits"),
    ("map_clutch_x", "离合 %1$s×", "Clutch %1$s×"),
    ("footnote", "手稳度按夹爪尖加速度的抖动算；离合映射移植自 XRoboToolkit（MIT）。抓取按规则判定，不是物理仿真。", "Steadiness is from tip jitter. Clutch mapping ported from XRoboToolkit (MIT). Grasping is rule-based, not physics."),
    # scorecard
    ("card_title_steady", "稳手", "Steady hands"),
    ("card_title_skilled", "熟手", "Skilled"),
    ("card_title_getting", "上手了", "Getting it"),
    ("card_title_new", "刚上手", "Just started"),
    ("card_rows", "三关最好成绩", "Best of each drill"),
    ("card_compare", "映射对比", "Mappings compared"),
    ("card_col_map", "映射", "Mapping"),
    ("card_col_best", "最好", "Best"),
    ("card_col_avg", "平均用时", "Avg time"),
    ("card_demo", "能当示范 %1$d 次", "Demo-worthy ×%1$d"),
    ("card_empty", "练一关就有了", "Play a drill to fill this in"),
    ("card_date", "本机记录 · %1$s", "On this device · %1$s"),
    ("dash", "—", "—"),
    # settings
    ("set_title", "设置", "Settings"),
    ("set_lang", "语言", "Language"),
    ("lang_zh", "中文", "中文"),
    ("lang_en", "English", "English"),
    ("set_hand", "惯用手", "Teleop hand"),
    ("hand_right", "右手", "Right"),
    ("hand_left", "左手", "Left"),
    ("set_table", "桌高", "Table"),
    ("table_sit", "坐", "Sit"),
    ("table_stand", "站", "Stand"),
    ("table_cm", "%1$d cm", "%1$d cm"),
    ("set_control", "控制（标准档）", "Control (standard)"),
    ("set_env", "环境", "Surroundings"),
    ("env_mr", "透视", "Passthrough"),
    ("env_vr", "虚拟车间", "Workshop"),
    ("set_volume", "音量", "Volume"),
    ("vol_pct", "%1$d%%", "%1$d%%"),
    ("move_cell", "移动工作台", "Move workcell"),
    ("cell_aside", "工作台摆到右前", "Workcell to front-right"),
    ("cell_aside_left", "工作台摆到左前", "Workcell to front-left"),
    ("recall", "叫回面板", "Bring panel back"),
    ("recall_note", "先看向左边空处再点", "Look at an empty spot first"),
    ("reset", "重置进度", "Reset progress"),
    ("reset_q", "清掉所有成绩？", "Clear all scores?"),
    ("reset_yes", "清掉", "Clear"),
    ("finish_first", "先结束这局", "End the round first"),
    ("about", "关于", "About"),
    ("about_close", "收起", "Close"),
    ("about_1", "离合映射、夹爪开合和配置字段移植自 XRoboToolkit-Teleop-Sample-Python（MIT © 2025 XR Robotics）。", "Clutch mapping, gripper opening and config fields are ported from XRoboToolkit-Teleop-Sample-Python (MIT © 2025 XR Robotics)."),
    ("about_2", "手柄数据字段名来自 XRoboToolkit-PC-Service（Apache-2.0）。UR5e 尺寸来自 Universal Robots 官方参数。", "Controller field names come from XRoboToolkit-PC-Service (Apache-2.0). UR5e dimensions are Universal Robots' published values."),
    ("about_3", "全部数据只在本机，不联网。", "Everything stays on this device. No network."),
    ("quit", "退出应用", "Quit app"),
    ("exit_title", "要走了？", "Leaving?"),
    ("exit_yes", "退出", "Quit"),
    ("cancel", "取消", "Cancel"),
    # S02 placing
    ("place_title", "移动工作台", "Move the workcell"),
    ("place_hint", "指地面放标记 · 摇杆左右转", "Point at the floor for the marker · stick turns it"),
    ("place_hint_2", "扣扳机定下标记，再点「移到标记处」", "Trigger sets the marker, then tap Move here"),
    ("place_here", "移到标记处", "Move here"),
    # Stage panels
    ("open_panel", "打开面板", "Open panel"),
    ("look_level", "平视前方", "Look straight ahead"),
    ("look_level_2", "面板就出来", "and the panel opens"),
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace('"', '\\"')


def write(d, idx):
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "strings.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- generated by tools/strings.py: edit there -->\n<resources>\n')
        for row in S:
            f.write('    <string name="%s"%s>%s</string>\n' % (row[0], ' formatted="false"' if "%%" in row[idx] and "%1" not in row[idx] else "", esc(row[idx])))
        f.write("</resources>\n")


def main():
    keys = [r[0] for r in S]
    dup = {k for k in keys if keys.count(k) > 1}
    if dup:
        sys.exit("duplicate keys: %s" % dup)
    res = os.path.join(ROOT, "app", "src", "main", "res")
    write(os.path.join(res, "values"), 1)
    write(os.path.join(res, "values-zh"), 1)
    write(os.path.join(res, "values-en"), 2)
    for row in S:
        if "embodit" in (row[1] + row[2]).lower():
            sys.exit("company name in copy: %s" % row[0])
    print("strings: %d keys" % len(S))


if __name__ == "__main__":
    main()
