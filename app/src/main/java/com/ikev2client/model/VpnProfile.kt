package com.ikev2client.model

import com.google.gson.annotations.SerializedName

data class VpnProfile(
    @SerializedName("id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @SerializedName("name")
    val name: String,

    @SerializedName("server")
    val server: String,

    @SerializedName("remote_id")
    val remoteId: String,

    @SerializedName("username")
    val username: String,

    @SerializedName("password")
    val password: String,

    @SerializedName("ca_cert")
    val caCert: String? = null,

    @SerializedName("expires_at")
    val expiresAt: String,  // ISO 8601 format: "2025-07-15T14:30:00Z"

    @SerializedName("auth_method")
    val authMethod: String = "eap-mschapv2",  // eap-mschapv2, psk, certificate

    @SerializedName("psk")
    val psk: String? = null,

    @SerializedName("mtu")
    val mtu: Int = 1400,

    @SerializedName("split_tunneling")
    val splitTunneling: Boolean = false,

    @SerializedName("allowed_apps")
    val allowedApps: List<String>? = null
) {
    fun isExpired(): Boolean {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val expiry = sdf.parse(expiresAt)
            expiry != null && System.currentTimeMillis() > expiry.time
        } catch (e: Exception) {
            true // If we can't parse, treat as expired
        }
    }

    fun getExpiryDisplayString(): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val expiry = sdf.parse(expiresAt)
            val displayFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.US)
            displayFormat.timeZone = java.util.TimeZone.getDefault()
            if (expiry != null) displayFormat.format(expiry) else "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun getTimeRemainingString(): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val expiry = sdf.parse(expiresAt) ?: return "Expired"
            val remaining = expiry.time - System.currentTimeMillis()
            if (remaining <= 0) return "Expired"

            val days = remaining / (1000 * 60 * 60 * 24)
            val hours = (remaining % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
            val minutes = (remaining % (1000 * 60 * 60)) / (1000 * 60)

            buildString {
                if (days > 0) append("${days}d ")
                if (hours > 0) append("${hours}h ")
                append("${minutes}m")
            }.trim()
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

data class ProfileBundle(
    @SerializedName("profiles")
    val profiles: List<VpnProfile>
)
