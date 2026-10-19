@file:OptIn(ExperimentalMaterial3Api::class)

package com.transcriber.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transcriber.app.app
import com.transcriber.app.data.Presets
import com.transcriber.app.data.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JobsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val jobs by ctx.app.jobs.jobs.collectAsState()
    val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Транскрибатор") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Новая") })
        },
    ) { pad ->
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Выберите аудио или видео — приложение распознает речь прямо на телефоне.\n\n" +
                        "Можно также «Поделиться» файлом из галереи или мессенджера в Транскрибатор.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(jobs, key = { it.id }) { j ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(j.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(j.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${Presets.get(ctx, j.preset).name} · ${j.model} · ${fmt.format(Date(j.created))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                j.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when (j.status) {
                                    Status.ERROR -> MaterialTheme.colorScheme.error
                                    Status.DONE -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (j.isActive) {
                                Spacer(Modifier.height(6.dp))
                                if (j.status == Status.QUEUED) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
