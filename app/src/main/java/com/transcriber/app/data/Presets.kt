package com.transcriber.app.data

import android.content.Context
import org.json.JSONArray

data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val prompt: String,
    val screenshots: String,
) {
    val usesClaude get() = prompt.isNotBlank()
    val numbered get() = id == "howto"
}

object Presets {
    @Volatile
    private var cache: List<Preset>? = null

    fun all(ctx: Context): List<Preset> = cache ?: run {
        val text = ctx.assets.open("presets.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Preset(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description"),
                prompt = o.optString("prompt"),
                screenshots = o.optString("screenshots", "none"),
            )
        }.also { cache = it }
    }

    fun get(ctx: Context, id: String): Preset = all(ctx).firstOrNull { it.id == id } ?: all(ctx).first()
}
