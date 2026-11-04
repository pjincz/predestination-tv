package io.jcz.predestination.exo

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.*
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Receives while the player is visible. */
internal class CastService : Service(), Renderer {
    private val main = Handler(Looper.getMainLooper())
    private val router = ReceiverRouter {}
    private var receiver: DlnaReceiver? = null
    private var admin: AdminPage? = null
    private var lock: WifiManager.MulticastLock? = null
    private var address: String? = null
    private var registered = false
    private var statusText: (Context) -> String = { it.tr(R.string.receiver_not_started) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { connect() } }
        override fun onLost(network: Network) { main.post { connect() } }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
            main.post { connect() }
        }
    }
    private val retry = object : Runnable {
        override fun run() { connect(); main.postDelayed(this, 15000) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        updateNotification()
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        registered = true
        visiblePlayer?.let { router.attach(it) }
        retry.run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (visiblePlayer == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("cast-listener", tr(R.string.cast_receiver), NotificationManager.IMPORTANCE_LOW))
        startForeground(2101, notification())
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, TvActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "cast-listener").setSmallIcon(R.drawable.tv_icon)
            .setContentTitle(this.tr(R.string.notification_title)).setContentText(this.tr(R.string.notification_text))
            .setContentIntent(pending).setOngoing(true).build()
    }

    private fun connect() {
        // The default network may be a VPN or cellular connection; neither can advertise a LAN receiver.
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@mapNotNull null
            val ip = connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ip?.let { network to it }
        }
        val lan = candidates.firstOrNull { it.second.hostAddress == address } ?: candidates.firstOrNull()
        val ip = lan?.second
        if (ip?.hostAddress == address && receiver != null) return
        disconnect()
        if (ip == null) {
            statusText = { context: Context -> context.tr(R.string.waiting_lan) }
            return
        }
        try {
            lock = getSystemService(WifiManager::class.java)?.createMulticastLock("tv-background-cast")
                ?.apply { setReferenceCounted(false); acquire() }
            val prefs = getSharedPreferences("receiver", MODE_PRIVATE)
            val id = prefs.getString("uuid", null) ?: "uuid:${UUID.randomUUID()}".also {
                prefs.edit().putString("uuid", it).apply()
            }
            val page = AdminPage(applicationContext)
            receiver = DlnaReceiver(ip, id, "Predestination TV Exo · ${Build.MODEL}", this, lan.first, page)
            admin = page
            AppLog.record("TvExoReceiver", "Listening at ${receiver?.baseUrl}")
            address = ip.hostAddress
            statusText = { context: Context -> context.tr(R.string.waiting_cast, address.orEmpty(), receiver?.baseUrl.orEmpty()) }
        } catch (error: Exception) {
            android.util.Log.d("receiver", "Startup failed: ${error.javaClass.simpleName}: ${error.message}")
            android.util.Log.w("TvCast", "Receiver startup failed on ${ip.hostAddress}", error)
            disconnect()
            statusText = if (error is java.net.SocketException &&
                generateSequence<Throwable>(error) { it.cause }.any {
                    it is android.system.ErrnoException && it.errno == android.system.OsConstants.EPERM
                }) {
                { context: Context -> context.tr(R.string.network_denied) }
            } else { context: Context -> context.tr(R.string.receiver_error, error.javaClass.simpleName) }
        }
    }

    private fun disconnect() {
        android.util.Log.d("receiver", "Disconnecting receiver; previous address=$address")
        receiver?.close()
        receiver = null
        admin = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        address = null
        statusText = { context: Context -> context.tr(R.string.waiting_network) }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask { if (instance !== this) throw UpnpError(501, "Service stopped"); block() }
        main.post(task)
        try { return task.get(3, TimeUnit.SECONDS) } catch (error: Exception) {
            task.cancel(false)
            throw (error.cause as? UpnpError ?: UpnpError(501, "Receiver unavailable"))
        }
    }

    override fun action(service: String, action: SoapAction) = onMain {
        router.action(service, action)
    }

    override fun event(service: String) = onMain { router.event(service) }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        if (registered) connectivity.unregisterNetworkCallback(callback)
        disconnect()
        super.onDestroy()
    }

    companion object {
        private var instance: CastService? = null
        private var visiblePlayer: Renderer? = null
        fun status(context: Context): String = instance?.statusText?.invoke(context)
            ?: context.tr(R.string.receiver_not_started)

        fun refreshLanguage() { instance?.updateNotification() }

        fun adminPage() = instance?.admin

        fun attach(context: Context, player: Renderer) {
            visiblePlayer = player
            context.startForegroundService(Intent(context, CastService::class.java))
            instance?.router?.attach(player)
        }

        fun detach(context: Context, player: Renderer) {
            if (visiblePlayer !== player && visiblePlayer != null) return
            instance?.router?.detach(player)
            if (visiblePlayer === player) visiblePlayer = null
            instance?.disconnect()
            context.stopService(Intent(context, CastService::class.java))
        }
    }
}
