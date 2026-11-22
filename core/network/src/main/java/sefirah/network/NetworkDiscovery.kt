package sefirah.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.isClosed
import io.ktor.utils.io.core.buildPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.io.readLine
import kotlinx.io.writeString
import sefirah.common.util.checkLocationPermissions
import sefirah.database.AppRepository
import sefirah.database.model.NetworkEntity
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.DiscoveredDevice
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.UdpBroadcast
import sefirah.domain.util.AddressUtils
import sefirah.domain.util.MessageSerializer
import sefirah.network.util.NetworkHelper
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.properties.Delegates

@Singleton
class NetworkDiscovery @Inject constructor(
    private val nsdService: NsdService,
    private val preferencesRepository: PreferencesRepository,
    private val appRepository: AppRepository,
    private val socketFactory: SocketFactory,
    private val deviceManager: DeviceManager,
    private val networkManager: NetworkManager,
    private val context: Context,
) {
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkCallbackRegistered = false
    private var discoveryJob: Job? = null
    private var rediscoveryJob: Job? = null
    private var udpSocket: BoundDatagramSocket? = null
    private var lastBroadcastTime: Long = 0
    private lateinit var udpBroadcast: UdpBroadcast
    private lateinit var udpBroadcastMessage: String
    private var tcpServerPort by Delegates.notNull<Int>()

    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob()
    private var udpPort: Int = 5149

    private var trustAllNetworks: Boolean = true

    /** Networks the platform currently reports as available; used to avoid premature teardown. */
    private val activeNetworks = mutableSetOf<Network>()

    private val _currentWifiSsid = MutableStateFlow<String?>(null)
    val currentWifiSsid: StateFlow<String?> = _currentWifiSsid.asStateFlow()

    suspend fun initialize(tcpServerPort: Int) {
        Log.i(TAG, "Initializing discovery")
        this.tcpServerPort = tcpServerPort
        val localDevice = deviceManager.localDevice
        udpBroadcast = UdpBroadcast(
            port = tcpServerPort,
            deviceId = localDevice.deviceId,
            deviceName = localDevice.deviceName,
        )
        udpBroadcastMessage = MessageSerializer.serialize(udpBroadcast)
            ?: throw Exception("Failed to serialize UDP broadcast")

        // The network callback must stay registered in both trust modes. It is the only reliable
        // signal for "the phone moved to another network", which is exactly when a reconnect is
        // needed. The trust setting only decides whether discovery actually runs.
        register()

        preferencesRepository.readTrustAllNetworks().collectLatest { trustAllNetworks ->
            this.trustAllNetworks = trustAllNetworks
            refreshWifiSsid()
            applyTrustPolicy(startIfAllowed = true)
        }
    }

    /**
     * Starts or stops discovery according to the trust setting and the current SSID.
     *
     * Discovery is also allowed whenever the location permission is missing, because the SSID
     * cannot be read without it and refusing to discover would make the app unusable.
     *
     * @return true when discovery is allowed on the current network.
     */
    private suspend fun applyTrustPolicy(startIfAllowed: Boolean): Boolean {
        val allowed = trustAllNetworks || !checkLocationPermissions(context) ||
            _currentWifiSsid.value?.let { appRepository.getNetwork(it) != null } == true

        if (allowed) {
            if (startIfAllowed) startDiscovery()
        } else {
            stopDiscovery()
        }
        return allowed
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return

        Log.d(TAG, "Starting discovery")
        discoveryJob = scope.launch {
            try {
                nsdService.advertiseService(udpBroadcast, tcpServerPort)
                if (udpSocket == null || udpSocket?.isClosed == true) {
                    udpSocket = socketFactory.udpSocket(udpPort)
                }

                launch { startDeviceListener() }
                launch { startNSDDiscovery() }

                broadcastDevice(force = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery", e)
            }
        }
        startPeriodicRediscovery()
    }

    private fun stopDiscovery() {
        try {
            if (discoveryJob?.isActive == false) return
            Log.d(TAG, "Stopping discovery")
            discoveryJob?.cancel()
            discoveryJob = null
            rediscoveryJob?.cancel()
            rediscoveryJob = null
            udpSocket?.close()
            udpSocket = null
            nsdService.stopAdvertisingService()
            nsdService.stopDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }

    /**
     * Tears discovery down and brings it back up. mDNS subscriptions and the bound UDP socket both
     * go stale when the underlying interface changes, and the platform will not recover them on its
     * own, so a network change has to rebuild them.
     */
    private suspend fun restartDiscovery() {
        Log.d(TAG, "Restarting discovery after network change")
        stopDiscovery()
        delay(NETWORK_CHANGE_SETTLE_MS)
        startDiscovery()
    }

    /**
     * Periodically re-announces this device and recycles the mDNS subscription while disconnected.
     *
     * Both discovery mechanisms are otherwise purely event driven, so a missed announcement (or a
     * dead NSD subscription) strands the app until the user manually reconnects.
     */
    private fun startPeriodicRediscovery() {
        if (rediscoveryJob?.isActive == true) return
        rediscoveryJob = scope.launch {
            while (isActive) {
                delay(REDISCOVERY_INTERVAL_MS)
                if (discoveryJob?.isActive != true) continue
                if (hasConnectedDevice()) continue

                Log.d(TAG, "Periodic rediscovery tick")
                broadcastDevice(force = true)
                nsdService.restartDiscovery()
            }
        }
    }

    private fun hasConnectedDevice(): Boolean =
        deviceManager.pairedDevices.value.any { it.connectionState.isConnectedOrConnecting }

    fun register() {
        if (isNetworkCallbackRegistered) return
        Log.d(TAG, "Registering network callback")

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        refreshWifiSsid()

        // Watch every local transport, not only Wi-Fi: disconnecting Wi-Fi to fall back to Ethernet
        // or a Wi-Fi hotspot is also a network change that needs a reconnect.
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    fun unregister() {
        if (!isNetworkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }

    /** Reads the current SSID on platforms where the network callback does not supply it. */
    private fun refreshWifiSsid() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            if (connectionInfo.supplicantState == SupplicantState.COMPLETED) {
                updateWifiSsid(connectionInfo.ssid)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read current SSID: ${e.message}")
        }
    }

    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                markAvailable(network)
                val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                deviceDiscoveryCallback(wifiInfo)
            }

            override fun onLost(network: Network) {
                handleNetworkLost(network)
            }
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                markAvailable(network)
                deviceDiscoveryCallback(readWifiInfo())
            }

            override fun onLost(network: Network) {
                handleNetworkLost(network)
            }
        }
    }

    private fun markAvailable(network: Network) {
        synchronized(activeNetworks) { activeNetworks += network }
    }

    /**
     * Only tears discovery down once *no* local network is left.
     *
     * When roaming between Wi-Fi networks the platform delivers the new network's callback before
     * the old one's `onLost`. Stopping on every `onLost` would therefore shut down the discovery
     * that the new network had just started.
     */
    private fun handleNetworkLost(network: Network) {
        val remaining = synchronized(activeNetworks) {
            activeNetworks -= network
            activeNetworks.size
        }
        Log.d(TAG, "Network lost, $remaining local network(s) remaining")
        if (remaining == 0) {
            _currentWifiSsid.value = null
            stopDiscovery()
        }
    }

    private fun readWifiInfo(): WifiInfo? = try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.connectionInfo
    } catch (e: Exception) {
        null
    }

    // Passive wifi discovery callback: rebuild discovery on the new interface, but only where the
    // trust policy allows it.
    private fun deviceDiscoveryCallback(wifiInfo: WifiInfo?) {
        scope.launch {
            try {
                if (wifiInfo != null && wifiInfo.ssid != UNKNOWN_SSID && wifiInfo.networkId != -1) {
                    updateWifiSsid(wifiInfo.ssid)
                }

                val allowed = applyTrustPolicy(startIfAllowed = false)
                if (allowed) {
                    // The interface changed underneath us; rebuild every discovery primitive and
                    // announce immediately so the peer can react instead of waiting for a tick.
                    restartDiscovery()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling network change", e)
            }
        }
    }

    suspend fun startNSDDiscovery() {
        nsdService.startDiscovery()
        nsdService.services.collectLatest { serviceInfoList ->
            serviceInfoList.forEach { serviceInfo ->
                try {
                    val attributes = serviceInfo.attributes
                    if (attributes != null) {
                        val deviceId = serviceInfo.serviceName
                        val port = attributes["serverPort"]
                            ?.let { String(it, Charsets.UTF_8).toIntOrNull() }
                            ?: serviceInfo.port

                        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
                        ) {
                            serviceInfo.hostAddresses.mapNotNull { it.hostAddress }
                        } else {
                            listOfNotNull(serviceInfo.host?.hostAddress)
                        }.mapNotNull { AddressUtils.normalizeAddress(it) }

                        if (port <= 0 || addresses.isEmpty()) return@forEach

                        when (val device = deviceManager.getDevice(deviceId)) {
                            is PairedDevice -> {
                                if (device.connectionState.isConnectedOrConnecting) return@forEach

                                val updatedAddresses = AddressUtils.merge(device.addresses, addresses)
                                val updatedDevice = device.copy(
                                    addresses = updatedAddresses,
                                    port = port,
                                )
                                networkManager.connectPaired(updatedDevice)
                            }
                            is DiscoveredDevice -> return@forEach
                            null -> {
                                val connectionDetails = ConnectionDetails(deviceId, port, addresses)
                                networkManager.connectTo(connectionDetails)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing service info", e)
                }
            }
        }
    }

    /**
     * Announces this device over UDP.
     *
     * Sent to the limited broadcast address *and* to every local subnet's directed broadcast,
     * because 255.255.255.255 follows the default route which is not necessarily the LAN the peer
     * is on. Previously known peer addresses are included for good measure.
     */
    fun broadcastDevice(force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (!force && currentTime - lastBroadcastTime < BROADCAST_RATE_LIMIT_MS) return

        lastBroadcastTime = currentTime

        scope.launch {
            try {
                if (udpSocket == null || udpSocket?.isClosed == true) {
                    udpSocket = socketFactory.udpSocket(udpPort)
                }

                val subnetBroadcasts = runCatching { NetworkHelper.subnetBroadcastAddresses() }
                    .getOrDefault(emptyList())

                val broadcastList = (
                    deviceManager.pairedDevices.value
                        .flatMap { device -> device.addresses.filter { it.isEnabled }.map { it.address } } +
                        subnetBroadcasts +
                        LIMITED_BROADCAST
                    ).distinct()

                broadcastList.forEach { hostname ->
                    try {
                        val address = InetSocketAddress(hostname, udpPort)
                        val bytePacket = buildPacket { writeString(udpBroadcastMessage) }
                        udpSocket?.send(Datagram(bytePacket, address))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to broadcast to $hostname: ${e.message?.take(30)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcasting failed", e)
            }
        }
    }

    /**
     * Listens for announcements. Kept alive across socket replacement: a network change closes the
     * bound socket, and without this the listener coroutine would die with it.
     */
    private suspend fun startDeviceListener() {
        Log.d(TAG, "Device listener started")
        var lastErrorLog = 0L

        while (currentCoroutineContext().isActive) {
            val socket = udpSocket
            if (socket == null || socket.isClosed) {
                delay(SOCKET_RETRY_DELAY_MS)
                continue
            }

            try {
                handleDatagram(socket.receive())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                val now = System.currentTimeMillis()
                if (now - lastErrorLog > ERROR_LOG_INTERVAL_MS) {
                    lastErrorLog = now
                    Log.e(TAG, "Error receiving on port $udpPort: ${e.message}", e)
                }
                delay(SOCKET_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun handleDatagram(datagram: Datagram) {
        val udpBroadcast = datagram.packet.readLine()?.let {
            MessageSerializer.deserialize(it) as? UdpBroadcast
        } ?: return

        if (udpBroadcast.deviceId == deviceManager.localDevice.deviceId) return
        Log.d(TAG, "Received UDP broadcast from ${udpBroadcast.deviceName}")

        val senderIp = NetworkHelper.senderAddress(datagram.address)
        if (senderIp == null) {
            Log.w(TAG, "Announcement from ${udpBroadcast.deviceName} has no usable IPv4 source address")
            return
        }

        when (val device = deviceManager.getDevice(udpBroadcast.deviceId)) {
            is PairedDevice -> {
                if (device.connectionState.isConnectedOrConnecting) return

                val updatedDevice = device.copy(
                    addresses = AddressUtils.merge(device.addresses, listOf(senderIp)),
                    port = udpBroadcast.port,
                )
                networkManager.connectPaired(updatedDevice)
            }
            null -> {
                val connectionDetails =
                    ConnectionDetails(udpBroadcast.deviceId, udpBroadcast.port, listOf(senderIp))
                networkManager.connectTo(connectionDetails)
            }
        }
    }

    fun updateWifiSsid(ssid: String) {
        val ssid = ssid.removeSurrounding("\"")
        when {
            ssid.equals(UNKNOWN_SSID, ignoreCase = true) -> return
            ssid.isBlank() -> return
        }

        _currentWifiSsid.value = ssid
    }

    fun saveCurrentNetworkAsTrusted() {
        scope.launch {
            _currentWifiSsid.value?.let { appRepository.addNetwork(NetworkEntity(it)) }
        }
    }

    companion object {
        private const val TAG = "NetworkDiscovery"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val BROADCAST_RATE_LIMIT_MS = 200L
        private const val LIMITED_BROADCAST = "255.255.255.255"
        private const val REDISCOVERY_INTERVAL_MS = 20_000L
        private const val NETWORK_CHANGE_SETTLE_MS = 500L
        private const val SOCKET_RETRY_DELAY_MS = 250L
        private const val ERROR_LOG_INTERVAL_MS = 5_000L
    }
}
