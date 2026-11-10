package io.embodit.teleopdojo.ui.scene

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.teleopdojo.domain.ArmFrame
import io.embodit.teleopdojo.domain.Block
import io.embodit.teleopdojo.domain.BlockState
import io.embodit.teleopdojo.domain.Drill
import io.embodit.teleopdojo.domain.Gripper
import io.embodit.teleopdojo.domain.Grasp
import io.embodit.teleopdojo.domain.HandInput
import io.embodit.teleopdojo.domain.HeadPose
import io.embodit.teleopdojo.domain.Ik
import io.embodit.teleopdojo.domain.LevelId
import io.embodit.teleopdojo.domain.MappingMode
import io.embodit.teleopdojo.domain.Num
import io.embodit.teleopdojo.domain.Placement
import io.embodit.teleopdojo.domain.Pose
import io.embodit.teleopdojo.domain.Quat4
import io.embodit.teleopdojo.domain.Tool
import io.embodit.teleopdojo.domain.Upright
import io.embodit.teleopdojo.domain.Ur5e
import io.embodit.teleopdojo.domain.Ur5eKinematics
import io.embodit.teleopdojo.domain.Vec3
import io.embodit.teleopdojo.domain.Workcell
import io.embodit.teleopdojo.domain.WorkcellPlacer
import io.embodit.teleopdojo.domain.Yaw
import io.embodit.teleopdojo.platform.TdLog
import io.embodit.teleopdojo.platform.input.Controllers
import io.embodit.teleopdojo.platform.input.Ray
import io.embodit.teleopdojo.platform.tracking.HeadTracker
import io.embodit.teleopdojo.ui.ControllerState
import io.embodit.teleopdojo.ui.Live
import io.embodit.teleopdojo.ui.Screen
import io.embodit.teleopdojo.ui.TdController
import io.embodit.teleopdojo.ui.TdUiState
import io.embodit.teleopdojo.ui.Ui
import io.embodit.teleopdojo.ui.components.PanelSizes
import io.embodit.teleopdojo.ui.theme.TdColors
import io.embodit.teleopdojo.ui.window.WindowCtl
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The scene of the open Stage (debug hooks and the frame system talk to it). Main thread only. */
internal object ActiveScene {
    var current: TdScene? = null
}

/** The one per-frame ECS system (registerSystem in TdScreen): IK, clutch, grasp and scoring all run here. */
class TdFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

object PanelIds {
    /** "打开面板" on the little post at the workcell's near corner (and "平视前方" while the window waits). */
    const val OPEN = "open"
    /** The tip tag over the workcell: J4 到头了 / 轻点，桌子在这 / 夹到了 … */
    const val TIP = "tip"
    val pool = listOf(OPEN, TIP)
}

/** Meshes and materials of the scene, built once on the IO thread. */
internal class Kit(
    val cube: MeshResource,
    val ball: MeshResource,
    val cyl: MeshResource,
    val ring: MeshResource,
    val table: MeshResource,
    val grid: MeshResource,
    val stripes: MeshResource,
    val stripesInk: MeshResource,
    val dome: MeshResource,
    val floor: MeshResource,
    val tableTop: Material,
    val tableLeg: Material,
    val gridMat: Material,
    val yellow: Material,
    val ink: Material,
    val lightIdle: Material,
    val lightDone: Material,
    val blockMats: List<Material>,
    val outline: Material,
    val tray: Material,
    val dash: Material,
    val tether: Material,
    val ripple: Material,
    val ghostLine: Material,
    val marker: Material,
    val domeMat: Material,
    val floorMat: Material,
    val lamp: Material,
    val look: RobotLook,
)

/**
 * The 3D side (design entities3d): the workcell with the UR5e and its gripper, the drill objects, the control line, the
 * target ghost, the placement ghost and the optional workshop dome. Everything per frame happens in [onFrame]: head +
 * controllers -> [io.embodit.teleopdojo.domain.Arm] (clutch, IK) -> [Drill] (judging, metrics) -> transforms. The panels
 * hear through TdController only when what they show changes. Main thread only.
 */
class TdScene {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = HeadTracker()
    val controllers = Controllers()
    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var cellRoot: Entity? = null
    private var objRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var kit: Kit? = null
    private var robot: RobotView? = null
    private var ghostGripper: GripperView? = null
    private var ghostLine: Entity? = null
    private var tether: Entity? = null
    private var ripple: Entity? = null
    private var post: Entity? = null
    private var placeGhost: Entity? = null
    private var placeMarker: Entity? = null
    private var dome: Entity? = null
    private var buildJob: Job? = null
    private var built = false
    private var attached = false
    private var paused = false

    // head
    private var pose = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var headSinceMs = -1L
    private val yawHistory = ArrayDeque<Pair<Long, Float>>()

    // workcell
    var cell: Workcell? = null
        private set
    private var placeYaw = 0f
    private var placeAt: Vec3? = null
    private var pendingPlace: String? = null

    // frame state
    private var startMs = 0L
    private var lastFrameMs = 0L
    private var frames = 0L
    private var lastErrorMs = 0L
    private var pendingSinceMs = -1L
    private var lastLive: Live? = null
    private var lastLiveMs = 0L
    private var lastArm: ArmFrame? = null
    private var opening = Gripper.OPEN_M
    private var drillShown: Drill? = null
    private val lightViews = ArrayList<Entity>()
    private val blockViews = ArrayList<Pair<Entity, Entity>>()
    private val litAt = HashMap<Int, Long>()
    private var rippleMs = 0L
    private var rippleAt: Vec3? = null
    private var tooClose = false
    private var tooCloseSinceMs = 0L
    private var inCone = false
    private var windowPose: Upright? = null
    private var windowEpoch = -1
    private var chipShown = false
    private var levelHintShown = false
    private var domeLevel = 0f
    private var startupLogs = 0
    private var robotOpacity = 1f

    /** Debug `head x y z [pitch]`: added to the tracked head pose. */
    var debugHeadShift = Vec3.ZERO
    var debugHeadPitchDeg = 0f

    /** Debug `view DEG`: placements use the head turned by DEG (+ = left); the emulator camera cannot turn. */
    var viewYawDeg = 0f

    private val placePose: HeadPose
        get() = if (viewYawDeg == 0f) pose else HeadPose(pose.position, Quat4.axisAngle(Vec3.UP, Num.rad(viewYawDeg)) * pose.rotation)

    // =========================================================================================== lifecycle

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments, context: Context) {
        if (attached || stageRoot != null) detach()
        startMs = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.OPEN, PanelSizes.OPEN_W, PanelSizes.OPEN_H, PanelSizes.SCALE).maxPitch = 0f
        rig.world(PanelIds.TIP, PanelSizes.TIP_W, PanelSizes.TIP_H, PanelSizes.SCALE).maxPitch = 0f
        head.start()
        controllers.leftHanded = TdController.state.value.prefs.leftHanded
        controllers.start()
        ActiveScene.current = this
        attached = true
        TdController.quitHook = { releaseAll() }
        TdController.cellProvider = { cell }
        TdController.placementHook = { why -> main.post { onPlacement(why) } }
        bindPanels(attachments)
        buildJob = scope?.launch {
            try {
                build(root)
            } catch (t: Throwable) {
                if (t !is CancellationException) TdLog.e("SCENE build failed: $t", t)
                throw t
            }
        }
        TdLog.i("SCENE attached root offset=${TdLog.pos(offset.domain())}")
    }

    private suspend fun frameDelay() = delay(16)

    /** Resources first, then the entities in batches with a frame in between (the first mesh build blocks ~3 s, design notes). */
    private suspend fun build(root: Entity) {
        delay(150)
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val k = makeKit(sh)
        kit = k
        val sun = Entity().named("sun")
        sun.components.set(DirectionalLightComponent(Color4(1f, 0.96f, 0.9f, 1f), SUN_LUX, false))
        root.addChild(sun)
        sun.setLocal(rotation = Quat4.fromTo(Vec3(0f, 0f, -1f), Vec3(0.25f, -0.8f, -0.5f).normalized()))
        frameDelay()
        // the workshop dome (S23), hidden until chosen
        val d = Entity().named("dome")
        root.addChild(d)
        model(k.dome, k.domeMat, "dome:shell", d)
        model(k.floor, k.floorMat, "dome:floor", d).setLocal(position = Vec3(0f, 0.002f, 0f), rotation = Quat4.axisAngle(Vec3.RIGHT, Num.rad(-90f)), scale = Vec3(12f, 12f, 1f))
        for (i in -2..2) model(k.cube, k.lamp, "dome:lamp$i", d).setLocal(position = Vec3(i * 1.6f, 4.2f, -1f), scale = Vec3(0.12f, 0.04f, 3.5f))
        for (i in 0..5) {
            val a = Num.rad(-60f + i * 24f)
            model(k.cube, k.tableLeg, "dome:shelf$i", d).setLocal(position = Vec3(-sin(a) * 5f, 1.1f, -kotlin.math.cos(a) * 5f), rotation = Quat4.axisAngle(Vec3.UP, a), scale = Vec3(1.8f, 2.2f, 0.5f))
        }
        d.enabled = false
        d.setOpacity(0f)
        dome = d
        frameDelay()
        // workcell root (table-local space) with the table, the post for "打开面板" and the robot
        val cr = Entity().named("cell")
        root.addChild(cr)
        cr.enabled = false
        cellRoot = cr
        model(k.table, k.tableTop, "cell:table", cr)
        for ((sx, sz) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val leg = model(k.cyl, k.tableLeg, "cell:leg", cr)
            legs += Triple(leg, sx * 0.46f, sz * 0.36f)
        }
        model(k.grid, k.gridMat, "cell:grid", cr).setLocal(position = Vec3(0f, 0.0008f, 0f))
        model(k.stripes, k.yellow, "cell:stripe", cr).setLocal(position = Vec3(0f, 0.0012f, 0f))
        model(k.stripesInk, k.ink, "cell:stripeInk", cr).setLocal(position = Vec3(0f, 0.0014f, 0f))
        post = model(k.cyl, k.tableLeg, "cell:post", cr)
        frameDelay()
        val objs = Entity().named("objects")
        cr.addChild(objs)
        objRoot = objs
        robot = RobotView(cr, k.look, RobotMeshes(k.cyl, k.ball, k.cube))
        frameDelay()
        tether = model(k.cube, k.tether, "tether", root).also { it.enabled = false }
        ghostLine = model(k.cube, k.ghostLine, "ghostLine", root).also { it.enabled = false }
        val gg = Entity().named("ghostGripper")
        root.addChild(gg)
        ghostGripper = GripperView(gg, k.look, RobotMeshes(k.cyl, k.ball, k.cube), ghost = true).also { it.setShown(false) }
        ripple = model(k.ring, k.ripple, "ripple", root).also { it.enabled = false }
        // S02 placement: a ghost outline of the table and a floor marker
        val pg = Entity().named("placeGhost")
        root.addChild(pg)
        model(k.table, k.outline, "placeGhost:top", pg)
        pg.enabled = false
        placeGhost = pg
        placeMarker = model(k.ring, k.marker, "placeMarker", root).also { it.enabled = false }
        built = true
        TdLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
    }

    private val legs = ArrayList<Triple<Entity, Float, Float>>()
    private val drillEntities = ArrayList<Entity>()

    private fun makeKit(sh: SharedResources): Kit {
        val m = SceneMaterials(sh)
        val look = RobotLook(
            arm = m.pbr(TdColors.ArmWhite, 0.55f),
            joint = m.pbr(TdColors.JointGrey, 0.6f),
            ring = m.unlit(TdColors.UrBlue),
            ringWarn = m.unlit(TdColors.Alert),
            ringLimit = m.unlit(TdColors.Error),
            gripper = m.pbr(TdColors.GripperBody, 0.5f),
            pad = m.pbr(TdColors.PadRubber, 0.9f),
            ghost = m.sheer(TdColors.Ghost, 0.3f),
        )
        return Kit(
            cube = sh.keep(Shapes.cube("cube")),
            ball = sh.keep(Shapes.ball("ball")),
            cyl = sh.keep(Shapes.cylinder("cyl", 24)),
            ring = sh.keep(Shapes.ring("ring", 0.42f, 48)),
            table = sh.keep(Meshes.tableTop("tableTop")),
            grid = sh.keep(Meshes.grid("grid")),
            stripes = sh.keep(Meshes.stripes("stripes", 0)),
            stripesInk = sh.keep(Meshes.stripes("stripesInk", 1)),
            dome = sh.keep(Meshes.dome("domeShell", 6f)),
            floor = sh.keep(Shapes.disc("domeFloor", 48)),
            tableTop = m.pbr(TdColors.TableTop, 0.9f),
            tableLeg = m.pbr(TdColors.TableLeg, 0.6f),
            gridMat = m.sheer(TdColors.GridLine, 0.22f),
            yellow = m.unlit(TdColors.Tether),
            ink = m.unlit(TdColors.StripeInk),
            lightIdle = m.sheer(TdColors.Target, 0.45f),
            lightDone = m.unlit(TdColors.Done),
            blockMats = listOf(m.pbr(TdColors.BlockRed, 0.75f), m.pbr(TdColors.BlockBlue, 0.75f), m.pbr(TdColors.BlockYellow, 0.75f)),
            outline = m.sheer(TdColors.FrameWhite, 0.35f),
            tray = m.pbr(TdColors.TrayGrey, 0.8f),
            dash = m.sheer(TdColors.FrameWhite, 0.75f),
            tether = m.sheer(TdColors.Tether, 0.5f),
            ripple = m.sheer(TdColors.Error, 0.8f),
            ghostLine = m.sheer(TdColors.Ghost, 0.6f),
            marker = m.sheer(TdColors.Tether, 0.85f),
            domeMat = m.unlit(TdColors.DomeWall),
            floorMat = m.unlit(TdColors.DomeFloor),
            lamp = m.unlit(TdColors.FrameWhite),
            look = look,
        )
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        controllers.stop()
        TdLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        controllers.start()
        lastFrameMs = 0L
        TdLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    private fun releaseAll() {
        head.stop()
        controllers.stop()
    }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        TdController.quitHook = null
        TdController.cellProvider = null
        TdController.placementHook = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        releaseAll()
        panelShown.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        shared?.close()
        shared = null
        kit = null
        robot = null
        stageRoot = null
        cellRoot = null
        objRoot = null
        rig = null
        built = false
        lightViews.clear()
        blockViews.clear()
        drillShown = null
        TdLog.i("SCENE detached")
    }

    // =========================================================================================== per frame

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, MAX_DT)
        lastFrameMs = now
        frames++
        head.pose()?.let {
            // a first frame of all zeros + identity is "not tracking yet" (hand-sign teacher lesson)
            val zero = it.position.length < 1e-3f && abs(it.rotation.w) > 0.99999f
            if (!zero) {
                // debug `head`: a shifted / pitched head, as if you leaned in or looked down (the emulator camera is fixed)
                pose = if (debugHeadShift == Vec3.ZERO && debugHeadPitchDeg == 0f) it
                else HeadPose(it.position + debugHeadShift, (it.rotation * Quat4.axisAngle(Vec3.RIGHT, Num.rad(debugHeadPitchDeg))).normalized())
                if (!hasHead) headSinceMs = now
                hasHead = true
            }
        }
        if (hasHead) {
            yawHistory.addLast(now to pose.yaw(0f))
            while (yawHistory.isNotEmpty() && now - yawHistory.first().first > 1200) yawHistory.removeFirst()
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        // D9: the main window opens only while the head is level (the real head, not the debug `view` turn)
        WindowCtl.stepGate(now, if (hasHead) Num.deg(pose.pitch) else null)
        placeLevelHint()
        if (!attached || paused) return
        try {
            stepStart(now)
            if (built && cell != null) {
                val ui = TdController.state.value
                stepController(ui, now)
                if (ui.screen == Screen.PLACING) stepPlacing(dt) else stepArm(ui, now, dt)
                stepView(TdController.state.value, now, dt)
                placePanels(TdController.state.value, now)
            }
        } catch (t: Throwable) {
            // an engine call failing for one frame (runtime restarting) must not take the app down
            if (now - lastErrorMs > 2000) {
                lastErrorMs = now
                TdLog.e("FRAME step failed: $t", t)
            }
        }
        rig.faceViewer(pose.position)
        stepLogs(now)
    }

    /** S01: wait for the head (<= 2 s, then show the wait), lay the workcell out by yaw only, then S03 / S05. */
    private fun stepStart(now: Long) {
        val ui = TdController.state.value
        if (ui.screen != Screen.STARTING) return
        if (!hasHead) {
            if (now - startMs > 2000 && !ui.headWaiting) TdController.onHeadWaiting(true)
            return
        }
        if (!built || now - headSinceMs < 300) return
        placeDefault("start")
        TdController.onSceneReady()
    }

    /** The default workcell: 45° towards the teleop hand from your view (window straight ahead), yaw only (D9). */
    private fun placeDefault(why: String, headYaw: Float = placePose.yaw(0f)) {
        val p = TdController.state.value.prefs
        val c = Workcell.forHead(placePose, p.tableHeight, p.leftHanded, headYaw)
        setCell(c, why)
    }

    private fun setCell(c: Workcell, why: String) {
        cell = c
        val cr = cellRoot ?: return
        cr.setLocal(position = c.origin, rotation = c.rotation)
        cr.enabled = true
        // table legs from the top down to the floor
        val h = c.topY
        for ((l, x, z) in legs) l.setLocal(position = Vec3(x, -Workcell.THICK - (h - Workcell.THICK) / 2f, z), scale = Vec3(0.04f, h - Workcell.THICK, 0.04f))
        val chip = c.hand(CHIP_CORNER)
        post?.setLocal(position = Vec3(chip.x, CHIP_POST_H / 2f, chip.z), scale = Vec3(0.02f, CHIP_POST_H, 0.02f))
        robot?.place(Workcell.BASE_LOCAL, Num.yawQuat(c.robotYawInTable))
        controllers.leftHanded = c.mirror < 0f
        TdLog.i(String.format(Locale.US, "WORKCELL %s origin=%s yaw=%.1f top=%.2f mirror=%.0f base=%s headDist=%.2f", why, TdLog.pos(c.origin), Num.deg(c.yaw), c.topY, c.mirror, TdLog.pos(c.base), Vec3(c.base.x - pose.position.x, 0f, c.base.z - pose.position.z).length))
    }

    /** Settings / S02 / "工作台摆到右前" (from TdController). */
    private fun onPlacement(why: String) {
        val ui = TdController.state.value
        val c = cell ?: return
        when {
            why == "start" -> {
                placeYaw = c.yaw
                placeAt = Vec3(c.origin.x, 0f, c.origin.z)
                TdController.onMarker(false)
                placeGhost?.enabled = true
                placeMarker?.enabled = true
            }
            why == "confirm" -> {
                val at = placeAt ?: return
                setCell(Workcell.onMarker(at, placeYaw, ui.prefs.tableHeight, ui.prefs.leftHanded), "marker")
                placeGhost?.enabled = false
                placeMarker?.enabled = false
            }
            why == "cancel" -> {
                placeGhost?.enabled = false
                placeMarker?.enabled = false
            }
            why == "aside" -> {
                // D29: the key is pressed while looking at the window; use the median head yaw of the last second
                val yaws = yawHistory.map { it.second }.sorted()
                val yaw = if (yaws.isEmpty()) pose.yaw(0f) else yaws[yaws.size / 2]
                // debug `view DEG` turns this placement like a turned head (the emulator camera cannot turn)
                placeDefault("aside", Yaw.wrap(yaw + Num.rad(viewYawDeg)))
            }
            why.startsWith("prefs") -> {
                if (ui.inRound) {
                    pendingPlace = why
                    return
                }
                // same place, new height / handedness: re-pose around the table centre
                setCell(Workcell(Vec3(c.origin.x, ui.prefs.tableHeight, c.origin.z), c.yaw, if (ui.prefs.leftHanded) -1f else 1f), why)
                TdController.arm.resetPose()
            }
        }
    }

    // ---------------------------------------------------------------------------------------- input

    private fun stepController(ui: TdUiState, now: Long) {
        val s = controllers.supportState()
        val state = when (s) {
            DataProvider.SupportState.SUPPORTED -> ControllerState.OK
            DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> ControllerState.NOT_SUPPORTED
            DataProvider.SupportState.WITHOUT_PERMISSION -> ControllerState.NO_PERMISSION
            DataProvider.SupportState.NOT_IN_FULL_SPACE -> ControllerState.NOT_FULL_SPACE
            else -> {
                if (pendingSinceMs < 0L) pendingSinceMs = now
                if (now - pendingSinceMs > 3000) ControllerState.NOT_SUPPORTED else ControllerState.PENDING
            }
        }
        if (state != ControllerState.PENDING) pendingSinceMs = -1L
        TdController.onControllerState(state)
        val b = controllers.takeButtons()
        if (ui.screen == Screen.PLACING) {
            if (b.triggerPresses > 0 && placeAt != null) TdController.onMarker(true)
            return
        }
        repeat(b.b) { TdController.onEvent(Ui.ButtonB) }
        repeat(b.a) { TdController.onEvent(Ui.ButtonA) }
        repeat(b.stick) { TdController.onEvent(Ui.StickPress) }
        // S25: the window closed during a round pauses it
        if (!WindowCtl.open && WindowCtl.panelState != "never" && ui.screen == Screen.LIVE) TdController.onWindowClosed()
    }

    /** S02: a ray (either controller) on the floor moves the marker; either stick turns the ghost; trigger sets it. */
    private fun stepPlacing(dt: Float) {
        val c = cell ?: return
        val ray = controllers.rayOf(controllers.otherPose()) ?: controllers.rayOf(controllers.teleopPose())
        if (ray != null && !TdController.state.value.markerSet) {
            Placement.rayFloor(ray.origin, ray.dir, 0f, 8f)?.let { placeAt = it }
        }
        val (lx, rx) = controllers.stickX()
        placeYaw = WorkcellPlacer.turn(placeYaw, if (abs(lx) > abs(rx)) lx else rx, dt, pose.position, placeAt ?: c.origin)
        val at = placeAt ?: return
        placeGhost?.setLocal(position = Vec3(at.x, c.topY, at.z), rotation = Num.yawQuat(placeYaw))
        placeMarker?.setLocal(position = Vec3(at.x, 0.005f, at.z), rotation = Quat4.axisAngle(Vec3.RIGHT, Num.rad(-90f)), scale = Vec3(0.3f, 0.3f, 1f))
    }

    private fun stepArm(ui: TdUiState, now: Long, dt: Float) {
        val c = cell ?: return
        val arm = TdController.arm
        val hand = controllers.hand()
        val teleopOk = ui.screen == Screen.LIVE || ui.screen == Screen.HOME || ui.screen == Screen.INTRO || ui.screen == Screen.RESULT || ui.screen == Screen.COACH
        val drill = TdController.drill?.takeIf { ui.screen == Screen.LIVE || ui.screen == Screen.PAUSED || ui.screen == Screen.COUNTDOWN || ui.screen == Screen.RESULT }
        val held = drill?.held
        val f = arm.step(dt, if (teleopOk) hand else HandInput(hand.pose, 0f, hand.trigger), c, TdController.config(ui), held?.half ?: 0f, teleopOk)
        lastArm = f
        // trigger -> gripper (calc_parallel_gripper_position); not while the free trigger is clicking the window
        val pointingAtWindow = !f.clutched && rayHitsWindow(controllers.rayOf(hand.pose))
        if (!pointingAtWindow && ui.screen != Screen.PAUSED && ui.screen != Screen.COUNTDOWN) opening = Gripper.opening(hand.trigger)
        if (drill != null && ui.screen == Screen.LIVE) {
            val evs = drill.step(dt, f, opening)
            if (evs.isNotEmpty()) {
                TdLog.i("DRILL t=${"%.2f".format(Locale.US, drill.t)} ${evs.joinToString()}")
                TdController.onDrillEvents(evs)
            }
        }
        if (f.tableHit && f.contact != null) {
            rippleAt = f.contact
            rippleMs = now
        }
        if (f.grabbedNow || f.releasedNow) TdLog.i("CLUTCH ${if (f.grabbedNow) "grab" else "release"} map=${ui.setup.mapping} scale=${ui.setup.scale} control=${ui.setup.control(ui.prefs)} tcp=${TdLog.pos(f.tcp.p)} hand=${TdLog.pos(hand.pose?.p)}")
        // live snapshot (only on change, at most 10 / s)
        val live = Live(
            clutched = f.clutched,
            openingMm = (Grasp.visualOpening(opening, held) * 1000f).toInt(),
            done = drill?.doneCount ?: 0,
            targets = drill?.layout?.targets ?: 0,
            timeS = drill?.t?.toInt() ?: 0,
            oops = drill?.meter?.events?.toMap() ?: emptyMap(),
            holding = held != null,
            jointLimit = f.limitJoint,
            singular = f.singular,
            unreachable = f.unreachable,
            trackingLost = f.trackingLost,
            tooClose = tooClose,
            inWindowCone = inCone,
            towerHeight = drill?.towerHeight ?: 0,
        )
        if (live != lastLive && now - lastLiveMs >= 100) {
            lastLive = live
            lastLiveMs = now
            TdController.onLive(live)
        }
    }

    /** Is the ray of the teleop hand on the main window (estimated where the system opened it)? */
    private fun rayHitsWindow(ray: Ray?): Boolean {
        val r = ray ?: return false
        val w = windowPose ?: return false
        if (!WindowCtl.open) return false
        val n = Yaw.dir(w.yaw) * -1f
        val denom = r.dir.dot(n)
        if (abs(denom) < 1e-3f) return false
        val t = (w.position - r.origin).dot(n) / denom
        if (t <= 0f || t > 5f) return false
        val hit = r.origin + r.dir * t
        val right = Vec3(kotlin.math.cos(w.yaw), 0f, -sin(w.yaw))
        val d = hit - w.position
        val halfW = PanelSizes.WINDOW_W * WindowCtl.dpM / 2f + 0.05f
        val halfH = PanelSizes.WINDOW_H * WindowCtl.dpM / 2f + 0.05f
        return abs(d.dot(right)) <= halfW && abs(d.y) <= halfH
    }

    // ---------------------------------------------------------------------------------------- view

    private fun stepView(ui: TdUiState, now: Long, dt: Float) {
        val c = cell ?: return
        val k = kit ?: return
        val arm = TdController.arm
        val robot = robot ?: return
        val drill = TdController.drill?.takeIf { ui.screen == Screen.LIVE || ui.screen == Screen.PAUSED || ui.screen == Screen.COUNTDOWN || ui.screen == Screen.RESULT }
        // robot
        val held = drill?.held
        val frames = robot.setPose(Ur5eKinematics.degrees(arm.q), Grasp.visualOpening(opening, held))
        val f = lastArm
        val margins = Ik.margins(arm.q)
        robot.setRings(IntArray(6) { if (margins[it] < Ik.LIMIT_MARGIN) 2 else if (margins[it] < Ik.WARN_MARGIN) 1 else 0 })
        // S21 too close / S24 in the window's line of sight: the whole arm at 40 %
        val world = frames.map { c.toStage(Workcell.BASE_LOCAL + Num.rotY(it.origin, c.robotYawInTable)) }
        val tip = f?.tcp?.p ?: world.last()
        val close = headToArm(world, tip)
        val follow = ui.setup.mapping == MappingMode.FOLLOW
        if (close < (if (tooClose) 0.30f else 0.25f)) {
            if (!tooClose && (!follow || now - tooCloseSinceMs > 500)) tooClose = true
            if (tooCloseSinceMs == 0L) tooCloseSinceMs = now
        } else {
            tooCloseSinceMs = 0L
            if (close > 0.30f) tooClose = false
        }
        inCone = WindowCtl.open && windowPose != null && world.any { inWindowCone(it) }
        val wantOpacity = if (tooClose || inCone) 0.4f else 1f
        if (abs(wantOpacity - robotOpacity) > 0.01f) {
            robotOpacity = wantOpacity
            robot.setOpacity(wantOpacity)
        }
        // control line (S08) and the target ghost when the IK cannot get there
        val hp = controllers.teleopPose()
        if (f != null && f.clutched && hp != null) {
            tether?.on(true)
            tether?.bar(hp.p, f.tcp.p, 0.003f)
        } else tether?.on(false)
        val cmd = f?.commanded
        if (f != null && f.unreachable && cmd != null) {
            ghostGripper?.setShown(true)
            val o = cmd.p - Tool.approach(cmd.q) * Gripper.TCP_M
            ghostGripper?.setPose(o, Tool.closing(cmd.q), Tool.across(cmd.q), Tool.approach(cmd.q), opening)
            ghostLine?.on(true)
            ghostLine?.bar(cmd.p, f.tcp.p, 0.002f)
        } else {
            ghostGripper?.setShown(false)
            ghostLine?.on(false)
        }
        // table-hit ripple (S10)
        val ra = rippleAt
        val age = now - rippleMs
        if (ra != null && age < 600) {
            val s = 0.04f + 0.16f * (age / 600f)
            ripple?.on(true)
            ripple?.setLocal(position = ra + Vec3(0f, 0.003f, 0f), rotation = Quat4.axisAngle(Vec3.RIGHT, Num.rad(-90f)), scale = Vec3(s, s, 1f))
        } else ripple?.on(false)
        // drill objects
        if (drill !== drillShown) showDrill(drill, k)
        if (drill != null) updateDrill(drill, now)
        // workshop dome (S23): 0.6 s fade
        val wantDome = if (ui.prefs.vr) 1f else 0f
        if (domeLevel != wantDome) {
            domeLevel = if (wantDome > domeLevel) minOf(wantDome, domeLevel + dt / 0.6f) else maxOf(wantDome, domeLevel - dt / 0.6f)
            dome?.let {
                it.on(domeLevel > 0f)
                it.setLocal(position = Vec3(pose.position.x, 0f, pose.position.z))
                it.setOpacity(domeLevel)
            }
        }
        if (pendingPlace != null && !ui.inRound) {
            val p = pendingPlace!!
            pendingPlace = null
            onPlacement(p)
        }
    }

    /** Smallest distance from the head to the arm's capsules (gripper and wrist count 10 cm closer). */
    private fun headToArm(world: List<Vec3>, tip: Vec3): Float {
        val h = pose.position
        var best = Float.MAX_VALUE
        for (i in 0 until world.size - 1) {
            val d = segDist(h, world[i], world[i + 1]) - Ur5e.CAPSULE_R[i]
            best = minOf(best, if (i >= 4) d - 0.10f else d)
        }
        best = minOf(best, segDist(h, world.last(), tip) - 0.045f - 0.10f)
        return best
    }

    private fun segDist(p: Vec3, a: Vec3, b: Vec3): Float {
        val ab = b - a
        val l2 = ab.dot(ab)
        val t = if (l2 < 1e-8f) 0f else ((p - a).dot(ab) / l2).coerceIn(0f, 1f)
        return p.distanceTo(a + ab * t)
    }

    /** S24: a point of the arm between your head and the window, inside the cone that covers the window. */
    private fun inWindowCone(p: Vec3): Boolean {
        val w = windowPose ?: return false
        val toW = w.position - pose.position
        val dW = toW.length
        val toP = p - pose.position
        val dP = toP.length
        if (dP >= dW || dP < 0.05f) return false
        val halfW = PanelSizes.WINDOW_W * WindowCtl.dpM / 2f
        val cone = kotlin.math.atan2(halfW + 0.05f, dW)
        val cos = toW.dot(toP) / (dW * dP)
        return cos > kotlin.math.cos(cone)
    }

    private fun showDrill(d: Drill?, k: Kit) {
        val objs = objRoot ?: return
        for (e in drillEntities) runCatching { e.destroy(true) }
        drillEntities.clear()
        lightViews.clear()
        blockViews.clear()
        litAt.clear()
        drillShown = d
        if (d == null) return
        for ((i, l) in d.lights.withIndex()) {
            val e = model(k.ball, k.lightIdle, "light$i", objs).also { drillEntities += it }
            val r = l.spec.radius * 2
            e.setLocal(position = l.spec.c, scale = Vec3(r, r, r))
            lightViews += e
            l.spec.arrow?.let { a ->
                // the approach to match: a stick from the light back along the approach (where the gripper comes from)
                model(k.cube, k.dash, "arrow$i", objs).also { drillEntities += it }.bar(l.spec.c, l.spec.c - a * 0.06f, 0.004f)
            }
        }
        for (b in d.blocks) {
            val body = model(k.cube, k.blockMats[b.color % k.blockMats.size], "block${b.id}", objs).also { drillEntities += it }
            val outline = model(k.cube, k.outline, "block${b.id}:outline", objs).also { drillEntities += it }.also { it.enabled = false }
            blockViews += body to outline
        }
        for ((i, t) in d.trays.withIndex()) {
            val half = t.inner / 2f
            if (t.grid) {
                // dashed square + corner marks, 3 mm lines on the table
                for (s in 0 until 4) {
                    val a = Vec3(if (s % 2 == 0) -half else half, 0.002f, if (s < 2) -half else half)
                    val corners = listOf(Vec3(-half, 0f, -half), Vec3(half, 0f, -half), Vec3(half, 0f, half), Vec3(-half, 0f, half))
                    val p0 = corners[s]
                    val p1 = corners[(s + 1) % 4]
                    for (dsh in 0 until 4) {
                        val t0 = dsh / 4f + 0.05f
                        val t1 = t0 + 0.15f
                        model(k.cube, k.dash, "grid$i:$s:$dsh", objs).also { drillEntities += it }.bar(Vec3(t.x, 0.002f, t.z) + p0.lerp(p1, t0), Vec3(t.x, 0.002f, t.z) + p0.lerp(p1, t1), 0.003f)
                    }
                    @Suppress("UNUSED_VARIABLE") val unused = a
                }
            } else {
                val h = 0.02f
                val corners = listOf(Vec3(-half, 0f, -half), Vec3(half, 0f, -half), Vec3(half, 0f, half), Vec3(-half, 0f, half))
                for (s in 0 until 4) {
                    val p0 = corners[s] + Vec3(t.x, h / 2f, t.z)
                    val p1 = corners[(s + 1) % 4] + Vec3(t.x, h / 2f, t.z)
                    model(k.cube, k.tray, "tray$i:$s", objs).also { drillEntities += it }.bar(p0, p1, h)
                }
            }
        }
        d.stackBase?.let { b ->
            val s = 0.035f
            model(k.cube, k.dash, "cross:x", objs).also { drillEntities += it }.bar(b + Vec3(-s, 0.002f, 0f), b + Vec3(s, 0.002f, 0f), 0.004f)
            model(k.cube, k.dash, "cross:z", objs).also { drillEntities += it }.bar(b + Vec3(0f, 0.002f, -s), b + Vec3(0f, 0.002f, s), 0.004f)
            val corners = listOf(Vec3(-s, 0.002f, -s), Vec3(s, 0.002f, -s), Vec3(s, 0.002f, s), Vec3(-s, 0.002f, s))
            for (i in 0 until 4) model(k.cube, k.dash, "cross:e$i", objs).also { drillEntities += it }.bar(b + corners[i], b + corners[(i + 1) % 4], 0.003f)
        }
        TdLog.i("DRILL shown level=${d.layout.level} kind=${d.layout.kind} lights=${d.lights.size} blocks=${d.blocks.size} trays=${d.trays.size}")
    }

    private fun updateDrill(d: Drill, now: Long) {
        val k = kit ?: return
        for ((i, l) in d.lights.withIndex()) {
            val e = lightViews.getOrNull(i) ?: continue
            if (l.lit) {
                if (!litAt.containsKey(i)) {
                    litAt[i] = now
                    e.setMaterial(k.lightDone)
                }
                val age = (now - litAt[i]!!) / 1000f
                val r = l.spec.radius * 2 * (1f + 0.4f * maxOf(0f, 1f - age / 0.4f))
                e.setLocal(scale = Vec3(r, r, r))
            } else {
                // slow breathing while waiting
                val r = l.spec.radius * 2 * (1f + 0.06f * sin(now / 400f + i))
                e.setLocal(scale = Vec3(r, r, r))
            }
        }
        for ((i, b) in d.blocks.withIndex()) {
            val (body, outline) = blockViews.getOrNull(i) ?: continue
            val rot = Num.yawQuat(b.yaw)
            body.setLocal(position = b.c, rotation = rot, scale = Vec3(b.size, b.size, b.size))
            val heldNow = b.state == BlockState.HELD
            outline.on(heldNow)
            if (heldNow) {
                val s = b.size + 0.006f
                outline.setLocal(position = b.c, rotation = rot, scale = Vec3(s, s, s))
            }
        }
    }

    // ---------------------------------------------------------------------------------------- panels

    private fun estimateWindow(why: String) {
        val up = Placement.window(pose)
        windowPose = up
        TdLog.i(String.format(Locale.US, "WINDOW estimate why=%s at=%s yaw=%.1f head=%s pitch=%.1f size=%.2fx%.2f m", why, TdLog.pos(up.position), Num.deg(up.yaw), TdLog.pos(pose.position), Num.deg(pose.pitch), PanelSizes.WINDOW_W * WindowCtl.dpM, PanelSizes.WINDOW_H * WindowCtl.dpM))
    }

    /** "平视前方" while the window waits for a level head: on the line of sight, upright (yaw only, D9); same slot as the chip. */
    private fun placeLevelHint() {
        val rig = rig ?: return
        if (WindowCtl.levelHint && hasHead) {
            val h = Placement.levelHint(pose)
            if (!levelHintShown) TdLog.i("LEVEL hint show at=${TdLog.pos(h.position)} pitch=${"%.1f".format(Locale.US, Num.deg(pose.pitch))}")
            levelHintShown = true
            chipShown = false
            showAt(PanelIds.OPEN, h.position)
            rig[PanelIds.OPEN]?.facePoint = null
        } else if (levelHintShown) {
            levelHintShown = false
            rig[PanelIds.OPEN]?.hide()
        }
    }

    private fun placePanels(ui: TdUiState, now: Long) {
        val rig = rig ?: return
        val c = cell ?: return
        if (WindowCtl.createdEpoch != windowEpoch && hasHead) {
            val again = windowEpoch >= 0
            windowEpoch = WindowCtl.createdEpoch
            if (windowEpoch > 0) estimateWindow(if (again) "reopen" else "first")
        }
        // "打开面板" on the post at the near corner (towards the window side) while the window is closed / minimised
        if (!WindowCtl.levelHint) {
            val want = WindowCtl.chipWanted(now) && ui.screen != Screen.STARTING
            if (want) {
                val p = c.toStage(c.hand(CHIP_CORNER) + Vec3(0f, CHIP_POST_H + 0.07f, 0f))
                if (!chipShown) TdLog.i("CHIP show at=${TdLog.pos(p)} window=${WindowCtl.panelState}")
                chipShown = true
                showAt(PanelIds.OPEN, p)
                rig[PanelIds.OPEN]?.facePoint = pose.position
            } else if (chipShown) {
                chipShown = false
                rig[PanelIds.OPEN]?.hide()
            }
        }
        // the tip tag over the far side of the table (away from the window) while there is something to say
        val tipOn = ui.screen != Screen.STARTING && TipText.wanted(ui)
        if (tipOn) {
            showAt(PanelIds.TIP, c.toStage(c.hand(TIP_AT)))
            rig[PanelIds.TIP]?.facePoint = pose.position
        } else rig[PanelIds.TIP]?.hide()
    }

    private fun showAt(id: String, at: Vec3) {
        val slot = rig?.get(id) ?: return
        if (slot.shown) slot.moveTo(at) else slot.show(at)
    }

    // ---------------------------------------------------------------------------------------- logs / debug

    private fun stepLogs(now: Long) {
        val since = now - startMs
        if (startupLogs < 3 && since > STARTUP_LOG_MS[startupLogs]) {
            startupLogs++
            TdLog.i("STARTUP ${since / 1000}s ${where()}")
        }
    }

    fun where(): String {
        val c = cell
        val f = lastArm
        val tcpL = if (c != null && f != null) c.toLocal(f.tcp.p) else null
        return String.format(Locale.US, "head=%s yaw=%.1f pitch=%.1f cell=%s cellYaw=%.1f base=%s tcpLocal=%s clutched=%s opening=%.0fmm q=%s window=%s chip=%s",
            TdLog.pos(pose.position), Num.deg(pose.yaw(0f)), Num.deg(pose.pitch), TdLog.pos(c?.origin), Num.deg(c?.yaw ?: 0f), TdLog.pos(c?.base), TdLog.pos(tcpL), f?.clutched, opening * 1000f,
            Ur5eKinematics.degrees(TdController.arm.q).joinToString(",") { "%.0f".format(Locale.US, it) }, WindowCtl.panelState, chipShown)
    }

    /** Debug `trigger v` while no controller: sets the commanded opening directly through the same mapping. */
    fun debugOpening(trigger: Float) {
        opening = Gripper.opening(trigger)
    }

    fun headLocal(): Vec3? = cell?.toLocal(pose.position)

    companion object {
        /** Frame step cap: the drill clock stays close to real time even at the emulator's 2-4 fps. */
        private const val MAX_DT = 0.25f
        private const val SUN_LUX = 4.0f
        private val STARTUP_LOG_MS = longArrayOf(1000, 3000, 5000)
        /** Left near corner (right hand), where the post with "打开面板" stands. */
        val CHIP_CORNER = Vec3(-0.44f, 0f, 0.34f)
        const val CHIP_POST_H = 0.35f
        /** The tip tag: over the far right of the table (right hand), away from the window. */
        val TIP_AT = Vec3(0.30f, 0.62f, -0.30f)
    }
}

/** When the tip tag over the table has something to say (S09-S12, S22, the countdown). */
object TipText {
    fun wanted(ui: TdUiState): Boolean = ui.screen == Screen.COUNTDOWN || ui.screen == Screen.PAUSED ||
        (ui.screen == Screen.LIVE && ui.hint != io.embodit.teleopdojo.ui.Hint.NONE && ui.hint != io.embodit.teleopdojo.ui.Hint.HOLD_GRIP) ||
        ui.screen == Screen.PLACING
}
