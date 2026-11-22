package sefirah.network

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.RequiresExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sefirah.domain.model.UdpBroadcast
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NsdService @Inject constructor(val context: Context) {
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _services = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val services: StateFlow<List<NsdServiceInfo>> = _services.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var currentServiceID: String? = null

    /** Resolved services, keyed by instance name. Instance names are the peer device ids. */
    private val resolvedServices = mutableMapOf<String, NsdServiceInfo>()

    private var multicastLock: WifiManager.MulticastLock? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startDiscovery() {
        try {
            if (discoveryListener != null) return

            Log.d(TAG, "Starting mDNS discovery")
            acquireMulticastLock()
            discoveryListener = createDiscoveryListener()
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error in startDiscovery: ${e.message}", e)
        }
    }

    fun stopDiscovery() {
        try {
            val listener = discoveryListener ?: return

            nsdManager.stopServiceDiscovery(listener)
            releaseMulticastLock()
            discoveryListener = null
            Log.d(TAG, "Stopped service discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error stopping discovery: ${e.message}", e)
        }
    }

    /**
     * Restarts the discovery subscription, keeping the resolved cache so already-known peers are
     * not re-resolved from scratch.
     *
     * The platform NSD daemon does not survive every interface change: the subscription can become
     * permanently silent while still reporting itself as active. The only recovery is to drop the
     * listener and register a new one.
     */
    fun restartDiscovery() {
        try {
            val listener = discoveryListener
            if (listener != null) {
                runCatching { nsdManager.stopServiceDiscovery(listener) }
                    .onFailure { Log.w(TAG, "stopServiceDiscovery during restart failed: ${it.message}") }
                releaseMulticastLock()
                discoveryListener = null
            }
            startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting discovery: ${e.message}", e)
        }
    }

    fun advertiseService(broadcast: UdpBroadcast, port: Int) {
        try {
            if (registrationListener != null) {
                stopAdvertisingService()
            }

            currentServiceID = broadcast.deviceId

            val serviceInfo = NsdServiceInfo().also {
                it.serviceType = SERVICE_TYPE
                it.serviceName = currentServiceID
                it.port = port
            }
            serviceInfo.setAttribute("deviceName", broadcast.deviceName)
            serviceInfo.setAttribute("serverPort", port.toString())

            registrationListener = createRegistrationListener()
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error advertising service: ${e.message}")
        }
    }

    fun stopAdvertisingService() {
        try {
            if (registrationListener != null)
                nsdManager.unregisterService(registrationListener)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error stopping service advertisement: ${e.message}")
        }
        registrationListener = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val lock = multicastLock ?: wifiManager
                .createMulticastLock("SefirahMulticastLock")
                .also { multicastLock = it }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }
    }

    private fun publish(serviceInfo: NsdServiceInfo) {
        resolvedServices[serviceInfo.serviceName] = serviceInfo
        _services.value = resolvedServices.values.toList()
    }

    private fun remove(serviceName: String) {
        resolvedServices.remove(serviceName)
        _services.value = resolvedServices.values.toList()
    }

    private fun createRegistrationListener(): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Service registration failed: Error code: $errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: Error code: $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service registered successfully: $serviceInfo")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service unregistered successfully: $serviceInfo")
            }
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Ignore own service
                if (service.serviceName == currentServiceID) return

                Log.d(TAG, "Service found: $service")
                if (!service.serviceType.startsWith(SERVICE_TYPE_NO_DOT)) return

                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: $service")
                remove(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start: Error code: $errorCode")
                // A failed start leaves the listener registered but dead; clear it so the next
                // restart actually tries again.
                discoveryListener = null
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop: Error code: $errorCode")
            }
        }
    }

    private fun resolve(service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
        ) {
            // The callback API reuses a single registered callback, so it has to be unregistered
            // before the next service can be registered. Same 500ms grace period the platform
            // needs between unregister and register on the same NsdManager.
            runCatching { nsdManager.unregisterServiceInfoCallback(serviceInfoCallback) }
            mainHandler.postDelayed({
                try {
                    nsdManager.registerServiceInfoCallback(service, executor, serviceInfoCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering ServiceInfoCallback: ${e.message}")
                }
            }, RESOLVE_DELAY_MS)
        } else {
            try {
                nsdManager.resolveService(service, createResolveListener())
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving service: ${e.message}")
            }
        }
    }

    /**
     * Returns a new listener instance since NsdManager wants a different listener each time you call resolveService
     */
    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "MDNS Resolve failed: Error code: $errorCode")
                // Resolution failure often means the queue is backed up; retry shortly.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    mainHandler.postDelayed({ resolve(serviceInfo) }, RESOLVE_DELAY_MS)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "MDNS Resolved successfully $serviceInfo")
                publish(serviceInfo)
            }
        }
    }

    // ServiceInfoCallback for API level 34+
    private val serviceInfoCallback by lazy {
        @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = 7)
        object : NsdManager.ServiceInfoCallback {
            private var currentMonitoredService: NsdServiceInfo? = null

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.e(TAG, "ServiceInfoCallback registration failed: $errorCode")
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service updated: $serviceInfo")
                currentMonitoredService = serviceInfo
                publish(serviceInfo)
            }

            override fun onServiceLost() {
                Log.d(TAG, "Service lost")
                currentMonitoredService?.let { remove(it.serviceName) }
            }

            override fun onServiceInfoCallbackUnregistered() {
                Log.d(TAG, "ServiceInfoCallback unregistered")
                currentMonitoredService = null
            }
        }
    }

    companion object {
        private const val TAG = "NsdService"
        private const val SERVICE_TYPE = "_sefirah._udp."
        private const val SERVICE_TYPE_NO_DOT = "_sefirah._udp"
        private const val RESOLVE_DELAY_MS = 500L
    }
}
