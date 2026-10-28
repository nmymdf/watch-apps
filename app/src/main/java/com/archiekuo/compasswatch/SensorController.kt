package com.archiekuo.compasswatch

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
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

enum class Screen { Compass, Calibrate }

class CompassState {
    var heading by mutableFloatStateOf(0f)
    var pressure by mutableStateOf<Float?>(null)
    var altitude by mutableStateOf<Double?>(null)
    var accuracy by mutableIntStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE)
    var screen by mutableStateOf(Screen.Compass)
}

class SensorController(
    private val ctx: Context,
    private val st: CompassState
) : SensorEventListener, LocationListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val presSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val hasRot = rotSensor != null

    private var declination = 0f
    private var gpsAlt: Double? = null
    private var seaLevel = SensorManager.PRESSURE_STANDARD_ATMOSPHERE
    private var seaLevelFromGps = false
    private var pSmooth: Float? = null

    private var sinS = 0.0
    private var cosS = 1.0
    private var hasHeading = false

    private val rot = FloatArray(9)
    private val rem = FloatArray(9)
    private val ori = FloatArray(3)
    private val rm = FloatArray(9)
    private val im = FloatArray(9)
    private var grav: FloatArray? = null
    private var geo: FloatArray? = null

    fun start() {
        if (hasRot) {
            sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        presSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startLocation()
    }

    fun stop() {
        sm.unregisterListener(this)
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    fun startLocation() {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 5000L, 0f, this, Looper.getMainLooper())
                }
            }
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                applyRotation(rot)
            }
            Sensor.TYPE_ACCELEROMETER -> if (!hasRot) {
                grav = lowPass(grav, e.values)
                updateFromAccMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> if (!hasRot) {
                geo = lowPass(geo, e.values)
                updateFromAccMag()
            }
            Sensor.TYPE_PRESSURE -> onPressure(e.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) st.accuracy = accuracy
    }

    private fun lowPass(prev: FloatArray?, v: FloatArray): FloatArray {
        if (prev == null) return v.clone()
        for (i in 0..2) prev[i] = prev[i] + 0.2f * (v[i] - prev[i])
        return prev
    }

    private fun updateFromAccMag() {
        val g = grav ?: return
        val m = geo ?: return
        if (SensorManager.getRotationMatrix(rm, im, g, m)) applyRotation(rm)
    }

    private fun applyRotation(r: FloatArray) {
        val m = if (abs(r[8]) < 0.6f) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, rem)
            rem
        } else r
        SensorManager.getOrientation(m, ori)
        val az = Math.toDegrees(ori[0].toDouble()) + declination
        val rad = Math.toRadians(az)
        if (!hasHeading) {
            sinS = sin(rad)
            cosS = cos(rad)
            hasHeading = true
        } else {
            sinS += 0.15 * (sin(rad) - sinS)
            cosS += 0.15 * (cos(rad) - cosS)
        }
        val h = Math.toDegrees(atan2(sinS, cosS))
        st.heading = ((h + 360.0) % 360.0).toFloat()
    }

    private fun onPressure(p: Float) {
        val prev = pSmooth
        pSmooth = if (prev == null) p else prev + 0.1f * (p - prev)
        st.pressure = pSmooth
        recompute()
    }

    private fun recompute() {
        val p = pSmooth
        st.altitude = if (p != null) {
            SensorManager.getAltitude(seaLevel, p).toDouble()
        } else {
            gpsAlt
        }
    }

    override fun onLocationChanged(loc: Location) {
        val a = if (loc.hasAltitude()) loc.altitude else 0.0
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), a.toFloat(), System.currentTimeMillis()
        ).declination

        val alt: Double? = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) {
            loc.mslAltitudeMeters
        } else if (loc.hasAltitude()) {
            loc.altitude
        } else null

        val verticalOk = !(Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) ||
            loc.verticalAccuracyMeters <= 40f

        if (alt != null && verticalOk) {
            gpsAlt = alt
            val p = pSmooth
            if (p != null) {
                val target = (p / (1.0 - alt / 44330.0).pow(5.255)).toFloat()
                seaLevel = if (!seaLevelFromGps) target else seaLevel + 0.3f * (target - seaLevel)
                seaLevelFromGps = true
            }
        }
        recompute()
    }
}
