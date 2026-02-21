package com.ikev2client

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ikev2client.model.VpnProfile
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.SSLContext
import android.net.Ikev2VpnProfile as PlatformIkev2Profile

class VpnConnectionService : VpnService() {

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
                updateState(ConnectionState.ERROR, e.message)
            }
        }.start()
    }

    private fun connectIkev2(profile: VpnProfile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Use platform IKEv2 via VpnManager
                val vpnManager = getSystemService(android.net.VpnManager::class.java)

                val builder = PlatformIkev2Profile.Builder(profile.server, profile.remoteId)

                // Parse CA cert if provided
                var serverCaCert: java.security.cert.X509Certificate? = null
                if (!profile.caCert.isNullOrBlank()) {
                    try {
                        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                        serverCaCert = cf.generateCertificate(
                            java.io.ByteArrayInputStream(profile.caCert.toByteArray())
                        ) as java.security.cert.X509Certificate
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CA cert, using system trust", e)
                    }
                }

                when (profile.authMethod) {
                    "psk" -> {
                        val pskBytes = (profile.psk ?: profile.password).toByteArray()
                        builder.setAuthPsk(pskBytes)
                    }
                    else -> {
                        builder.setAuthUsernamePassword(
                            profile.username,
                            profile.password,
                            serverCaCert
                        )
                    }
                }

                builder.setMaxMtu(profile.mtu)
                builder.setBypassable(profile.splitTunneling)

                val ikev2Profile = builder.build()

                // Provision and start
                vpnManager.provisionVpnProfile(ikev2Profile)

                // This is the key call that triggers the system VPN
                val startIntent = vpnManager.provisionVpnProfile(ikev2Profile)
                vpnManager.startProvisionedVpnProfile()

                updateState(ConnectionState.CONNECTED, null)
                updateNotification("Connected to ${profile.name}")
                startExpiryMonitor(profile)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Need VPN consent", e)
            updateState(ConnectionState.ERROR, "VPN permission needed. Open Settings > VPN and allow this app.")
        } catch (e: Exception) {
            Log.e(TAG, "IKEv2 error", e)
            updateState(ConnectionState.ERROR, e.message)
        }
    }

    private fun startExpiryMonitor(profile: VpnProfile) {
        expiryTimer?.cancel()
        expiryTimer = Timer()
        expiryTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (profile.isExpired()) {
                    android.os.Handler(mainLooper).post {
                        stopConnection()
                    }
                }
            }
        }, 30000, 30000)
    }

    fun stopConnection() {
        updateState(ConnectionState.DISCONNECTING, null)
        isRunning = false
        expiryTimer?.cancel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val vpnManager = getSystemService(android.net.VpnManager::class.java)
                vpnManager.stopProvisionedVpnProfile()
                vpnManager.deleteProvisionedVpnProfile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }

        currentProfileId = null
        updateState(ConnectionState.DISCONNECTED, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(state: ConnectionState, msg: String?) {
        connectionState = state
        stateListener?.invoke(state, msg)
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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

    override fun onRevoke() {
        stopConnection()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopConnection()
        super.onDestroy()
    }
}
