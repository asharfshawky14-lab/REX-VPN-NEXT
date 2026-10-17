package com.ashraf.rexvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInterface as AndroidNetworkInterface
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborEntryIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress

class RexSingBoxService : VpnService(), PlatformInterface {

    companion object {
        private const val TAG = "REX-SINGBOX"
        private const val CHANNEL_ID = "rex_singbox_vpn"
        private const val NOTIFICATION_ID = 4831

        const val ACTION_CONNECT =
            "com.ashraf.rexvpn.action.SINGBOX_CONNECT"

        const val ACTION_DISCONNECT =
            "com.ashraf.rexvpn.action.SINGBOX_DISCONNECT"

        const val EXTRA_CONFIG =
            "com.ashraf.rexvpn.extra.SINGBOX_CONFIG"

        fun connectIntent(
            context: Context,
            configJson: String
        ): Intent {
            return Intent(context, RexSingBoxService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, configJson)
            }
        }

        fun disconnectIntent(context: Context): Intent {
            return Intent(context, RexSingBoxService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        }
    }

    private var engine: SingBoxEngine? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var connecting = false

    private val connectivityManager: ConnectivityManager
        get() = getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "REX sing-box service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)

                if (config.isNullOrBlank()) {
                    Log.e(TAG, "Missing sing-box configuration")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("جاري تشغيل الاتصال")
                )

                try {
                    startSingBox(config)
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to start sing-box", error)
                    stopSingBox()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }

            ACTION_DISCONNECT -> {
                stopSingBox()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }

            else -> {
                Log.w(TAG, "Unknown service action")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    @Synchronized
    private fun startSingBox(configJson: String) {
        check(!connecting) {
            "sing-box is already starting"
        }

        connecting = true

        try {
            stopSingBox()

            val newEngine = SingBoxEngine(this)
            engine = newEngine

            newEngine.connect(configJson)

            Log.i(TAG, "sing-box started successfully")
        } catch (error: Exception) {
            engine?.disconnect()
            engine = null

            tunDescriptor?.let {
                runCatching { it.close() }
            }
            tunDescriptor = null

            throw error
        } finally {
            connecting = false
        }
    }

    @Synchronized
    private fun stopSingBox() {
        val currentEngine = engine
        engine = null

        try {
            currentEngine?.disconnect()
        } catch (error: Exception) {
            Log.e(TAG, "Error stopping sing-box engine", error)
        }

        tunDescriptor?.let {
            try {
                it.close()
            } catch (error: Exception) {
                Log.w(TAG, "Error closing TUN descriptor", error)
            }
        }

        tunDescriptor = null
        connecting = false

        Log.i(TAG, "sing-box stopped")
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopSingBox()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopSingBox()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "REX sing-box service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------
    // VPN tunnel
    // ---------------------------------------------------------

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(this) == null) {
            "VPN permission has not been granted"
        }

        check(options.mtu > 0) {
            "Invalid VPN MTU"
        }

        val builder = Builder()
            .setSession("REX VPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val ipv4Addresses = options.inet4Address
        while (ipv4Addresses.hasNext()) {
            val address = ipv4Addresses.next()
            builder.addAddress(
                address.address(),
                address.prefix()
            )
        }

        val ipv6Addresses = options.inet6Address
        while (ipv6Addresses.hasNext()) {
            val address = ipv6Addresses.next()
            builder.addAddress(
                address.address(),
                address.prefix()
            )
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServers = options.dnsServerAddress

                while (dnsServers.hasNext()) {
                    builder.addDnsServer(dnsServers.next())
                }
            }

            // IPv4 routes
            val ipv4Routes = options.inet4RouteRange
            while (ipv4Routes.hasNext()) {
                val route = ipv4Routes.next()
                builder.addRoute(
                    route.address(),
                    route.prefix()
                )
            }

            // IPv6 routes
            val ipv6Routes = options.inet6RouteRange
            while (ipv6Routes.hasNext()) {
                val route = ipv6Routes.next()
                builder.addRoute(
                    route.address(),
                    route.prefix()
                )
            }

            // Applications explicitly included in the VPN
            val includedPackages = options.includePackage
            while (includedPackages.hasNext()) {
                val packageName = includedPackages.next()

                try {
                    builder.addAllowedApplication(packageName)
                } catch (error: PackageManager.NameNotFoundException) {
                    Log.w(
                        TAG,
                        "Included application not found: $packageName",
                        error
                    )
                }
            }

            // Applications explicitly excluded from the VPN
            val excludedPackages = options.excludePackage
            while (excludedPackages.hasNext()) {
                val packageName = excludedPackages.next()

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (error: PackageManager.NameNotFoundException) {
                    Log.w(
                        TAG,
                        "Excluded application not found: $packageName",
                        error
                    )
                }
            }
        }

        val descriptor = builder.establish()
            ?: error("Android could not establish the VPN tunnel")

        tunDescriptor = descriptor

        Log.i(TAG, "Android TUN established")
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        return true
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protect(fd)) {
            "Unable to protect socket from VPN routing"
        }
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )

        check(uid >= 0) {
            "Android could not identify the connection owner"
        }

        val owner = ConnectionOwner()
        owner.userId = uid
        owner.userName =
            packageManager.getPackagesForUid(uid)
                ?.firstOrNull() ?: ""

        return owner
    }

    // ---------------------------------------------------------
    // Network interface monitoring
    // ---------------------------------------------------------

    override fun startDefaultInterfaceMonitor(
        listener: InterfaceUpdateListener
    ) {
        Log.d(TAG, "Default interface monitor requested")
    }

    override fun closeDefaultInterfaceMonitor(
        listener: InterfaceUpdateListener
    ) {
        Log.d(TAG, "Default interface monitor closed")
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<
            io.nekohasekai.libbox.NetworkInterface
        >()

        try {
            val androidInterfaces =
                AndroidNetworkInterface.getNetworkInterfaces()

            while (androidInterfaces.hasMoreElements()) {
                val androidInterface =
                    androidInterfaces.nextElement()

                if (!androidInterface.isUp) continue

                val item =
                    io.nekohasekai.libbox.NetworkInterface()

                item.name = androidInterface.name
                item.index = androidInterface.index

                try {
                    item.mtu = androidInterface.mtu
                } catch (_: Exception) {
                    // MTU is optional interface metadata.
                }

                item.addresses = StringArray(
                    androidInterface.interfaceAddresses.mapNotNull {
                        val address = it.address ?: return@mapNotNull null
                        val host = address.hostAddress
                            ?: return@mapNotNull null

                        "$host/${it.networkPrefixLength}"
                    }.iterator()
                )

                item.dnsServer = StringArray(emptyList<String>().iterator())
                item.dnsSearchDomain = StringArray(emptyList<String>().iterator())
                item.gateway = StringArray(emptyList<String>().iterator())
                item.type = Libbox.InterfaceTypeOther
                item.flags = 0
                item.metered = false

                interfaces.add(item)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to enumerate network interfaces", error)
        }

        val iterator = interfaces.iterator()

        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): io.nekohasekai.libbox.NetworkInterface {
                return iterator.next()
            }
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
        Log.d(TAG, "DNS cache clear requested")
    }

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(
        listener: NeighborUpdateListener?
    ) {
        Log.d(TAG, "Neighbor monitoring is not implemented")
    }

    override fun closeNeighborMonitor(
        listener: NeighborUpdateListener?
    ) {
        Log.d(TAG, "Neighbor monitoring closed")
    }

    override fun registerMyInterface(name: String?) {
        Log.d(TAG, "Interface registration requested: $name")
    }

    // ---------------------------------------------------------
    // Optional platform features not supported by this service
    // ---------------------------------------------------------

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("Platform shell is not supported by REX VPN")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession {
        error("Platform shell sessions are not supported by REX VPN")
    }

    override fun readSystemSSHHostKey(): String {
        error("System SSH host key lookup is not supported")
    }

    override fun lookupSFTPServer(): String {
        error("SFTP server lookup is not supported")
    }

    override fun tailscaleHostname(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(
        options: BridgeOptions?
    ): BridgeSession {
        error("Platform bridge is not supported by REX VPN")
    }

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirect(
        options: ByteArray?,
        handler: AutoRedirectHandler?
    ): AutoRedirectSession {
        error("Platform auto-redirect is not supported by REX VPN")
    }

    override fun lookupUser(username: String?): PlatformUser {
        error("Platform user lookup is not supported by REX VPN")
    }

    // ---------------------------------------------------------
    // Notification
    // ---------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "REX VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "حالة اتصال REX VPN"
                setShowBadge(false)
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        message: String
    ): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("REX VPN")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private class StringArray(
        private val iterator: Iterator<String>
    ) : StringIterator {

        override fun len(): Int = 0

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()
    }
}
