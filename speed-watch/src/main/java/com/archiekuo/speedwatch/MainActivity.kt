package com.archiekuo.speedwatch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private var wantStart = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (wantStart && r[Manifest.permission.ACCESS_FINE_LOCATION] == true) begin()
        wantStart = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CamDb.load(this)
        CamDb.updateIfOld(this)
        setContent { Root(::tapStart, ::tapStop) { on -> CamDb.setVoice(this, on) } }
    }

    override fun onResume() {
        super.onResume()
        keepScreen(Live.mode == Mode.Driving)
    }

    private fun keepScreen(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun tapStart() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION; need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) begin() else { wantStart = true; perms.launch(need.toTypedArray()) }
    }

    private fun begin() {
        SpeedService.start(this)
        keepScreen(true)
    }

    private fun tapStop() {
        SpeedService.stop(this)
        keepScreen(false)
    }
}

// ---------------- 畫面（480 x 480 設計座標） ----------------

private const val GOLD = 0xFFD4AF37.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val GREY = 0xFF9FB6C8.toInt()
private const val LIGHT = 0xFFC9D3DD.toInt()
private const val DIM = 0xFF7D8896.toInt()
private const val YELLOW = 0xFFFFD36B.toInt()
private const val GREEN_TXT = 0xFF7FE0A8.toInt()
private const val RED = 0xFFE02020.toInt()

private fun txt(color: Int, size: Float, bold: Boolean = true, italic: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color; textSize = size; textAlign = Paint.Align.CENTER
    typeface = Typeface.create(Typeface.DEFAULT, when {
        bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL
    })
}

private fun android.graphics.Canvas.t(p: Paint, s: String, x: Float, y: Float, maxW: Float = 400f) {
    val sz = p.textSize; val w = p.measureText(s)
    if (w > maxW) p.textSize = sz * maxW / w
    drawText(s, x, y, p); p.textSize = sz
}

private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

private fun green(top: Float, bottom: Float) =
    LinearGradient(0f, top, 0f, bottom, 0xFF2FD47A.toInt(), 0xFF14A356.toInt(), Shader.TileMode.CLAMP)

private fun pill(c: android.graphics.Canvas, r: RectF, color: Int? = null, shader: Shader? = null) {
    fill.shader = shader; if (color != null) fill.color = color
    c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill); fill.shader = null
}

/** 紅框白底的速限牌 */
private fun sign(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float, limit: Int) {
    fill.shader = null; fill.color = WHITE; c.drawCircle(cx, cy, r, fill)
    stroke.color = RED; stroke.strokeWidth = r * 0.2f; c.drawCircle(cx, cy, r - 4f - r * 0.1f, stroke)
    val s = if (limit > 0) "$limit" else "!"
    c.t(txt(0xFF111111.toInt(), if (s.length >= 3) r * 0.78f else r * 0.95f), s, cx, cy + r * 0.33f, r * 1.4f)
}

private data class Hit(val r: RectF, val action: () -> Unit)

@Composable
private fun Root(onStart: () -> Unit, onStop: () -> Unit, onVoice: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val ver = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0.1"
    }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val hits = remember { mutableListOf<Hit>() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }

    // 讀取這些狀態，畫面才會跟著更新
    val mode = Live.mode; val speed = Live.speed; val cam = Live.cam; val dist = Live.camDist
    val over = Live.over; val ahead = Live.aheadKm; val gps = Live.gpsOk
    val count = Live.count; val upd = Live.updated; val updating = Live.updating; val voice = Live.voice

    Canvas(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { sz = it }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val s = min(sz.width, sz.height) / 480f
                    if (s <= 0f) return@detectTapGestures
                    val x = (o.x - (sz.width - 480f * s) / 2f) / s
                    val y = (o.y - (sz.height - 480f * s) / 2f) / s
                    hits.lastOrNull { it.r.contains(x, y) }?.action?.invoke()
                }
            }
    ) {
        if (tick < 0) return@Canvas
        drawIntoCanvas { cv ->
            val c = cv.nativeCanvas
            val s = min(size.width, size.height) / 480f
            c.save()
            c.translate((size.width - 480f * s) / 2f, (size.height - 480f * s) / 2f)
            c.scale(s, s)
            hits.clear()

            if (mode == Mode.Idle) {
                c.t(txt(GREY, 34f), "測速提醒", 240f, 84f)
                fill.shader = green(118f, 294f); c.drawCircle(240f, 206f, 88f, fill); fill.shader = null
                c.t(txt(WHITE, 44f), "開始", 240f, 222f)
                hits += Hit(RectF(150f, 116f, 330f, 296f)) { onStart() }
                // 語音開關
                val vr = RectF(130f, 312f, 350f, 374f)
                if (voice) pill(c, vr, shader = green(vr.top, vr.bottom)) else pill(c, vr, 0xFF2A3038.toInt())
                stroke.color = if (voice) 0xFFBFFFD8.toInt() else 0xFF4A5360.toInt(); stroke.strokeWidth = 2f
                c.drawRoundRect(vr, 31f, 31f, stroke)
                c.t(txt(if (voice) WHITE else 0xFF9AA4B2.toInt(), 30f), if (voice) "語音：開" else "語音：關", 240f, 354f)
                hits += Hit(RectF(120f, 304f, 360f, 382f)) { onVoice(!voice) }
                val info = "全台 %,d 支 · ".format(Locale.US, count) + (if (updating) "更新中…" else "更新 $upd")
                c.t(txt(DIM, 22f, false), info, 240f, 412f, 300f)
            } else if (cam == null) {
                // 行車中
                c.t(txt(if (gps) GREEN_TXT else YELLOW, 26f), if (gps) "行車中" else "尋找 GPS…", 240f, 84f)
                c.t(txt(WHITE, 120f), "$speed", 240f, 226f)
                c.t(txt(GREY, 28f, false), "公里/小時", 240f, 272f)
                val msg = if (ahead == null) "前方 3 公里無測速"
                else "前方 %.1f 公里有測速".format(Locale.US, ahead)
                pill(c, RectF(100f, 306f, 380f, 370f), 0xFF1E2630.toInt())
                c.t(txt(LIGHT, 27f), msg, 240f, 349f, 260f)
                pill(c, RectF(180f, 384f, 300f, 428f), 0xFF3A2224.toInt())
                c.t(txt(0xFFFF9A9A.toInt(), 24f), "停止", 240f, 414f)
                hits += Hit(RectF(170f, 378f, 310f, 434f)) { onStop() }
            } else {
                // 接近測速 / 超速
                val bg = if (over) 0xFFB01414.toInt() else 0xFF101418.toInt()
                fill.color = bg; c.drawCircle(240f, 240f, 240f, fill)
                c.t(txt(if (over) WHITE else YELLOW, if (over) 32f else 30f), if (over) "超速！請減速" else "前方測速", 240f, 74f)
                sign(c, 150f, 190f, 70f, cam.limit)
                c.t(txt(WHITE, 64f), "$dist", 320f, 190f, 140f)
                c.t(txt(if (over) 0xFFFFE1E1.toInt() else LIGHT, 26f), "公尺", 320f, 228f)
                val r = RectF(90f, 296f, 390f, 382f)
                if (over) {
                    pill(c, r, WHITE); c.t(txt(0xFFD01818.toInt(), 44f), "你 $speed", 240f, 357f)
                } else {
                    pill(c, r, shader = green(r.top, r.bottom)); c.t(txt(WHITE, 40f), "你 $speed ✓", 240f, 355f)
                }
                if (cam.name.isNotBlank()) c.t(txt(if (over) 0xFFFFE1E1.toInt() else DIM, 20f, false), cam.name, 240f, 414f, 300f)
            }

            c.t(txt(GOLD, 17f, true, true), "作者：ArchieKUO · v$ver", 240f, 452f)
            c.restore()
        }
    }
}
