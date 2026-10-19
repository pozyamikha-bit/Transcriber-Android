package com.transcriber.app.doc

import android.graphics.BitmapFactory
import com.transcriber.app.data.Doc
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Минимальный генератор Word (.docx): заголовки, абзацы, списки, таблицы, картинки. */
class Docx {
    private val body = StringBuilder()
    private val images = mutableListOf<File>()

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun runs(text: String, bold: Boolean = false, size: Int? = null, color: String? = null): String {
        val sb = StringBuilder()
        for (part in splitKeep(text)) {
            if (part.isEmpty()) continue
            var t = part
            var b = bold
            var code = false
            if (t.startsWith("**") && t.endsWith("**") && t.length > 4) { t = t.substring(2, t.length - 2); b = true }
            else if (t.startsWith("`") && t.endsWith("`") && t.length > 2) { t = t.substring(1, t.length - 1); code = true }
            sb.append("<w:r><w:rPr>")
            if (code) sb.append("""<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>""")
            if (b) sb.append("<w:b/>")
            if (color != null) sb.append("""<w:color w:val="$color"/>""")
            if (size != null) sb.append("""<w:sz w:val="$size"/>""")
            sb.append("""</w:rPr><w:t xml:space="preserve">${x(t)}</w:t></w:r>""")
        }
        return sb.toString()
    }

    private fun splitKeep(text: String): List<String> {
        val res = mutableListOf<String>()
        var last = 0
        for (m in Regex("""\*\*.+?\*\*|`.+?`""").findAll(text)) {
            res += text.substring(last, m.range.first)
            res += m.value
            last = m.range.last + 1
        }
        res += text.substring(last)
        return res
    }

    private fun para(content: String, style: String? = null, indentLeft: Int? = null, hanging: Int? = null, center: Boolean = false) {
        body.append("<w:p><w:pPr>")
        if (style != null) body.append("""<w:pStyle w:val="$style"/>""")
        if (indentLeft != null) body.append("""<w:ind w:left="$indentLeft"${if (hanging != null) " w:hanging=\"$hanging\"" else ""}/>""")
        if (center) body.append("""<w:jc w:val="center"/>""")
        body.append("</w:pPr>").append(content).append("</w:p>")
    }

    fun heading(text: String, level: Int, stamp: String? = null) {
        val style = if (level == 0) "Title" else "Heading$level"
        var c = runs(text)
        if (stamp != null) c += runs("  ·  $stamp", size = 20, color = "6B7280")
        para(c, style)
    }

    fun image(file: File, widthCm: Double = 16.0) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return
        images += file
        val n = images.size
        val cx = (widthCm * 360000).toLong()
        val cy = cx * o.outHeight / o.outWidth
        val drawing = """<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$n" name="Picture $n"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="$n" name="img$n.jpg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdImg$n"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""
        para(drawing, center = true)
    }

    /** Те же правила Markdown, что и в MdHtml; нумерацию пишем текстом, чтобы она начиналась заново в каждом разделе. */
    fun markdown(text: String) {
        val rows = mutableListOf<String>()
        var counter = 0
        var inOl = false
        fun flush() {
            val cells = MdHtml.tableCells(rows)
            rows.clear()
            if (cells.isEmpty()) return
            val cols = cells.maxOf { it.size }
            body.append("""<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>""")
            for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                body.append("""<w:$side w:val="single" w:sz="4" w:space="0" w:color="BFC5CC"/>""")
            }
            body.append("</w:tblBorders></w:tblPr><w:tblGrid>")
            repeat(cols) { body.append("<w:gridCol/>") }
            body.append("</w:tblGrid>")
            cells.forEachIndexed { ri, r ->
                body.append("<w:tr>")
                for (ci in 0 until cols) {
                    body.append("<w:tc><w:p>").append(runs(r.getOrElse(ci) { "" }, bold = ri == 0)).append("</w:p></w:tc>")
                }
                body.append("</w:tr>")
            }
            body.append("</w:tbl>")
            para("")
        }
        for (line in text.lines()) {
            if (line.trimStart().startsWith("|")) { rows += line; continue }
            if (rows.isNotEmpty()) flush()
            val m = Regex("""(\s*)(?:([-*])|(\d+)\.)\s+(.*)""").matchEntire(line)
            if (m != null) {
                val raw = m.groupValues[4]
                val isBox = Regex("""\[( |x|X)]\s+.*""").matches(raw)
                val t = MdHtml.checkbox(raw)
                when {
                    m.groupValues[1].length >= 2 -> para(runs("◦ ") + runs(t), indentLeft = 1080, hanging = 340)
                    m.groupValues[3].isNotEmpty() -> {
                        counter = if (inOl) counter + 1 else 1
                        inOl = true
                        para(runs("$counter. ") + runs(t), indentLeft = 510, hanging = 340)
                    }
                    else -> {
                        inOl = false
                        para((if (isBox) "" else runs("•  ")) + runs(t), indentLeft = 510, hanging = 340)
                    }
                }
                continue
            }
            inOl = false
            val h = Regex("""(#{2,3})\s+(.*)""").matchEntire(line)
            if (h != null) heading(h.groupValues[2], h.groupValues[1].length)
            else if (line.isNotBlank()) para(runs(line))
        }
        if (rows.isNotEmpty()) flush()
    }

    fun save(out: File) {
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(name: String, s: String) {
                z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            images.forEachIndexed { i, _ ->
                rels.append("""<Relationship Id="rIdImg${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/img${i + 1}.jpg"/>""")
            }
            rels.append("</Relationships>")
            put("word/_rels/document.xml.rels", rels.toString())
            put("word/styles.xml", STYLES)
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""")
            images.forEachIndexed { i, f ->
                z.putNextEntry(ZipEntry("word/media/img${i + 1}.jpg")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    companion object {
        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:color w:val="17365D"/><w:sz w:val="48"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3864"/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="300" w:after="120"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="24"/></w:rPr></w:style></w:styles>"""

        fun build(doc: Doc, numbered: Boolean, imagesDir: File, out: File) {
            val d = Docx()
            d.heading(doc.title, 0)
            d.markdown(doc.summary)
            doc.sections.forEachIndexed { i, sec ->
                d.heading(if (numbered) "${i + 1}. ${sec.title}" else sec.title, 2, DocBuilder.ts(sec.start))
                for (name in sec.images) {
                    val f = File(imagesDir, name)
                    if (f.exists()) d.image(f)
                }
                d.markdown(sec.body)
            }
            if (doc.appendix.isNotBlank()) d.markdown(doc.appendix)
            d.save(out)
        }
    }
}
