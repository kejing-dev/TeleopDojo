package io.embodit.teleopdojo.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/**
 * The four short cues (design rules.audio): 点亮 (UI chime), 夹住 (gripper click), 放稳 (block set down), 撞桌 (bump on
 * the table); no continuous sound. WAVs in assets/sfx (sources in apps/teleop-dojo/build/sound-sources.md). A cue
 * stops its previous instance before it plays again (one instance per event) with ±5 % pitch jitter on the physical
 * sounds. Master volume 0-100 % (default 90, the "high" snap) and the mute key on every page (D15) scale everything.
 */
class Sound(private val context: Context) {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, IntArray>()
    private val streams = HashMap<String, Int>()
    private var master = 0.6f
    private var muted = false
    private val rnd = Random(11)

    fun start() {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        for ((cue, files) in CUES) {
            val loaded = files.mapNotNull { f -> runCatching { context.assets.openFd("sfx/$f.wav").use { fd -> p.load(fd, 1) } }.onFailure { TdLog.w("SOUND load $f failed: $it") }.getOrNull() }
            if (loaded.isNotEmpty()) ids[cue] = loaded.toIntArray()
        }
        TdLog.i("SOUND ready ${ids.mapValues { it.value.size }}")
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
        streams.clear()
    }

    fun setVolume(percent: Int, mute: Boolean) {
        master = percent.coerceIn(0, 100) / 100f
        muted = mute
        TdLog.i("SOUND volume=$percent muted=$mute")
    }

    private val gain: Float
        get() = if (muted) 0f else master

    private fun play(cue: String, g: Float, jitter: Boolean) {
        val p = pool ?: return
        val variants = ids[cue] ?: return
        val v = (gain * g).coerceIn(0f, 1f)
        if (v <= 0f) return
        streams[cue]?.let { p.stop(it) }
        val rate = if (jitter) 1f + (rnd.nextFloat() - 0.5f) * 0.1f else 1f
        streams[cue] = p.play(variants[rnd.nextInt(variants.size)], v, v, 1, 0, rate)
    }

    fun lit() = play("lit", 0.8f, false)

    fun grab() = play("grab", 0.9f, true)

    fun settle() = play("settle", 0.9f, true)

    fun bump() = play("bump", 1f, true)

    companion object {
        val CUES = mapOf(
            "lit" to listOf("lit"),
            "grab" to listOf("grab1", "grab2"),
            "settle" to listOf("settle1", "settle2", "settle3"),
            "bump" to listOf("bump1", "bump2"),
        )
    }
}
