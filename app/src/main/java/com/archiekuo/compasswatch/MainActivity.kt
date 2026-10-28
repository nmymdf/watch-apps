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
    val minorTick = mkPaint("#5B616C", style = Paint.Style.STROKE).apply {
        strokeWidth = 10f
        pathEffect = DashPathEffect(floatArrayOf(2f, 13.88f), 1f)
    }
    val majorTick = mkPaint("#E8EBF0", style = Paint.Style.STROKE).apply {
        strokeWidth = 16f
        pathEffect = DashPathEffect(floatArrayOf(3f, 90.72f), 1.5f)
    }
    val north = mkPaint("#FF6B3D", 32f, bold = true)
    val cardinal = mkPaint("#E8EBF0", 26f)
    val inter = mkPaint("#8A919C", 16f)
    val gold = mkPaint("#D4AF37")
    val goldText = mkPaint("#D4AF37", 15f, skew = -0.22f)
    val white = mkPaint("#FFFFFF")
    val headingText = mkPaint("#FFFFFF", 64f, bold = true)
    val dirText = mkPaint("#AEB4BF", 22f)
    val labelText = mkPaint("#8A919C", 16f)
    val baroText = mkPaint("#8A919C", 14f)
    val altNum = mkPaint("#5AD1C8", 50f, bold = true, align = Paint.Align.LEFT)
    val altUnit = mkPaint("#5AD1C8", 22f, align = Paint.Align.LEFT)
    val line = mkPaint("#343A44", style = Paint.Style.STROKE).apply { strokeWidth = 2f }
    val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            240f, 222f, 170f,
            android.graphics.Color.parseColor("#1A1D24"),
            android.graphics.Color.BLACK,
            Shader.TileMode.CLAMP
        )
    }
    val bg = mkPaint("#000000")
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

private fun drawCompass(
    c: android.graphics.Canvas, p: Paints, heading: Float,
    altitude: Double?, pressure: Float?, version: String
) {
    c.drawRect(0f, 0f, 480f, 480f, p.bg)
    c.drawCircle(240f, 222f, 170f, p.disc)

    c.save()
    c.rotate(-heading, 240f, 222f)
    val minor = Path().apply { addCircle(240f, 222f, 182f, Path.Direction.CW) }
    val major = Path().apply { addCircle(240f, 222f, 179f, Path.Direction.CW) }
    c.drawPath(minor, p.minorTick)
    c.drawPath(major, p.majorTick)
    val tri = Path().apply {
        moveTo(240f, 35f); lineTo(230f, 17f); lineTo(250f, 17f); close()
    }
    c.drawPath(tri, p.north)
    c.restore()

    val pointer = Path().apply {
        moveTo(231f, 8f); lineTo(249f, 8f); lineTo(240f, 28f); close()
    }
    c.drawPath(pointer, p.gold)

    val letters = arrayOf("N", "E", "S", "W")
    for (i in 0..3) {
        val phi = Math.toRadians((i * 90 - heading).toDouble())
        val x = 240f + 150f * sin(phi).toFloat()
        val y = 222f - 150f * cos(phi).toFloat()
        drawMid(c, letters[i], x, y, if (i == 0) p.north else p.cardinal)
    }
    val inters = arrayOf("NE", "SE", "SW", "NW")
    for (i in 0..3) {
        val phi = Math.toRadians((45 + i * 90 - heading).toDouble())
        val x = 240f + 150f * sin(phi).toFloat()
        val y = 222f - 150f * cos(phi).toFloat()
        drawMid(c, inters[i], x, y, p.inter)
    }

    val deg = heading.roundToInt() % 360
    c.drawText("$deg°", 240f, 212f, p.headingText)
    c.drawText(dirName(heading), 240f, 244f, p.dirText)
    c.drawLine(206f, 262f, 274f, 262f, p.line)
    c.drawText("海拔高度", 240f, 288f, p.labelText)

    val num = if (altitude != null) String.format(Locale.US, "%,.0f", altitude) else "--"
    p.altNum.color = if (altitude != null && altitude < 0) {
        android.graphics.Color.parseColor("#FF5A5A")
    } else {
        android.graphics.Color.parseColor("#5AD1C8")
    }
    val wNum = p.altNum.measureText(num)
    val wUnit = p.altUnit.measureText("m") + 4f
    val x0 = 240f - (wNum + wUnit) / 2f
    c.drawText(num, x0, 332f, p.altNum)
    c.drawText("m", x0 + wNum + 4f, 332f, p.altUnit)

    val baro = if (pressure != null) {
        "氣壓 " + String.format(Locale.US, "%,.0f", pressure) + " hPa"
    } else "氣壓 --"
    c.drawText(baro, 240f, 428f, p.baroText)
    c.drawText("作者：ArchieKUO · v$version", 240f, 454f, p.goldText)
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
        }.getOrNull() ?: "0.1"
    }
    var px by remember { mutableStateOf(IntSize.Zero) }

    BackHandler(enabled = st.screen == Screen.Calibrate) { st.screen = Screen.Compass }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { px = it }
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
                            if (hypot(dx - 240f, dy - 222f) < 150f) st.screen = Screen.Calibrate
                        }
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
                Screen.Compass ->
                    drawCompass(nc, paints, st.heading, st.altitude, st.pressure, version)
                Screen.Calibrate ->
                    drawCalibrate(nc, paints, st.accuracy, version)
            }
            nc.restore()
        }
    }
}
