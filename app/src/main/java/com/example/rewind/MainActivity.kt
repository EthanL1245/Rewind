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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RewindTheme {
                val context = LocalContext.current

                // --- permissions launchers (same as before) ---
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

                // --- one MediaPlayer for the whole screen ---
                var player by remember { mutableStateOf<MediaPlayer?>(null) }
                DisposableEffect(Unit) {
                    onDispose { player?.release(); player = null }
                }

                // --- load capsules from storage ---
                fun loadCapsules(): List<File> {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    return dir.listFiles()
                        ?.filter { it.name.startsWith("rewind_") && it.name.endsWith(".wav") }
                        ?.sortedByDescending { it.lastModified() }
                        ?: emptyList()
                }

                var capsules by remember { mutableStateOf(loadCapsules()) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // --- top controls ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }

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
                        ) { Text("Start Session") }

                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { capsules = loadCapsules() }
                        ) { Text("Refresh") }
                    }

                    Text("Capsules (${capsules.size})")

                    // --- capsule list ---
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(capsules) { file ->
                            CapsuleRow(
                                file = file,
                                onPlay = {
                                    // stop previous
                                    player?.stop()
                                    player?.release()
                                    player = null

                                    // play selected
                                    player = MediaPlayer().apply {
                                        setDataSource(file.absolutePath)
                                        setOnPreparedListener { it.start() }
                                        setOnCompletionListener { mp -> mp.release(); player = null }
                                        prepareAsync()
                                    }
                                }
                            )
                        }
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

@Composable
private fun CapsuleRow(
    file: File,
    onPlay: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    val whenText = fmt.format(Date(file.lastModified()))

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Rewind Capsule")
                Text(whenText)
                Text(file.name) // optional: remove later if you want it cleaner
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onPlay) { Text("Play") }
        }
    }
}