package com.ikev2client.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ikev2client.model.VpnProfile

/**
 * Stores VPN profiles in encrypted SharedPreferences.
 */
class ProfileStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "vpn_profiles_storage", Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val KEY_PROFILES = "saved_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }

    fun saveProfiles(profiles: List<VpnProfile>) {
        val json = gson.toJson(profiles)
        prefs.edit().putString(KEY_PROFILES, json).apply()
    }

    fun loadProfiles(): MutableList<VpnProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<VpnProfile>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addProfiles(newProfiles: List<VpnProfile>) {
        val existing = loadProfiles()
        // Replace profiles with same name+server, add new ones
        newProfiles.forEach { newProfile ->
            val existingIndex = existing.indexOfFirst {
                it.name == newProfile.name && it.server == newProfile.server
            }
            if (existingIndex >= 0) {
                existing[existingIndex] = newProfile
            } else {
                existing.add(newProfile)
            }
        }
        saveProfiles(existing)
    }

    fun removeProfile(profileId: String) {
        val profiles = loadProfiles()
        profiles.removeAll { it.id == profileId }
        saveProfiles(profiles)
        if (getActiveProfileId() == profileId) {
            setActiveProfileId(null)
        }
    }

    fun removeExpiredProfiles(): Int {
        val profiles = loadProfiles()
        val initialSize = profiles.size
        profiles.removeAll { it.isExpired() }
        saveProfiles(profiles)
        return initialSize - profiles.size
    }

    fun getActiveProfileId(): String? {
        return prefs.getString(KEY_ACTIVE_PROFILE, null)
    }

    fun setActiveProfileId(profileId: String?) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profileId).apply()
    }

    fun getActiveProfile(): VpnProfile? {
        val id = getActiveProfileId() ?: return null
        return loadProfiles().find { it.id == id }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
