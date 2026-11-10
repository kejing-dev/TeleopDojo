package io.embodit.teleopdojo.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Damped least-squares IK for the UR5e TCP (design rules.ik). The XRoboToolkit / placo task weights are the starting
 * point: end-effector task 1.0, manipulability 1e-2, joint regularisation (back to the home pose) 1e-4. At most
 * [MAX_ITER] iterations a frame; the damping grows as the manipulability falls. When the target cannot be reached the
 * arm stops at the nearest reachable point (DLS converges there) instead of flailing. Pure JVM (IkTest).
 *
 * All in the DH base frame (Z up, metres, radians).
 */
class Ik(
    val wEe: Double = 1.0,
    val wManip: Double = 1e-2,
    val wReg: Double = 1e-4,
) {
    /** Result of one frame. */
    class Step(val q: DoubleArray, val posErr: Double, val rotErr: Double, val manip: Double, val reached: Boolean)

    /**
     * Moves [q] towards the TCP target ([p], rotation columns [r] = tool x / y / z); [orientWeight] 0 ignores the
     * orientation (unused: "position only" passes a down-pointing target instead), [rest] the regularisation pose.
     */
    fun solve(q0: DoubleArray, p: DoubleArray, r0: Array<DoubleArray>, rest: DoubleArray = HOME, orientWeight: Double = 0.4): Step {
        val q = q0.copyOf()
        // the parallel gripper looks the same turned 180° about its approach: aim for whichever is closer
        val r = symmetricTarget(Ur5eKinematics.fkTcp(q, Gripper.TCP_M.toDouble()), r0)
        var posErr = 0.0
        var rotErr = 0.0
        var manip = manipulability(q)
        // the manipulability task only pulls near a singularity (a square 6x6 task has no null space: a constant pull
        // would bias the end effector), clipped to unit length
        val grad = if (manip < MANIP_LOW) manipGradient(q, manip).let { g -> val n = sqrt(g.sumOf { it * it }); if (n > 1) DoubleArray(6) { g[it] / n } else g } else DoubleArray(6)
        for (it in 0 until MAX_ITER) {
            val t = Ur5eKinematics.fkTcp(q, Gripper.TCP_M.toDouble())
            val pos = t.position
            val e = DoubleArray(6)
            for (i in 0..2) e[i] = p[i] - pos[i]
            val w = rotError(t, r)
            for (i in 0..2) e[3 + i] = w[i] * orientWeight
            posErr = sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2])
            rotErr = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
            if (posErr < 5e-4 && rotErr < 5e-3) break
            // clamp the task step so one frame never asks for a jump
            val scale = minOf(1.0, MAX_STEP_M / maxOf(posErr, 1e-9))
            for (i in 0..5) e[i] *= scale
            val j = Ur5eKinematics.jacobian(q, Gripper.TCP_M.toDouble())
            for (c in 0..5) for (rr in 3..5) j[rr][c] *= orientWeight
            manip = manipulability(q)
            val lambda = LAMBDA0 + if (manip < MANIP_LOW) (1 - manip / MANIP_LOW).let { it * it } * LAMBDA_MAX else 0.0
            // (w_ee J^T J + lambda^2 I + w_reg I) dq = w_ee J^T e + w_reg (rest - q) + w_manip grad
            val a = Array(6) { DoubleArray(6) }
            val b = DoubleArray(6)
            for (r1 in 0..5) {
                for (c in 0..5) {
                    var s = 0.0
                    for (k in 0..5) s += j[k][r1] * j[k][c]
                    a[r1][c] = wEe * s
                }
                a[r1][r1] += lambda * lambda + wReg
                var s = 0.0
                for (k in 0..5) s += j[k][r1] * e[k]
                b[r1] = wEe * s + wReg * Ur5eKinematics.wrapPi(rest[r1] - q[r1]) + wManip * grad[r1]
            }
            val dq = solve6(a, b) ?: break
            for (i in 0..5) {
                val d = dq[i].coerceIn(-MAX_DQ, MAX_DQ)
                q[i] = (q[i] + d).coerceIn(-LIMITS[i], LIMITS[i])
            }
        }
        val t = Ur5eKinematics.fkTcp(q, Gripper.TCP_M.toDouble())
        val pos = t.position
        posErr = sqrt((p[0] - pos[0]).let { it * it } + (p[1] - pos[1]).let { it * it } + (p[2] - pos[2]).let { it * it })
        val w = rotError(t, r)
        rotErr = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        manip = manipulability(q)
        return Step(q, posErr, rotErr, manip, posErr < REACH_TOL_M && rotErr < REACH_TOL_RAD)
    }

    /** Runs [solve] repeatedly from [q0] (offline checks: level layouts, the free-play generator, unit tests). */
    fun converge(q0: DoubleArray, p: DoubleArray, r: Array<DoubleArray>, frames: Int = 40): Step {
        var s = solve(q0, p, r)
        repeat(frames - 1) {
            if (s.posErr < 1e-3 && s.rotErr < 1e-2) return s
            s = solve(s.q, p, r)
        }
        return s
    }

    companion object {
        const val MAX_ITER = 8
        const val MAX_STEP_M = 0.08
        const val MAX_DQ = 0.25
        const val LAMBDA0 = 0.01
        const val LAMBDA_MAX = 0.12
        /** Manipulability under which damping grows (fraction of home taken at init). */
        val MANIP_LOW: Double by lazy { 0.3 * manipulability(HOME) }
        const val REACH_TOL_M = 0.01
        const val REACH_TOL_RAD = 0.12

        /** Joint limits (UR e-series joint_limits.yaml: elbow ±180°, others ±360°), the elbow kept to ±165° in the UI. */
        val LIMITS = doubleArrayOf(2 * PI, 2 * PI, Math.toRadians(165.0), 2 * PI, 2 * PI, 2 * PI)

        /** "Joint at its limit" under this margin; the joint ring warms from [WARN_MARGIN]. */
        val LIMIT_MARGIN = Math.toRadians(10.0)
        val WARN_MARGIN = Math.toRadians(25.0)

        /**
         * Home pose (design frame.home): TCP 0.20 m over the table centre, gripper down, fingers along the table width;
         * solved by IK once (HomeTest pins the numbers).
         */
        val HOME = doubleArrayOf(-0.4962, -2.1724, 2.3345, -1.7329, -1.5708, -0.4962)

        fun manipulability(q: DoubleArray): Double = Ur5eKinematics.singularity(q).manipulability

        /** Numeric gradient of the manipulability. */
        fun manipGradient(q: DoubleArray, m0: Double = manipulability(q)): DoubleArray {
            val g = DoubleArray(6)
            val h = 1e-4
            for (i in 0..5) {
                val qq = q.copyOf()
                qq[i] += h
                g[i] = (manipulability(qq) - m0) / h
            }
            // normalise so the weight means "per unit of home manipulability"
            val home = manipulability(HOME).coerceAtLeast(1e-9)
            for (i in 0..5) g[i] /= home
            return g
        }

        /** Orientation error (axis * angle, DH) from the current TCP frame to the target columns [r]. */
        fun rotError(t: Ur5eKinematics.Mat4, r: Array<DoubleArray>): DoubleArray {
            // R_err = R_t * R_c^T, columns of R_t are r[0..2]
            val c = arrayOf(t.axis(0), t.axis(1), t.axis(2))
            val m = Array(3) { DoubleArray(3) }
            for (i in 0..2) for (k in 0..2) {
                var s = 0.0
                for (a in 0..2) s += r[a][i] * c[a][k]
                m[i][k] = s
            }
            val tr = m[0][0] + m[1][1] + m[2][2]
            val angle = acos(((tr - 1) / 2).coerceIn(-1.0, 1.0))
            val v = doubleArrayOf(m[2][1] - m[1][2], m[0][2] - m[2][0], m[1][0] - m[0][1])
            val s = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (angle < 1e-9) return doubleArrayOf(0.0, 0.0, 0.0)
            if (s < 1e-9) {
                // 180°: any axis from the symmetric part
                val ax = doubleArrayOf(sqrt(maxOf(0.0, (m[0][0] + 1) / 2)), sqrt(maxOf(0.0, (m[1][1] + 1) / 2)), sqrt(maxOf(0.0, (m[2][2] + 1) / 2)))
                return doubleArrayOf(ax[0] * angle, ax[1] * angle, ax[2] * angle)
            }
            return doubleArrayOf(v[0] / s * angle, v[1] / s * angle, v[2] / s * angle)
        }

        /** [r] or [r] turned 180° about its approach (x, y negated), whichever is closer to the TCP frame [t]. */
        fun symmetricTarget(t: Ur5eKinematics.Mat4, r: Array<DoubleArray>): Array<DoubleArray> {
            val flipped = arrayOf(DoubleArray(3) { -r[0][it] }, DoubleArray(3) { -r[1][it] }, r[2])
            val a = rotError(t, r).let { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }
            val b = rotError(t, flipped).let { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }
            return if (b < a) flipped else r
        }

        /** Smallest distance of any joint to its limit, radians, and which joint (0-based). */
        fun limitMargin(q: DoubleArray): Pair<Double, Int> {
            var best = Double.MAX_VALUE
            var which = 0
            for (i in 0..5) {
                val m = LIMITS[i] - abs(q[i])
                if (m < best) {
                    best = m
                    which = i
                }
            }
            return best to which
        }

        /** Per-joint margins to the limits, radians. */
        fun margins(q: DoubleArray): DoubleArray = DoubleArray(6) { LIMITS[it] - abs(q[it]) }

        /** Gaussian elimination for the 6x6 system. */
        fun solve6(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val n = 6
            val a = Array(n) { a0[it].copyOf() }
            val b = b0.copyOf()
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
                if (abs(a[p][c]) < 1e-14) return null
                if (p != c) {
                    val t = a[p]; a[p] = a[c]; a[c] = t
                    val tb = b[p]; b[p] = b[c]; b[c] = tb
                }
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c until n) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (k in r + 1 until n) s -= a[r][k] * x[k]
                x[r] = s / a[r][r]
            }
            return x
        }
    }
}
