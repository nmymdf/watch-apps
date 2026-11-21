package com.archiekuo.speedwatch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 背景服務：開車時一直算車速、比對測速點；導航畫面在前面也照常提醒 */
class SpeedService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private lateinit var nm: NotificationManager
    private var wake: PowerManager.WakeLock? = null
    private var last: Location? = null
    private var heading: Float? = null
    private var alertedCam: Cam? = null
    private var lastOverBuzz = 0L
    // 連續確認：同一個測速點要連續 3 次都在正前方、而且越來越近，才提醒
    private var candCam: Cam? = null
    private var candHits = 0
    private var candDist = 0f
    private var awayHits = 0

    companion object {
        const val CH_RUN = "run"
        const val CH_ALERT = "alert"
        const val N_RUN = 1
        const val N_ALERT = 2

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SpeedService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SpeedService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "行車中", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "測速提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false) // 震動由程式自己控制
        })
        if (CamDb.cams.isEmpty()) CamDb.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = runNote("行車中，開始測速提醒")
        if (Build.VERSION.SDK_INT >= 29) startForeground(N_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(N_RUN, n)
        Live.mode = Mode.Driving
        Live.gpsOk = false
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (_: Exception) {}
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speedwatch:drive").apply { acquire(4 * 3600_000L) }
        return START_STICKY
    }

    override fun onDestroy() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        nm.cancel(N_ALERT)
        Live.mode = Mode.Idle
        Live.cam = null; Live.over = false; Live.aheadKm = null; Live.speed = 0
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun runNote(text: String): Notification =
        Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("測速提醒")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun alertNote(title: String, text: String) {
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        try { nm.notify(N_ALERT, n) } catch (_: Exception) {}
    }

    private fun buzz(pattern: LongArray) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onLocationChanged(loc: Location) {
        val prev = last
        last = loc
        Live.gpsOk = !loc.hasAccuracy() || loc.accuracy <= 50f

        // 車速：優先用 GPS 給的速度，沒有就用兩點距離算
        var kmh = if (loc.hasSpeed()) loc.speed * 3.6f else -1f
        if (kmh < 0 && prev != null) {
            val dt = (loc.time - prev.time) / 1000f
            if (dt in 0.5f..10f) kmh = prev.distanceTo(loc) / dt * 3.6f
        }
        if (kmh < 0) kmh = 0f
        Live.speed = kmh.toInt()

        // 行進方向：時速 8 公里以上才可靠
        if (kmh >= 8f) {
            heading = when {
                loc.hasBearing() -> loc.bearing
                prev != null && prev.distanceTo(loc) > 5f -> prev.bearingTo(loc)
                else -> heading
            }
        }

        // 提前多遠提醒：時速 90 以上 1000 公尺、70 以上 800 公尺，以下 300 公尺
        val alertM = when { kmh >= 90f -> 1000f; kmh >= 70f -> 800f; else -> 300f }
        // 高速時：只看正前方窄角度、路線兩旁 40 公尺內，並排除速限 60 以下的平面道路測速
        val fast = kmh >= 80f
        val near = CamDb.nearestAhead(
            loc, heading, 3000f,
            cone = if (kmh >= 60f) 20f else 45f,
            sideMax = if (fast) 40f else 9999f,
            minLimit = if (fast) 61 else 0,
            dirTol = 80f   // 資料上的方向是大方向（例如「北往南」），道路會轉彎，所以放寬
        )
        Live.aheadKm = near?.second?.let { it / 1000f }

        // 確認目前要提醒的點
        var show: Pair<Cam, Float>? = null
        if (near != null && near.second <= alertM) {
            if (alertedCam === near.first) {
                show = near
            } else if (candCam === near.first && near.second < candDist) {
                candHits++
                if (candHits >= 3) show = near
            } else {
                candCam = near.first; candHits = 1
            }
            candDist = near.second
        } else {
            candCam = null; candHits = 0
        }
        // 已經在提醒中的點：開過頭（距離連續變遠）才結束
        val cur = alertedCam
        if (show == null && cur != null) {
            val rr = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, cur.lat, cur.lon, rr)
            if (rr[0] < Live.camDist + 15f && rr[0] < alertM + 100f) { awayHits = 0; show = cur to rr[0] }
            else if (++awayHits < 2) show = cur to rr[0]
        } else awayHits = 0

        if (show != null) {
            val (cam, d) = show
            Live.cam = cam
            Live.camDist = d.toInt()
            val over = cam.limit > 0 && kmh > cam.limit + 5
            Live.over = over
            if (alertedCam !== cam) {
                alertedCam = cam
                buzz(longArrayOf(0, 300, 150, 300))
            }
            val now = System.currentTimeMillis()
            if (over && now - lastOverBuzz > 3000) {
                lastOverBuzz = now
                buzz(longArrayOf(0, 500, 150, 500, 150, 500))
            }
            val lim = if (cam.limit > 0) "速限 ${cam.limit}" else "測速"
            alertNote(if (over) "超速！請減速" else "前方$lim", "${d.toInt()} 公尺　你 ${kmh.toInt()}")
        } else {
            if (alertedCam != null) {
                alertedCam = null
                nm.cancel(N_ALERT)
            }
            Live.cam = null
            Live.over = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { Live.gpsOk = false }
}
