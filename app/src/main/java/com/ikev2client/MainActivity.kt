package com.ikev2client

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.ikev2client.crypto.ProfileCrypto
import com.ikev2client.databinding.ActivityMainBinding
import com.ikev2client.storage.ProfileStorage

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ProfileStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ProfileStorage(this)

        binding.btnScanQr.setOnClickListener {
            startActivity(Intent(this, ScanQrActivity::class.java))
        }

        binding.btnPasteProfile.setOnClickListener {
            showPasteDialog()
        }

        binding.btnViewProfiles.setOnClickListener {
            startActivity(Intent(this, ProfileListActivity::class.java))
        }

        binding.btnCleanExpired.setOnClickListener {
            val removed = storage.removeExpiredProfiles()
            Toast.makeText(this, "Removed $removed expired profile(s)", Toast.LENGTH_SHORT).show()
            updateProfileCount()
        }

        updateProfileCount()
    }

    override fun onResume() {
        super.onResume()
        updateProfileCount()
    }

    private fun updateProfileCount() {
        val profiles = storage.loadProfiles()
        val valid = profiles.count { !it.isExpired() }
        val expired = profiles.count { it.isExpired() }
        binding.tvProfileCount.text = "Profiles: $valid active, $expired expired (${profiles.size} total)"
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
                    Toast.makeText(this, "Both fields are required", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(
                    this,
                    "All ${expired.size} profile(s) have expired!",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (valid.isEmpty()) {
                Toast.makeText(this, "No valid profiles found", Toast.LENGTH_SHORT).show()
                return
            }

            storage.addProfiles(valid)

            val message = buildString {
                append("Imported ${valid.size} profile(s)")
                if (expired.isNotEmpty()) {
                    append("\nSkipped ${expired.size} expired profile(s)")
                }
            }

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            updateProfileCount()

        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Import failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
