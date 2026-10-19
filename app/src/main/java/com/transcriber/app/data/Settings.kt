package com.transcriber.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val apiKey: String = "",
    val claudeModel: String = Settings.DEFAULT_CLAUDE_MODEL,
    val threads: Int = Settings.defaultThreads(),
    val useVad: Boolean = true,
    val model: String = "small",
    val language: String = "ru",
    val preset: String = "transcript",
)

class Settings(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state
    val value get() = _state.value

    private fun read() = AppSettings(
        apiKey = prefs.getString("apiKey", "") ?: "",
        claudeModel = prefs.getString("claudeModel", DEFAULT_CLAUDE_MODEL) ?: DEFAULT_CLAUDE_MODEL,
        threads = prefs.getInt("threads", defaultThreads()),
        useVad = prefs.getBoolean("useVad", true),
        model = prefs.getString("model", "small") ?: "small",
        language = prefs.getString("language", "ru") ?: "ru",
        preset = prefs.getString("preset", "transcript") ?: "transcript",
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        prefs.edit()
            .putString("apiKey", s.apiKey.trim())
            .putString("claudeModel", s.claudeModel.trim().ifEmpty { DEFAULT_CLAUDE_MODEL })
            .putInt("threads", s.threads)
            .putBoolean("useVad", s.useVad)
            .putString("model", s.model)
            .putString("language", s.language)
            .putString("preset", s.preset)
            .apply()
        _state.value = read()
    }

    companion object {
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
        fun maxThreads() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        fun defaultThreads() = maxThreads().coerceIn(1, 4)
    }
}
