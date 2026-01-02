package com.thiyagu.media_server.model

import androidx.documentfile.provider.DocumentFile

enum class NodeType { FOLDER, FILE, LOADING }

data class MediaNode(
    val type: NodeType,
    val name: String,
    val uriString: String,
    val file: DocumentFile? = null, 
    val level: Int,
    var isExpanded: Boolean = false,
    var isLoaded: Boolean = false,
    var isLoading: Boolean = false,
    var isVisible: Boolean = true,
    val size: Long = 0,
    val children: MutableList<MediaNode> = mutableListOf()
)
