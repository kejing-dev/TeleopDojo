package io.embodit.teleopdojo.ui.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.teleopdojo.domain.Quat4
import io.embodit.teleopdojo.domain.Vec3
import io.embodit.teleopdojo.platform.TdLog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/**
 * Overall opacity of an entity and its children. At >= 0.999 the component is REMOVED: an opacity component left at
 * 1.0 still renders the model blended (inside-orchestra v0.1.4 lesson), so fully opaque means "no component".
 */
internal fun Entity.setOpacity(opacity: Float) {
    // skip unchanged values: every component write is a command to the runtime (1 fps emulator, runtime watchdog)
    val key = System.identityHashCode(this)
    val q = (opacity.coerceIn(0f, 1f) * 200f).toInt()
    if (OpacityCache.last[key] == q) return
    OpacityCache.last[key] = q
    val existing = components[OpacityControllerComponent::class.java]
    if (opacity >= 0.999f) {
        if (existing != null) runCatching { components.remove(OpacityControllerComponent::class.java) }
        return
    }
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal object OpacityCache {
    val last = HashMap<Int, Int>()
}

internal val Entity.hasOpacityComponent: Boolean
    get() = components[OpacityControllerComponent::class.java] != null

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler), so shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Materials of the course: Unlit for the flat course marks (readable over passthrough), PBR where light matters. */
internal class SceneMaterials(private val shared: SharedResources) {
    fun pbr(color: Color, roughness: Float = 0.85f): PhysicallyBasedMaterial =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setMetallic(0f)
                setRoughness(roughness)
                // a little of the base colour as emission so the shaded side is not black over passthrough
                runCatching { setEmissiveColor(Color4(color.red * AMBIENT, color.green * AMBIENT, color.blue * AMBIENT, 1f)) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun unlit(color: Color, texture: TextureResource? = null): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                if (texture != null) setBaseColorTexture(texture)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun sheer(color: Color, opacity: Float, texture: TextureResource? = null): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                if (texture != null) setBaseColorTexture(texture)
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    fun texture(bitmap: Bitmap): TextureResource = shared.keep(TextureResource.create(flipV(bitmap)))

    /** A bitmap already run through [flipV] (decoded and flipped off the main thread). */
    fun textureFlipped(bitmap: Bitmap): TextureResource = shared.keep(TextureResource.create(bitmap))

    companion object {
        private const val AMBIENT = 0.15f

        /** White disc fading to transparent towards the rim (gaze light, shadow blobs). */
        fun radial(size: Int = 128, power: Double = 1.6): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = (size - 1) / 2f
            for (y in 0 until size) for (x in 0 until size) {
                val r = kotlin.math.hypot(x - c, y - c) / c
                val a = if (r >= 1f) 0f else Math.pow((1.0 - r), power).toFloat()
                bmp.setPixel(x, y, android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255))
            }
            return bmp
        }

        /** The engine's texture V runs from the bottom row up (Sky Dome QA q1-2): flip once before upload. */
        fun flipV(src: Bitmap): Bitmap {
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        }
    }
}

/**
 * The baked VR workshop (design rules.vrMode, QA #1 q1-1): assets/env/workshop_{wall,floor,ceiling}.jpg made by
 * tools/workshop/gen_workshop.py, lighting baked in, drawn unlit. A part that fails to load falls back to a flat colour.
 */
internal class WorkshopTextures(private val flipped: Map<String, Bitmap>) {
    /** The part's bitmap, already flipped for the engine; null when it failed to load. */
    fun bitmap(part: String): Bitmap? = flipped[part]

    companion object {
        val PARTS = listOf("wall", "floor", "ceiling")

        fun decode(context: android.content.Context?): WorkshopTextures {
            val out = HashMap<String, Bitmap>()
            if (context == null) return WorkshopTextures(out)
            val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            for (part in PARTS) {
                runCatching {
                    context.assets.open("env/workshop_$part.jpg").use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                }.onFailure { TdLog.w("WORKSHOP load $part failed: $it") }.getOrNull()?.let { out[part] = SceneMaterials.flipV(it) }
            }
            TdLog.i("WORKSHOP textures ${out.map { (k, v) -> "$k=${v.width}x${v.height}" }}")
            return WorkshopTextures(out)
        }
    }
}

/** Accumulates triangles and builds (or refills) a mesh. */
internal class MeshBuilder(capacity: Int = 64) {
    val positions = ArrayList<Vector3>(capacity)
    val normals = ArrayList<Vector3>(capacity)
    val uvs = ArrayList<Vector2>(capacity)
    val indices = ArrayList<Int>(capacity * 3 / 2)
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE

    val vertexCount: Int
        get() = positions.size

    fun vertex(p: Vec3, n: Vec3, uv: Vector2 = ZERO_UV): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += uv
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    fun bounds(): BoundingBox =
        if (positions.isEmpty()) BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.01f, 0.01f, 0.01f))
        else BoundingBox(center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2), halfExtent = Vector3(max(0.001f, (maxX - minX) / 2), max(0.001f, (maxY - minY) / 2), max(0.001f, (maxZ - minZ) / 2)))

    /** A degenerate triangle keeps an emptied mesh valid. */
    private fun ensureNotEmpty() {
        if (positions.isNotEmpty()) return
        val n = Vec3(0f, 0f, 1f)
        tri(vertex(Vec3.ZERO, n), vertex(Vec3(0f, 0.0001f, 0f), n), vertex(Vec3(0.0001f, 0f, 0f), n))
    }

    fun model(): MeshModel {
        ensureNotEmpty()
        return MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
    }

    fun build(name: String): MeshResource = MeshResource.createWithMeshModel(model = model(), bounds = bounds(), name = name)

    /** Refills [mesh]; returns false when the engine refused (the caller keeps the old one). */
    fun replace(mesh: MeshResource): Boolean =
        try {
            val m = model()
            mesh.replaceWithMeshModel(m, bounds())
        } catch (t: Throwable) {
            TdLog.e("SCENE mesh replace failed: $t", t)
            false
        }

    companion object {
        val ZERO_UV = Vector2(0.5f, 0.5f)
    }
}

/** Programmatic meshes, built once per scene. */
internal object Shapes {
    /** A flat disc of radius 0.5 in local XY facing +Z. */
    fun disc(name: String, seg: Int = 40): MeshResource {
        val b = MeshBuilder(seg + 2)
        val n = Vec3(0f, 0f, 1f)
        // UVs map the disc onto the whole texture (the gaze pool's radial fade)
        val c = b.vertex(Vec3.ZERO, n, Vector2(0.5f, 0.5f))
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n, Vector2((0.5 + 0.5 * cos(a)).toFloat(), (0.5 - 0.5 * sin(a)).toFloat()))
        }
        for (i in 1..seg) b.tri(c, i, i + 1)
        return b.build(name)
    }

    /** A flat ring (annulus) between radii [inner] and 0.5 in local XY facing +Z. */
    fun ring(name: String, inner: Float, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 2 + 2)
        val n = Vec3(0f, 0f, 1f)
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((inner * cos(a)).toFloat(), (inner * sin(a)).toFloat(), 0f), n)
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n)
        }
        for (i in 0 until seg) b.quad(i * 2, i * 2 + 1, i * 2 + 3, i * 2 + 2)
        return b.build(name)
    }

    /** A unit cube centred on the origin (own mesh: flat-shaded faces). */
    fun cube(name: String): MeshResource = MeshBuilder(24).also { it.addBox(Vec3.ZERO, Vec3(1f, 1f, 1f)) }.build(name)

    /** A UV sphere of diameter 1 with smooth normals. */
    fun ball(name: String, around: Int = 24, rings: Int = 14): MeshResource {
        val b = MeshBuilder((around + 1) * (rings + 1))
        for (j in 0..rings) {
            val phi = PI * j / rings - PI / 2
            val y = sin(phi).toFloat()
            val r = cos(phi).toFloat()
            for (i in 0..around) {
                val a = 2 * PI * i / around
                val n = Vec3(r * cos(a).toFloat(), y, r * sin(a).toFloat())
                b.vertex(n * 0.5f, n)
            }
        }
        val row = around + 1
        for (j in 0 until rings) for (i in 0 until around) {
            val a = j * row + i
            b.tri(a, a + row, a + 1)
            b.tri(a + 1, a + row, a + row + 1)
        }
        return b.build(name)
    }

    /** A cylinder of diameter 1 and height 1 about local Y (the dial disc; built here so its size is exact). */
    fun cylinder(name: String, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 4 + 2)
        val up = Vec3(0f, 1f, 0f)
        val down = Vec3(0f, -1f, 0f)
        val top = b.vertex(Vec3(0f, 0.5f, 0f), up)
        val bottom = b.vertex(Vec3(0f, -0.5f, 0f), down)
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val p0 = Vec3((0.5 * cos(a0)).toFloat(), 0f, (0.5 * sin(a0)).toFloat())
            val p1 = Vec3((0.5 * cos(a1)).toFloat(), 0f, (0.5 * sin(a1)).toFloat())
            val h = Vec3(0f, 0.5f, 0f)
            b.tri(top, b.vertex(p1 + h, up), b.vertex(p0 + h, up))
            b.tri(bottom, b.vertex(p0 - h, down), b.vertex(p1 - h, down))
            val n = p0.normalized()
            b.quad(b.vertex(p0 - h, n), b.vertex(p1 - h, n), b.vertex(p1 + h, n), b.vertex(p0 + h, n))
        }
        return b.build(name)
    }
}

/** Places a unit-cube entity as a thin bar between two points. */
internal fun Entity.bar(from: Vec3, to: Vec3, thickness: Float) {
    val d = to - from
    val len = d.length
    if (len < 1e-5f) {
        setLocal(scale = Vec3(1e-4f, 1e-4f, 1e-4f))
        return
    }
    val dir = d / len
    setLocal(position = (from + to) * 0.5f, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), dir), scale = Vec3(thickness, thickness, len))
}

internal fun model(mesh: MeshResource, material: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, material).also {
        runCatching { it.setName(name) }
        parent.addChild(it)
    }

/** A flat quad of [w] x [h] metres in local XY facing +Z (labels, billboards). Top-left of the bitmap at the top-left. */
internal fun quadXY(w: Float, h: Float, name: String): MeshResource {
    val b = MeshBuilder(4)
    val n = Vec3(0f, 0f, 1f)
    // SceneMaterials.texture() flips the bitmap once (engine v = 0 is the bottom row): v = 0 here is the bitmap's top row
    b.quad(
        b.vertex(Vec3(-w / 2, -h / 2, 0f), n, Vector2(0f, 1f)),
        b.vertex(Vec3(w / 2, -h / 2, 0f), n, Vector2(1f, 1f)),
        b.vertex(Vec3(w / 2, h / 2, 0f), n, Vector2(1f, 0f)),
        b.vertex(Vec3(-w / 2, h / 2, 0f), n, Vector2(0f, 0f)),
    )
    return b.build(name)
}

/** A label bitmap: light text on a rounded dark slate card (13:1), [pxW] x [pxH]. */
internal fun labelBitmap(text: String, pxW: Int, pxH: Int, bg: Int = android.graphics.Color.rgb(0x1E, 0x2A, 0x33), fg: Int = android.graphics.Color.rgb(0xE6, 0xF1, 0xF7), typeface: android.graphics.Typeface? = null): Bitmap {
    val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    val r = pxH * 0.22f
    c.drawRoundRect(android.graphics.RectF(0f, 0f, pxW.toFloat(), pxH.toFloat()), r, r, bgPaint)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        textAlign = android.graphics.Paint.Align.CENTER
        this.typeface = typeface ?: android.graphics.Typeface.DEFAULT_BOLD
        textSize = pxH * 0.62f
    }
    // shrink to fit the width
    while (p.measureText(text) > pxW * 0.88f && p.textSize > 8f) p.textSize = p.textSize - 2f
    val fm = p.fontMetrics
    c.drawText(text, pxW / 2f, pxH / 2f - (fm.ascent + fm.descent) / 2f, p)
    return bmp
}

/** Enables / disables only on change (a write is a runtime command even when the value is the same). */
internal fun Entity.on(v: Boolean) {
    if (enabled != v) enabled = v
}

/** Adds a box bar from [a] to [b] ([t] thick, square section) to this builder (flat-shaded, 24 vertices). */
internal fun MeshBuilder.addBar(a: Vec3, b: Vec3, t: Float) {
    val d = b - a
    val len = d.length
    if (len < 1e-5f) return
    val z = d / len
    val helper = if (kotlin.math.abs(z.y) < 0.9f) Vec3.UP else Vec3.RIGHT
    val x = helper.cross(z).normalized() * (t / 2)
    val y = z.cross(x.normalized()).normalized() * (t / 2)
    val c = listOf(a - x - y, a + x - y, a + x + y, a - x + y, b - x - y, b + x - y, b + x + y, b - x + y)
    fun face(i0: Int, i1: Int, i2: Int, i3: Int) {
        val n = (c[i1] - c[i0]).cross(c[i2] - c[i0]).normalized()
        quad(vertex(c[i0], n), vertex(c[i1], n), vertex(c[i2], n), vertex(c[i3], n))
    }
    face(0, 3, 2, 1) // start cap
    face(4, 5, 6, 7) // end cap
    face(0, 1, 5, 4)
    face(1, 2, 6, 5)
    face(2, 3, 7, 6)
    face(3, 0, 4, 7)
}

/** Adds an upright cylinder (radius [r], from y0 to y1) at [at] to this builder. */
internal fun MeshBuilder.addCylinder(at: Vec3, r: Float, y0: Float, y1: Float, seg: Int = 16) {
    val up = Vec3(0f, 1f, 0f)
    val top = vertex(Vec3(at.x, y1, at.z), up)
    val bottom = vertex(Vec3(at.x, y0, at.z), -up)
    for (i in 0 until seg) {
        val a0 = 2 * PI * i / seg
        val a1 = 2 * PI * (i + 1) / seg
        val p0 = Vec3((r * cos(a0)).toFloat(), 0f, (r * sin(a0)).toFloat())
        val p1 = Vec3((r * cos(a1)).toFloat(), 0f, (r * sin(a1)).toFloat())
        val b0 = Vec3(at.x, y0, at.z) + p0
        val b1 = Vec3(at.x, y0, at.z) + p1
        val t0 = Vec3(at.x, y1, at.z) + p0
        val t1 = Vec3(at.x, y1, at.z) + p1
        tri(top, vertex(t1, up), vertex(t0, up))
        tri(bottom, vertex(b0, -up), vertex(b1, -up))
        val n = p0.normalized()
        quad(vertex(b0, n), vertex(b1, n), vertex(t1, n), vertex(t0, n))
    }
}

/** Adds an axis-aligned box ([size] full extents) centred at [c] to this builder. */
internal fun MeshBuilder.addBox(c: Vec3, size: Vec3) {
    val h = size * 0.5f
    val p = listOf(
        Vec3(c.x - h.x, c.y - h.y, c.z - h.z), Vec3(c.x + h.x, c.y - h.y, c.z - h.z), Vec3(c.x + h.x, c.y + h.y, c.z - h.z), Vec3(c.x - h.x, c.y + h.y, c.z - h.z),
        Vec3(c.x - h.x, c.y - h.y, c.z + h.z), Vec3(c.x + h.x, c.y - h.y, c.z + h.z), Vec3(c.x + h.x, c.y + h.y, c.z + h.z), Vec3(c.x - h.x, c.y + h.y, c.z + h.z),
    )
    fun face(a: Int, b: Int, cc: Int, d: Int, n: Vec3) = quad(vertex(p[a], n), vertex(p[b], n), vertex(p[cc], n), vertex(p[d], n))
    face(0, 3, 2, 1, Vec3(0f, 0f, -1f))
    face(4, 5, 6, 7, Vec3(0f, 0f, 1f))
    face(0, 1, 5, 4, Vec3(0f, -1f, 0f))
    face(3, 7, 6, 2, Vec3(0f, 1f, 0f))
    face(0, 4, 7, 3, Vec3(-1f, 0f, 0f))
    face(1, 2, 6, 5, Vec3(1f, 0f, 0f))
}
