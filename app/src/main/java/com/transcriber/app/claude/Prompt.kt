package com.transcriber.app.claude

import com.transcriber.app.data.Doc
import com.transcriber.app.data.Section
import com.transcriber.app.data.Segment
import org.json.JSONException
import org.json.JSONObject

/** Тексты и разбор ответа — те же, что в postprocess.py настольной версии. */
object Prompt {
    const val SYSTEM =
        "Ты превращаешь транскрипт записи (вебинар, лекция, совещание) в структурированный документ. " +
            "Транскрипт содержит метки времени в секундах в формате [сек] текст. " +
            "Пиши на языке транскрипта. Опирайся только на содержание записи, ничего не выдумывай. " +
            "Транскрипт получен автоматическим распознаванием и может содержать ошибки в терминах и именах: " +
            "исправляй очевидные ошибки по контексту. " +
            "Для каждого раздела укажи start — секунду, с которой докладчик начинает эту тему или шаг " +
            "(значение из меток времени транскрипта). Разделы идут в хронологическом порядке. " +
            "Если перед репликами указаны спикеры (имена или «Спикер N»), учитывай, кто что сказал, " +
            "и называй участников так же."

    val SCHEMA: JSONObject
        get() = JSONObject(
            """
            {"type":"object","properties":{
              "title":{"type":"string"},
              "summary":{"type":"string","description":"Markdown"},
              "appendix":{"type":"string","description":"Markdown в конец документа; пустая строка, если не нужно"},
              "sections":{"type":"array","items":{"type":"object","properties":{
                  "title":{"type":"string"},
                  "body":{"type":"string","description":"Markdown"},
                  "start":{"type":"number"}},
                "required":["title","body","start"],"additionalProperties":false}}},
             "required":["title","summary","sections","appendix"],"additionalProperties":false}
            """.trimIndent()
        )

    private val MANUAL_FORMAT = """
Верни результат СТРОГО в виде одного JSON-объекта — без пояснений до и после него, в одном блоке кода ```json.

Формат:
{
  "title": "Заголовок документа",
  "summary": "Вводная часть в Markdown (абзацы, списки) — как указано в задании",
  "sections": [
    {
      "title": "Заголовок раздела или шага",
      "body": "Текст раздела в Markdown (абзацы, нумерованные и маркированные списки, **выделение**)",
      "start": 125
    }
  ],
  "appendix": "Markdown, который будет добавлен в конец документа (таблицы, списки, заголовки ##). Пустая строка, если задание приложения не требует."
}

Требования к JSON:
- start — число: секунда записи, с которой начинается этот раздел или шаг (берётся из меток [секунды] транскрипта). По этой секунде из видео будет вырезан скриншот, поэтому указывай момент, когда докладчик реально начинает тему или показывает действие.
- Разделы в хронологическом порядке, start не убывает.
- Внутри строк экранируй кавычки и переводы строк (\" и \n), чтобы JSON был валидным.
- Внутри body, summary и appendix допустим Markdown: **жирный**, `код`, списки (нумерованные — «1. », маркированные — «- »), таблицы через |, чекбоксы «- [ ] », заголовки ## только в appendix.
- Других полей не добавляй.
""".trim()

    fun transcriptForLlm(segments: List<Segment>, name: (Int) -> String = { "Спикер ${it + 1}" }) =
        segments.joinToString("\n") {
            "[${it.start.toInt()}] " + (if (it.speaker >= 0) "${name(it.speaker)}: " else "") + it.text
        }

    fun build(segments: List<Segment>, presetPrompt: String, name: (Int) -> String = { "Спикер ${it + 1}" }) =
        "$SYSTEM\n\nЗАДАНИЕ\n$presetPrompt\n\nФОРМАТ ОТВЕТА\n$MANUAL_FORMAT\n\nТРАНСКРИПТ\n${transcriptForLlm(segments, name)}\n"

    /** Достаёт документ из ответа Claude: терпим к ```json-обёртке и тексту вокруг. */
    fun parseReply(text: String): Doc {
        val m = Regex("```(?:json)?\\s*(\\{.*\\})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
        val raw = m?.groupValues?.get(1) ?: run {
            val a = text.indexOf('{')
            val b = text.lastIndexOf('}')
            if (a >= 0 && b > a) text.substring(a, b + 1) else ""
        }
        if (raw.isBlank()) throw IllegalArgumentException("В ответе не найден JSON")
        val data = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw IllegalArgumentException("JSON не разобран: ${e.message}")
        }
        val arr = data.optJSONArray("sections") ?: throw IllegalArgumentException("В JSON нет списка sections")
        val sections = List(arr.length()) { i ->
            val s = arr.optJSONObject(i) ?: throw IllegalArgumentException("Раздел ${i + 1}: не объект")
            if (!s.has("title") || !s.has("start")) {
                throw IllegalArgumentException("Раздел ${i + 1}: нужны поля title и start (число)")
            }
            val start = s.optDouble("start", Double.NaN)
            if (start.isNaN()) throw IllegalArgumentException("Раздел ${i + 1}: start должен быть числом")
            Section(title = s.optString("title"), body = s.optString("body"), start = start)
        }
        if (sections.isEmpty()) throw IllegalArgumentException("Список sections пуст")
        return Doc(
            title = data.optString("title").ifBlank { "Документ" },
            summary = data.optString("summary"),
            sections = sections,
            appendix = data.optString("appendix"),
        )
    }
}
