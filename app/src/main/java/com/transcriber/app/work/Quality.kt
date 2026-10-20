package com.transcriber.app.work

import com.transcriber.app.data.Segment

/** Настройки декодирования Whisper и очистка результата. */
object Quality {
    data class Params(val beamSize: Int, val bestOf: Int, val temperatureInc: Float)

    fun params(mode: String) = when (mode) {
        // без повторных попыток с другой «температурой» — заметно быстрее на сложных местах
        "fast" -> Params(beamSize = 1, bestOf = 1, temperatureInc = 0f)
        // поиск по 5 лучевым вариантам — точнее, особенно на именах и терминах
        "accurate" -> Params(beamSize = 5, bestOf = 5, temperatureInc = 0.2f)
        else -> Params(beamSize = 1, bestOf = 2, temperatureInc = 0.2f)
    }

    /**
     * Подсказка для Whisper: пример текста с пунктуацией на нужном языке (модель подражает стилю)
     * и словарь терминов — так имена и названия распознаются правильнее.
     */
    fun prompt(language: String, vocabulary: String): String? {
        val style = when (language) {
            "ru" -> "Добрый день. Сегодня обсудим, как это работает, и ответим на вопросы."
            "uk" -> "Добрий день. Сьогодні обговоримо, як це працює, і відповімо на запитання."
            "en" -> "Hello. Today we'll discuss how it works, and answer your questions."
            else -> ""
        }
        val terms = vocabulary.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val t = if (terms.isEmpty()) "" else terms.joinToString(", ", postfix = ".")
        return listOf(style, t).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /** Типичные «галлюцинации» Whisper на тишине, музыке и в конце записи. */
    private val JUNK = listOf(
        Regex("""(?iu)^\s*(продолжение следует|субтитры.*|редактор субтитров.*|корректор.*|спасибо за просмотр|подписывайтесь на канал.*|ставьте лайк.*)[.!…]*\s*$"""),
        Regex("""(?iu)^\s*(thanks for watching|thank you for watching|subtitles by.*|please subscribe.*)[.!…]*\s*$"""),
        Regex("""(?iu)dimatorzok|амара\.орг|amara\.org"""),
        Regex("""(?iu)^\s*[\[(]?(музыка|music|аплодисменты|applause|смех|laughter)[\])]?\s*$"""),
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), "").trim()

    /** Слово или фраза, повторённые 4+ раза подряд внутри фрагмента, — признак зацикливания: оставляем один раз. */
    fun collapseLoops(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return text
        val keys = words.map(::norm)
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            var collapsed = false
            for (n in 1..6) {
                if (i + 4 * n > words.size) break
                var reps = 1
                var j = i + n
                while (j + n <= words.size && (0 until n).all { keys[j + it] == keys[i + it] }) { reps++; j += n }
                if (reps >= 4) {
                    out.addAll(words.subList(i, i + n)); i = j; collapsed = true; break
                }
            }
            if (!collapsed) { out += words[i]; i++ }
        }
        return out.joinToString(" ")
    }

    fun clean(segs: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        var repeats = 0
        for (s in segs) {
            val text = collapseLoops(s.text.trim())
            if (text.isEmpty() || JUNK.any { it.containsMatchIn(text) }) continue
            if (norm(text).isEmpty()) continue
            val prev = out.lastOrNull()
            if (prev != null && norm(prev.text) == norm(text)) {
                // одна и та же фраза подряд: 2 раза бывает в речи, 3 и больше — зацикливание
                repeats++
                if (repeats >= 2) continue
            } else repeats = 0
            out += s.copy(text = text)
        }
        return out
    }
}
