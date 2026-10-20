@file:OptIn(ExperimentalMaterial3Api::class)

package com.transcriber.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Build

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9DB8FF))
        else -> lightColorScheme(primary = Color(0xFF2F5BD3))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

fun copyToClipboard(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun readClipboard(ctx: Context): String {
    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
}

fun fmtTime(sec: Double): String {
    val s = sec.toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}

val LANGUAGES = listOf(
    "ru" to "Русский", "en" to "English", "uk" to "Українська", "de" to "Deutsch",
    "fr" to "Français", "es" to "Español", "auto" to "Автоопределение",
)

fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
    "txt", "srt", "vtt", "md" -> "text/plain"
    "json" -> "application/json"
    "html" -> "text/html"
    "zip" -> "application/zip"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else -> "application/octet-stream"
}

/** Копия файла с человеческим именем во временную папку — для отправки. */
private fun shareCopy(ctx: Context, file: File, name: String): Uri {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, name.replace('/', '_'))
    file.copyTo(f, overwrite = true)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
}

fun shareFile(ctx: Context, file: File, name: String) {
    if (!file.exists()) { toast(ctx, "Файл ещё не готов"); return }
    val uri = shareCopy(ctx, file, name)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mimeFor(name)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, name))
}

/** Кнопка формата: «Поделиться» или «Сохранить в файл». */
@Composable
fun ExportChip(label: String, file: File, name: String, enabled: Boolean = true) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeFor(name))) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                toast(ctx, "Сохранено")
            } catch (e: Exception) {
                toast(ctx, "Не удалось сохранить: ${e.message}")
            }
        }
    }
    Box {
        AssistChip(onClick = { menu = true }, label = { Text(label) }, enabled = enabled)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menu = false; shareFile(ctx, file, name) })
            DropdownMenuItem(text = { Text("Сохранить в файл") }, onClick = {
                menu = false
                if (file.exists()) saver.launch(name) else toast(ctx, "Файл ещё не готов")
            })
        }
    }
}

@Composable
fun <T> Dropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (v, t) ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onSelect(v); expanded = false })
            }
        }
    }
}

@Composable
fun FileImage(file: File, modifier: Modifier = Modifier, maxSize: Int = 480) {
    val bmp by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= maxSize) s *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

/** Markdown для показа на экране: **жирный**, `код`, списки, чекбоксы, ## заголовки. */
fun mdAnnotated(md: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val lines = md.trim().lines()
        lines.forEachIndexed { idx, raw ->
            var line = raw
            var heading = false
            Regex("""^#{1,3}\s+(.*)""").matchEntire(line)?.let { line = it.groupValues[1]; heading = true }
            Regex("""^(\s*)[-*]\s+(.*)""").matchEntire(line)?.let { m ->
                val indent = if (m.groupValues[1].length >= 2) "    " else ""
                val body = m.groupValues[2]
                line = indent + when {
                    body.startsWith("[ ] ") -> "☐ " + body.drop(4)
                    body.startsWith("[x] ", true) -> "☑ " + body.drop(4)
                    else -> (if (indent.isEmpty()) "• " else "◦ ") + body
                }
            }
            if (Regex("""^\s*\|?\s*:?-{2,}.*""").matches(line) && line.contains("|")) return@forEachIndexed
            if (line.trimStart().startsWith("|")) line = line.trim().trim('|').split("|").joinToString("  ·  ") { it.trim() }
            if (heading) pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            var last = 0
            for (m in Regex("""\*\*(.+?)\*\*|`(.+?)`""").findAll(line)) {
                append(line.substring(last, m.range.first))
                if (m.groupValues[1].isNotEmpty()) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                    append(m.groupValues[1])
                } else {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    append(m.groupValues[2])
                }
                pop()
                last = m.range.last + 1
            }
            append(line.substring(last))
            if (heading) pop()
            if (idx < lines.lastIndex) append("\n")
        }
    }
