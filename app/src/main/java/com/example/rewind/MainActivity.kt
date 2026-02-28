package com.example.rewind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.rewind.ui.theme.RewindTheme
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import com.example.rewind.rewind.RewindService
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Build
import android.media.MediaPlayer
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RewindTheme {
                val context = LocalContext.current

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                val micLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        val i = Intent(context, RewindService::class.java)
                        ContextCompat.startForegroundService(context, i)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // 1) Ask notifications permission first (Android 13+)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }

                            // 2) Mic permission logic
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasMic) {
                                val i = Intent(context, RewindService::class.java)
                                ContextCompat.startForegroundService(context, i)
                            } else {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Text("Start Session")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val dir = context.getExternalFilesDir(null) ?: context.filesDir
                            val latest = dir.listFiles()
                                ?.filter { it.name.startsWith("rewind_") && it.name.endsWith(".wav") }
                                ?.maxByOrNull { it.lastModified() }

                            if (latest != null) {
                                MediaPlayer().apply {
                                    setDataSource(latest.absolutePath)
                                    setOnPreparedListener { it.start() }
                                    setOnCompletionListener { mp -> mp.release() }
                                    prepareAsync()
                                }
                            }
                        }
                    ) {
                        Text("Play Last Rewind")
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RewindTheme {
        Greeting("Android")
    }
}