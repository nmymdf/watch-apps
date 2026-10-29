package com.archiekuo.compasswatch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val state = CompassState()
    private lateinit var controller: SensorController

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.startLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = SensorController(this, state)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        setContent { AppRoot(state) }
    }

    override fun onResume() {
        super.onResume()
        controller.start()
    }

    override fun onPause() {
        super.onPause()
        controller.stop()
    }
}

private fun mkPaint(
    color: String,
    size: Float = 0f,
    bold: Boolean = false,
    style: Paint.Style = Paint.Style.FILL,
    align: Paint.Align = Paint.Align.CENTER,
    skew: Float = 0f
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = android.graphics.Color.parseColor(color)
    this.textSize = size
    this.style = style
    this.textAlign = align
    this.textSkewX = skew
    this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

private class Paints {
    // ring: tick circles pushed out to the edge of the screen (design size 480)
    val minorTick = mkPaint("#5B616C", style = Paint.Style.STROKE).apply {
        strokeWidth = 10f
        pathEffect = DashPathEffect(floatArrayOf(2f, 16.85f), 1f)
    }
    val majorTick = mkPaint("#E8EBF0", style = Paint.Style.STROKE).apply {
        strokeWidth = 16f
        pathEffect = DashPathEffect(floatArrayOf(3f, 108.52f), 1.5f)
    }
    val minorPath = Path().apply { addCircle(240f, 240f, 216f, Path.Direction.CW) }
    val majorPath = Path().apply { addCircle(240f, 240f, 213f, Path.Direction.CW) }

    val north = mkPaint("#FF6B3D", 34f, bold = true)
    val cardinal = mkPaint("#E8EBF0", 28f)
    val gold = mkPaint("#D4AF37")
    val goldText = mkPaint("#D4AF37", 16f, skew = -0.22f)
    val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            240f, 240f, 205f,
            android.graphics.Color.parseColor("#15262A"),
            android.graphics.Color.BLACK,
            Shader.TileMode.CLAMP
        )
    }
    val bg = mkPaint("#000000")

    val label1 = mkPaint("#8A919C", 22f)
    val label2 = mkPaint("#8A919C", 18f)
    val dirBig = mkPaint("#FFFFFF", 34f)
    val altNum = mkPaint("#5AD1C8", 112f, bold = true, align = Paint.Align.LEFT)
    val altUnit = mkPaint("#5AD1C8", 38f, align = Paint.Align.LEFT)
    val degNum = mkPaint("#FFFFFF", 56f, bold = true, align = Paint.Align.LEFT)
    val degDir = mkPaint("#AEB4BF", 24f, align = Paint.Align.LEFT)
    val barNum = mkPaint("#7FB2FF", 56f, bold = true, align = Paint.Align.LEFT)
    val barUnit = mkPaint("#7FB2FF", 22f, align = Paint.Align.LEFT)
    val line = mkPaint("#343A44", style = Paint.Style.STROKE).apply { strokeWidth = 2f }
    val dotOn = mkPaint("#FFFFFF")
    val dotOff = mkPaint("#4A505A")

    // calibration screen
    val title = mkPaint("#FFFFFF", 28f, bold = true)
    val caption = mkPaint("#AEB4BF", 20f)
    val percent = mkPaint("#FFFFFF", 38f, bold = true)
    val loopTrack = mkPaint("#2A2F37", style = Paint.Style.STROKE).apply {
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val loopDone = mkPaint("#5AD1C8", style = Paint.Style.STROKE).apply {
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val button = mkPaint("#5AD1C8")
    val buttonText = mkPaint("#06201E", 22f, bold = true)
}

private val DIRS = arrayOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")

private fun dirName(h: Float): String = DIRS[(((h + 22.5f) / 45f).toInt()) % 8]

private fun drawMid(c: android.graphics.Canvas, text: String, x: Float, y: Float, p: Paint) {
    val fm = p.fontMetrics
    c.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, p)
}

/** Draws "big" + "small" side by side, the pair centred on cx, shrunk if wider than maxW. */
private fun drawPair(
    c: android.graphics.Canvas, big: String, small: String,
    pBig: Paint, pSmall: Paint, cx: Float, baseline: Float, gap: Float, maxW: Float
) {
    val sBig = pBig.textSize
    val sSmall = pSmall.textSize
    var w = pBig.measureText(big) + gap + pSmall.measureText(small)
    var k = 1f
    if (w > maxW) {
        k = maxW / w
        pBig.textSize = sBig * k
        pSmall.textSize = sSmall * k
        w = pBig.measureText(big) + gap * k + pSmall.measureText(small)
    }
    val x0 = cx - w / 2f
    c.drawText(big, x0, baseline, pBig)
    c.drawText(small, x0 + pBig.measureText(big) + gap * k, baseline, pSmall)
    pBig.textSize = sBig
    pSmall.textSize = sSmall
}

private fun figureEight(): Path = Path().apply {
    moveTo(240f, 215f)
    cubicTo(240f, 165f, 330f, 165f, 330f, 215f)
    cubicTo(330f, 265f, 240f, 265f, 240f, 215f)
    cubicTo(240f, 165f, 150f, 165f, 150f, 215f)
    cubicTo(150f, 265f, 240f, 265f, 240f, 215f)
    close()
}

private val BUTTON_RECT = RectF(160f, 356f, 320f, 408f)

private fun calibrationProgress(acc: Int): Float = when (acc) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 1f
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.65f
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.35f
    else -> 0.12f
}

/** Shared by page 1 and page 2: dial background, rotating ring, N/E/S/W, gold pointer, author. */
private fun drawDial(c: android.graphics.Canvas, p: Paints, heading: Float, version: String) {
    c.drawRect(0f, 0f, 480f, 480f, p.bg)
    c.drawCircle(240f, 240f, 205f, p.disc)

    c.save()
    c.rotate(-heading, 240f, 240f)
    c.drawPath(p.minorPath, p.minorTick)
    c.drawPath(p.majorPath, p.majorTick)
    c.restore()

    val pointer = Path().apply {
        moveTo(231f, 1f); lineTo(249f, 1f); lineTo(240f, 17f); close()
    }
    c.drawPath(pointer, p.gold)

    val letters = arrayOf("N", "E", "S", "W")
    for (i in 0..3) {
        val phi = Math.toRadians((i * 90 - heading).toDouble())
        val x = 240f + 178f * sin(phi).toFloat()
        val y = 240f - 178f * cos(phi).toFloat()
        drawMid(c, letters[i], x, y, if (i == 0) p.north else p.cardinal)
    }

    c.drawText("作者：ArchieKUO · v$version", 240f, 358f, p.goldText)
}

private fun drawDots(c: android.graphics.Canvas, p: Paints, page: Int) {
    c.drawCircle(470f, 225f, 4f, if (page == 0) p.dotOn else p.dotOff)
    c.drawCircle(470f, 255f, 4f, if (page == 1) p.dotOn else p.dotOff)
}

private fun drawPage1(
    c: android.graphics.Canvas, p: Paints, heading: Float, altitude: Double?, version: String
) {
    drawDial(c, p, heading, version)

    c.drawText("海拔高度", 240f, 146f, p.label1)

    val num = if (altitude != null) String.format(Locale.US, "%,.0f", altitude) else "--"
    p.altNum.color = if (altitude != null && altitude < 0) {
        android.graphics.Color.parseColor("#FF5A5A")
    } else {
        android.graphics.Color.parseColor("#5AD1C8")
    }
    p.altUnit.color = p.altNum.color
    drawPair(c, num, "m", p.altNum, p.altUnit, 240f, 262f, 6f, 330f)

    c.drawText(dirName(heading), 240f, 318f, p.dirBig)
    drawDots(c, p, 0)
}

private fun drawPage2(
    c: android.graphics.Canvas, p: Paints, heading: Float, pressure: Float?, version: String
) {
    drawDial(c, p, heading, version)

    c.drawText("方向角度", 240f, 132f, p.label2)
    val deg = heading.roundToInt() % 360
    drawPair(c, "$deg°", dirName(heading), p.degNum, p.degDir, 240f, 190f, 10f, 260f)
    c.drawLine(206f, 214f, 274f, 214f, p.line)

    c.drawText("氣壓", 240f, 248f, p.label2)
    val pr = if (pressure != null) String.format(Locale.US, "%,.0f", pressure) else "--"
    drawPair(c, pr, "hPa", p.barNum, p.barUnit, 240f, 310f, 6f, 260f)
    drawDots(c, p, 1)
}

private fun drawCalibrate(
    c: android.graphics.Canvas, p: Paints, accuracy: Int, version: String
) {
    c.drawRect(0f, 0f, 480f, 480f, p.bg)
    c.drawText("校正指南針", 240f, 112f, p.title)

    val path = figureEight()
    c.drawPath(path, p.loopTrack)
    val prog = calibrationProgress(accuracy)
    val pm = PathMeasure(path, false)
    val seg = Path()
    pm.getSegment(0f, pm.length * prog, seg, true)
    c.drawPath(seg, p.loopDone)

    val done = prog >= 1f
    c.drawText(if (done) "已校正，可按完成" else "手腕畫 8 字", 240f, 298f, p.caption)
    c.drawText("${(prog * 100).roundToInt()}%", 240f, 338f, p.percent)

    c.drawRoundRect(BUTTON_RECT, 26f, 26f, p.button)
    c.drawText("完成", 240f, 390f, p.buttonText)
    c.drawText("作者：ArchieKUO · v$version", 240f, 446f, p.goldText)
}

@Composable
fun AppRoot(st: CompassState) {
    val ctx = LocalContext.current
    val paints = remember { Paints() }
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "0.2"
    }
    var px by remember { mutableStateOf(IntSize.Zero) }

    BackHandler(enabled = st.screen != Screen.Compass) { st.screen = Screen.Compass }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { px = it }
            .pointerInput(Unit) {
                // push up = next page, pull down = back to page 1
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -50f && st.screen == Screen.Compass) {
                            st.screen = Screen.Details
                        } else if (total > 50f && st.screen == Screen.Details) {
                            st.screen = Screen.Compass
                        }
                    },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val s = min(px.width, px.height) / 480f
                    if (s <= 0f) return@detectTapGestures
                    val ox = (px.width - 480f * s) / 2f
                    val oy = (px.height - 480f * s) / 2f
                    val dx = (off.x - ox) / s
                    val dy = (off.y - oy) / s
                    when (st.screen) {
                        Screen.Compass -> {
                            if (hypot(dx - 240f, dy - 240f) < 150f) st.screen = Screen.Calibrate
                        }
                        Screen.Details -> {}
                        Screen.Calibrate -> {
                            if (BUTTON_RECT.contains(dx, dy)) st.screen = Screen.Compass
                        }
                    }
                }
            }
    ) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val s = min(size.width, size.height) / 480f
            val ox = (size.width - 480f * s) / 2f
            val oy = (size.height - 480f * s) / 2f
            nc.save()
            nc.translate(ox, oy)
            nc.scale(s, s)
            when (st.screen) {
                Screen.Compass -> drawPage1(nc, paints, st.heading, st.altitude, version)
                Screen.Details -> drawPage2(nc, paints, st.heading, st.pressure, version)
                Screen.Calibrate -> drawCalibrate(nc, paints, st.accuracy, version)
            }
            nc.restore()
        }
    }
}
