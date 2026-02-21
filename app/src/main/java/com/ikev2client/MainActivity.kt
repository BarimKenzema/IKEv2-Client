package com.ikev2client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.ikev2client.adapter.ProfileAdapter
import com.ikev2client.crypto.ProfileCrypto
import com.ikev2client.databinding.ActivityMainBinding
import com.ikev2client.model.VpnProfile
import com.ikev2client.storage.ProfileStorage
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ProfileStorage
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ProfileStorage(this)

        adapter = ProfileAdapter(
            profiles = mutableListOf(),
            onConnect = { profile -> onConnectClicked(profile) },
            onDelete = { profile, position -> onDeleteClicked(profile, position) }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter

        binding.btnScanQr.setOnClickListener {
            startActivity(Intent(this, ScanQrActivity::class.java))
        }

        binding.btnPasteProfile.setOnClickListener {
            showPasteDialog()
        }

        binding.btnCleanExpired.setOnClickListener {
            val removed = storage.removeExpiredProfiles()
            Toast.makeText(this, "Removed $removed expired profile(s)", Toast.LENGTH_SHORT).show()
            refreshProfiles()
        }

        refreshProfiles()
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        updateVpnStatus()
    }

    private fun refreshProfiles() {
        val profiles = storage.loadProfiles()
        adapter.updateProfiles(profiles)

        val valid = profiles.count { !it.isExpired() }
        val expired = profiles.count { it.isExpired() }
        binding.tvProfileCount.text = "$valid active, $expired expired"

        if (profiles.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvProfiles.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvProfiles.visibility = View.VISIBLE
        }
    }

    private fun updateVpnStatus() {
        val isVpn = isVpnActive()
        binding.tvConnectionStatus.text = if (isVpn) "VPN Active" else "VPN Not Active"
        binding.tvConnectionStatus.setTextColor(
            if (isVpn) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.parseColor("#BBDEFB")
        )
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    private fun onConnectClicked(profile: VpnProfile) {
        if (profile.isExpired()) {
            Toast.makeText(this, "This profile has expired!", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if strongSwan is installed
        if (!isStrongSwanInstalled()) {
            AlertDialog.Builder(this)
                .setTitle("strongSwan Required")
                .setMessage("Please install 'strongSwan VPN Client' from Google Play Store first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Show password dialog, then export to strongSwan
        showPasswordAndExport(profile)
    }

    private fun showPasswordAndExport(profile: VpnProfile) {
        AlertDialog.Builder(this)
            .setTitle("Connect: ${profile.name}")
            .setMessage(
                "Server: ${profile.server}\n" +
                "Username: ${profile.username}\n" +
                "Password: ${profile.password}\n\n" +
                "Steps:\n" +
                "1. Tap 'Copy Password' below\n" +
                "2. Tap 'Open strongSwan'\n" +
                "3. In strongSwan, tap 'IMPORT'\n" +
                "4. Paste the password in the password field\n" +
                "5. Tap 'SAVE' then tap the profile to connect"
            )
            .setPositiveButton("Copy Password") { _, _ ->
                // Copy password to clipboard
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("vpn_password", profile.password)
                )
                Toast.makeText(this, "Password copied!", Toast.LENGTH_SHORT).show()

                // Then export to strongSwan
                exportToStrongSwan(profile)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Open strongSwan") { _, _ ->
                // Just launch strongSwan (profile already imported before)
                launchStrongSwan()
            }
            .show()
    }

    private fun exportToStrongSwan(profile: VpnProfile) {
        try {
            // Create .sswan profile file
            val json = JSONObject()
            json.put("uuid", UUID.randomUUID().toString())
            json.put("name", profile.name)
            json.put("type", "ikev2-eap")

            val remote = JSONObject()
            remote.put("addr", profile.server)
            if (profile.remoteId.isNotBlank()) {
                remote.put("id", profile.remoteId)
            }

            // Convert PEM certificate to base64 DER for strongSwan
            if (!profile.caCert.isNullOrBlank()) {
                val base64Der = profile.caCert
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replace("\n", "")
                    .replace("\r", "")
                    .replace(" ", "")
                    .trim()
                remote.put("cert", base64Der)
            }

            json.put("remote", remote)

            val local = JSONObject()
            local.put("eap_id", profile.username)
            json.put("local", local)

            // Write to cache directory
            val fileName = profile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".sswan"
            val file = File(cacheDir, fileName)
            file.writeText(json.toString(2))

            // Open with strongSwan via FileProvider
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.strongswan.profile")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isStrongSwanInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("org.strongswan.android", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchStrongSwan() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("org.strongswan.android")
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "Could not launch strongSwan", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleteClicked(profile: VpnProfile, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Delete '${profile.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                storage.removeProfile(profile.id)
                adapter.removeAt(position)
                refreshProfiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_paste_profile, null)
        val etEncrypted = dialogView.findViewById<TextInputEditText>(R.id.etEncryptedData)
        val etPassphrase = dialogView.findViewById<TextInputEditText>(R.id.etPassphrase)

        AlertDialog.Builder(this)
            .setTitle("Import Encrypted Profile")
            .setView(dialogView)
            .setPositiveButton("Import") { _, _ ->
                val encrypted = etEncrypted.text?.toString()?.trim() ?: ""
                val passphrase = etPassphrase.text?.toString() ?: ""
                if (encrypted.isEmpty() || passphrase.isEmpty()) {
                    Toast.makeText(this, "Both fields required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                importEncryptedProfile(encrypted, passphrase)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importEncryptedProfile(encryptedData: String, passphrase: String) {
        try {
            val profiles = ProfileCrypto.decryptProfiles(encryptedData, passphrase)
            val (valid, expired) = ProfileCrypto.filterValidProfiles(profiles)

            if (valid.isEmpty() && expired.isNotEmpty()) {
                Toast.makeText(this, "All ${expired.size} profile(s) expired!", Toast.LENGTH_LONG).show()
                return
            }
            if (valid.isEmpty()) {
                Toast.makeText(this, "No valid profiles found", Toast.LENGTH_SHORT).show()
                return
            }

            storage.addProfiles(valid)
            val msg = "Imported ${valid.size} profile(s)" +
                    if (expired.isNotEmpty()) ", ${expired.size} expired skipped" else ""
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            refreshProfiles()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
