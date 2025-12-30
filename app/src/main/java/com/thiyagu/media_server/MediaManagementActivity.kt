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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.thiyagu.media_server.utils.VideoVisibilityManager
import java.util.Locale

class MediaManagementActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoAdapter
    private lateinit var visibilityManager: VideoVisibilityManager
    private var videoList: MutableList<VideoItem> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_management)

        visibilityManager = VideoVisibilityManager(this)
        
        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        recyclerView = findViewById(R.id.recycler_view_videos)
        adapter = VideoAdapter(videoList, visibilityManager)
        recyclerView.adapter = adapter

        loadVideos()
    }

    private fun loadVideos() {
        val folderUriString = intent.getStringExtra("FOLDER_URI")
        if (folderUriString != null) {
            val folderUri = Uri.parse(folderUriString)
            val files = DocumentFile.fromTreeUri(this, folderUri)?.listFiles()
            
            videoList.clear()
            files?.forEach { file ->
                if (file.isFile) {
                    val name = file.name ?: ""
                    val lowerName = name.lowercase(Locale.getDefault())
                    if (lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".avi") || lowerName.endsWith(".mov")) {
                        // For DocumentFile, the uri.path or uri.toString() might not be the absolute file path 
                        // but VideoVisibilityManager needs a consistent ID. 
                        // We will use the file name as ID for simplicity within a folder context, 
                        // or better, the URI string if persistent permissions are used.
                        // However, to match Ktor server which likely serves by name or uses real path,
                        // let's use the name for now as the server is likely serving the same folder.
                        videoList.add(VideoItem(name, file.length(), file.uri.toString()))
                    }
                }
            }
            adapter.notifyDataSetChanged()
        }
    }

    data class VideoItem(val name: String, val size: Long, val uriString: String)

    class VideoAdapter(
        private val videos: List<VideoItem>,
        private val visibilityManager: VideoVisibilityManager
    ) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.video_name)
            val sizeText: TextView = view.findViewById(R.id.video_size)
            val visibilitySwitch: SwitchMaterial = view.findViewById(R.id.switch_visibility)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video_management, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val video = videos[position]
            holder.nameText.text = video.name
            holder.sizeText.text = formatSize(video.size)
            
            // Checking visibility by Name is safer if we scan by folder in Server
            holder.visibilitySwitch.setOnCheckedChangeListener(null)
            holder.visibilitySwitch.isChecked = !visibilityManager.isVideoHidden(video.name)
            
            holder.visibilitySwitch.setOnCheckedChangeListener { _, isChecked ->
                visibilityManager.setVideoHidden(video.name, !isChecked)
            }
        }

        override fun getItemCount() = videos.size

        private fun formatSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
    }
}
