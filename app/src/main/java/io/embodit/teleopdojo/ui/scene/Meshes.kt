package io.embodit.teleopdojo.ui.scene

import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.teleopdojo.domain.Vec3
import io.embodit.teleopdojo.domain.Workcell
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Programmatic meshes of the workcell (design entities3d.workcell / vrDome), table-local metres. */
internal object Meshes {
    /** Table top 1.00 x 0.80 x 0.04 m: its upper face at y = 0. */
    fun tableTop(name: String): MeshResource =
        MeshBuilder(24).also { it.addBox(Vec3(0f, -Workcell.THICK / 2f, 0f), Vec3(Workcell.WIDTH, Workcell.THICK, Workcell.DEPTH)) }.build(name)

    /** Faint 1 cm grid on the top (alignment aid), every 5th line a little wider; flat quads facing up. */
    fun grid(name: String): MeshResource {
        val b = MeshBuilder(2000)
        val n = Vec3(0f, 1f, 0f)
        fun line(a: Vec3, c: Vec3, w: Float) {
            val d = (c - a).normalized()
            val s = Vec3(-d.z, 0f, d.x) * (w / 2f)
            b.quad(b.vertex(a - s, n), b.vertex(a + s, n), b.vertex(c + s, n), b.vertex(c - s, n))
        }
        val hw = Workcell.WIDTH / 2f - 0.01f
        val hd = Workcell.DEPTH / 2f - 0.03f
        var i = -49
        while (i <= 49) {
            line(Vec3(i / 100f, 0f, -hd), Vec3(i / 100f, 0f, hd), if (i % 5 == 0) 0.0016f else 0.0007f)
            i++
        }
        var j = -36
        while (j <= 36) {
            line(Vec3(-hw, 0f, j / 100f), Vec3(hw, 0f, j / 100f), if (j % 5 == 0) 0.0016f else 0.0007f)
            j++
        }
        return b.build(name)
    }

    /** The 2 cm yellow-black safety stripe along the near edge: [phase] 0 = yellow bars, 1 = black bars. */
    fun stripes(name: String, phase: Int): MeshResource {
        val b = MeshBuilder(200)
        val n = Vec3(0f, 1f, 0f)
        val z0 = Workcell.DEPTH / 2f - 0.025f
        val z1 = Workcell.DEPTH / 2f - 0.005f
        val step = 0.04f
        var x = -Workcell.WIDTH / 2f + 0.01f
        var k = 0
        while (x < Workcell.WIDTH / 2f - 0.01f) {
            if (k % 2 == phase) {
                val x1 = minOf(x + step, Workcell.WIDTH / 2f - 0.01f)
                // slanted bars
                b.quad(b.vertex(Vec3(x, 0f, z1), n), b.vertex(Vec3(x1, 0f, z1), n), b.vertex(Vec3(x1 + 0.01f, 0f, z0), n), b.vertex(Vec3(x + 0.01f, 0f, z0), n))
            }
            x += step
            k++
        }
        return b.build(name)
    }

    /** The workshop dome (S23): an inward-facing sphere cap of [r] metres standing on the floor. */
    fun dome(name: String, r: Float, around: Int = 40, rings: Int = 16): MeshResource {
        val b = MeshBuilder((around + 1) * (rings + 1))
        for (jj in 0..rings) {
            val phi = (PI / 2) * jj / rings
            val y = (sin(phi) * r).toFloat()
            val rr = (cos(phi) * r).toFloat()
            for (ii in 0..around) {
                val a = 2 * PI * ii / around
                val p = Vec3(rr * cos(a).toFloat(), y, rr * sin(a).toFloat())
                b.vertex(p, (p * -1f).normalized())
            }
        }
        val row = around + 1
        for (jj in 0 until rings) for (ii in 0 until around) {
            val a = jj * row + ii
            b.tri(a, a + 1, a + row)
            b.tri(a + 1, a + row + 1, a + row)
        }
        return b.build(name)
    }
}
