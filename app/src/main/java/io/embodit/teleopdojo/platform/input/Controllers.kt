package io.embodit.teleopdojo.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerAction
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.teleopdojo.domain.HandInput
import io.embodit.teleopdojo.domain.Pose
import io.embodit.teleopdojo.domain.Quat4
import io.embodit.teleopdojo.domain.Vec3
import io.embodit.teleopdojo.platform.TdLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A pointing ray in Stage metres. */
data class Ray(val origin: Vec3, val dir: Vec3)

/** Button edges since the last frame (teleop hand: B / Y start-end, A / X pause, stick press void). */
data class Buttons(val b: Int, val a: Int, val stick: Int, val triggerPresses: Int)

/**
 * Both PICO controllers (design rules.input). The teleop hand (right by default, left in left-handed mode) gives the
 * pose + grip / trigger analog values (ControllerTrackingProvider, Full Space only, SDK 6.1 controller-tracking); its
 * B / A / stick press (left: Y / X / stick press) follow the XRoboToolkit layout. The other hand's ray points at UI.
 * `ControllerActionListener` runs on the data source's thread: it only copies values into atomics (the doc: no
 * time-consuming work in the callback); the frame system reads them.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    private val gripR = AtomicInteger(0)
    private val gripL = AtomicInteger(0)
    private val trigR = AtomicInteger(0)
    private val trigL = AtomicInteger(0)
    private val stickRx = AtomicInteger(0)
    private val stickLx = AtomicInteger(0)
    private val bEdges = AtomicInteger(0)
    private val aEdges = AtomicInteger(0)
    private val stickEdges = AtomicInteger(0)
    private val trigEdges = AtomicInteger(0)
    private val down = Array(8) { AtomicBoolean(false) }
    private val actions = AtomicInteger(0)
    private var lastSupport: DataProvider.SupportState? = null
    @Volatile var leftHanded = false

    /** Debug overrides of the teleop hand's analog values (`grip v`, `trigger v`): same path as the real values. */
    @Volatile var debugGrip: Float? = null
    @Volatile var debugTrigger: Float? = null

    /**
     * Debug `controller on`: the provider counts as SUPPORTED. PICO Emulator 6.1.0 reports DEVICE_NOT_SUPPORTED even in
     * Right Controller Mode, so QA drives rounds with the grip / trigger / tcp hooks (design qaHooks) on top of this.
     */
    @Volatile var debugPresent = false

    /** Debug `lost`: the teleop hand's pose reads as null (S22), as when tracking drops out. */
    @Volatile var debugLost = false

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
            actions.incrementAndGet()
            val r = runCatching { a.right }.getOrNull()
            val l = runCatching { a.left }.getOrNull()
            gripR.set(bits(r?.let { runCatching { it.gripValue }.getOrDefault(0f) } ?: 0f))
            gripL.set(bits(l?.let { runCatching { it.gripValue }.getOrDefault(0f) } ?: 0f))
            trigR.set(bits(r?.let { runCatching { it.triggerValue }.getOrDefault(0f) } ?: 0f))
            trigL.set(bits(l?.let { runCatching { it.triggerValue }.getOrDefault(0f) } ?: 0f))
            stickRx.set(bits(r?.let { runCatching { it.thumbstickValue.x }.getOrDefault(0f) } ?: 0f))
            stickLx.set(bits(l?.let { runCatching { it.thumbstickValue.x }.getOrDefault(0f) } ?: 0f))
            val hand: ControllerAction? = if (leftHanded) l else r
            val other: ControllerAction? = if (leftHanded) r else l
            edge(0, hand?.let { runCatching { if (leftHanded) it.yButtonPressed else it.bButtonPressed }.getOrDefault(false) } == true, bEdges)
            edge(1, hand?.let { runCatching { if (leftHanded) it.xButtonPressed else it.aButtonPressed }.getOrDefault(false) } == true, aEdges)
            edge(2, hand?.let { runCatching { it.thumbstickPressed }.getOrDefault(false) } == true, stickEdges)
            val anyTrig = listOfNotNull(r, l).any { runCatching { it.triggerPressed }.getOrDefault(false) }
            edge(3, anyTrig, trigEdges)
            @Suppress("UNUSED_VARIABLE") val unused = other
        }

    private fun edge(i: Int, pressed: Boolean, counter: AtomicInteger) {
        if (pressed && !down[i].getAndSet(true)) counter.incrementAndGet()
        if (!pressed) down[i].set(false)
    }

    private fun bits(v: Float) = java.lang.Float.floatToIntBits(if (v.isFinite()) v else 0f)

    private fun f(a: AtomicInteger) = java.lang.Float.intBitsToFloat(a.get())

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        // listener first, then start (design container.lifecycle)
        runCatching { p.addControllerActionListener(listener) }
        val r = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        TdLog.i("TRACK controller start=${r.getOrNull() ?: r.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        for (a in listOf(gripR, gripL, trigR, trigL, stickRx, stickLx)) a.set(0)
    }

    fun supportState(): DataProvider.SupportState? {
        val s = if (debugPresent) DataProvider.SupportState.SUPPORTED else provider?.let { runCatching { it.supportState }.getOrNull() }
        if (s != lastSupport) {
            lastSupport = s
            TdLog.i("TRACK controller support=$s actions=${actions.get()}")
        }
        return s
    }

    private fun pose(right: Boolean): Pose? {
        val p = provider ?: return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val c = (if (right) data.right else data.left) ?: return null
        val pos = Vec3(c.position.x, c.position.y, c.position.z)
        if (!pos.isFinite()) return null
        return Pose(pos, Quat4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w).normalized())
    }

    /** The teleop hand this frame. */
    fun hand(): HandInput {
        val right = !leftHanded
        val grip = debugGrip ?: f(if (right) gripR else gripL)
        val trig = debugTrigger ?: f(if (right) trigR else trigL)
        return HandInput(if (debugLost) null else pose(right), grip, trig)
    }

    /** The other hand's pose (UI ray). */
    fun otherPose(): Pose? = pose(leftHanded)

    fun teleopPose(): Pose? = pose(!leftHanded)

    fun takeButtons(): Buttons = Buttons(bEdges.getAndSet(0), aEdges.getAndSet(0), stickEdges.getAndSet(0), trigEdges.getAndSet(0))

    /** Both sticks' X (S02 turns the workcell with either). */
    fun stickX(): Pair<Float, Float> = f(stickLx) to f(stickRx)

    fun rayOf(p: Pose?): Ray? {
        val pose = p ?: return null
        val fwd = pose.q.rotate(Vec3.AHEAD)
        if (!fwd.isFinite() || fwd.length < 0.5f) return null
        return Ray(pose.p, fwd.normalized())
    }
}
