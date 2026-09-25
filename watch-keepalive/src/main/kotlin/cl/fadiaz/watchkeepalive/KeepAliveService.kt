package cl.fadiaz.watchkeepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Holds a `PARTIAL_WAKE_LOCK` so a wireless adb session survives the watch suspending.
 *
 *     adb shell am start-foreground-service -n cl.fadiaz.watchkeepalive/.KeepAliveService
 *     adb shell am stopservice              -n cl.fadiaz.watchkeepalive/.KeepAliveService
 *
 * A partial wake lock keeps the CPU running with the screen off, which is the one thing that
 * settings could not buy: the screen is not the lever, because Wear OS dozes on a wrist drop or a
 * palm gesture without consulting `screen_off_timeout`, and the Wi-Fi radio was never what failed
 * (see build.gradle.kts for the measurements).
 *
 * **It is not unkillable, and nothing on Android is.** A foreground service survives doze and
 * ordinary memory pressure; `force-stop`, a reboot and extreme pressure still end it. What it
 * buys is a session that outlives the screen going off, which is the case that was breaking.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val vencer = Runnable {
        Log.i(TAG, "Limite alcanzado tras ${LIMITE_MS / 60_000} min; soltando el wake lock")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            ID_NOTIFICACION,
            notificacion(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            // Reference counting off on purpose: a second `am start-foreground-service` lands
            // here again, and with counting on it would take two stops to actually release.
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_WAKELOCK).apply {
                setReferenceCounted(false)
                // The timeout is the point, not a formality. Whoever started this is a debugging
                // session that may simply end -- the laptop closes, the terminal dies -- and a
                // wake lock nobody releases flattens a watch overnight.
                acquire(LIMITE_MS)
            }
            Log.i(TAG, "Wake lock tomado; limite ${LIMITE_MS / 60_000} min")
        }

        handler.removeCallbacks(vencer)
        handler.postDelayed(vencer, LIMITE_MS)

        // START_STICKY so a kill under memory pressure brings it back. An explicit
        // `am stopservice` is not a kill and does not restart it.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(vencer)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "Wake lock soltado")
        super.onDestroy()
    }

    private fun notificacion(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_MIN: Android demands a notification for a foreground service, so the only
        // choice left is how loud it is. This one is as quiet as the platform allows.
        manager.createNotificationChannel(
            NotificationChannel(CANAL, "Depuracion", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CANAL)
            .setContentTitle("Depuracion activa")
            .setContentText("El reloj no se suspende mientras dure la sesion")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "WatchKeepAlive"
        const val TAG_WAKELOCK = "watchkeepalive:adb"
        const val CANAL = "keepalive"
        const val ID_NOTIFICACION = 1
        const val LIMITE_MS = 60L * 60L * 1000L
    }
}
