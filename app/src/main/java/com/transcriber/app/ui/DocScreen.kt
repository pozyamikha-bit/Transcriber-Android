@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.transcriber.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcriber.app.app
import com.transcriber.app.data.Doc
import com.transcriber.app.data.Presets
import com.transcriber.app.doc.DocBuilder
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DocScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mgr = ctx.app.jobs
    val jobs by mgr.jobs.collectAsState()
    val job = jobs.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(mgr.document(id)) }
    var busy by remember { mutableStateOf(false) }
    var addFor by remember { mutableStateOf<Int?>(null) }
    val dir = mgr.dir(id)
    val cand = File(dir, "candidates")
    val preset = job?.let { Presets.get(ctx, it.preset) }
    val canAdd = preset?.screenshots == "sections" && mgr.videoAvailable(id)

    fun launchDoc(block: suspend () -> Doc) {
        busy = true
        scope.launch {
            try {
                doc = block()
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Ошибка")
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(doc?.title ?: "Документ", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { pad ->
        val d = doc
        if (d == null || job == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("Документа ещё нет") }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportChip("Word", File(dir, "document.docx"), "${job.stem}.docx", !busy)
                        ExportChip("Markdown", File(dir, "document.md"), "${job.stem}.md", !busy)
                        ExportChip("HTML", File(dir, "document.html"), "${job.stem}.html", !busy)
                        ExportChip("ZIP", File(dir, "document.zip"), "${job.stem}.zip", !busy)
                    }
                    if (preset?.screenshots == "sections") {
                        Text(
                            "Нажмите на кадр, чтобы добавить его в документ или убрать. Документ пересобирается сразу.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (d.summary.isNotBlank()) Text(mdAnnotated(d.summary), style = MaterialTheme.typography.bodyMedium)
                }
            }
            itemsIndexed(d.sections) { i, sec ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val prefix = if (preset?.numbered == true) "${i + 1}. " else ""
                        Text("$prefix${sec.title}  ·  ${DocBuilder.ts(sec.start)}", style = MaterialTheme.typography.titleSmall)
                        if (sec.candidates.isNotEmpty() || canAdd) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(sec.candidates, key = { it.file }) { c ->
                                    val selected = c.file in sec.images
                                    Box(
                                        Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(8.dp))
                                            .border(
                                                if (selected) 3.dp else 1.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .clickable(enabled = !busy) {
                                                val names = if (selected) sec.images - c.file else sec.images + c.file
                                                launchDoc { mgr.applySelection(id, mapOf(i to names)) }
                                            },
                                    ) {
                                        FileImage(File(cand, c.file), Modifier.fillMaxSize())
                                        if (selected) Icon(
                                            Icons.Default.CheckCircle, null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                        )
                                        Text(
                                            DocBuilder.ts(c.t),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.align(Alignment.BottomStart)
                                                .padding(4.dp),
                                        )
                                    }
                                }
                                if (canAdd) item {
                                    Box(
                                        Modifier.size(90.dp).clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                            .clickable(enabled = !busy) { addFor = i },
                                        contentAlignment = Alignment.Center,
                                    ) { Text("+ кадр") }
                                }
                            }
                        }
                        Text(mdAnnotated(sec.body), style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (d.appendix.isNotBlank()) item {
                Text(mdAnnotated(d.appendix), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    val sectionIdx = addFor
    if (sectionIdx != null) {
        val start = doc?.sections?.getOrNull(sectionIdx)?.start ?: 0.0
        var text by remember(sectionIdx) { mutableStateOf(fmtTime(start)) }
        AlertDialog(
            onDismissRequest = { addFor = null },
            title = { Text("Кадр на секунде") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("мм:сс или ч:мм:сс") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = parseTime(text)
                    if (t == null) toast(ctx, "Введите время, например 2:15")
                    else {
                        addFor = null
                        launchDoc { mgr.addFrame(id, sectionIdx, t) }
                    }
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addFor = null }) { Text("Отмена") } },
        )
    }
}

fun parseTime(s: String): Double? {
    val parts = s.trim().replace(',', '.').split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    var total = 0.0
    for (p in parts) total = total * 60 + (p.toDoubleOrNull() ?: return null)
    return total
}
