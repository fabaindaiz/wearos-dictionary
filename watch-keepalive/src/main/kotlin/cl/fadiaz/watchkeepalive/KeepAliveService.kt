package cl.fadiaz.watchkeepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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
    private var red: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observadorAdb: ContentObserver? = null
    private var receptorWifi: BroadcastReceiver? = null
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

        pedirRed()
        tomarWifiLock()
        vigilarFinDeSesion()

        handler.removeCallbacks(vencer)
        handler.postDelayed(vencer, LIMITE_MS)

        // START_STICKY so a kill under memory pressure brings it back. An explicit
        // `am stopservice` is not a kill and does not restart it.
        return START_STICKY
    }

    /**
     * Asks for a Wi-Fi network and keeps asking, which is a different thing from the wake lock.
     *
     * **Measured 2026-09-25**: with the lock held and nothing requesting a network, the session
     * still died 44 s in. A `PARTIAL_WAKE_LOCK` keeps the CPU running; it does not keep the radio
     * on. What the radio watches is `mNumWifiRequests`, and while the screen is on the screen is
     * what holds the only one -- so it reaches zero the moment the screen goes off, the mediator
     * logs `OFF_NO_REQUESTS`, and the CPU stays awake serving a socket on an interface that is
     * gone.
     *
     * `registerNetworkCallback` would not do: it observes and does not count. Only
     * `requestNetwork` counts, and that one needs `CHANGE_NETWORK_STATE`.
     */
    private fun pedirRed() {
        if (red != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val peticion = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {}
        try {
            cm.requestNetwork(peticion, callback)
            red = callback
            Log.i(TAG, "NetworkRequest de Wi-Fi tomado")
        } catch (e: SecurityException) {
            // Said out loud instead of swallowed: without this the lock is held, the service
            // looks healthy, and the session dies anyway -- which is the exact failure this
            // whole module exists to stop being invisible.
            Log.e(TAG, "SIN NetworkRequest: falta CHANGE_NETWORK_STATE (${e.message})")
        }
    }

    /**
     * Takes the Wi-Fi chip out of deep power save, which is a third thing again.
     *
     * **Measured 2026-09-25**: with the lock *and* the network request held, the session reached
     * 157 s of the screen being off and then died anyway, and the transport was left reading
     * `offline` rather than absent -- a half-open socket with mDNS still advertising the port.
     * That is not a radio that was switched off; it is a chip that stopped answering.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY` is the modern constant and is no use here: it applies only
     * while the screen is on and the app is in the foreground, which is exactly the case this
     * module does not have. `WIFI_MODE_FULL_HIGH_PERF` is deprecated and is the one that works
     * with the screen off, so the deprecation is suppressed on purpose rather than worked around.
     */
    @Suppress("DEPRECATION")
    private fun tomarWifiLock() {
        if (wifiLock != null) return
        val wifi = getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG_WIFILOCK).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "WifiLock tomado (FULL_HIGH_PERF)")
    }

    /**
     * Stops when the person ends the session, without waiting for a `stop` that may never arrive.
     *
     * Two signals, and both mean the same thing on purpose: **somebody turned the session off by
     * hand.** Turning wireless debugging off is unambiguous. Turning Wi-Fi off is not quite --- a
     * transient drop is not a decision --- but on a watch the radio does not go down by itself
     * while something holds a request for it, which is precisely what this service is doing.
     *
     * It exists because `stop` can fail to arrive at all: the watch drops off adb constantly,
     * which is the whole reason this module exists, and a service left holding a wake lock
     * because the command never landed is a flat battery by morning. The hour-long expiry is the
     * floor; this is the part that reacts.
     */
    private fun vigilarFinDeSesion() {
        if (observadorAdb == null) {
            val observador = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    if (Settings.Global.getInt(contentResolver, AJUSTE_ADB_WIFI, 0) == 0) {
                        Log.i(TAG, "Depuracion inalambrica apagada; termino la sesion")
                        stopSelf()
                    }
                }
            }
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(AJUSTE_ADB_WIFI), false, observador,
            )
            observadorAdb = observador
        }

        if (receptorWifi == null) {
            val receptor = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val estado = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN,
                    )
                    if (estado == WifiManager.WIFI_STATE_DISABLING ||
                        estado == WifiManager.WIFI_STATE_DISABLED
                    ) {
                        Log.i(TAG, "Wi-Fi apagado; termino la sesion")
                        stopSelf()
                    }
                }
            }
            // NOT_EXPORTED: the only sender that matters is the system, which is exempt from the
            // flag. Exporting it would let any app on the watch fake the end of a session.
            registerReceiver(
                receptor,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED,
            )
            receptorWifi = receptor
        }
    }

    override fun onDestroy() {
        observadorAdb?.let { contentResolver.unregisterContentObserver(it) }
        observadorAdb = null
        receptorWifi?.let { unregisterReceiver(it) }
        receptorWifi = null
        handler.removeCallbacks(vencer)
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        red?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        red = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "Wake lock, NetworkRequest y WifiLock soltados")
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
        const val TAG_WIFILOCK = "watchkeepalive:wifi"

        /**
         * The global setting wireless debugging lives in.
         *
         * Spelled out rather than taken from a constant because the constant is `@hide`: passing
         * the name as a string uses the public `Settings.Global` overloads and asks nothing of
         * the hidden API list. Verified on the device with `settings get global adb_wifi_enabled`.
         */
        const val AJUSTE_ADB_WIFI = "adb_wifi_enabled"
        const val CANAL = "keepalive"
        const val ID_NOTIFICACION = 1
        const val LIMITE_MS = 60L * 60L * 1000L
    }
}
