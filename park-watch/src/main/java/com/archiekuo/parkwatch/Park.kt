package com.archiekuo.parkwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class Screen { Home, Floor, Pillar, Find }

/** 樓層：0 = 地面，-1 = B1 … -4 = B4 */
val FLOORS = listOf(-4, -3, -2, -1, 0)
fun floorName(f: Int): String = if (f == 0) "地面" else "B${-f}"

/** 記下來的停車位置 */
data class Spot(
    val lat: Double?,
    val lon: Double?,
    val acc: Float?,
    val pressure: Float?,
    val floor: Int,
    val pillar: String,
    val time: Long
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("park", Context.MODE_PRIVATE)

    fun load(): Spot? {
        if (!sp.contains("time")) return null
        fun d(k: String): Double? = if (sp.contains(k)) java.lang.Double.longBitsToDouble(sp.getLong(k, 0)) else null
        fun f(k: String): Float? = if (sp.contains(k)) sp.getFloat(k, 0f) else null
        return Spot(d("lat"), d("lon"), f("acc"), f("p"), sp.getInt("floor", 0), sp.getString("pillar", "") ?: "", sp.getLong("time", 0))
    }

    fun save(s: Spot) {
        val e = sp.edit().clear()
        s.lat?.let { e.putLong("lat", java.lang.Double.doubleToRawLongBits(it)) }
        s.lon?.let { e.putLong("lon", java.lang.Double.doubleToRawLongBits(it)) }
        s.acc?.let { e.putFloat("acc", it) }
        s.pressure?.let { e.putFloat("p", it) }
        e.putInt("floor", s.floor).putString("pillar", s.pillar).putLong("time", s.time).apply()
    }
}

class ParkState {
    var screen by mutableStateOf(Screen.Home)
    var saved by mutableStateOf<Spot?>(null)

    // 正在記錄中的資料
    var draftLat: Double? = null
    var draftLon: Double? = null
    var draftAcc: Float? = null
    var draftPressure: Float? = null
    var draftFloor by mutableStateOf<Int?>(null)
    var suggestFloor by mutableStateOf<Int?>(null)
    var draftPillar by mutableStateOf("")
    var draftGps by mutableStateOf("定位中…")

    // 即時感測
    var heading by mutableFloatStateOf(0f)
    var hasHeading by mutableStateOf(false)
    var pressure by mutableStateOf<Float?>(null)
    var here by mutableStateOf<Location?>(null)

    var showArrow by mutableStateOf(false)
    var toast by mutableStateOf("")
}

/** 指南針方向、氣壓、GPS */
class Sensors(private val ctx: Context, private val st: ParkState) : SensorEventListener, LocationListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val presSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var declination = 0f
    private var sinS = 0.0
    private var cosS = 1.0
    private val rot = FloatArray(9)
    private val rem = FloatArray(9)
    private val ori = FloatArray(3)

    fun start() {
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        presSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startLocation()
    }

    fun stop() {
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (_: Exception) {}
    }

    fun startLocation() {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                val m = if (abs(rot[8]) < 0.6f) {
                    SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rem); rem
                } else rot
                SensorManager.getOrientation(m, ori)
                val rad = Math.toRadians(Math.toDegrees(ori[0].toDouble()) + declination)
                if (!st.hasHeading) {
                    sinS = sin(rad); cosS = cos(rad); st.hasHeading = true
                } else {
                    sinS += 0.15 * (sin(rad) - sinS); cosS += 0.15 * (cos(rad) - cosS)
                }
                st.heading = ((Math.toDegrees(atan2(sinS, cosS)) + 360.0) % 360.0).toFloat()
            }
            Sensor.TYPE_PRESSURE -> {
                val p = e.values[0]
                val prev = st.pressure
                st.pressure = if (prev == null) p else prev + 0.1f * (p - prev)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        val cur = st.here
        // 只接受比較新的、或比較準的位置
        if (cur != null && loc.provider == LocationManager.NETWORK_PROVIDER &&
            cur.provider == LocationManager.GPS_PROVIDER && loc.time - cur.time < 15000
        ) return
        st.here = loc
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            (if (loc.hasAltitude()) loc.altitude else 0.0).toFloat(), System.currentTimeMillis()
        ).declination
    }
}

/** 現在位置是否「新鮮且準」：30 秒內、誤差 30 公尺以內 */
fun goodFix(l: Location?): Boolean =
    l != null && System.currentTimeMillis() - l.time < 30_000 && l.hasAccuracy() && l.accuracy <= 30f

/** 用氣壓算出跟停車時差幾層（每層約 3 公尺）；時間太久天氣會改變氣壓，就不算 */
fun floorDiff(saved: Spot, nowP: Float?): Int? {
    val p0 = saved.pressure ?: return null
    val p1 = nowP ?: return null
    if (System.currentTimeMillis() - saved.time > 4 * 3600_000L) return null
    val h0 = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p0)
    val h1 = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p1)
    return Math.round((h1 - h0) / 3.0f)   // 正數：你比車子高（要往下）
}
