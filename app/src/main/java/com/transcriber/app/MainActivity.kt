package com.transcriber.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.transcriber.app.ui.AppTheme
import com.transcriber.app.ui.DocScreen
import com.transcriber.app.ui.JobScreen
import com.transcriber.app.ui.JobsScreen
import com.transcriber.app.ui.NewJobScreen
import com.transcriber.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)
    private val sharedUris = mutableMapOf<String, Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            AppTheme {
                val nav = rememberNavController()
                LaunchedEffect(pendingRoute) {
                    pendingRoute?.let { nav.navigate(it); pendingRoute = null }
                }
                NavHost(nav, startDestination = "jobs") {
                    composable("jobs") {
                        JobsScreen(
                            onOpen = { nav.navigate("job/$it") },
                            onNew = { nav.navigate("new") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(
                        "new?shared={shared}",
                        arguments = listOf(navArgument("shared") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { e ->
                        val uri = e.arguments?.getString("shared")?.let { sharedUris[it] }
                        NewJobScreen(
                            initialUri = uri,
                            onBack = { nav.popBackStack() },
                            onCreated = { id -> nav.navigate("job/$id") { popUpTo("jobs") } },
                        )
                    }
                    composable("job/{id}") { e ->
                        val id = e.arguments?.getString("id").orEmpty()
                        JobScreen(id, onBack = { nav.popBackStack() }, onDocument = { nav.navigate("doc/$id") })
                    }
                    composable("doc/{id}") { e ->
                        DocScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_JOB)?.let { pendingRoute = "job/$it"; return }
        intent.getStringExtra("doc")?.let { pendingRoute = "doc/$it"; return }
        if (intent.hasExtra("settings")) { pendingRoute = "settings"; return }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            val key = sharedUris.size.toString()
            sharedUris[key] = uri
            pendingRoute = "new?shared=$key"
        }
    }

    companion object {
        const val EXTRA_JOB = "job"
    }
}
