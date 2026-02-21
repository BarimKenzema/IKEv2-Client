package com.ikev2client

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ikev2client.model.VpnProfile
import java.util.Timer
import java.util.TimerTask

class VpnConnectionService : Service() {

    companion object {
        const val TAG = "VpnService"
        const val ACTION_MONITOR = "MONITOR"
        const val ACTION_DISCONNECT = "DISCONNECT"
        const val EXTRA_PROFILE_JSON = "profile_json"
        const val CONNECTION_TIMEOUT_MS = 20000L

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expiryTimer: Timer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var timeoutRunnable: Runnable? = null
    private var currentProfile: VpnProfile? = null

    inner class LocalBinder : Binder() {
        fun getService(): VpnConnectionService = this@VpnConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MONITOR -> {
                val json = intent.getStringExtra(EXTRA_PROFILE_JSON)
                if (json != null) {
                    val profile = Gson().fromJson(json, VpnProfile::class.java)
                    startMonitoring(profile)
                }
            }
            ACTION_DISCONNECT -> {
                stopMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring(profile: VpnProfile) {
        currentProfile = profile
        currentProfileId = profile.id
        isRunning = true
        updateState(ConnectionState.CONNECTING, null)

        startForeground(1, createNotification("Connecting to ${profile.name}..."))

        // Register network callback to detect VPN
        registerVpnCallback()

        // Set connection timeout
        timeoutRunnable = Runnable {
            if (connectionState == ConnectionState.CONNECTING) {
                Log.w(TAG, "Connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
                updateState(
                    ConnectionState.ERROR,
                    "Connection timed out. Check server address, credentials, and certificate."
                )
                stopMonitoring()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, CONNECTION_TIMEOUT_MS)

        // Also check immediately if VPN is already up
        mainHandler.postDelayed({
            if (connectionState == ConnectionState.CONNECTING && isVpnActive()) {
                onVpnConnected()
            }
        }, 2000)

        // Start expiry monitoring
        startExpiryMonitor(profile)

        Log.d(TAG, "Started monitoring VPN for ${profile.server}")
    }

    private fun registerVpnCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return

        // Clean up previous callback
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    mainHandler.post { onVpnConnected() }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (connectionState != ConnectionState.CONNECTED) {
                        mainHandler.post { onVpnConnected() }
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                mainHandler.post {
                    if (connectionState == ConnectionState.CONNECTED) {
                        updateState(ConnectionState.DISCONNECTED, "VPN disconnected")
                        stopMonitoring()
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        cm.registerNetworkCallback(request, networkCallback!!)
        Log.d(TAG, "VPN network callback registered")
    }

    private fun onVpnConnected() {
        if (connectionState == ConnectionState.CONNECTED) return

        Log.d(TAG, "VPN connected!")

        // Cancel timeout
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        updateState(ConnectionState.CONNECTED, null)
        updateNotification("Connected to ${currentProfile?.name ?: "VPN"}")
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun startExpiryMonitor(profile: VpnProfile) {
        expiryTimer?.cancel()
        expiryTimer = Timer()
        expiryTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (profile.isExpired()) {
                    Log.w(TAG, "Profile expired, disconnecting")
                    mainHandler.post {
                        updateState(ConnectionState.ERROR, "Profile expired")
                        stopMonitoring()
                    }
                }
            }
        }, 30000, 30000)
    }

    fun stopMonitoring() {
        updateState(ConnectionState.DISCONNECTING, null)
        isRunning = false

        // Cancel timeout
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        // Cancel expiry timer
        expiryTimer?.cancel()
        expiryTimer = null

        // Unregister network callback
        networkCallback?.let {
            try {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering callback", e)
            }
        }
        networkCallback = null

        // Stop VPN via VpnManager
        try {
            val vpnManager = getSystemService(VpnManager::class.java)
            vpnManager?.stopProvisionedVpnProfile()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }

        // Delete provisioned profile
        try {
            val vpnManager = getSystemService(VpnManager::class.java)
            vpnManager?.deleteProvisionedVpnProfile()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting VPN profile", e)
        }

        currentProfileId = null
        currentProfile = null
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
        stopMonitoring()
        super.onDestroy()
    }
}
