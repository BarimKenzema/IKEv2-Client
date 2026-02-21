package com.ikev2client

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.ikev2client.adapter.ProfileAdapter
import com.ikev2client.databinding.ActivityProfileListBinding
import com.ikev2client.model.VpnProfile
import com.ikev2client.storage.ProfileStorage

class ProfileListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileListBinding
    private lateinit var storage: ProfileStorage
    private lateinit var adapter: ProfileAdapter
    private var pendingProfile: VpnProfile? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingProfile?.let { connectToProfile(it) }
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingProfile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ProfileStorage(this)

        adapter = ProfileAdapter(
            profiles = mutableListOf(),
            onConnect = { profile -> onConnectClicked(profile) },
            onDelete = { profile, position -> onDeleteClicked(profile, position) }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }

        // Listen for connection state changes
        VpnConnectionService.stateListener = { state, message ->
            runOnUiThread {
                updateConnectionUI(state, message)
            }
        }

        loadProfiles()
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
        updateConnectionUI(VpnConnectionService.connectionState, null)
    }

    override fun onDestroy() {
        VpnConnectionService.stateListener = null
        super.onDestroy()
    }

    private fun loadProfiles() {
        val profiles = storage.loadProfiles()
        adapter.updateProfiles(profiles)

        binding.tvEmpty.visibility = if (profiles.isEmpty()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun onConnectClicked(profile: VpnProfile) {
        if (VpnConnectionService.isRunning && VpnConnectionService.currentProfileId == profile.id) {
            // Disconnect
            disconnectVpn()
            return
        }

        if (VpnConnectionService.isRunning) {
            // Disconnect current first
            disconnectVpn()
        }

        if (profile.isExpired()) {
            Toast.makeText(this, "This profile has expired!", Toast.LENGTH_SHORT).show()
            return
        }

        // Check VPN permission
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingProfile = profile
            vpnPermissionLauncher.launch(intent)
        } else {
            connectToProfile(profile)
        }
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
    }

    private fun disconnectVpn() {
        val intent = Intent(this, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_DISCONNECT
        }
        startService(intent)
        storage.setActiveProfileId(null)
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
                Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateConnectionUI(state: VpnConnectionService.ConnectionState, message: String?) {
        val isConnected = state == VpnConnectionService.ConnectionState.CONNECTED
        adapter.setConnectionState(VpnConnectionService.currentProfileId, isConnected)

        binding.tvConnectionStatus.text = when (state) {
            VpnConnectionService.ConnectionState.DISCONNECTED -> "Status: Disconnected"
            VpnConnectionService.ConnectionState.CONNECTING -> "Status: Connecting..."
            VpnConnectionService.ConnectionState.CONNECTED -> "Status: Connected"
            VpnConnectionService.ConnectionState.DISCONNECTING -> "Status: Disconnecting..."
            VpnConnectionService.ConnectionState.ERROR -> "Status: Error - ${message ?: "Unknown"}"
        }
    }
}
