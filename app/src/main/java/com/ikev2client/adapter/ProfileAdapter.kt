package com.ikev2client.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ikev2client.R
import com.ikev2client.model.VpnProfile

class ProfileAdapter(
    private var profiles: MutableList<VpnProfile>,
    private val onConnect: (VpnProfile) -> Unit,
    private val onDelete: (VpnProfile, Int) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private var activeProfileId: String? = null
    private var isConnected: Boolean = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvProfileName)
        val tvServer: TextView = view.findViewById(R.id.tvProfileServer)
        val tvExpiry: TextView = view.findViewById(R.id.tvProfileExpiry)
        val tvStatus: TextView = view.findViewById(R.id.tvProfileStatus)
        val btnConnect: Button = view.findViewById(R.id.btnConnect)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
        val ivStatus: ImageView = view.findViewById(R.id.ivStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]
        val isExpired = profile.isExpired()
        val isActive = profile.id == activeProfileId

        holder.tvName.text = profile.name
        holder.tvServer.text = profile.server
        holder.tvExpiry.text = if (isExpired) {
            "EXPIRED"
        } else {
            "Expires: ${profile.getExpiryDisplayString()} (${profile.getTimeRemainingString()} left)"
        }

        holder.tvExpiry.setTextColor(
            if (isExpired) Color.RED else Color.parseColor("#888888")
        )

        when {
            isExpired -> {
                holder.tvStatus.text = "Expired"
                holder.tvStatus.setTextColor(Color.RED)
                holder.btnConnect.isEnabled = false
                holder.btnConnect.text = "Expired"
                holder.ivStatus.setColorFilter(Color.RED)
            }
            isActive && isConnected -> {
                holder.tvStatus.text = "Connected"
                holder.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                holder.btnConnect.isEnabled = true
                holder.btnConnect.text = "Disconnect"
                holder.ivStatus.setColorFilter(Color.parseColor("#4CAF50"))
            }
            isActive -> {
                holder.tvStatus.text = "Connecting..."
                holder.tvStatus.setTextColor(Color.parseColor("#FF9800"))
                holder.btnConnect.isEnabled = true
                holder.btnConnect.text = "Cancel"
                holder.ivStatus.setColorFilter(Color.parseColor("#FF9800"))
            }
            else -> {
                holder.tvStatus.text = "Ready"
                holder.tvStatus.setTextColor(Color.parseColor("#888888"))
                holder.btnConnect.isEnabled = true
                holder.btnConnect.text = "Connect"
                holder.ivStatus.setColorFilter(Color.parseColor("#888888"))
            }
        }

        holder.btnConnect.setOnClickListener { onConnect(profile) }
        holder.btnDelete.setOnClickListener { onDelete(profile, holder.adapterPosition) }
    }

    override fun getItemCount() = profiles.size

    fun updateProfiles(newProfiles: List<VpnProfile>) {
        profiles.clear()
        profiles.addAll(newProfiles)
        notifyDataSetChanged()
    }

    fun setConnectionState(profileId: String?, connected: Boolean) {
        activeProfileId = profileId
        isConnected = connected
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        profiles.removeAt(position)
        notifyItemRemoved(position)
    }
}
