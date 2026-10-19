package com.transcriber.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Candidate(val file: String, val t: Double)

data class Section(
    val title: String,
    val body: String,
    val start: Double,
    val candidates: List<Candidate> = emptyList(),
    val images: List<String> = emptyList(),
)

data class Doc(
    val title: String,
    val summary: String,
    val sections: List<Section>,
    val appendix: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title); put("summary", summary); put("appendix", appendix)
        put("sections", JSONArray().apply {
            sections.forEach { s ->
                put(JSONObject().apply {
                    put("title", s.title); put("body", s.body); put("start", s.start)
                    put("candidates", JSONArray().apply {
                        s.candidates.forEach { put(JSONObject().put("file", it.file).put("t", it.t)) }
                    })
                    put("images", JSONArray(s.images))
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Doc {
            val arr = o.getJSONArray("sections")
            return Doc(
                title = o.optString("title", "Документ"),
                summary = o.optString("summary"),
                appendix = o.optString("appendix"),
                sections = List(arr.length()) { i ->
                    val s = arr.getJSONObject(i)
                    val c = s.optJSONArray("candidates") ?: JSONArray()
                    val im = s.optJSONArray("images") ?: JSONArray()
                    Section(
                        title = s.optString("title"),
                        body = s.optString("body"),
                        start = s.optDouble("start", 0.0),
                        candidates = List(c.length()) { k ->
                            val cc = c.getJSONObject(k); Candidate(cc.getString("file"), cc.getDouble("t"))
                        },
                        images = List(im.length()) { k -> im.getString(k) },
                    )
                },
            )
        }
    }
}
