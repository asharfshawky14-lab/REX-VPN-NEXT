
package com.ashraf.rexvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

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

        const val ACTION_STATUS =
            "com.ashraf.rexvpn.action.SINGBOX_STATUS"

        const val EXTRA_STATUS =
            "com.ashraf.rexvpn.extra.SINGBOX_STATUS"

        const val EXTRA_MESSAGE =
            "com.ashraf.rexvpn.extra.SINGBOX_MESSAGE"

        const val STATUS_CONNECTING = "CONNECTING"
        const val STATUS_STARTED = "STARTED"
        const val STATUS_DISCONNECTED = "DISCONNECTED"
        const val STATUS_FAILED = "FAILED"

        fun connectIntent(
            context: Context,
            configJson: String
        ): Intent = Intent(context, RexSingBoxService::class.java).apply {
            action = ACTION_CONNECT
            putExtra(EXTRA_CONFIG, configJson)
        }

        fun disconnectIntent(context: Context): Intent =
            Intent(context, RexSingBoxService::class.java).apply {
                action = ACTION_DISCONNECT
            }
    }

    private var engine: SingBoxEngine? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var connecting = false

    private val connectivityManager: ConnectivityManager
        get() = getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
    private fun reportStatus(status: String, message: String) {
        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }

        sendBroadcast(statusIntent)
        Log.i(TAG, "Status reported: $status - $message")
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
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
                    reportStatus(
                        STATUS_FAILED,
                        "إعدادات sing-box فارغة"
                    )
                    Log.e(TAG, "Missing sing-box configuration")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                reportStatus(
                    STATUS_CONNECTING,
                    "جاري تشغيل sing-box"
                )

                try {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("جاري تشغيل الاتصال")
                    )

                    startSingBox(config)

                    reportStatus(
                        STATUS_STARTED,
                        "بدأت الخدمة؛ لم يتم تأكيد وصول الإنترنت بعد"
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to start sing-box", error)
                    stopSingBox()

                    reportStatus(
                        STATUS_FAILED,
                        error.message ?: "فشل تشغيل sing-box"
                    )

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }

            ACTION_DISCONNECT -> {
                stopSingBox()

                reportStatus(
                    STATUS_DISCONNECTED,
                    "تم فصل sing-box"
                )

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
        check(!connecting) { "sing-box is already starting" }

        // Close any previous session before marking a new one as starting.
        stopSingBox()
        connecting = true

        try {
            val newEngine = SingBoxEngine(this)
            engine = newEngine
            newEngine.connect(configJson)

            Log.i(TAG, "sing-box start request completed")
        } catch (error: Exception) {
            runCatching { engine?.disconnect() }
                .onFailure {
                    Log.e(TAG, "Engine cleanup failed", it)
                }
            engine = null

            runCatching { tunDescriptor?.close() }
                .onFailure {
                    Log.e(TAG, "TUN cleanup failed", it)
                }
            tunDescriptor = null

            throw error
        } finally {
            connecting = false
        }
    }

    @Synchronized
    private fun stopSingBox() {
        val oldEngine = engine
        engine = null

        runCatching { oldEngine?.disconnect() }
            .onFailure {
                Log.e(TAG, "Engine shutdown failed", it)
            }

        runCatching { tunDescriptor?.close() }
            .onFailure {
                Log.e(TAG, "TUN close failed", it)
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
        super.onDestroy()
    }

    // VPN tunnel

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(this) == null) {
            "VPN permission has not been granted"
        }
        check(options.mtu > 0) { "Invalid VPN MTU" }

        val builder = Builder()
            .setSession("REX VPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val ipv4Addresses = options.inet4Address
        while (ipv4Addresses.hasNext()) {
            val address = ipv4Addresses.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val ipv6Addresses = options.inet6Address
        while (ipv6Addresses.hasNext()) {
            val address = ipv6Addresses.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServers = options.dnsServerAddress
                while (dnsServers.hasNext()) {
                    builder.addDnsServer(dnsServers.next())
                }
            }

            val ipv4Routes = options.inet4RouteRange
            while (ipv4Routes.hasNext()) {
                val route = ipv4Routes.next()
                builder.addRoute(route.address(), route.prefix())
            }

            val ipv6Routes = options.inet6RouteRange
            while (ipv6Routes.hasNext()) {
                val route = ipv6Routes.next()
                builder.addRoute(route.address(), route.prefix())
            }

            val includedPackages = options.includePackage
            while (includedPackages.hasNext()) {
                val packageName = includedPackages.next()
                try {
                    builder.addAllowedApplication(packageName)
                } catch (error: PackageManager.NameNotFoundException) {
                    Log.w(
                        TAG,
                        "Included package not found: $packageName",
                        error
                    )
                }
            }

            val excludedPackages = options.excludePackage
            while (excludedPackages.hasNext()) {
                val packageName = excludedPackages.next()
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (error: PackageManager.NameNotFoundException) {
                    Log.w(
                        TAG,
                        "Excluded package not found: $packageName",
                        error
                    )
                }
            }
        }

        val descriptor = builder.establish()
            ?: error("Android could not establish the VPN tunnel")

        tunDescriptor = descriptor
        Log.i(TAG, "TUN established")
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protect(fd)) {
            "Unable to protect socket from VPN routing"
        }
    }

    override fun useProcFS(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Connection owner lookup requires Android 10 or later"
        }

        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )

        check(uid >= 0) { "Connection owner not found" }

        return ConnectionOwner().apply {
            userId = uid
            userName = packageManager.getPackagesForUid(uid)
                ?.firstOrNull() ?: ""
        }
    }

    // Network interface information

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
        val interfaces =
            mutableListOf<io.nekohasekai.libbox.NetworkInterface>()

        try {
            val networkInterfaces = JavaNetworkInterface.getNetworkInterfaces()

            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if (!networkInterface.isUp) continue

                val item = io.nekohasekai.libbox.NetworkInterface()
                item.name = networkInterface.name
                item.index = networkInterface.index

                runCatching { item.mtu = networkInterface.mtu }

                val addresses = networkInterface.interfaceAddresses
                    .mapNotNull { entry ->
                        val address = entry.address ?: return@mapNotNull null
                        val host = address.hostAddress ?: return@mapNotNull null
                        "$host/${entry.networkPrefixLength}"
                    }

                item.addresses = StringArray(addresses)
                item.dnsServer = StringArray(emptyList())
                item.gateway = StringArray(emptyList())
                item.type = Libbox.InterfaceTypeOther
                item.flags = 1
                item.metered = false

                interfaces.add(item)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to enumerate network interfaces", error)
        }

        val iterator = interfaces.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): io.nekohasekai.libbox.NetworkInterface =
                iterator.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
        Log.d(TAG, "DNS cache clear requested")
    }

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
        Log.d(TAG, "Neighbor monitoring is not implemented")
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
        Log.d(TAG, "Neighbor monitoring closed")
    }

    override fun registerMyInterface(name: String?) {
        Log.d(TAG, "Interface registration requested: $name")
    }

    // Unsupported optional platform features

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("Platform shell is not supported")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession {
        error("Platform shell sessions are not supported")
    }

    override fun readSystemSSHHostKey(): String {
        error("System SSH host key lookup is not supported")
    }

    override fun lookupSFTPServer(): String {
        error("SFTP server lookup is not supported")
    }

    override fun tailscaleHostname(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession {
        error("Platform bridge is not supported")
    }

    override fun lookupUser(username: String?): PlatformUser {
        error("Platform user lookup is not supported")
    }

    // libbox notifications

    override fun sendNotification(
        notification: io.nekohasekai.libbox.Notification
    ) {
        Log.d(TAG, "sing-box notification received")
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        Log.d(TAG, "sing-box notification cancelled: $identifier")
    }

    // Android foreground notification

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

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        message: String
    ): android.app.Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("REX VPN")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .build()
    }

    private class StringArray(
        private val values: List<String>
    ) : StringIterator {
        private var index = 0

        override fun len(): Int = values.size

        override fun hasNext(): Boolean = index < values.size

        override fun next(): String = values[index++]
    }
}
