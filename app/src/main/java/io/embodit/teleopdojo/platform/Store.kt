package io.embodit.teleopdojo.platform

import android.content.Context
import io.embodit.teleopdojo.domain.ControlMode
import io.embodit.teleopdojo.domain.LevelId
import io.embodit.teleopdojo.domain.MappingMode
import io.embodit.teleopdojo.ui.Best
import io.embodit.teleopdojo.ui.Lang
import io.embodit.teleopdojo.ui.MapStat
import io.embodit.teleopdojo.ui.Prefs

/**
 * Local storage (design persistence): preferences, the best result per level and difficulty, the mapping comparison, which
 * mappings each level was played with, the free-play round. SharedPreferences, this device only, no network. The workcell
 * position is not stored (it is posed from the head at every start).
 */
class Store(context: Context) {
    private val p = context.getSharedPreferences("teleop_dojo", Context.MODE_PRIVATE)

    fun prefs(): Prefs {
        val d = Prefs()
        return Prefs(
            lang = runCatching { Lang.valueOf(p.getString("lang", d.lang.name)!!) }.getOrDefault(d.lang),
            leftHanded = p.getBoolean("left", d.leftHanded),
            standing = p.getBoolean("standing", d.standing),
            tableAdjustCm = p.getInt("tableAdj", d.tableAdjustCm),
            controlStandard = runCatching { ControlMode.valueOf(p.getString("control", d.controlStandard.name)!!) }.getOrDefault(d.controlStandard),
            vr = p.getBoolean("vr", d.vr),
            volume = p.getInt("volume", d.volume),
            muted = p.getBoolean("muted", d.muted),
            coachSeen = p.getBoolean("coach", d.coachSeen),
        )
    }

    fun savePrefs(v: Prefs) {
        p.edit().putString("lang", v.lang.name).putBoolean("left", v.leftHanded).putBoolean("standing", v.standing).putInt("tableAdj", v.tableAdjustCm)
            .putString("control", v.controlStandard.name).putBoolean("vr", v.vr).putInt("volume", v.volume).putBoolean("muted", v.muted).putBoolean("coach", v.coachSeen)
            .apply()
    }

    fun bests(): Map<String, Best> {
        val out = HashMap<String, Best>()
        for (k in p.getStringSet("bestKeys", emptySet())!!) {
            val s = p.getString("best_$k", null) ?: continue
            val f = s.split(';')
            if (f.size < 6) continue
            runCatching { out[k] = Best(f[0].toInt(), f[1].toInt(), f[2].toFloat(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f.getOrNull(6)?.toInt() ?: -1, f.getOrNull(7)?.toInt() ?: -1) }
        }
        return out
    }

    fun saveBests(m: Map<String, Best>) {
        val e = p.edit()
        for (k in p.getStringSet("bestKeys", emptySet())!!) e.remove("best_$k")
        for ((k, b) in m) e.putString("best_$k", "${b.score};${b.stars};${b.timeS};${b.steady};${b.oops};${b.demo};${b.done};${b.total}")
        e.putStringSet("bestKeys", m.keys.toSet()).apply()
    }

    fun mapStats(): Map<String, MapStat> {
        val out = HashMap<String, MapStat>()
        for (k in listOf("clutch0.5", "clutch1.0", "clutch1.5", "follow")) {
            val s = p.getString("map_$k", null) ?: continue
            val f = s.split(';')
            if (f.size < 3) continue
            runCatching { out[k] = MapStat(f[0].toInt(), f[1].toFloat(), f[2].toInt()) }
        }
        return out
    }

    fun saveMapStats(m: Map<String, MapStat>) {
        val e = p.edit()
        for ((k, s) in m) e.putString("map_$k", "${s.best};${s.sumTime};${s.n}")
        e.apply()
    }

    fun levelPlays(): Map<LevelId, Set<MappingMode>> =
        LevelId.values().associateWith { l -> p.getStringSet("plays_${l.name}", emptySet())!!.mapNotNull { n -> MappingMode.values().firstOrNull { it.name == n } }.toSet() }

    fun saveLevelPlays(m: Map<LevelId, Set<MappingMode>>) {
        val e = p.edit()
        for ((l, s) in m) e.putStringSet("plays_${l.name}", s.map { it.name }.toSet())
        e.apply()
    }

    var freeRound: Int
        get() = p.getInt("freeRound", 1)
        set(v) = p.edit().putInt("freeRound", v).apply()

    /** 重置进度: scores, comparison, plays and the free-play round; preferences stay. */
    fun clearProgress() {
        val e = p.edit()
        for (k in p.getStringSet("bestKeys", emptySet())!!) e.remove("best_$k")
        e.remove("bestKeys")
        for (k in listOf("clutch0.5", "clutch1.0", "clutch1.5", "follow")) e.remove("map_$k")
        for (l in LevelId.values()) e.remove("plays_${l.name}")
        e.remove("freeRound").apply()
    }
}
