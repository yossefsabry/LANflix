package com.thiyagu.media_server

import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.thiyagu.media_server.ui.adapter.MediaTreeAdapter
import com.thiyagu.media_server.utils.UiState
import com.thiyagu.media_server.viewmodel.MediaManagementViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class MediaManagementActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MediaTreeAdapter
    
    // Inject ViewModel using Koin
    private val viewModel: MediaManagementViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_management)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        recyclerView = findViewById(R.id.recycler_view_videos)
        recyclerView.itemAnimator = null 
        
        // Initialize Adapter with callbacks to ViewModel
        adapter = MediaTreeAdapter(
            onExpandClick = { node -> viewModel.onFolderExpand(node) },
            onVisibilityToggle = { node, isVisible -> viewModel.toggleVisibility(node, isVisible) }
        )
        recyclerView.adapter = adapter

        // Observe State
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is UiState.Loading -> {
                        // Optional: Show loading indicator if initial load
                    }
                    is UiState.Success -> {
                        adapter.submitList(state.data)
                    }
                    is UiState.Error -> {
                        Toast.makeText(this@MediaManagementActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        
        // Initial Load
        if (savedInstanceState == null) {
             val folderUriString = intent.getStringExtra("FOLDER_URI")
             viewModel.loadMedia(folderUriString)
        }
    }
}
