package com.thiyagu.media_server.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.thiyagu.media_server.R
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

internal class ClientDiagnosticsController(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val lastServerIpKey: String,
    private val lastServerPortKey: String,
    private val phaseProvider: () -> ConnectionPhase,
    private val lastErrorProvider: () -> String?,
    private val clientIdProvider: () -> String
) {
    private val eventLog = ArrayDeque<String>(50)

    fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (eventLog.size >= 50) {
            eventLog.removeFirst()
        }
        eventLog.addLast("$timestamp $message")
    }

    fun showDiagnosticsDialog() {
        val logText = buildString {
            appendLine("Phase: ${phaseProvider()}")
            lastErrorProvider()?.let { appendLine("LastError: $it") }
            appendLine("ClientId: ${clientIdProvider()}")
            appendLine(
                "LastServer: ${prefs.getString(lastServerIpKey, "-")}:${prefs.getInt(lastServerPortKey, -1)}"
            )
            appendLine("---")
            eventLog.forEach { appendLine(it) }
        }.trim()

        val content = TextView(context).apply {
            text = if (logText.isBlank()) "No diagnostics yet." else logText
            setTextColor(ContextCompat.getColor(context, R.color.lanflix_text_main))
            setPadding(40, 20, 40, 20)
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(context)
            .setTitle("Diagnostics")
            .setView(content)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("LANflix Diagnostics", content.text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
