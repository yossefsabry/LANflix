package com.thiyagu.media_server.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.switchmaterial.SwitchMaterial
import com.thiyagu.media_server.R
import com.thiyagu.media_server.model.MediaNode
import com.thiyagu.media_server.model.NodeType
import java.util.Locale

class MediaTreeAdapter(
    private val onExpandClick: (MediaNode) -> Unit,
    private val onVisibilityToggle: (MediaNode, Boolean) -> Unit
) : RecyclerView.Adapter<MediaTreeAdapter.ViewHolder>() {

    private var displayList: List<MediaNode> = emptyList()

    fun submitList(newList: List<MediaNode>) {
        displayList = newList
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val expandIcon: ImageView? = view.findViewById(R.id.iv_expand)
        val typeIcon: ImageView? = view.findViewById(R.id.iv_icon)
        val nameText: TextView = view.findViewById(R.id.video_name)
        val sizeText: TextView? = view.findViewById(R.id.video_size)
        val visibilitySwitch: SwitchMaterial? = view.findViewById(R.id.switch_visibility)
        val progressBar: ProgressBar? = view.findViewById(R.id.progressBar_loading)
    }

    override fun getItemViewType(position: Int): Int {
        return if (displayList[position].type == NodeType.LOADING) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_management, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = displayList[position]
        
        holder.visibilitySwitch?.setOnCheckedChangeListener(null)
        
        // Indentation
        val indentDp = node.level * 24
        val scale = holder.itemView.context.resources.displayMetrics.density
        val indentPx = (indentDp * scale).toInt()
        val layout = holder.itemView as ViewGroup
        if (layout.childCount > 0) {
             val contentContainer = layout.getChildAt(0)
             contentContainer.setPadding(indentPx + (16 * scale).toInt(), contentContainer.paddingTop, contentContainer.paddingRight, contentContainer.paddingBottom)
        }

        holder.nameText.text = node.name

        if (node.type == NodeType.LOADING) {
            holder.expandIcon?.visibility = View.INVISIBLE
            holder.typeIcon?.visibility = View.INVISIBLE
            holder.sizeText?.visibility = View.GONE
            holder.visibilitySwitch?.visibility = View.GONE
            holder.progressBar?.visibility = View.VISIBLE
            return
        }
        holder.progressBar?.visibility = View.GONE
        
        holder.sizeText?.visibility = View.VISIBLE
        holder.visibilitySwitch?.visibility = View.VISIBLE
        
        if (node.type == NodeType.FOLDER) {
            holder.sizeText?.text = if (node.isLoaded) "${node.children.size} items" else "..."
            holder.typeIcon?.visibility = View.VISIBLE
            holder.typeIcon?.setImageResource(R.drawable.ic_folder_open)
            holder.typeIcon?.alpha = 0.5f 
            holder.typeIcon?.setPadding(20, 20, 20, 20)
            
            holder.expandIcon?.visibility = View.VISIBLE
            holder.expandIcon?.rotation = if (node.isExpanded) 90f else 0f
            holder.expandIcon?.setOnClickListener {
                onExpandClick(node)
            }
        } else {
            holder.sizeText?.text = formatSize(node.size)
            holder.typeIcon?.visibility = View.VISIBLE
            
            holder.typeIcon?.let { iconView ->
                 iconView.alpha = 1.0f
                 iconView.setPadding(0, 0, 0, 0)
                 Glide.with(holder.itemView.context)
                     .load(node.uriString)
                     .override(128, 128)
                     .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
                     .error(R.drawable.ic_movie)
                     .placeholder(R.drawable.ic_movie)
                     .centerCrop()
                     .into(iconView)
            }

            holder.expandIcon?.visibility = View.INVISIBLE 
        }

        holder.visibilitySwitch?.isChecked = node.isVisible
        holder.visibilitySwitch?.setOnCheckedChangeListener { _, isChecked ->
            onVisibilityToggle(node, isChecked)
        }
    }

    override fun getItemCount() = displayList.size

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
