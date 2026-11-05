package com.archiekuo.parkwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val st = ParkState()
    private lateinit var sensors: Sensors
    private lateinit var store: Store

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.any { it }) sensors.startLocation()
        }

    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val txt = list?.firstOrNull()
            if (!txt.isNullOrBlank()) st.draftPillar = cleanPillar(txt)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        sensors = Sensors(this, st)
        st.saved = store.load()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        setContent { AppRoot(st, ::startSave, ::finishSave, ::listen) }
    }

    override fun onResume() { super.onResume(); sensors.start() }
    override fun onPause() { super.onPause(); sensors.stop() }

    /** 按「停好了」：先記下現在的位置和氣壓 */
    private fun startSave() {
        val l = st.here
        val fresh = goodFix(l)
        if (l != null && System.currentTimeMillis() - l.time < 120_000) {
            st.draftLat = l.latitude; st.draftLon = l.longitude; st.draftAcc = if (l.hasAccuracy()) l.accuracy else null
        } else {
            st.draftLat = null; st.draftLon = null; st.draftAcc = null
        }
        st.draftPressure = st.pressure
        st.suggestFloor = if (fresh) 0 else null
        st.draftFloor = st.suggestFloor
        st.draftPillar = ""
        st.draftGps = when {
            fresh -> "GPS 已記錄"
            st.draftLat != null -> "GPS 記錄（較不準）"
            else -> "收不到 GPS"
        }
        st.screen = Screen.Floor
    }

    private fun finishSave() {
        val s = Spot(st.draftLat, st.draftLon, st.draftAcc, st.draftPressure, st.draftFloor ?: 0,
            st.draftPillar, System.currentTimeMillis())
        store.save(s)
        st.saved = s
        st.toast = "已記住"
        st.screen = Screen.Home
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "說柱子編號，例如 C12")
        try { speech.launch(i) } catch (_: Exception) { st.toast = "這支手錶無法語音輸入" }
    }
}

/** 語音結果整理成短短的柱號，例如「c 12」→「C12」 */
fun cleanPillar(s: String): String {
    var t = s.uppercase(Locale.US)
    val zh = mapOf('零' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4', '五' to '5',
        '六' to '6', '七' to '7', '八' to '8', '九' to '9', '兩' to '2')
    t = t.map { zh[it] ?: it }.joinToString("")
    t = t.replace("號", "").replace("柱", "").replace("區", "").replace(" ", "").replace("-", "")
    return t.take(8)
}

// ---------------- 畫面（480 x 480 設計座標） ----------------

private const val GOLD = 0xFFD4AF37.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val GREY = 0xFF9FB6C8.toInt()
private const val LIGHT = 0xFFC9D3DD.toInt()
private const val YELLOW = 0xFFFFD36B.toInt()
private const val GREEN_TXT = 0xFF7FE0A8.toInt()

private class Paints {
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    fun text(color: Int, size: Float, bold: Boolean = true, italic: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, when {
            bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL
        })
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
}

private fun blueShader(top: Float, bottom: Float) =
    LinearGradient(0f, top, 0f, bottom, 0xFF2F7BFF.toInt(), 0xFF1550C8.toInt(), Shader.TileMode.CLAMP)
private fun greenShader(top: Float, bottom: Float) =
    LinearGradient(0f, top, 0f, bottom, 0xFF2FD47A.toInt(), 0xFF14A356.toInt(), Shader.TileMode.CLAMP)

private fun android.graphics.Canvas.t(p: Paint, s: String, x: Float, y: Float, maxW: Float = 400f) {
    val sz = p.textSize
    val w = p.measureText(s)
    if (w > maxW) p.textSize = sz * maxW / w
    drawText(s, x, y, p)
    p.textSize = sz
}

private fun credit(c: android.graphics.Canvas, ps: Paints, ver: String) {
    c.t(ps.text(GOLD, 17f, true, true), "作者：ArchieKUO · v$ver", 240f, 452f)
}

private fun ageText(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "剛剛"
        m < 60 -> "$m 分鐘前"
        m < 60 * 24 -> "${m / 60} 小時前"
        else -> "${m / 1440} 天前"
    }
}

private fun spotLabel(s: Spot): String =
    if (s.pillar.isBlank()) floorName(s.floor) else "${floorName(s.floor)} · ${s.pillar}"

/** 每個畫面可以點的區域 */
private data class Hit(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val action: () -> Unit)

@Composable
private fun AppRoot(st: ParkState, onSave: () -> Unit, onDone: () -> Unit, onListen: () -> Unit) {
    val ctx = LocalContext.current
    val ps = remember { Paints() }
    val ver = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0.1"
    }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    LaunchedEffect(st.toast) { if (st.toast.isNotEmpty()) { delay(1800); st.toast = "" } }

    BackHandler(enabled = st.screen != Screen.Home) {
        st.screen = when (st.screen) {
            Screen.Pillar -> Screen.Floor
            else -> Screen.Home
        }
    }

    val hits = remember { mutableListOf<Hit>() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { sz = it }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val s = min(sz.width, sz.height) / 480f
                    if (s <= 0f) return@detectTapGestures
                    val x = (off.x - (sz.width - 480f * s) / 2f) / s
                    val y = (off.y - (sz.height - 480f * s) / 2f) / s
                    hits.lastOrNull { x in it.x0..it.x1 && y in it.y0..it.y1 }?.action?.invoke()
                }
            }
    ) {
        val t0 = tick; if (t0 < 0) return@Canvas
        drawIntoCanvas { cv ->
            val c = cv.nativeCanvas
            val s = min(size.width, size.height) / 480f
            c.save()
            c.translate((size.width - 480f * s) / 2f, (size.height - 480f * s) / 2f)
            c.scale(s, s)
            hits.clear()
            when (st.screen) {
                Screen.Home -> drawHome(c, ps, st, hits, onSave)
                Screen.Floor -> drawFloor(c, ps, st, hits)
                Screen.Pillar -> drawPillar(c, ps, st, hits, onDone, onListen)
                Screen.Find -> drawFind(c, ps, st, hits)
            }
            if (st.toast.isNotEmpty()) {
                ps.fill.shader = null; ps.fill.color = 0xE6202833.toInt()
                c.drawRoundRect(RectF(120f, 200f, 360f, 280f), 40f, 40f, ps.fill)
                c.t(ps.text(WHITE, 34f), st.toast, 240f, 252f, 220f)
            }
            credit(c, ps, ver)
            c.restore()
        }
    }
}

private fun drawHome(c: android.graphics.Canvas, ps: Paints, st: ParkState, hits: MutableList<Hit>, onSave: () -> Unit) {
    c.t(ps.text(GREY, 34f), "找車位", 240f, 92f)
    ps.fill.shader = blueShader(130f, 326f)
    c.drawCircle(240f, 228f, 98f, ps.fill)
    ps.fill.shader = null
    // P 圖示
    ps.fill.color = WHITE
    val p = Path().apply {
        addRect(212f, 172f, 228f, 262f, Path.Direction.CW)
        addRoundRect(RectF(212f, 172f, 270f, 226f), 27f, 27f, Path.Direction.CW)
    }
    c.drawPath(p, ps.fill)
    ps.fill.color = 0xFF2266E8.toInt()
    c.drawRoundRect(RectF(228f, 188f, 254f, 210f), 11f, 11f, ps.fill)
    c.t(ps.text(WHITE, 32f), "停好了", 240f, 302f)
    hits += Hit(140f, 128f, 340f, 328f) { onSave() }

    val sv = st.saved
    if (sv != null) {
        c.t(ps.text(YELLOW, 30f), "上次：${spotLabel(sv)}", 240f, 374f, 330f)
        c.t(ps.text(LIGHT, 24f, false), "點這裡找車（${ageText(sv.time)}）", 240f, 410f, 320f)
        hits += Hit(80f, 340f, 400f, 425f) { st.showArrow = false; st.screen = Screen.Find }
    } else {
        c.t(ps.text(LIGHT, 26f, false), "還沒有記錄", 240f, 390f)
    }
}

private fun drawFloor(c: android.graphics.Canvas, ps: Paints, st: ParkState, hits: MutableList<Hit>) {
    c.t(ps.text(WHITE, 30f), "停在哪一層？", 240f, 74f)
    val sub = st.suggestFloor?.let { "建議：${floorName(it)}（${st.draftGps}）" } ?: st.draftGps
    c.t(ps.text(GREEN_TXT, 22f, false), sub, 240f, 108f, 330f)
    val pos = listOf(118f to 170f, 240f to 170f, 362f to 170f, 150f to 268f, 330f to 268f)
    FLOORS.forEachIndexed { i, f ->
        val (x, y) = pos[i]
        val sel = st.draftFloor == f
        val r = RectF(x - 52f, y - 40f, x + 52f, y + 40f)
        if (sel) ps.fill.shader = greenShader(r.top, r.bottom) else { ps.fill.shader = null; ps.fill.color = 0xFF232A33.toInt() }
        c.drawRoundRect(r, 20f, 20f, ps.fill)
        ps.fill.shader = null
        ps.stroke.color = if (sel) 0xFFBFFFD8.toInt() else 0xFF3B4552.toInt(); ps.stroke.strokeWidth = 2f
        c.drawRoundRect(r, 20f, 20f, ps.stroke)
        c.t(ps.text(WHITE, if (f == 0) 34f else 40f), floorName(f), x, y + 14f)
        hits += Hit(r.left, r.top, r.right, r.bottom) { st.draftFloor = f }
    }
    val ok = st.draftFloor != null
    val b = RectF(150f, 330f, 330f, 400f)
    if (ok) ps.fill.shader = blueShader(b.top, b.bottom) else { ps.fill.shader = null; ps.fill.color = 0xFF2A3038.toInt() }
    c.drawRoundRect(b, 35f, 35f, ps.fill); ps.fill.shader = null
    c.t(ps.text(if (ok) WHITE else 0xFF6B7480.toInt(), 30f), if (ok) "下一步" else "先選樓層", 240f, 376f)
    if (ok) hits += Hit(b.left, b.top, b.right, b.bottom) { st.screen = Screen.Pillar }
}

private fun drawPillar(c: android.graphics.Canvas, ps: Paints, st: ParkState, hits: MutableList<Hit>,
                       onDone: () -> Unit, onListen: () -> Unit) {
    c.t(ps.text(WHITE, 30f), "柱子編號", 240f, 92f)
    val box = RectF(120f, 128f, 360f, 238f)
    ps.fill.shader = null; ps.fill.color = 0xFF101820.toInt()
    c.drawRoundRect(box, 24f, 24f, ps.fill)
    ps.stroke.color = YELLOW; ps.stroke.strokeWidth = 3f
    c.drawRoundRect(box, 24f, 24f, ps.stroke)
    if (st.draftPillar.isBlank()) c.t(ps.text(0xFF8C96A2.toInt(), 30f, false), "可不填", 240f, 194f)
    else c.t(ps.text(YELLOW, 72f), st.draftPillar, 240f, 210f, 220f)
    hits += Hit(box.left, box.top, box.right, box.bottom) { onListen() }

    // 麥克風
    ps.fill.color = 0xFF232A33.toInt(); c.drawCircle(160f, 320f, 46f, ps.fill)
    ps.fill.color = WHITE; c.drawRoundRect(RectF(150f, 296f, 170f, 330f), 10f, 10f, ps.fill)
    ps.stroke.color = WHITE; ps.stroke.strokeWidth = 4f
    c.drawArc(RectF(140f, 298f, 180f, 338f), 0f, 180f, false, ps.stroke)
    c.drawLine(160f, 338f, 160f, 350f, ps.stroke)
    c.t(ps.text(LIGHT, 22f, false), "用說的", 160f, 392f)
    hits += Hit(110f, 270f, 210f, 400f) { onListen() }

    // 完成
    ps.fill.shader = greenShader(274f, 366f); c.drawCircle(320f, 320f, 46f, ps.fill); ps.fill.shader = null
    ps.stroke.color = WHITE; ps.stroke.strokeWidth = 8f; ps.stroke.strokeCap = Paint.Cap.ROUND; ps.stroke.strokeJoin = Paint.Join.ROUND
    c.drawPath(Path().apply { moveTo(300f, 322f); lineTo(314f, 336f); lineTo(342f, 306f) }, ps.stroke)
    ps.stroke.strokeCap = Paint.Cap.BUTT
    c.t(ps.text(LIGHT, 22f, false), "完成", 320f, 392f)
    hits += Hit(270f, 270f, 370f, 400f) { onDone() }
}

private fun distanceText(m: Float): Pair<String, String> =
    if (m < 1000f) "${Math.round(m)}" to "公尺" else String.format(Locale.US, "%.1f", m / 1000f) to "公里"

private fun drawFind(c: android.graphics.Canvas, ps: Paints, st: ParkState, hits: MutableList<Hit>) {
    val sv = st.saved ?: run { st.screen = Screen.Home; return }
    val underground = sv.floor != 0
    val here = st.here
    val canArrow = sv.lat != null && sv.lon != null && goodFix(here)

    if (underground && !st.showArrow) {
        c.t(ps.text(GREY, 28f), "你的車在", 240f, 84f)
        c.t(ps.text(YELLOW, 96f), floorName(sv.floor), 240f, 190f)
        if (sv.pillar.isNotBlank()) c.t(ps.text(WHITE, 62f), sv.pillar, 240f, 262f, 300f)
        val d = floorDiff(sv, st.pressure)
        val msg = when {
            d == null -> "記得是 ${floorName(sv.floor)}"
            d > 0 -> "再往下 $d 層"
            d < 0 -> "再往上 ${-d} 層"
            else -> "就在這一層"
        }
        val b = RectF(110f, 300f, 370f, 370f)
        ps.fill.shader = null; ps.fill.color = 0xFF1E2630.toInt(); c.drawRoundRect(b, 35f, 35f, ps.fill)
        ps.stroke.color = 0xFF2F7BFF.toInt(); ps.stroke.strokeWidth = 3f; c.drawRoundRect(b, 35f, 35f, ps.stroke)
        c.t(ps.text(WHITE, 30f), msg, 240f, 346f, 240f)
        if (sv.lat != null) {
            c.t(ps.text(LIGHT, 20f, false), "點一下：帶我到入口", 240f, 410f)
            hits += Hit(60f, 60f, 420f, 425f) { st.showArrow = true }
        }
        return
    }

    // 箭頭畫面
    c.t(ps.text(GREY, 28f), if (underground) "往停車場入口" else "往車子走", 240f, 84f)
    if (!canArrow) {
        c.t(ps.text(WHITE, 34f), if (sv.lat == null) "當時沒有 GPS" else "等待 GPS…", 240f, 220f, 340f)
        c.t(ps.text(LIGHT, 24f, false), if (sv.lat == null) "請看樓層和柱號" else "請到室外空曠處", 240f, 262f, 340f)
    } else {
        val out = FloatArray(2)
        Location.distanceBetween(here!!.latitude, here.longitude, sv.lat!!, sv.lon!!, out)
        val dist = out[0]; val bearing = out[1]
        if (dist < 15f) {
            c.t(ps.text(0xFF2FD47A.toInt(), 56f), "就在附近", 240f, 230f)
            c.t(ps.text(LIGHT, 24f, false), "四處看看你的車", 240f, 276f)
        } else {
            val ang = if (st.hasHeading) bearing - st.heading else bearing
            c.save(); c.rotate(ang, 240f, 200f)
            val path = Path().apply { moveTo(240f, 112f); lineTo(300f, 250f); lineTo(240f, 222f); lineTo(180f, 250f); close() }
            ps.fill.shader = greenShader(112f, 250f); c.drawPath(path, ps.fill); ps.fill.shader = null
            ps.stroke.color = 0xFFBFFFD8.toInt(); ps.stroke.strokeWidth = 3f; ps.stroke.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, ps.stroke)
            c.restore()
            val (n, u) = distanceText(dist)
            val pn = ps.text(WHITE, 76f); val pu = ps.text(WHITE, 36f)
            val wn = pn.measureText(n); val wu = pu.measureText(u); val x0 = 240f - (wn + 10f + wu) / 2f
            pn.textAlign = Paint.Align.LEFT; pu.textAlign = Paint.Align.LEFT
            c.drawText(n, x0, 338f, pn); c.drawText(u, x0 + wn + 10f, 338f, pu)
        }
    }
    c.t(ps.text(YELLOW, 26f), if (underground) spotLabel(sv) else "地面停車場", 240f, 392f, 320f)
    if (underground) hits += Hit(60f, 60f, 420f, 425f) { st.showArrow = false }
    if (!st.hasHeading && canArrow) c.t(ps.text(LIGHT, 18f, false), "（沒有指南針，箭頭以北方為上）", 240f, 420f)
}

@Suppress("unused")
private fun dist(a: Float, b: Float) = hypot(a, b)
