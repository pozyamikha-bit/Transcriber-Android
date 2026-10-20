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
    val quality: String = "balanced",
    val vocabulary: String = "",
    val diarize: Boolean = false,
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
        quality = prefs.getString("quality", "balanced") ?: "balanced",
        vocabulary = prefs.getString("vocabulary", "") ?: "",
        diarize = prefs.getBoolean("diarize", false),
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
            .putString("quality", s.quality)
            .putString("vocabulary", s.vocabulary)
            .putBoolean("diarize", s.diarize)
            .apply()
        _state.value = read()
    }

    companion object {
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
        fun maxThreads() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        /** Число производительных ядер: на них Whisper работает быстрее всего (энергоэффективные только тормозят). */
        fun defaultThreads(): Int {
            val freqs = (0 until maxThreads()).mapNotNull { i ->
                try {
                    java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                } catch (_: Exception) {
                    null
                }
            }
            if (freqs.size < 2) return maxThreads().coerceIn(1, 4)
            val top = freqs.max()
            // ядра не медленнее 70 % самого быстрого — «большие»
            val big = freqs.count { it >= top * 0.7 }
            return big.coerceIn(2, 8)
        }

        val QUALITY = listOf(
            "fast" to "Быстро — без повторных попыток",
            "balanced" to "Сбалансированно",
            "accurate" to "Точно — поиск лучшего варианта (медленнее в 2–3 раза)",
        )
    }
}
