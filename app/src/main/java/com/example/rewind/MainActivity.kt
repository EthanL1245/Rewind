package com.example.rewind

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.rewind.rewind.RewindService
import com.example.rewind.ui.theme.RewindTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val active = remember { loadActiveSession(context) }
            val startDest = if (active != null) "session_active" else "setup"

            RewindTheme {
                val nav = rememberNavController()

                NavHost(navController = nav, startDestination = startDest) {
                    composable("session_active") {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val a = loadActiveSession(ctx)

                        if (a == null) {
                            // session ended while app was closed -> go to setup
                            LaunchedEffect(Unit) { nav.navigate("setup") { popUpTo(0) } }
                        } else {
                            SessionScreen(
                                sessionSeconds = a.sessionSeconds,
                                rewindSeconds = a.rewindSeconds,
                                fixedEndAtMs = a.endAtMs, // <-- we'll add this param below
                                onViewCapsules = { nav.navigate("capsules") },
                                onBackToSetup = { nav.navigate("setup") { popUpTo(0) } }
                            )
                        }
                    }

                    composable("setup") {
                        SetupScreen(
                            onStart = { sessionSeconds, rewindSeconds ->
                                nav.navigate("session_active")
                            },
                            onViewCapsules = { nav.navigate("capsules") }
                        )
                    }

                    composable(
                        route = "session/{sessionSeconds}/{rewindSeconds}",
                        arguments = listOf(
                            navArgument("sessionSeconds") { type = NavType.IntType },
                            navArgument("rewindSeconds") { type = NavType.IntType }
                        )
                    ) { backStack ->
                        val sessionSeconds = backStack.arguments?.getInt("sessionSeconds") ?: 1800
                        val rewindSeconds = backStack.arguments?.getInt("rewindSeconds") ?: 30
                        SessionScreen(
                            sessionSeconds = sessionSeconds,
                            rewindSeconds = rewindSeconds,
                            onViewCapsules = { nav.navigate("capsules") },
                            onBackToSetup = { nav.popBackStack("setup", inclusive = false) }
                        )
                    }

                    composable("capsules") {
                        CapsulesScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

/* -------------------- Screen 1: SETUP -------------------- */

private data class ModePreset(val name: String, val sessionSeconds: Int)

@Composable
private fun SetupScreen(
    onStart: (sessionSeconds: Int, rewindSeconds: Int) -> Unit,
    onViewCapsules: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // presets (edit freely)
    val presets = listOf(
        ModePreset("Lecture", 60 * 60),
        ModePreset("Meeting", 45 * 60),
        ModePreset("Walk", 30 * 60),
        ModePreset("Brainstorm", 20 * 60)
    )

    var selectedPreset by remember { mutableStateOf(presets.first()) }

    // rewind slider: 15s..120s
    var rewindSeconds by remember { mutableIntStateOf(30) }

    // permissions launchers
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // start service + jump to Session screen
            val endAt = System.currentTimeMillis() + selectedPreset.sessionSeconds * 1000L
            saveActiveSession(context, endAt, selectedPreset.sessionSeconds, rewindSeconds)
            startRewindService(context, selectedPreset.sessionSeconds, rewindSeconds)
            onStart(selectedPreset.sessionSeconds, rewindSeconds)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("REWIND", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text("Pick a mode, set rewind length, start a timed session.")

        Text("Mode", fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.take(3).forEach { p -> // Lecture, Meeting, Walk
                FilterChip(
                    selected = (p == selectedPreset),
                    onClick = { selectedPreset = p },
                    label = { Text(p.name) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.drop(3).forEach { p -> // Brainstorm
                FilterChip(
                    selected = (p == selectedPreset),
                    onClick = { selectedPreset = p },
                    label = { Text(p.name) }
                )
            }
        }

        val minutes = selectedPreset.sessionSeconds / 60
        Text("Session length: ${minutes} min", fontWeight = FontWeight.SemiBold)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rewind length: ${rewindSeconds}s", fontWeight = FontWeight.SemiBold)
            Slider(
                value = rewindSeconds.toFloat(),
                onValueChange = { rewindSeconds = it.toInt() },
                valueRange = 15f..120f,
                steps = (120 - 15) / 5 - 1 // snap ~every 5s
            )
            Text("15s  •  2m", modifier = Modifier.align(Alignment.End))
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // notifications permission (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                val hasMic = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasMic) {
                    val endAt = System.currentTimeMillis() + selectedPreset.sessionSeconds * 1000L
                    saveActiveSession(context, endAt, selectedPreset.sessionSeconds, rewindSeconds)
                    startRewindService(context, selectedPreset.sessionSeconds, rewindSeconds)
                    onStart(selectedPreset.sessionSeconds, rewindSeconds)
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        ) { Text("Start Session") }

        Text(
            "This runs a Foreground Service (visible notification) so Android allows ongoing mic buffering.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onViewCapsules
        ) { Text("View Capsules") }
    }
}

/* -------------------- Screen 2: SESSION -------------------- */

@Composable
private fun SessionScreen(
    sessionSeconds: Int,
    rewindSeconds: Int,
    fixedEndAtMs: Long? = null,
    onViewCapsules: () -> Unit,
    onBackToSetup: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // countdown driven by an "end time"
    val endAtMs = remember(fixedEndAtMs, sessionSeconds) {
        fixedEndAtMs ?: (System.currentTimeMillis() + sessionSeconds * 1000L)
    }
    var remainingMs by remember { mutableLongStateOf(endAtMs - System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            remainingMs = (endAtMs - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingMs == 0L) break
            kotlinx.coroutines.delay(250)
        }
        // session finished -> stop service
        stopRewindService(context)
        clearActiveSession(context)
    }

    val mins = (remainingMs / 1000) / 60
    val secs = (remainingMs / 1000) % 60

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Session Running", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text("Rewind length: ${rewindSeconds}s")

        Text(
            text = String.format("Time left: %02d:%02d", mins, secs),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    stopRewindService(context)
                    clearActiveSession(context)
                    onBackToSetup()
                }
            ) { Text("End Session") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onViewCapsules
            ) { Text("View Capsules") }
        }

        Text(
            "Use the notification action “REWIND” anytime to save the last ${rewindSeconds}s into a capsule.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/* -------------------- Screen 3: CAPSULES -------------------- */

private data class Capsule(
    val audioFile: File,
    val createdAtMs: Long,
    val seconds: Int?,
    val tags: Set<String>
)

@Composable
private fun CapsulesScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var capsules by remember { mutableStateOf(loadCapsulesWithMeta(context)) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Capsules (${capsules.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { capsules = loadCapsulesWithMeta(context) }) { Text("Refresh") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(capsules, key = { it.audioFile.name }) { cap ->
                CapsuleCard(
                    cap = cap,
                    onPlay = {
                        player?.stop(); player?.release(); player = null
                        player = MediaPlayer().apply {
                            setDataSource(cap.audioFile.absolutePath)
                            setOnPreparedListener { it.start() }
                            setOnCompletionListener { mp -> mp.release(); player = null }
                            prepareAsync()
                        }
                    },
                    onDelete = {
                        deleteCapsule(cap.audioFile)
                        capsules = loadCapsulesWithMeta(context)
                    },
                    onSetTag = { newTag ->
                        updateCapsuleTag(cap.audioFile, newTag)
                        capsules = loadCapsulesWithMeta(context)
                    }
                )
            }
        }
    }
}

@Composable
private fun CapsuleCard(
    cap: Capsule,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onSetTag: (String) -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    val whenText = fmt.format(Date(cap.createdAtMs))
    val lenText = cap.seconds?.let { "${it}s" } ?: "?"

    val options = listOf("Idea", "Instruction", "Moment")
    val currentTag = cap.tags.firstOrNull() ?: "Moment"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header (full-width, no buttons here so it won't get squished)
            Text(
                text = "Rewind Capsule",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "$whenText • $lenText • $currentTag",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onPlay
                ) { Text("Play") }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onDelete
                ) { Text("Delete") }
            }

            // Label chips (scroll instead of wrapping into ugly vertical stack)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                options.forEach { t ->
                    FilterChip(
                        selected = (t == currentTag),
                        onClick = { onSetTag(t) },
                        label = { Text(t) }
                    )
                }
            }
        }
    }
}

/* -------------------- Helpers (files + service start/stop) -------------------- */

private fun startRewindService(context: android.content.Context, sessionSeconds: Int, rewindSeconds: Int) {
    val i = Intent(context, RewindService::class.java).apply {
        putExtra(RewindService.EXTRA_SESSION_SECONDS, sessionSeconds)
        putExtra(RewindService.EXTRA_REWIND_SECONDS, rewindSeconds)
    }
    ContextCompat.startForegroundService(context, i)
}

private fun stopRewindService(context: android.content.Context) {
    val i = Intent(context, RewindService::class.java).apply { action = RewindService.ACTION_STOP }
    context.startService(i)
}

private fun capsuleJsonFile(audio: File): File =
    File(audio.parentFile, audio.name.replace(".wav", ".json"))

private fun deleteCapsule(wav: File) {
    runCatching { capsuleJsonFile(wav).delete() }
    runCatching { wav.delete() }
}

private fun updateCapsuleTag(wav: File, newTag: String) {
    val meta = capsuleJsonFile(wav)

    val obj = if (meta.exists()) {
        runCatching { org.json.JSONObject(meta.readText()) }.getOrNull() ?: org.json.JSONObject()
    } else {
        org.json.JSONObject()
    }

    val arr = org.json.JSONArray()
    arr.put(newTag)          // single category tag
    obj.put("tags", arr)

    meta.writeText(obj.toString())
}

private fun loadCapsulesWithMeta(context: android.content.Context): List<Capsule> {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir

    val wavs = dir.listFiles()
        ?.filter { it.name.startsWith("rewind_") && it.name.endsWith(".wav") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    return wavs.map { wav ->
        val meta = capsuleJsonFile(wav)
        var seconds: Int? = null
        var tags: Set<String> = emptySet()

        if (meta.exists()) {
            runCatching {
                val obj = org.json.JSONObject(meta.readText())
                seconds = if (obj.has("seconds")) obj.optInt("seconds") else null
                val arr = obj.optJSONArray("tags")
                tags = buildSet {
                    if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i))
                }
            }
        }

        Capsule(
            audioFile = wav,
            createdAtMs = wav.lastModified(),
            seconds = seconds,
            tags = tags
        )
    }
}

private const val PREFS = "rewind_prefs"
private const val KEY_END_AT = "endAtMs"
private const val KEY_SESSION = "sessionSeconds"
private const val KEY_REWIND = "rewindSeconds"

private fun saveActiveSession(context: android.content.Context, endAtMs: Long, sessionSeconds: Int, rewindSeconds: Int) {
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_END_AT, endAtMs)
        .putInt(KEY_SESSION, sessionSeconds)
        .putInt(KEY_REWIND, rewindSeconds)
        .apply()
}

private fun clearActiveSession(context: android.content.Context) {
    context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_END_AT)
        .remove(KEY_SESSION)
        .remove(KEY_REWIND)
        .apply()
}

private data class ActiveSession(val endAtMs: Long, val sessionSeconds: Int, val rewindSeconds: Int)

private fun loadActiveSession(context: android.content.Context): ActiveSession? {
    val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    val endAt = prefs.getLong(KEY_END_AT, 0L)
    if (endAt <= System.currentTimeMillis()) return null
    return ActiveSession(
        endAtMs = endAt,
        sessionSeconds = prefs.getInt(KEY_SESSION, 0),
        rewindSeconds = prefs.getInt(KEY_REWIND, 30)
    )
}