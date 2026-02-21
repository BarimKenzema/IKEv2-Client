package com.ikev2client

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ikev2client.model.VpnProfile
import java.net.InetAddress
import java.util.Timer
import java.util.TimerTask

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
    private var vpnInterface: ParcelFileDescriptor? = null
    private var ikeSession: android.net.ipsec.ike.IkeSession? = null
    private var connectionThread: Thread? = null

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

        connectionThread = Thread {
            try {
                connectWithIke(profile)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                android.os.Handler(mainLooper).post {
                    updateState(ConnectionState.ERROR, e.message ?: "Connection failed")
                }
            }
        }
        connectionThread?.start()
    }

    private fun connectWithIke(profile: VpnProfile) {
        try {
            val serverAddress = InetAddress.getByName(profile.server)

            // Build IKE session parameters
            val ikeParamsBuilder = android.net.ipsec.ike.IkeSessionParams.Builder()
                .setServerHostname(profile.server)

            // Set authentication
            if (profile.authMethod == "psk") {
                val psk = (profile.psk ?: profile.password).toByteArray()
                ikeParamsBuilder.setAuthPsk(psk)
            } else {
                // EAP authentication (MSCHAPv2, etc.)
                val eapConfig = android.net.eap.EapSessionConfig.Builder()
                    .setEapMsChapV2Config(profile.username, profile.password)
                    .build()

                // Set remote (server) auth
                if (!profile.caCert.isNullOrBlank()) {
                    try {
                        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                        val cert = cf.generateCertificate(
                            java.io.ByteArrayInputStream(profile.caCert.toByteArray())
                        ) as java.security.cert.X509Certificate
                        ikeParamsBuilder.setAuthDigitalSignature(
                            cert,
                            cert,
                            java.util.ArrayList<java.security.cert.X509Certificate>()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "CA cert parse failed, using system trust", e)
                        // Use system trust store
                        val trustManager = javax.net.ssl.TrustManagerFactory
                            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                        trustManager.init(null as java.security.KeyStore?)
                        val x509tm = trustManager.trustManagers
                            .filterIsInstance<javax.net.ssl.X509TrustManager>()
                            .first()
                        val certs = x509tm.acceptedIssuers
                        if (certs.isNotEmpty()) {
                            ikeParamsBuilder.setAuthDigitalSignature(
                                certs[0],
                                certs[0],
                                java.util.ArrayList<java.security.cert.X509Certificate>()
                            )
                        }
                    }
                }

                ikeParamsBuilder.setAuthEap(null, eapConfig)
            }

            // Set IKE SA proposals
            val ikeSaProposal = android.net.ipsec.ike.IkeSaProposal.Builder()
                .addEncryptionAlgorithm(
                    android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC,
                    android.net.ipsec.ike.SaProposal.KEY_LEN_AES_256
                )
                .addEncryptionAlgorithm(
                    android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC,
                    android.net.ipsec.ike.SaProposal.KEY_LEN_AES_128
                )
                .addIntegrityAlgorithm(
                    android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128
                )
                .addIntegrityAlgorithm(
                    android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96
                )
                .addDhGroup(android.net.ipsec.ike.SaProposal.DH_GROUP_2048_BIT_MODP)
                .addDhGroup(android.net.ipsec.ike.SaProposal.DH_GROUP_1024_BIT_MODP)
                .addPseudorandomFunction(
                    android.net.ipsec.ike.SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1
                )
                .build()

            ikeParamsBuilder.addSaProposal(ikeSaProposal)

            // Set remote identification
            if (profile.remoteId.contains("@")) {
                ikeParamsBuilder.setRemoteIdentification(
                    android.net.ipsec.ike.IkeKeyIdIdentification(profile.remoteId.toByteArray())
                )
            } else {
                try {
                    val addr = InetAddress.getByName(profile.remoteId)
                    ikeParamsBuilder.setRemoteIdentification(
                        android.net.ipsec.ike.IkeIpv4AddrIdentification(
                            addr as java.net.Inet4Address
                        )
                    )
                } catch (e: Exception) {
                    ikeParamsBuilder.setRemoteIdentification(
                        android.net.ipsec.ike.IkeFqdnIdentification(profile.remoteId)
                    )
                }
            }

            // Set local identification
            if (profile.username.contains("@")) {
                ikeParamsBuilder.setLocalIdentification(
                    android.net.ipsec.ike.IkeRfc822AddrIdentification(profile.username)
                )
            } else {
                ikeParamsBuilder.setLocalIdentification(
                    android.net.ipsec.ike.IkeKeyIdIdentification(profile.username.toByteArray())
                )
            }

            val ikeParams = ikeParamsBuilder.build()

            // Build Child SA (IPsec tunnel) parameters
            val childSaProposal = android.net.ipsec.ike.ChildSaProposal.Builder()
                .addEncryptionAlgorithm(
                    android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC,
                    android.net.ipsec.ike.SaProposal.KEY_LEN_AES_256
                )
                .addEncryptionAlgorithm(
                    android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC,
                    android.net.ipsec.ike.SaProposal.KEY_LEN_AES_128
                )
                .addIntegrityAlgorithm(
                    android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128
                )
                .addIntegrityAlgorithm(
                    android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96
                )
                .build()

            val childParams = android.net.ipsec.ike.TunnelModeChildSessionParams.Builder()
                .addSaProposal(childSaProposal)
                .addInternalAddressRequest(android.net.ipsec.ike.TunnelModeChildSessionParams.TUNNEL_MODE_CHILD_SESSION_INTERNAL_ADDRESS_REQUEST, 4)
                .addInternalDnsServerRequest(android.net.ipsec.ike.TunnelModeChildSessionParams.TUNNEL_MODE_CHILD_SESSION_INTERNAL_ADDRESS_REQUEST, 4)
                .build()

            // Create IKE session
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

            val ikeCallback = object : android.net.ipsec.ike.IkeSessionCallback {
                override fun onOpened(sessionConfiguration: android.net.ipsec.ike.IkeSessionConfiguration) {
                    Log.d(TAG, "IKE session opened")
                }

                override fun onClosed() {
                    Log.d(TAG, "IKE session closed")
                    android.os.Handler(mainLooper).post { stopConnection() }
                }

                override fun onClosedWithException(exception: android.net.ipsec.ike.exceptions.IkeException) {
                    Log.e(TAG, "IKE closed with error", exception)
                    android.os.Handler(mainLooper).post {
                        updateState(ConnectionState.ERROR, exception.message ?: "IKE error")
                        stopConnection()
                    }
                }

                override fun onError(exception: android.net.ipsec.ike.exceptions.IkeProtocolException) {
                    Log.e(TAG, "IKE protocol error", exception)
                }
            }

            val childCallback = object : android.net.ipsec.ike.ChildSessionCallback {
                override fun onOpened(sessionConfiguration: android.net.ipsec.ike.ChildSessionConfiguration) {
                    Log.d(TAG, "Child session opened")

                    try {
                        // Build VPN interface with addresses from the session
                        val builder = Builder()
                            .setSession(profile.name)
                            .setMtu(profile.mtu)

                        // Add internal addresses
                        for (addr in sessionConfiguration.internalAddresses) {
                            builder.addAddress(addr.address, addr.prefixLength)
                        }

                        // Add DNS servers
                        for (dns in sessionConfiguration.internalDnsServers) {
                            builder.addDnsServer(dns)
                        }

                        // If no DNS from session, add defaults
                        if (sessionConfiguration.internalDnsServers.isEmpty()) {
                            builder.addDnsServer("8.8.8.8")
                            builder.addDnsServer("8.8.4.4")
                        }

                        // Route all traffic through VPN
                        builder.addRoute("0.0.0.0", 0)
                        builder.addRoute("::", 0)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            builder.setMetered(false)
                        }

                        vpnInterface = builder.establish()

                        if (vpnInterface != null) {
                            android.os.Handler(mainLooper).post {
                                updateState(ConnectionState.CONNECTED, null)
                                updateNotification("Connected to ${profile.name}")
                                startExpiryMonitor(profile)
                            }
                        } else {
                            android.os.Handler(mainLooper).post {
                                updateState(ConnectionState.ERROR, "Failed to establish VPN interface")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "VPN interface setup failed", e)
                        android.os.Handler(mainLooper).post {
                            updateState(ConnectionState.ERROR, e.message)
                        }
                    }
                }

                override fun onClosed() {
                    Log.d(TAG, "Child session closed")
                }

                override fun onClosedWithException(exception: android.net.ipsec.ike.exceptions.IkeException) {
                    Log.e(TAG, "Child session error", exception)
                    android.os.Handler(mainLooper).post {
                        updateState(ConnectionState.ERROR, exception.message)
                        stopConnection()
                    }
                }

                override fun onIpSecTransformCreated(
                    ipSecTransform: android.net.IpSecTransform,
                    direction: Int
                ) {
                    Log.d(TAG, "IPSec transform created, direction: $direction")
                    vpnInterface?.let { vpnFd ->
                        try {
                            val manager = getSystemService(android.net.IpSecManager::class.java)
                            manager.applyTunnelModeTransform(
                                vpnFd.fileDescriptor,
                                direction,
                                ipSecTransform
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to apply transform", e)
                        }
                    }
                }

                override fun onIpSecTransformsMigrated(
                    inIpSecTransform: android.net.IpSecTransform,
                    outIpSecTransform: android.net.IpSecTransform
                ) {
                    Log.d(TAG, "IPSec transforms migrated")
                }

                override fun onError(exception: android.net.ipsec.ike.exceptions.IkeProtocolException) {
                    Log.e(TAG, "Child protocol error", exception)
                }
            }

            ikeSession = android.net.ipsec.ike.IkeSession(
                this,
                ikeParams,
                childParams,
                executor,
                ikeCallback,
                childCallback
            )

            Log.d(TAG, "IKE session started for ${profile.server}")

        } catch (e: Exception) {
            Log.e(TAG, "IKE setup failed", e)
            throw e
        }
    }

    private fun startExpiryMonitor(profile: VpnProfile) {
        expiryTimer?.cancel()
        expiryTimer = Timer()
        expiryTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (profile.isExpired()) {
                    android.os.Handler(mainLooper).post { stopConnection() }
                }
            }
        }, 30000, 30000)
    }

    fun stopConnection() {
        updateState(ConnectionState.DISCONNECTING, null)
        isRunning = false
        expiryTimer?.cancel()
        expiryTimer = null

        try {
            ikeSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing IKE session", e)
        }
        ikeSession = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        connectionThread?.interrupt()
        connectionThread = null

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
