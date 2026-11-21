package com.archiekuo.speedwatch

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** 一個測速點。dir = 拍攝的行車方向（0=往北…），-1 = 不分方向 */
class Cam(val lat: Double, val lon: Double, val limit: Int, val dir: Int, val name: String)

enum class Mode { Idle, Driving }

/** 給畫面看的即時狀態（App 和背景服務共用） */
object Live {
    var mode by mutableStateOf(Mode.Idle)
    var speed by mutableIntStateOf(0)          // 公里/小時
    var gpsOk by mutableStateOf(false)
    var cam by mutableStateOf<Cam?>(null)       // 正在提醒的測速點
    var camDist by mutableIntStateOf(0)         // 公尺
    var aheadKm by mutableStateOf<Float?>(null) // 前方最近測速點（公里），null = 3 公里內沒有
    var over by mutableStateOf(false)
    var count by mutableIntStateOf(0)
    var updated by mutableStateOf("")
    var updating by mutableStateOf(false)
}

object CamDb {
    private const val URL_DATA =
        "https://raw.githubusercontent.com/nmymdf/watch-apps/main/speed-watch/src/main/assets/cameras.csv"
    private const val UPDATE_DAYS = 30L

    @Volatile var cams: List<Cam> = emptyList()

    private fun parse(text: String): List<Cam> {
        val out = ArrayList<Cam>()
        text.lineSequence().drop(1).forEach { line ->
            val p = line.split(',')
            if (p.size < 4) return@forEach
            val la = p[0].toDoubleOrNull() ?: return@forEach
            val lo = p[1].toDoubleOrNull() ?: return@forEach
            out += Cam(la, lo, p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: -1, p.getOrNull(4) ?: "")
        }
        return out
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cams", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "cameras.csv")
        val text = if (f.exists()) f.readText() else ctx.assets.open("cameras.csv").bufferedReader().readText()
        cams = parse(text)
        Live.count = cams.size
        val t = prefs(ctx).getLong("time", 0L)
        Live.updated = if (t == 0L) "內建資料" else fmt(t)
    }

    private fun fmt(t: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
        return "${c.get(java.util.Calendar.MONTH) + 1}/${c.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    /** 超過 30 天就上網抓最新資料（背景執行緒） */
    fun updateIfOld(ctx: Context, force: Boolean = false) {
        val last = prefs(ctx).getLong("time", 0L)
        if (!force && System.currentTimeMillis() - last < UPDATE_DAYS * 86_400_000L) return
        if (Live.updating) return
        Live.updating = true
        Thread {
            try {
                val c = URL(URL_DATA).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode == 200) {
                    val text = c.inputStream.bufferedReader().readText()
                    val list = parse(text)
                    if (list.size > 500) {
                        File(ctx.filesDir, "cameras.csv").writeText(text)
                        val now = System.currentTimeMillis()
                        prefs(ctx).edit().putLong("time", now).apply()
                        cams = list
                        Live.count = list.size
                        Live.updated = fmt(now)
                    }
                }
                c.disconnect()
            } catch (_: Exception) {
            } finally {
                Live.updating = false
            }
        }.start()
    }

    fun angleDiff(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 540f) % 360f - 180f)
        return d
    }

    /** 找在你前方、方向符合、最近的測速點
     *  cone：前方允許的角度；sideMax：離你行進路線的橫向距離上限；minLimit：速限低於這個就不算（高速時排除平面道路） */
    fun nearestAhead(here: Location, heading: Float?, maxM: Float,
                     cone: Float = 45f, sideMax: Float = 9999f, minLimit: Int = 0, dirTol: Float = 60f): Pair<Cam, Float>? {
        var best: Cam? = null
        var bestD = Float.MAX_VALUE
        val r = FloatArray(2)
        val dLat = maxM / 111_000.0
        for (c in cams) {
            if (abs(c.lat - here.latitude) > dLat || abs(c.lon - here.longitude) > dLat * 1.2) continue
            if (c.limit in 1 until minLimit) continue
            Location.distanceBetween(here.latitude, here.longitude, c.lat, c.lon, r)
            val d = r[0]
            if (d > maxM || d >= bestD) continue
            if (heading != null) {
                val off = angleDiff(r[1], heading)
                if (d > 40f && off > cone) continue                                   // 不在前方
                if (d * kotlin.math.sin(Math.toRadians(off.toDouble())) > sideMax) continue // 離路線太遠（旁邊的路）
                if (c.dir >= 0 && angleDiff(c.dir.toFloat(), heading) > dirTol) continue    // 方向不符
            }
            best = c; bestD = d
        }
        return best?.let { it to bestD }
    }
}
