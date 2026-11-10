package io.embodit.teleopdojo.domain

import kotlin.math.abs

/**
 * S02 turning (design rules.placeWorkcell / taste D1): either stick's X turns the ghost workcell about its centre at
 * 60°/s past a 0.15 dead zone; within ±10° of facing you it snaps square (the near edge towards you).
 */
object WorkcellPlacer {
    const val DEAD = 0.15f
    const val RATE_DEG = 60f
    const val SNAP_DEG = 10f

    fun turn(yaw: Float, stickX: Float, dt: Float, head: Vec3, center: Vec3): Float {
        val facing = Yaw.of(Vec3(center.x - head.x, 0f, center.z - head.z))
        if (abs(stickX) <= DEAD) {
            val d = Yaw.wrap(yaw - facing)
            return if (abs(Num.deg(d)) <= SNAP_DEG) facing else yaw
        }
        val s = (abs(stickX) - DEAD) / (1f - DEAD) * if (stickX > 0) 1f else -1f
        // stick right turns clockwise seen from above (yaw decreases in the Yaw convention)
        return Yaw.wrap(yaw - Num.rad(RATE_DEG) * s * dt)
    }
}
