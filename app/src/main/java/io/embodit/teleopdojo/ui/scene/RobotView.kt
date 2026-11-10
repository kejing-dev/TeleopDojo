package io.embodit.teleopdojo.ui.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.teleopdojo.domain.Gripper
import io.embodit.teleopdojo.domain.Quat4
import io.embodit.teleopdojo.domain.Ur5e
import io.embodit.teleopdojo.domain.Vec3

/** Materials of the robot (design entities3d.ur5e / gripper, look A grey-white industrial). */
internal class RobotLook(
    val arm: Material,
    val joint: Material,
    val ring: Material,
    val ringWarn: Material,
    val ringLimit: Material,
    val gripper: Material,
    val pad: Material,
    val ghost: Material,
)

/** Unit meshes shared by the robot parts (cylinder: diameter 1, height 1 about Y centred; ball: diameter 1; cube: 1). */
internal class RobotMeshes(val cylinder: MeshResource, val ball: MeshResource, val cube: MeshResource)

/**
 * The UR5e (reused from Robot Cell Fit v0.1.3: the procedural white model on the official DH table, design
 * referenceRepo.take): base drum, link cylinders between the FK joint points, six darker joint drums with a colour band
 * each (hidden normally, warm orange < 25° from a limit, red < 10°: design entities3d.ur5e), and the parallel gripper
 * on the flange. Every part is a scaled unit mesh, so a pose change only writes transforms. Robot-local frame (Y up,
 * zero pose reaching along -X); the root is placed by [place].
 */
internal class RobotView(parent: Entity, private val look: RobotLook, private val meshes: RobotMeshes) {
    val root: Entity = Entity().named("robot")
    private val links = ArrayList<Pair<Int, Entity>>()
    private val balls = ArrayList<Pair<Int, Entity>>()
    private val drums = ArrayList<Entity>()
    private val rings = ArrayList<Entity>()
    private val ringLevel = IntArray(6)
    private val base: Entity
    private val flange: Entity
    val gripper: GripperView

    init {
        parent.addChild(root)
        base = model(meshes.cylinder, look.arm, "robot:base", root)
        for (j in 0 until 6) {
            drums += model(meshes.cylinder, look.joint, "robot:drum$j", root)
            rings += model(meshes.cylinder, look.ring, "robot:ring$j", root).also { it.enabled = false }
        }
        for (i in 1..5) links += i to model(meshes.cylinder, look.arm, "robot:link$i", root)
        for (i in 2..5) balls += i to model(meshes.ball, look.arm, "robot:ball$i", root)
        flange = model(meshes.cylinder, look.joint, "robot:flange", root)
        gripper = GripperView(root, look, meshes)
        base.setLocal(position = Vec3(0f, Ur5e.BASE_HEIGHT_M / 2f, 0f), scale = Vec3(Ur5e.BASE_RADIUS_M * 2, Ur5e.BASE_HEIGHT_M, Ur5e.BASE_RADIUS_M * 2))
    }

    fun place(mount: Vec3, rotation: Quat4) {
        root.setLocal(position = mount, rotation = rotation)
    }

    /** Writes every part for joint angles in degrees. Returns the frames (robot-local). */
    fun setPose(jointsDeg: FloatArray, openingM: Float): Array<Ur5e.Frame> {
        val f = Ur5e.frames(jointsDeg)
        for (j in 0 until 6) {
            val (c, axis) = drumAxis(f, j)
            val h = Ur5e.JOINT_H[j]
            val r = Ur5e.JOINT_R[j]
            val rot = Quat4.fromTo(Vec3.UP, axis)
            drums[j].setLocal(position = c, rotation = rot, scale = Vec3(r * 2, h, r * 2))
            rings[j].setLocal(position = c, rotation = rot, scale = Vec3((r + 0.003f) * 2, 0.012f, (r + 0.003f) * 2))
        }
        for ((i, e) in links) {
            val a = f[i].origin
            val b = f[i + 1].origin
            val d = b - a
            val len = d.length
            val r = Ur5e.LINK_R[i]
            if (len < 1e-4f) e.setLocal(position = a, scale = Vec3(r * 2, 0.001f, r * 2))
            else e.setLocal(position = (a + b) * 0.5f, rotation = Quat4.fromTo(Vec3.UP, d / len), scale = Vec3(r * 2, len, r * 2))
        }
        for ((i, e) in balls) {
            val r = Ur5e.LINK_R[i] * 2
            e.setLocal(position = f[i].origin, scale = Vec3(r, r, r))
        }
        val fl = f[6]
        flange.setLocal(position = fl.origin - fl.z * 0.006f, rotation = Quat4.fromTo(Vec3.UP, fl.z.normalized()), scale = Vec3(0.09f, 0.012f, 0.09f))
        gripper.setPose(fl, openingM)
        return f
    }

    /** Joint band levels: 0 hidden, 1 warn (orange), 2 limit (red). */
    fun setRings(levels: IntArray) {
        for (j in 0 until 6) {
            val l = levels[j]
            if (l == ringLevel[j]) continue
            ringLevel[j] = l
            rings[j].enabled = l > 0
            if (l > 0) rings[j].setMaterial(if (l >= 2) look.ringLimit else look.ringWarn)
        }
    }

    private fun drumAxis(f: Array<Ur5e.Frame>, j: Int): Pair<Vec3, Vec3> =
        if (j == 0) Vec3(0f, Ur5e.D1 - 0.03f, 0f) to Vec3.UP else f[j].origin to f[j].z.normalized()

    fun setOpacity(v: Float) = root.setOpacity(v)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/**
 * The parallel gripper (design entities3d.gripper, look A): Ø 75 x 70 mm flange body, a rail, two fingers and two
 * 20 x 10 x 45 mm pads (dark rubber) that open 0-85 mm; TCP = pad centre, 0.15 m from the flange. Tool frame: z the
 * approach, x the finger travel (= the flange frame's x / z).
 */
internal class GripperView(root: Entity, private val look: RobotLook, meshes: RobotMeshes, ghost: Boolean = false) {
    private val body = model(meshes.cylinder, if (ghost) look.ghost else look.gripper, "robot:gripBody", root)
    private val rail = model(meshes.cube, if (ghost) look.ghost else look.joint, "robot:gripRail", root)
    private val fingers = List(2) { model(meshes.cube, if (ghost) look.ghost else look.gripper, "robot:gripFinger$it", root) }
    private val pads = List(2) { model(meshes.cube, if (ghost) look.ghost else look.pad, "robot:gripPad$it", root) }
    private val all = listOf(body, rail) + fingers + pads

    fun setShown(v: Boolean) = all.forEach { it.on(v) }

    /** On the flange frame [f] (robot-local or any parent space) with the pads [openingM] apart (inner faces). */
    fun setPose(f: Ur5e.Frame, openingM: Float) = setPose(f.origin, f.x.normalized(), f.y.normalized(), f.z.normalized(), openingM)

    fun setPose(o: Vec3, x: Vec3, y: Vec3, z: Vec3, openingM: Float) {
        val rot = Quat4.fromBasis(x, y, z)
        fun at(dx: Float, dz: Float): Vec3 = o + x * dx + z * dz
        body.setLocal(position = at(0f, Gripper.FLANGE_H / 2f), rotation = Quat4.fromTo(Vec3.UP, z), scale = Vec3(Gripper.FLANGE_R * 2, Gripper.FLANGE_H, Gripper.FLANGE_R * 2))
        rail.setLocal(position = at(0f, Gripper.FLANGE_H + 0.008f), rotation = rot, scale = Vec3(0.11f, 0.024f, 0.016f))
        val half = openingM.coerceIn(0f, Gripper.OPEN_M) / 2f
        val fingerTop = Gripper.FLANGE_H + 0.016f
        val padTop = Gripper.TCP_M - Gripper.PAD_H / 2f
        for (s in 0..1) {
            val sign = if (s == 0) -1f else 1f
            val px = sign * (half + Gripper.PAD_W / 2f)
            fingers[s].setLocal(position = at(sign * (half + Gripper.PAD_W + 0.006f), (fingerTop + padTop + 0.01f) / 2f), rotation = rot, scale = Vec3(0.012f, 0.022f, padTop + 0.01f - fingerTop))
            pads[s].setLocal(position = at(px, Gripper.TCP_M), rotation = rot, scale = Vec3(Gripper.PAD_W, Gripper.PAD_D, Gripper.PAD_H))
        }
    }

    fun setOpacity(v: Float) = all.forEach { it.setOpacity(v) }
}
