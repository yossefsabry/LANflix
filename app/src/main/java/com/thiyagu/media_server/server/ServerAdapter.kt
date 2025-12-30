package com.thiyagu.media_server.server

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thiyagu.media_server.R

class ServerAdapter(private val onServerClick: (DiscoveredServer) -> Unit) : 
    ListAdapter<DiscoveredServer, ServerAdapter.ServerViewHolder>(ServerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server_discovered, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_server_name)
        private val tvIp: TextView = itemView.findViewById(R.id.tv_server_ip)
        private val ivLock: View = itemView.findViewById(R.id.iv_lock)

        fun bind(server: DiscoveredServer) {
            tvName.text = server.name
            tvIp.text = "${server.ip}:${server.port}"
            ivLock.visibility = if (server.isSecured) View.VISIBLE else View.GONE
            
            itemView.setOnClickListener {
                onServerClick(server)
            }
        }
    }

    class ServerDiffCallback : DiffUtil.ItemCallback<DiscoveredServer>() {
        override fun areItemsTheSame(oldItem: DiscoveredServer, newItem: DiscoveredServer): Boolean {
            return oldItem.ip == newItem.ip
        }

        override fun areContentsTheSame(oldItem: DiscoveredServer, newItem: DiscoveredServer): Boolean {
            return oldItem == newItem
        }
    }
}
