package com.transcriber.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Segment(val start: Double, val end: Double, val text: String)

object Status {
    const val QUEUED = "queued"
    const val PREPARING = "preparing"
    const val RUNNING = "running"
    const val AWAITING = "awaiting"
    const val POSTPROCESSING = "postprocessing"
    const val DONE = "done"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"

    val ACTIVE = setOf(QUEUED, PREPARING, RUNNING, POSTPROCESSING)
}

data class Job(
    val id: String,
    val name: String,
    val input: String,
    val model: String,
    val preset: String,
    val language: String,
    val status: String = Status.QUEUED,
    val message: String = "В очереди",
    val progress: Float = 0f,
    val duration: Double? = null,
    val detectedLanguage: String? = null,
    val created: Long = System.currentTimeMillis(),
    val hasDocument: Boolean = false,
    val postprocessError: String? = null,
    val segments: List<Segment> = emptyList(),
) {
    val isActive get() = status in Status.ACTIVE
    val stem get() = name.substringBeforeLast('.').ifBlank { "transcript" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("input", input); put("model", model)
        put("preset", preset); put("language", language); put("status", status)
        put("message", message); put("progress", progress.toDouble())
        put("duration", duration ?: JSONObject.NULL)
        put("detected_language", detectedLanguage ?: JSONObject.NULL)
        put("created", created); put("has_document", hasDocument)
        put("postprocess_error", postprocessError ?: JSONObject.NULL)
        put("segments", segmentsToJson(segments))
    }

    companion object {
        fun fromJson(o: JSONObject) = Job(
            id = o.getString("id"),
            name = o.optString("name"),
            input = o.optString("input"),
            model = o.optString("model", "small"),
            preset = o.optString("preset", "transcript"),
            language = o.optString("language"),
            status = o.optString("status", Status.ERROR),
            message = o.optString("message"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            duration = if (o.isNull("duration")) null else o.optDouble("duration"),
            detectedLanguage = if (o.isNull("detected_language")) null else o.optString("detected_language"),
            created = o.optLong("created"),
            hasDocument = o.optBoolean("has_document"),
            postprocessError = if (o.isNull("postprocess_error")) null else o.optString("postprocess_error"),
            segments = segmentsFromJson(o.optJSONArray("segments") ?: JSONArray()),
        )

        fun segmentsToJson(segs: List<Segment>) = JSONArray().apply {
            segs.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("text", it.text)) }
        }

        fun segmentsFromJson(a: JSONArray) = List(a.length()) {
            val s = a.getJSONObject(it)
            Segment(s.getDouble("start"), s.getDouble("end"), s.getString("text"))
        }
    }
}
