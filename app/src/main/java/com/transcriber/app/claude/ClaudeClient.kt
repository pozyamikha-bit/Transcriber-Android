package com.transcriber.app.claude

import com.transcriber.app.data.Doc
import com.transcriber.app.data.Segment
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/** Структурирование транскрипта через Claude API (потоковый ответ + JSON-схема). */
object ClaudeClient {
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"

    fun structure(
        apiKey: String,
        model: String,
        segments: List<Segment>,
        presetPrompt: String,
        cancelled: () -> Boolean,
    ): Doc {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 32000)
            put("stream", true)
            put("thinking", JSONObject().put("type", "adaptive"))
            put("output_config", JSONObject().put("format",
                JSONObject().put("type", "json_schema").put("schema", Prompt.SCHEMA)))
            put("system", Prompt.SYSTEM)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Задание:\n$presetPrompt\n\nТранскрипт:\n${Prompt.transcriptForLlm(segments)}")
            }))
        }
        val conn = (URL(URL_MESSAGES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = try {
                    JSONObject(err).getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    err.take(300)
                }
                throw IOException(
                    when (code) {
                        401 -> "Неверный API-ключ Claude"
                        404 -> "Модель «$model» не найдена — проверьте настройки"
                        else -> "Claude API: HTTP $code — $msg"
                    }
                )
            }
            val text = StringBuilder()
            var stopReason: String? = null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val ev = try {
                        JSONObject(line.substring(5).trim())
                    } catch (_: Exception) {
                        continue
                    }
                    when (ev.optString("type")) {
                        "content_block_delta" -> {
                            val d = ev.getJSONObject("delta")
                            if (d.optString("type") == "text_delta") text.append(d.optString("text"))
                        }
                        "message_delta" -> stopReason = ev.optJSONObject("delta")?.optString("stop_reason") ?: stopReason
                        "error" -> throw IOException(
                            "Claude API: " + (ev.optJSONObject("error")?.optString("message") ?: "ошибка")
                        )
                    }
                }
            }
            when (stopReason) {
                "refusal" -> throw IOException("Модель отказалась обработать запись")
                "max_tokens" -> throw IOException("Ответ модели обрезан: запись слишком длинная для одного прохода")
            }
            return Prompt.parseReply(text.toString())
        } finally {
            conn.disconnect()
        }
    }
}
