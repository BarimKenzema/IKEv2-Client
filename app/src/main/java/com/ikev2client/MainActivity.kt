package com.ikev2client

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.ikev2client.adapter.ProfileAdapter
import com.ikev2client.crypto.ProfileCrypto
import com.ikev2client.databinding.ActivityMainBinding
import com.ikev2client.model.VpnProfile
import com.ikev2client.storage.ProfileStorage

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

        VpnConnectionService.stateListener = { state, message ->
            runOnUiThread {
                updateConnectionStatus(state, message)
            }
        }

        refreshProfiles()
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
        updateConnectionStatus(VpnConnectionService.connectionState, null)
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

        val isConnected = VpnConnectionService.connectionState == VpnConnectionService.ConnectionState.CONNECTED
        adapter.setConnectionState(VpnConnectionService.currentProfileId, isConnected)
    }

    private fun onConnectClicked(profile: VpnProfile) {
        if (VpnConnectionService.isRunning && VpnConnectionService.currentProfileId == profile.id) {
            disconnectVpn()
            return
        }

        if (VpnConnectionService.isRunning) {
            disconnectVpn()
        }

        if (profile.isExpired()) {
            Toast.makeText(this, "This profile has expired!", Toast.LENGTH_SHORT).show()
            return
        }

        connectToProfile(profile)
    }

    private fun connectToProfile(profile: VpnProfile) {
        val intent = Intent(this, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_CONNECT
            putExtra(VpnConnectionService.EXTRA_PROFILE_JSON, Gson().toJson(profile))
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        storage.setActiveProfileId(profile.id)
        refreshProfiles()
    }

    private fun disconnectVpn() {
        val intent = Intent(this, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_DISCONNECT
        }
        startService(intent)
        storage.setActiveProfileId(null)
        refreshProfiles()
    }

    private fun onDeleteClicked(profile: VpnProfile, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Delete '${profile.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                if (VpnConnectionService.currentProfileId == profile.id) {
                    disconnectVpn()
                }
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

    private fun updateConnectionStatus(state: VpnConnectionService.ConnectionState, message: String?) {
        binding.tvConnectionStatus.text = when (state) {
            VpnConnectionService.ConnectionState.DISCONNECTED -> "Disconnected"
            VpnConnectionService.ConnectionState.CONNECTING -> "Connecting..."
            VpnConnectionService.ConnectionState.CONNECTED -> "Connected"
            VpnConnectionService.ConnectionState.DISCONNECTING -> "Disconnecting..."
            VpnConnectionService.ConnectionState.ERROR -> "Error: ${message ?: "Unknown"}"
        }

        binding.tvConnectionStatus.setTextColor(
            when (state) {
                VpnConnectionService.ConnectionState.CONNECTED -> android.graphics.Color.parseColor("#4CAF50")
                VpnConnectionService.ConnectionState.ERROR -> android.graphics.Color.parseColor("#F44336")
                VpnConnectionService.ConnectionState.CONNECTING -> android.graphics.Color.parseColor("#FF9800")
                else -> android.graphics.Color.parseColor("#BBDEFB")
            }
        )

        refreshProfiles()
    }

    override fun onDestroy() {
        VpnConnectionService.stateListener = null
        super.onDestroy()
    }
}
