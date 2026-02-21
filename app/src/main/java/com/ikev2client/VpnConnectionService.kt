package com.ikev2client

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ikev2client.model.VpnProfile
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Timer
import java.util.TimerTask

class VpnConnectionService : Service() {

    companion object {
        const val TAG = "VpnService"
        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"
        const val EXTRA_PROFILE_JSON = "profile_json"

        var isRunning = false
            private set
        var currentProfileId: String? = null
            private set
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
            private set
        var stateListener: ((ConnectionState, String?) -> Unit)? = null
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }

    private val binder = LocalBinder()
    private var expiryTimer: Timer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    inner class LocalBinder : Binder() {
        fun getService(): VpnConnectionService = this@VpnConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val json = intent.getStringExtra(EXTRA_PROFILE_JSON)
                if (json != null) {
                    val profile = Gson().fromJson(json, VpnProfile::class.java)
                    startConnection(profile)
                }
            }
            ACTION_DISCONNECT -> stopConnection()
        }
        return START_STICKY
    }

    private fun startConnection(profile: VpnProfile) {
        if (profile.isExpired()) {
            updateState(ConnectionState.ERROR, "Profile expired")
            stopSelf()
            return
        }

        updateState(ConnectionState.CONNECTING, null)
        currentProfileId = profile.id
        isRunning = true

        startForeground(1, createNotification("Connecting to ${profile.name}..."))

        Thread {
            try {
                connectIkev2(profile)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                mainHandler.post {
                    updateState(ConnectionState.ERROR, e.message ?: "Connection failed")
                }
            }
        }.start()
    }

    private fun connectIkev2(profile: VpnProfile) {
        val vpnManager = getSystemService(VpnManager::class.java)
        if (vpnManager == null) {
            throw Exception("VpnManager not available on this device")
        }

        // Build IKEv2 profile
        val builder = Ikev2VpnProfile.Builder(profile.server, profile.remoteId)

        // Parse CA certificate if provided
        var caCert: X509Certificate? = null
        if (!profile.caCert.isNullOrBlank()) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                caCert = cf.generateCertificate(
                    ByteArrayInputStream(profile.caCert.toByteArray(Charsets.UTF_8))
                ) as X509Certificate
                Log.d(TAG, "CA certificate parsed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "CA cert parse failed, will use system trust store", e)
                caCert = null
            }
        }

        // Set authentication method
        when (profile.authMethod) {
            "psk" -> {
                val pskBytes = (profile.psk ?: profile.password).toByteArray(Charsets.UTF_8)
                builder.setAuthPsk(pskBytes)
                Log.d(TAG, "Using PSK authentication")
            }
            else -> {
                // EAP-MSCHAPv2 or other EAP methods
                builder.setAuthUsernamePassword(
                    profile.username,
                    profile.password,
                    caCert  // null = use system trust store
                )
                Log.d(TAG, "Using EAP username/password authentication")
            }
        }

        // Set MTU
        builder.setMaxMtu(profile.mtu)

        // Set bypassable (split tunneling)
        builder.setBypassable(profile.splitTunneling)

        // Build the profile
        val ikev2Profile = builder.build()
        Log.d(TAG, "IKEv2 profile built for ${profile.server}")

        // Provision the profile (may show system consent dialog first time)
        try {
            vpnManager.provisionVpnProfile(ikev2Profile)
            Log.d(TAG, "VPN profile provisioned successfully")
        } catch (e: SecurityException) {
            throw Exception("VPN consent not given. Please approve the VPN dialog and try again.")
        }

        // Start the VPN connection
        try {
            vpnManager.startProvisionedVpnProfile()
            Log.d(TAG, "VPN start requested")
        } catch (e: SecurityException) {
            throw Exception("Cannot start VPN. Please check permissions.")
        } catch (e: IllegalStateException) {
            throw Exception("No VPN profile provisioned. Please try connecting again.")
        }

        // Monitor VPN connection state
        startVpnMonitor()

        // Monitor profile expiry
        startExpiryMonitor(profile)

        // Give the system a moment to establish, then check
        mainHandler.postDelayed({
            if (connectionState == ConnectionState.CONNECTING) {
                // Check if VPN is actually up
                if (isVpnActive()) {
                    updateState(ConnectionState.CONNECTED, null)
                    updateNotification("Connected to ${profile.name}")
                }
            }
        }, 3000)
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun startVpnMonitor() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        // Unregister previous callback if any
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    Log.d(TAG, "VPN network available")
                    mainHandler.post {
                        updateState(ConnectionState.CONNECTED, null)
                        updateNotification("Connected")
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                mainHandler.post {
                    if (isRunning && connectionState == ConnectionState.CONNECTED) {
                        updateState(ConnectionState.DISCONNECTED, "VPN disconnected")
                        stopConnection()
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (connectionState != ConnectionState.CONNECTED) {
                        mainHandler.post {
                            updateState(ConnectionState.CONNECTED, null)
                            updateNotification("Connected")
                        }
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()

        cm.registerNetworkCallback(request, networkCallback!!)
        Log.d(TAG, "VPN network monitor registered")
    }

    private fun startExpiryMonitor(profile: VpnProfile) {
        expiryTimer?.cancel()
        expiryTimer = Timer()
        expiryTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (profile.isExpired()) {
                    Log.w(TAG, "Profile expired, disconnecting")
                    mainHandler.post { stopConnection() }
                }
            }
        }, 30000, 30000)
    }

    fun stopConnection() {
        updateState(ConnectionState.DISCONNECTING, null)
        isRunning = false
        expiryTimer?.cancel()
        expiryTimer = null

        // Unregister network callback
        networkCallback?.let {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        networkCallback = null

        // Stop VPN via VpnManager
        try {
            val vpnManager = getSystemService(VpnManager::class.java)
            vpnManager?.stopProvisionedVpnProfile()
            Log.d(TAG, "VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }

        try {
            val vpnManager = getSystemService(VpnManager::class.java)
            vpnManager?.deleteProvisionedVpnProfile()
            Log.d(TAG, "VPN profile deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting VPN profile", e)
        }

        currentProfileId = null
        updateState(ConnectionState.DISCONNECTED, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(state: ConnectionState, msg: String?) {
        connectionState = state
        stateListener?.invoke(state, msg)
        Log.d(TAG, "State: $state ${msg ?: ""}")
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_VPN)
            .setContentTitle("IKEv2 VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(1, createNotification(text))
    }

    override fun onDestroy() {
        stopConnection()
        super.onDestroy()
    }
}
