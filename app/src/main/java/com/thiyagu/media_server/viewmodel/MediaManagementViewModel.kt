package com.thiyagu.media_server.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thiyagu.media_server.model.MediaNode
import com.thiyagu.media_server.model.NodeType
import com.thiyagu.media_server.utils.UiState
import com.thiyagu.media_server.utils.VideoVisibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MediaManagementViewModel(private val context: Context) : ViewModel() {

    private val visibilityManager = VideoVisibilityManager(context)
    
    // Master List
    private val rootNodes = mutableListOf<MediaNode>()
    
    private val _uiState = MutableStateFlow<UiState<List<MediaNode>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<MediaNode>>> = _uiState.asStateFlow()

    fun loadMedia(folderUriString: String?) {
        if (folderUriString == null) {
            _uiState.value = UiState.Error("No Folder Selected")
            return
        }
        
        _uiState.value = UiState.Loading
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folderUri = Uri.parse(folderUriString)
                val rootDir = DocumentFile.fromTreeUri(context, folderUri)

                if (rootDir != null) {
                    val nodes = scanDirectory(rootDir, 0)
                    
                    withContext(Dispatchers.Main) {
                        rootNodes.clear()
                        rootNodes.addAll(nodes)
                        updateDisplayList()
                    }
                } else {
                    _uiState.value = UiState.Error("Cannot access directory", null)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load media: ${e.message}", e)
            }
        }
    }
    
    private fun scanDirectory(root: DocumentFile, level: Int): List<MediaNode> {
        val nodes = mutableListOf<MediaNode>()
        if (!root.isDirectory) return nodes

        val files = root.listFiles()
        val dirs = files.filter { it.isDirectory }.sortedBy { it.name }
        val videos = files.filter { it.isFile && isValidVideo(it.name) }.sortedBy { it.name }
        
        for (dir in dirs) {
            nodes.add(createFolderNode(dir, level))
        }
        
        for (video in videos) {
            nodes.add(createFileNode(video, level))
        }
        
        return nodes
    }
    
    private fun createFolderNode(file: DocumentFile, level: Int): MediaNode {
        return MediaNode(
            type = NodeType.FOLDER,
            name = file.name ?: "Unknown",
            uriString = file.uri.toString(),
            file = file,
            level = level,
            isExpanded = false,
            isLoaded = false,
            isVisible = true,
            children = mutableListOf()
        )
    }

    private fun createFileNode(file: DocumentFile, level: Int): MediaNode {
        val isHidden = visibilityManager.isVideoHidden(file.uri.toString())
        return MediaNode(
            type = NodeType.FILE,
            name = file.name ?: "Unknown",
            uriString = file.uri.toString(),
            file = null,
            level = level,
            size = file.length(),
            isVisible = !isHidden,
            isLoaded = true
        )
    }
    
    private fun isValidVideo(name: String?): Boolean {
        val n = name?.lowercase(Locale.getDefault()) ?: return false
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
    }

    private fun updateDisplayList() {
        val newList = mutableListOf<MediaNode>()
        flattenList(rootNodes, newList)
        _uiState.value = UiState.Success(newList)
    }
    
    private fun flattenList(nodes: List<MediaNode>, destination: MutableList<MediaNode>) {
        for (node in nodes) {
            destination.add(node)
            if (node.type == NodeType.FOLDER && node.isExpanded) {
                if (node.isLoading) {
                    destination.add(MediaNode(NodeType.LOADING, "Loading...", "", null, node.level + 1))
                } else {
                    flattenList(node.children, destination)
                }
            }
        }
    }
    
    fun onFolderExpand(node: MediaNode) {
        if (node.isExpanded) {
            node.isExpanded = false
            updateDisplayList()
        } else {
            node.isExpanded = true
            if (!node.isLoaded) {
                loadFolderContents(node)
            }
            updateDisplayList()
        }
    }

    private fun loadFolderContents(node: MediaNode) {
        if (node.file == null || !node.file.isDirectory) return
        
        node.isLoading = true
        updateDisplayList()

        viewModelScope.launch(Dispatchers.IO) {
            val children = scanDirectory(node.file, node.level + 1)
            
            withContext(Dispatchers.Main) {
                node.children.clear()
                node.children.addAll(children)
                node.isLoaded = true
                node.isLoading = false
                
                if (children.isNotEmpty()) {
                    node.isVisible = children.any { it.isVisible }
                } else {
                    node.isVisible = false 
                }
                
                updateDisplayList()
            }
        }
    }
    
    fun toggleVisibility(node: MediaNode, isVisible: Boolean) {
        node.isVisible = isVisible
        
        if (node.type == NodeType.FILE) {
             visibilityManager.setVideoHidden(node.uriString, !isVisible)
        } else if (node.type == NodeType.FOLDER) {
             if (node.isLoaded) {
                 val allDescendants = collectAllFileUris(node)
                 visibilityManager.setVideosHidden(allDescendants, !isVisible)
                 recursiveSetLocalVisibility(node, isVisible)
             } else {
                 if (node.file != null) {
                     if (!node.isExpanded) node.isExpanded = true
                     loadFolderContents(node)
                 }
             }
        }
    }
    
    private fun recursiveSetLocalVisibility(node: MediaNode, isVisible: Boolean) {
        node.children.forEach { child ->
            child.isVisible = isVisible
            if (child.type == NodeType.FOLDER) {
                recursiveSetLocalVisibility(child, isVisible)
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
}
