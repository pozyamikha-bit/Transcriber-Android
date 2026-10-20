package com.transcriber.app

import com.transcriber.app.data.Segment
import com.transcriber.app.work.Diarizer
import com.transcriber.app.work.Quality
import com.transcriber.app.work.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun seg(a: Double, b: Double, t: String, sp: Int = -1) = Segment(a, b, t, sp)

    @Test fun dropsKnownHallucinations() {
        val out = Quality.clean(listOf(
            seg(0.0, 2.0, "Добрый день, коллеги."),
            seg(2.0, 4.0, "Продолжение следует..."),
            seg(4.0, 6.0, "Субтитры сделал DimaTorzok"),
            seg(6.0, 8.0, "[Музыка]"),
            seg(8.0, 9.0, "Начнём."),
        ))
        assertEquals(listOf("Добрый день, коллеги.", "Начнём."), out.map { it.text })
    }

    @Test fun dropsRepeatedSegments() {
        val out = Quality.clean(List(6) { seg(it.toDouble(), it + 1.0, "Спасибо.") } + seg(7.0, 8.0, "Дальше."))
        assertEquals(listOf("Спасибо.", "Спасибо.", "Дальше."), out.map { it.text })
    }

    @Test fun collapsesLoopsInsideSegment() {
        assertEquals("и вот так и так далее", Quality.collapseLoops("и вот так и так и так и так и так далее"))
        assertEquals("обычная фраза без повторов", Quality.collapseLoops("обычная фраза без повторов"))
    }

    @Test fun promptHasStyleAndTerms() {
        val p = Quality.prompt("ru", "Битрикс24, CRM;  Иван Петров\n")!!
        assertTrue(p.startsWith("Добрый день."))
        assertTrue(p.endsWith("Битрикс24, CRM, Иван Петров."))
        assertNull(Quality.prompt("de", ""))
    }

    @Test fun speakersByOverlapAndOrder() {
        val turns = Diarizer.renumber(listOf(
            Diarizer.Turn(0.0, 5.0, 7), Diarizer.Turn(5.0, 9.0, 3), Diarizer.Turn(9.0, 12.0, 7),
        ))
        assertEquals(listOf(0, 1, 0), turns.map { it.speaker })
        val segs = Diarizer.assign(listOf(
            seg(0.5, 4.0, "a"), seg(4.0, 8.5, "b"), seg(10.0, 11.0, "c"), seg(20.0, 21.0, "d"),
        ), turns)
        assertEquals(listOf(0, 1, 0, 0), segs.map { it.speaker })
    }

    @Test fun txtGroupsBySpeaker() {
        val segs = listOf(seg(0.0, 1.0, "Привет.", 0), seg(1.0, 2.0, "Как дела?", 0), seg(2.0, 3.0, "Хорошо.", 1))
        val names = { n: Int -> if (n == 0) "Анна" else "Спикер ${n + 1}" }
        assertEquals("Анна: Привет. Как дела?\n\nСпикер 2: Хорошо.\n", Subtitles.txt(segs, names))
        assertTrue(Subtitles.srt(segs, names).contains("Анна: Привет."))
    }
}
