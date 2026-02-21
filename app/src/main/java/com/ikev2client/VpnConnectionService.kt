package com.ikev2client

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ikev2client.model.VpnProfile
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import java.util.Timer
import java.util.TimerTask

class VpnConnectionService : VpnService() {

    companion object {
        const val TAG = "VpnConnectionService"
        const val ACTION_CONNECT = "com.ikev2client.CONNECT"
        const val ACTION_DISCONNECT = "com.ikev2client.DISCONNECT"
        const val EXTRA_PROFILE_JSON = "profile_json"

        var isRunning = false
            private set
        var currentProfileId: String? = null
            private set
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
            private set

        var stateListener: ((ConnectionState, String?) -> Unit)? = null

        fun prepareVpn(context: Context): Intent? {
            return VpnService.prepare(context)
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    private val binder = LocalBinder()
    private var expiryTimer: Timer? = null
    private var currentProfile: VpnProfile? = null

    inner class LocalBinder : Binder() {
        fun getService(): VpnConnectionService = this@VpnConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileJson = intent.getStringExtra(EXTRA_PROFILE_JSON)
                if (profileJson != null) {
                    val profile = Gson().fromJson(profileJson, VpnProfile::class.java)
                    startConnection(profile)
                }
            }
            ACTION_DISCONNECT -> {
                stopConnection()
            }
        }
        return START_STICKY
    }

    private fun startConnection(profile: VpnProfile) {
        // Check expiry first
        if (profile.isExpired()) {
            updateState(ConnectionState.ERROR, "Profile has expired")
            stopSelf()
            return
        }

        updateState(ConnectionState.CONNECTING, null)
        currentProfileId = profile.id
        currentProfile = profile
        isRunning = true

        startForeground(1, createNotification("Connecting to ${profile.name}..."))

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                connectWithPlatformApi(profile)
            } else {
                updateState(ConnectionState.ERROR, "Android 11+ required for IKEv2")
                stopConnection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            updateState(ConnectionState.ERROR, e.message ?: "Connection failed")
            stopConnection()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun connectWithPlatformApi(profile: VpnProfile) {
        try {
            val vpnManager = getSystemService(Context.VPN_MANAGEMENT_SERVICE) as? VpnManager

            // Build IKEv2 profile using Android's built-in API
            val builder = Ikev2VpnProfile.Builder(profile.server, profile.remoteId)

            // Set authentication
            when (profile.authMethod) {
                "eap-mschapv2", "eap-md5", "eap" -> {
                    builder.setAuthUsernamePassword(
                        profile.username,
                        profile.password,
                        null  // server root CA cert — null means system trust store
                    )
                }
                "psk" -> {
                    if (!profile.psk.isNullOrBlank()) {
                        builder.setAuthPsk(profile.psk.toByteArray())
                    } else {
                        builder.setAuthPsk(profile.password.toByteArray())
                    }
                }
                else -> {
                    builder.setAuthUsernamePassword(
                        profile.username,
                        profile.password,
                        null
                    )
                }
            }

            // Set CA certificate if provided
            if (!profile.caCert.isNullOrBlank()) {
                try {
                    val certFactory = CertificateFactory.getInstance("X.509")
                    val certBytes = profile.caCert.toByteArray()
                    val cert = certFactory.generateCertificate(
                        ByteArrayInputStream(certBytes)
                    ) as X509Certificate

                    // Rebuild with server CA
                    val builderWithCa = Ikev2VpnProfile.Builder(profile.server, profile.remoteId)
                    builderWithCa.setAuthUsernamePassword(
                        profile.username,
                        profile.password,
                        cert
                    )
                    builderWithCa.setMaxMtu(profile.mtu)
                    builderWithCa.setBypassable(profile.splitTunneling)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ features if available
                    }

                    val ikev2Profile = builderWithCa.build()
                    vpnManager?.provisionVpnProfile(ikev2Profile)

                    Log.d(TAG, "IKEv2 profile provisioned with custom CA")
                } catch (certError: Exception) {
                    Log.w(TAG, "Failed to parse CA cert, using system trust store", certError)
                    // Fall through to use without custom CA
                    builder.setMaxMtu(profile.mtu)
                    builder.setBypassable(profile.splitTunneling)
                    val ikev2Profile = builder.build()
                    vpnManager?.provisionVpnProfile(ikev2Profile)
                }
            } else {
                builder.setMaxMtu(profile.mtu)
                builder.setBypassable(profile.splitTunneling)
                val ikev2Profile = builder.build()
                vpnManager?.provisionVpnProfile(ikev2Profile)
            }

            // Start the VPN
            vpnManager?.startProvisionedVpnProfile()

            updateState(ConnectionState.CONNECTED, null)
            updateNotification("Connected to ${profile.name}")
            Log.d(TAG, "IKEv2 VPN connected to ${profile.server}")

            // Start expiry monitoring
            startExpiryMonitor(profile)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - VPN permission may be needed", e)
            updateState(ConnectionState.ERROR, "VPN permission denied: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid VPN profile configuration", e)
            updateState(ConnectionState.ERROR, "Invalid config: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            updateState(ConnectionState.ERROR, "Connection failed: ${e.message}")
        }
    }

    private fun startExpiryMonitor(profile: VpnProfile) {
        expiryTimer?.cancel()
        expiryTimer = Timer()
        expiryTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (profile.isExpired()) {
                    Log.w(TAG, "Profile expired during connection")
                    android.os.Handler(mainLooper).post {
                        updateState(ConnectionState.DISCONNECTING, "Profile expired")
                        stopConnection()
                    }
                }
            }
        }, 30_000, 30_000) // Check every 30 seconds
    }

    fun stopConnection() {
        updateState(ConnectionState.DISCONNECTING, null)
        isRunning = false
        expiryTimer?.cancel()
        expiryTimer = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val vpnManager = getSystemService(Context.VPN_MANAGEMENT_SERVICE) as? VpnManager
                vpnManager?.stopProvisionedVpnProfile()
                vpnManager?.deleteProvisionedVpnProfile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }

        currentProfileId = null
        currentProfile = null
        updateState(ConnectionState.DISCONNECTED, null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(state: ConnectionState, message: String?) {
        connectionState = state
        stateListener?.invoke(state, message)
        Log.d(TAG, "VPN State: $state ${message ?: ""}")
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ProfileListActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_VPN)
            .setContentTitle("IKEv2 VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(1, notification)
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        stopConnection()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopConnection()
        super.onDestroy()
    }
}
