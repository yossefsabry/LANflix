package com.thiyagu.media_server

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.thiyagu.media_server.utils.VideoVisibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MediaManagementActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MediaTreeAdapter
    private lateinit var visibilityManager: VideoVisibilityManager
    
    // Master Tree (Root Nodes)
    private val rootNodes = mutableListOf<MediaNode>()
    // Flattened List for Adapter
    private val displayList = mutableListOf<MediaNode>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_management)

        visibilityManager = VideoVisibilityManager(this)
        
        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        recyclerView = findViewById(R.id.recycler_view_videos)
        adapter = MediaTreeAdapter()
        recyclerView.adapter = adapter

        loadMedia()
    }

    private fun loadMedia() {
        val folderUriString = intent.getStringExtra("FOLDER_URI") ?: return
        val includeSubfolders = intent.getBooleanExtra("INCLUDE_SUBFOLDERS", false)
        
        // Show loading? (Optional, skipping for now)

        lifecycleScope.launch(Dispatchers.IO) {
            val folderUri = Uri.parse(folderUriString)
            val rootDir = DocumentFile.fromTreeUri(this@MediaManagementActivity, folderUri)

            if (rootDir != null) {
                val nodes = if (includeSubfolders) {
                    scanRecursively(rootDir, 0)
                } else {
                    scanFlat(rootDir)
                }
                
                withContext(Dispatchers.Main) {
                    rootNodes.clear()
                    rootNodes.addAll(nodes)
                    updateDisplayList()
                }
            }
        }
    }
    
    private fun scanFlat(root: DocumentFile): List<MediaNode> {
        val nodes = mutableListOf<MediaNode>()
        root.listFiles().forEach { file ->
            if (file.isFile && isValidVideo(file.name)) {
                nodes.add(createFileNode(file, 0))
            }
        }
        return nodes
    }
    
    private fun scanRecursively(root: DocumentFile, level: Int): List<MediaNode> {
        val nodes = mutableListOf<MediaNode>()
        // Simple optimization: Folders first, then files? Or alphabetical?
        // Let's sort simply: Folders then Files
        val files = root.listFiles()
        val dirs = files.filter { it.isDirectory }.sortedBy { it.name }
        val videos = files.filter { it.isFile && isValidVideo(it.name) }.sortedBy { it.name }
        
        // Process Directories
        for (dir in dirs) {
            val children = scanRecursively(dir, level + 1)
            // Only add folder if it has content (optional, but cleaner)
            if (children.isNotEmpty()) {
                val folderNode = createFolderNode(dir, level, children)
                nodes.add(folderNode)
            }
        }
        
        // Process Files
        for (video in videos) {
            nodes.add(createFileNode(video, level))
        }
        
        return nodes
    }
    
    private fun createFolderNode(file: DocumentFile, level: Int, children: List<MediaNode>): MediaNode {
        // Initial visibility check: If ALL descendants are hidden? Or any?
        // Simplified: Default visible.
        // Actually, logic: If user expands, they see real state. 
        // We need to calculate checkbox state.
        // If ANY child is visible -> Folder is Visible (checked)
        // If ALL children hidden -> Folder is Hidden (unchecked)
        val isAnyChildVisible = children.any { it.isVisible } // Recursive check
        
        return MediaNode(
            type = NodeType.FOLDER,
            name = file.name ?: "Unknown",
            uriString = file.uri.toString(),
            level = level,
            isExpanded = false, // Start collapsed
            isVisible = isAnyChildVisible,
            children = children.toMutableList()
        )
    }

    private fun createFileNode(file: DocumentFile, level: Int): MediaNode {
        // Here we use URI string to check visibility
        val isHidden = visibilityManager.isVideoHidden(file.uri.toString())
        return MediaNode(
            type = NodeType.FILE,
            name = file.name ?: "Unknown",
            uriString = file.uri.toString(),
            level = level,
            size = file.length(),
            isVisible = !isHidden
        )
    }
    
    private fun isValidVideo(name: String?): Boolean {
        val n = name?.lowercase(Locale.getDefault()) ?: return false
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
    }

    private fun updateDisplayList() {
        displayList.clear()
        flattenList(rootNodes, displayList)
        adapter.notifyDataSetChanged()
    }
    
    private fun flattenList(nodes: List<MediaNode>, destination: MutableList<MediaNode>) {
        for (node in nodes) {
            destination.add(node)
            if (node.type == NodeType.FOLDER && node.isExpanded) {
                flattenList(node.children, destination)
            }
        }
    }
    
    // Toggle Visibility Logic
    private fun toggleVisibility(node: MediaNode, isVisible: Boolean) {
        node.isVisible = isVisible
        
        if (node.type == NodeType.FILE) {
             visibilityManager.setVideoHidden(node.uriString, !isVisible)
        } else {
             // Recursive set
             val allDescendants = collectAllFileUris(node)
             visibilityManager.setVideosHidden(allDescendants, !isVisible)
             // Update memory state of children
             setChildrenVisibility(node, isVisible)
        }
    }
    
    private fun setChildrenVisibility(node: MediaNode, isVisible: Boolean) {
        node.children.forEach { child ->
            child.isVisible = isVisible
            if (child.type == NodeType.FOLDER) {
                setChildrenVisibility(child, isVisible)
            }
        }
    }
    
    private fun collectAllFileUris(node: MediaNode): List<String> {
        val uris = mutableListOf<String>()
        if (node.type == NodeType.FILE) {
            uris.add(node.uriString)
        } else {
            node.children.forEach { child ->
                uris.addAll(collectAllFileUris(child))
            }
        }
        return uris
    }

    // -- Inner Classes --

    enum class NodeType { FOLDER, FILE }

    data class MediaNode(
        val type: NodeType,
        val name: String,
        val uriString: String,
        val level: Int,
        var isExpanded: Boolean = false,
        var isVisible: Boolean = true,
        val size: Long = 0,
        val children: MutableList<MediaNode> = mutableListOf()
    )

    inner class MediaTreeAdapter : RecyclerView.Adapter<MediaTreeAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val expandIcon: ImageView = view.findViewById(R.id.iv_expand)
            val typeIcon: ImageView = view.findViewById(R.id.iv_icon)
            val nameText: TextView = view.findViewById(R.id.video_name)
            val sizeText: TextView = view.findViewById(R.id.video_size)
            val visibilitySwitch: SwitchMaterial = view.findViewById(R.id.switch_visibility)
            val container: View = (view.findViewById<ImageView>(R.id.iv_icon).parent) as View
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video_management, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val node = displayList[position]
            
            // Indentation using padding/margin
            val indentDp = node.level * 24
            val scale = holder.itemView.context.resources.displayMetrics.density
            val indentPx = (indentDp * scale).toInt()
            
            // Apply indentation to the LinearLayout container inside the card? 
            // The item_video_management root is CardView. Inside is LinearLayout.
            // Let's set paddingStart on the LinearLayout
            val layout = holder.itemView as ViewGroup
            val linearLayout = layout.getChildAt(0) // Assuming CardView -> Linear
            linearLayout.setPadding(indentPx + (16 * scale).toInt(), linearLayout.paddingTop, linearLayout.paddingRight, linearLayout.paddingBottom)

            holder.nameText.text = node.name
            
            if (node.type == NodeType.FOLDER) {
                holder.sizeText.text = "${node.children.size} items"
                holder.typeIcon.setImageResource(R.drawable.ic_folder_open ?: android.R.drawable.ic_menu_more) // Fallback
                // Use built-in or material folder icon if available? 
                // Creating a folder icon if missing?
                // Let's use generic logic or reuse ic_movie with different tint/bg?
                // Just use existing ic_movie for now but maybe change tint?
                // Or try to load "ic_folder" by name (might not exist)
                // Let's assume using ic_movie is confusing. 
                // Activity has access to resources.
                // Step 292: iv_icon src is @drawable/ic_movie
                holder.typeIcon.alpha = 0.5f // Dim folders slightly distinction
                
                holder.expandIcon.visibility = View.VISIBLE
                holder.expandIcon.rotation = if (node.isExpanded) 90f else 0f
                holder.expandIcon.setOnClickListener {
                    node.isExpanded = !node.isExpanded
                    updateDisplayList()
                }
            } else {
                holder.sizeText.text = formatSize(node.size)
                holder.typeIcon.setImageResource(R.drawable.ic_movie)
                holder.typeIcon.alpha = 1.0f
                holder.expandIcon.visibility = View.INVISIBLE // Occupy space? or GONE?
                // If GONE, indentation shifts. 
                // item_video_management: iv_expand is first.
                // If we want files aligned with folders, we should keep it invisible.
                holder.expandIcon.visibility = View.INVISIBLE 
            }

            holder.visibilitySwitch.setOnCheckedChangeListener(null)
            holder.visibilitySwitch.isChecked = node.isVisible
            
            holder.visibilitySwitch.setOnCheckedChangeListener { _, isChecked ->
                toggleVisibility(node, isChecked)
                // If folder, we need to refresh list logic (children status changed)
                if (node.type == NodeType.FOLDER && node.isExpanded) {
                    updateDisplayList() // simpler than fine-grained notify
                }
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
}
