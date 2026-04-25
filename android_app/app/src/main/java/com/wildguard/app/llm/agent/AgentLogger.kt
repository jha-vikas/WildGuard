package com.wildguard.app.llm.agent

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AgentLogger(private val context: Context) {

    private val gson = Gson()
    private val logDir = File(context.filesDir, "agent_logs").also { it.mkdirs() }
    private var currentFile: File? = null
    private var sessionStartMs: Long = 0L
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun startNewConversation() {
        sessionStartMs = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(sessionStartMs))
        currentFile = File(logDir, "conv_$ts.jsonl")
        cleanupOldLogs()
    }

    fun appendTurn(turn: AgentTurn) {
        val file = currentFile ?: return
        val obj = JsonObject()
        obj.addProperty("t", isoFmt.format(Date(turn.timestampMs)))

        when (turn) {
            is AgentTurn.User -> {
                obj.addProperty("role", "user")
                obj.addProperty("content", turn.content)
            }
            is AgentTurn.AssistantThought -> {
                obj.addProperty("role", "assistant")
                obj.addProperty("thought", turn.thought)
                val calls = turn.toolCalls.map { call ->
                    val c = JsonObject()
                    c.addProperty("id", call.id)
                    c.addProperty("name", call.name)
                    c.add("args", call.args)
                    c
                }
                obj.add("tool_calls", gson.toJsonTree(calls))
                turn.tokensUsed?.let { obj.addProperty("tokens", it) }
            }
            is AgentTurn.ToolResult -> {
                obj.addProperty("role", "tool")
                obj.addProperty("tool_call_id", turn.toolCallId)
                obj.addProperty("name", turn.toolName)
                obj.add("result", turn.result)
                obj.addProperty("elapsed_ms", turn.elapsedMs)
                if (turn.isError) obj.addProperty("is_error", true)
            }
            is AgentTurn.Final -> {
                obj.addProperty("role", "assistant")
                obj.addProperty("final", true)
                obj.addProperty("content", turn.content)
                turn.tokensUsed?.let { obj.addProperty("tokens", it) }
            }
            is AgentTurn.Error -> {
                obj.addProperty("role", "error")
                obj.addProperty("message", turn.message)
            }
        }

        try {
            FileOutputStream(file, true).use { fos ->
                fos.write((gson.toJson(obj) + "\n").toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    fun exportAsMarkdown(turns: List<AgentTurn>, providerName: String): String {
        val totalTokens = turns.filterIsInstance<AgentTurn.AssistantThought>()
            .mapNotNull { it.tokensUsed }.sum() +
            turns.filterIsInstance<AgentTurn.Final>().mapNotNull { it.tokensUsed }.sum()
        val toolCallCount = turns.count { it is AgentTurn.ToolResult }
        val totalElapsed = if (turns.size >= 2)
            (turns.last().timestampMs - turns.first().timestampMs) / 1000.0
        else 0.0

        val sb = StringBuilder()
        sb.appendLine("# Agent Session — ${isoFmt.format(Date(sessionStartMs))}")
        sb.appendLine("Provider: $providerName · ${turns.size} turns · $toolCallCount tool calls · ${"%.2f".format(totalElapsed)}s total · $totalTokens tokens")
        sb.appendLine()

        turns.forEachIndexed { index, turn ->
            val turnNum = index + 1
            when (turn) {
                is AgentTurn.User -> {
                    sb.appendLine("## Turn $turnNum — User")
                    sb.appendLine(turn.content)
                }
                is AgentTurn.AssistantThought -> {
                    sb.appendLine("## Turn $turnNum — Assistant (thought)")
                    if (turn.thought.isNotBlank()) sb.appendLine(turn.thought)
                    turn.toolCalls.forEach { call ->
                        sb.appendLine("→ tool_call: ${call.name}(${gson.toJson(call.args)})")
                    }
                }
                is AgentTurn.ToolResult -> {
                    sb.appendLine("## Turn $turnNum — Tool result: ${turn.toolName} (${turn.elapsedMs} ms)")
                    sb.appendLine("```json")
                    sb.appendLine(gson.toJson(turn.result))
                    sb.appendLine("```")
                }
                is AgentTurn.Final -> {
                    sb.appendLine("## Turn $turnNum — Assistant (final answer)")
                    sb.appendLine(turn.content)
                }
                is AgentTurn.Error -> {
                    sb.appendLine("## Turn $turnNum — Error")
                    sb.appendLine(turn.message)
                }
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun saveToDownloads(markdown: String): Boolean = try {
        val filename = "agent_log_${
            SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(sessionStartMs))
        }.md"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(markdown.toByteArray(Charsets.UTF_8))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, filename).writeText(markdown, Charsets.UTF_8)
        }
        true
    } catch (_: Exception) {
        false
    }

    fun createShareIntent(markdown: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_TEXT, markdown)
            putExtra(Intent.EXTRA_SUBJECT, "WildGuard Agent Log")
        }
    }

    private fun cleanupOldLogs() {
        try {
            val files = logDir.listFiles { f -> f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() } ?: return
            if (files.size > MAX_CONVERSATIONS) {
                files.drop(MAX_CONVERSATIONS).forEach { it.delete() }
            }
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        private const val MAX_CONVERSATIONS = 20
    }
}
