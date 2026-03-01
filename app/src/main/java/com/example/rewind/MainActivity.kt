package com.example.rewind

import java.io.FileOutputStream
import com.google.api.client.http.FileContent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.text.SimpleDateFormat
import androidx.compose.runtime.rememberCoroutineScope
import java.util.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.isActive
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.FileList
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.rewind.ui.theme.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import java.io.ByteArrayOutputStream

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
                    composable(
                        route = "details/{wavName}",
                        arguments = listOf(navArgument("wavName") { type = NavType.StringType })
                    ) { backStack ->
                        val wavName = backStack.arguments?.getString("wavName") ?: return@composable
                        CapsuleDetailsScreen(wavName = wavName, onBack = { nav.popBackStack() })
                    }
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
                        CapsulesScreen(
                            onBack = { nav.popBackStack() },
                            onOpenDetails = { wavName -> nav.navigate("details/$wavName") }
                        )
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

    RewindBackground {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // HEADER (logo-centered, tighter)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.rewind_logo),
                        contentDescription = "Rewind",
                        modifier = Modifier.size(200.dp)  // smaller + clean
                    )
                }

                Text(
                    "Pick a mode, set rewind length, start a timed session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )

                Spacer(Modifier.height(5.dp))
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
                            val endAt =
                                System.currentTimeMillis() + selectedPreset.sessionSeconds * 1000L
                            saveActiveSession(
                                context,
                                endAt,
                                selectedPreset.sessionSeconds,
                                rewindSeconds
                            )
                            startRewindService(
                                context,
                                selectedPreset.sessionSeconds,
                                rewindSeconds
                            )
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

    RewindBackground {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Session Running",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall
                )
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
    }
}

/* -------------------- Screen 3: CAPSULES -------------------- */

private data class Capsule(
    val audioFile: File,
    val createdAtMs: Long,
    val seconds: Int?,
    val tags: Set<String>,
    val title: String?,
    val summary: String?,
    val transcript: String?,
    val aiStatus: String?
)

@Composable
private fun CapsulesScreen(
    onBack: () -> Unit,
    onOpenDetails: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var capsules by remember { mutableStateOf(loadCapsulesWithMeta(context)) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    val activity = (context as? android.app.Activity)

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        val account = runCatching { task.result }.getOrNull()
        if (account != null) {
            scope.launch(Dispatchers.IO) {
                val drive = buildDriveService(context, account)
                uploadAllCapsulesToDrive(context, drive)

                withContext(Dispatchers.Main) {
                    capsules = loadCapsulesWithMeta(context)
                }
            }
        }
    }

    RewindBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_capsule),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Your Capsules",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Text(
                text = "Tap one to replay the last moments — and archive them when you’re done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { capsules = loadCapsulesWithMeta(context) }) {
                    Text("Refresh")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onBack) {
                    Text("Back")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                        .build()

                    val client = GoogleSignIn.getClient(context, gso)
                    signInLauncher.launch(client.signInIntent)
                }
            ) {
                Text("Archive to Drive + Clear")
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
                        onSetTag = { tagOrNull ->
                            updateCapsuleTag(cap.audioFile, tagOrNull)
                            capsules = loadCapsulesWithMeta(context)
                        },
                        onDetails = { onOpenDetails(cap.audioFile.name) },
                        onRetryAi = {
                            retryCapsuleAi(context, cap.audioFile)
                            capsules = loadCapsulesWithMeta(context)
                        }
                    )
                }
            }
        }
    }
}
@Composable
private fun CapsuleDetailsScreen(wavName: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val wav = remember(wavName) { File(dir, wavName) }
    val json = remember(wavName) { File(dir, wavName.replace(".wav", ".json")) }

    // Hold the latest JSON snapshot in state
    var obj by remember { mutableStateOf<org.json.JSONObject?>(null) }

    // Auto-refresh loop: read JSON repeatedly until done/error (or screen leaves)
    LaunchedEffect(wavName) {
        while (isActive) {
            val latest = if (json.exists()) {
                runCatching { org.json.JSONObject(json.readText()) }.getOrNull()
            } else null

            obj = latest

            val status = latest?.optString("aiStatus", "") ?: ""
            if (status == "done" || status == "error") break

            kotlinx.coroutines.delay(700) // refresh rate (ms)
        }
    }

    val title = obj?.optString("title", "Rewind Capsule") ?: "Rewind Capsule"
    val summary = obj?.optString("summary", "") ?: ""
    val transcript = obj?.optString("transcript", "") ?: ""
    val status = obj?.optString("aiStatus", "") ?: ""
    val err = obj?.optString("aiError", "") ?: ""

    RewindBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 16.dp), // <-- pushes down from status bar
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // HEADER IMAGE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = imageForTag(obj?.optJSONArray("tags")?.optString(0))),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }

            when (status) {
                "pending" -> Text("Summarizing…", style = MaterialTheme.typography.bodyMedium)
                "error" -> {
                    Text("AI summary failed.", style = MaterialTheme.typography.bodyMedium)
                    if (err.isNotBlank()) {
                        Text(err, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (summary.isNotBlank()) {
                Text("Summary", fontWeight = FontWeight.SemiBold)
                Text(summary)
            }

            if (transcript.isNotBlank()) {
                Text("Transcript", fontWeight = FontWeight.SemiBold)
                Text(transcript)
            }

            Text("Audio file: ${wav.name}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapsuleCard(
    cap: Capsule,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onSetTag: (String?) -> Unit,
    onDetails: () -> Unit,
    onRetryAi: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    val whenText = fmt.format(Date(cap.createdAtMs))
    val lenText = cap.seconds?.let { "${it}s" } ?: "?"

    val options = listOf("Idea", "Instruction", "Moment")
    val selectedTag: String? = cap.tags.firstOrNull()

    val tag = selectedTag
    val accent = accentFor(tag)
    val tagText = tag ?: "Unlabeled"

    val preview = cap.summary
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.take(90)
        .orEmpty()

    val shape = RoundedCornerShape(18.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f),
                shape = shape
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top row: accent bar + title + optional image
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accent)
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_capsule),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = cap.title?.takeIf { it.isNotBlank() } ?: "Rewind Capsule",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "$whenText • $lenText • $tagText",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Right-side tag image (optional)
                Image(
                    painter = painterResource(id = imageForTag(tag)),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .graphicsLayer(alpha = 0.92f)
                )
            }

            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Play") }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onDetails
                ) { Text("Details") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onDelete
                ) { Text("Delete") }

                if (cap.aiStatus == "error") {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onRetryAi
                    ) { Text("Retry") }
                }
            }

            // Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                options.forEach { t ->
                    val chipAccent = accentFor(t)
                    FilterChip(
                        selected = (t == selectedTag),
                        onClick = {
                            val next = if (t == selectedTag) null else t
                            onSetTag(next)
                        },
                        label = { Text(t) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = (t == selectedTag),
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
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

private fun updateCapsuleTag(wav: File, newTagOrNull: String?) {
    val meta = capsuleJsonFile(wav)

    val obj = if (meta.exists()) {
        runCatching { org.json.JSONObject(meta.readText()) }.getOrNull() ?: org.json.JSONObject()
    } else org.json.JSONObject()

    val arr = org.json.JSONArray()
    if (newTagOrNull != null) arr.put(newTagOrNull) // else: empty array (none selected)
    obj.put("tags", arr)

    // If we’re in DEV/mock mode, keep the title meaningful even without Gemini:
    val status = obj.optString("aiStatus", "")
    if (status == "mock") {
        val timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(wav.lastModified()))
        val prefix = newTagOrNull ?: "Capsule"
        obj.put("title", "$prefix • $timeLabel")
    }

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
        var title: String? = null
        var summary: String? = null
        var transcript: String? = null
        var aiStatus: String? = null

        if (meta.exists()) {
            runCatching {
                val obj = org.json.JSONObject(meta.readText())
                seconds = if (obj.has("seconds")) obj.optInt("seconds") else null
                val arr = obj.optJSONArray("tags")
                title = obj.optString("title", null)
                summary = obj.optString("summary", null)
                transcript = obj.optString("transcript", null)
                aiStatus = obj.optString("aiStatus", null)
                tags = buildSet {
                    if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i))
                }
            }
        }

        Capsule(
            audioFile = wav,
            createdAtMs = wav.lastModified(),
            seconds = seconds,
            tags = tags,
            title = title,
            summary = summary,
            transcript = transcript,
            aiStatus = aiStatus
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

private fun retryCapsuleAi(context: android.content.Context, wav: File) {
    val baseName = wav.name.removeSuffix(".wav")
    val i = Intent(context, RewindService::class.java).apply {
        action = RewindService.ACTION_RETRY_AI
        putExtra(RewindService.EXTRA_BASE_NAME, baseName)
    }
    context.startService(i) // service already foreground-running or will start as needed
}

private fun buildDriveService(context: android.content.Context, account: GoogleSignInAccount): Drive {
    val credential = GoogleAccountCredential.usingOAuth2(
        context,
        listOf(DriveScopes.DRIVE_FILE)
    )
    credential.selectedAccount = account.account

    return Drive.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential
    ).setApplicationName("REWIND").build()
}
private fun findOrCreateFolder(drive: Drive, name: String, parentId: String? = null): String {
    val q = buildString {
        append("mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false")
        if (parentId != null) append(" and '$parentId' in parents")
    }

    val results: FileList = drive.files().list()
        .setQ(q)
        .setSpaces("drive")
        .setFields("files(id,name)")
        .execute()

    val existing = results.files?.firstOrNull()
    if (existing != null) return existing.id

    val folderMeta = com.google.api.services.drive.model.File().apply {
        this.name = name
        this.mimeType = "application/vnd.google-apps.folder"
        if (parentId != null) this.parents = listOf(parentId)
    }

    val created = drive.files().create(folderMeta)
        .setFields("id")
        .execute()

    return created.id
}

private fun categoryFolderName(tag: String?): String = when (tag) {
    "Idea" -> "Idea"
    "Instruction" -> "Instruction"
    "Moment" -> "Moment"
    else -> "Misc"
}
private fun uploadAllCapsulesToDrive(
    context: android.content.Context,
    drive: Drive
) {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val wavs = dir.listFiles()?.filter { it.name.startsWith("rewind_") && it.name.endsWith(".wav") } ?: emptyList()
    if (wavs.isEmpty()) return

    // Ensure folder tree exists
    val rootId = findOrCreateFolder(drive, "REWIND")
    val ideaId = findOrCreateFolder(drive, "Idea", rootId)
    val instrId = findOrCreateFolder(drive, "Instruction", rootId)
    val momentId = findOrCreateFolder(drive, "Moment", rootId)
    val miscId = findOrCreateFolder(drive, "Misc", rootId)

    fun folderIdFor(tag: String?): String = when (tag) {
        "Idea" -> ideaId
        "Instruction" -> instrId
        "Moment" -> momentId
        else -> miscId
    }

    wavs.forEach { wav ->
        val json = File(wav.parentFile, wav.name.removeSuffix(".wav") + ".json")

        val obj = if (json.exists())
            runCatching { org.json.JSONObject(json.readText()) }.getOrNull() ?: org.json.JSONObject()
        else org.json.JSONObject()

        val tag = obj.optJSONArray("tags")?.optString(0, null)

        val aiTitle = obj.optString("title", "").trim()
        val label = tag ?: "Capsule"

        val fallback = "$label — ${prettyTime(wav.lastModified())}"
        val finalTitle =
            if (aiTitle.isNotBlank()) "$label — $aiTitle"
            else fallback

        val baseName = safeDriveName(finalTitle)
        val parent = folderIdFor(tag)

        val driveWavName = "$baseName.wav"

        // Upload WAV
        runCatching {
            val wavMeta = com.google.api.services.drive.model.File().apply {
                name = driveWavName
                parents = listOf(parent)
            }
            val wavMedia = com.google.api.client.http.FileContent("audio/wav", wav)
            drive.files().create(wavMeta, wavMedia).setFields("id").execute()
        }.getOrThrow()

        // Upload CAPSULE HTML (meaningful artifact)
        runCatching {
            val obj = if (json.exists())
                runCatching { org.json.JSONObject(json.readText()) }.getOrNull() ?: org.json.JSONObject()
            else org.json.JSONObject()

            val html = buildCapsuleHtml(context, obj, driveWavName, wav.lastModified())

            // write a temp html file (so Drive SDK can upload it)
            val tmp = File(context.cacheDir, "$baseName.html")
            FileOutputStream(tmp).use { it.write(html.toByteArray(Charsets.UTF_8)) }

            val htmlMeta = com.google.api.services.drive.model.File().apply {
                name = baseName                 // no .html extension
                parents = listOf(parent)
                mimeType = "application/vnd.google-apps.document"
            }

            val htmlMedia = FileContent("text/html", tmp)

            drive.files()
                .create(htmlMeta, htmlMedia)
                .setFields("id")
                .execute()

            runCatching { tmp.delete() }
        }.getOrThrow()

        // If upload succeeded, delete local
        runCatching { json.delete() }
        runCatching { wav.delete() }
    }
}

private fun buildCapsuleHtml(
    context: android.content.Context,
    meta: org.json.JSONObject,
    driveWavName: String,
    createdAtMs: Long
): String {
    fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    val tag = meta.optJSONArray("tags")?.optString(0, null) ?: "Unlabeled"
    val whenText = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
        .format(Date(createdAtMs))

    // Title should be the nice human title (NOT the raw rewind_*.wav)
    // If your JSON title is empty, fall back to "Capsule • 12:43 a.m."
    val jsonTitle = meta.optString("title", "").trim()
    val title = if (jsonTitle.isNotBlank()) jsonTitle else "Capsule • ${prettyTime(createdAtMs)}"

    val summary = meta.optString("summary", "").trim()
    val transcript = meta.optString("transcript", "").trim()

    // Emojis + super simple structure (Docs keeps this best)
    fun summaryBlock(): String {
        if (summary.isBlank()) return "<p><i>(No summary yet)</i></p>"
        val lines = summary.lines().map { it.trim() }.filter { it.isNotBlank() }
        return "<ul>" + lines.joinToString("") {
            "<li>${esc(it.trimStart('-', '•', ' '))}</li>"
        } + "</ul>"
    }

    fun transcriptBlock(): String {
        if (transcript.isBlank()) return "<p><i>(No transcript yet)</i></p>"
        return "<p style='white-space:pre-wrap;'>${esc(transcript)}</p>"
    }

    return """
<!doctype html>
<html>
<head>
  <meta charset="utf-8"/>
</head>
<body>
  <h1>🌀 ${esc(title)}</h1>

<p style="margin:6px 0;">
  <b>🏷️ Label:</b> ${esc(tag)}
</p>

<p style="margin:6px 0;">
  <b>🕒 Time:</b> ${esc(whenText)}
</p>

<p style="margin:6px 0 14px 0;">
  <b>🎧 Audio:</b> ${esc(driveWavName)}
</p>

  <hr/>

  <h2 style="margin-top:18px;">✨ Summary</h2>
  ${summaryBlock()}
  
  <hr style="margin:18px 0;" />

  <h2 style="margin-top:22px;">📝 Transcript</h2>
  ${transcriptBlock()}

  <hr style="margin:22px 0;" />
<p style="margin-top:10px;"><i>Generated by REWIND</i></p>
</body>
</html>
""".trimIndent()
}

private fun sanitizeForFilename(input: String): String {
    val cleaned = input
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[^A-Za-z0-9 _-]"), "")   // keep simple chars
        .take(40)                               // prevent super long filenames

    return if (cleaned.isBlank()) "Capsule" else cleaned
}

private fun prettyTime(ms: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))

private fun safeDriveName(raw: String): String {
    // Drive allows lots, but keep it clean + avoid weird characters
    return raw
        .replace(Regex("""[\\/:*?"<>|]"""), " ") // Windows-illegal chars
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(80)
}

private fun capsuleIconDataUri(context: android.content.Context): String {
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_capsule)
        ?: return ""

    val bitmap: Bitmap = if (drawable is BitmapDrawable) {
        drawable.bitmap
    } else {
        Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        ).also { bmp ->
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    return "data:image/png;base64,$b64"
}

private fun accentFor(tag: String?): androidx.compose.ui.graphics.Color = when (tag) {
    "Idea" -> AccentIdea
    "Instruction" -> AccentInstruction
    "Moment" -> AccentMoment
    else -> AccentMisc
}

private fun imageForTag(tag: String?): Int = when (tag) {
    "Idea" -> R.drawable.img_idea
    "Instruction" -> R.drawable.img_instruction
    "Moment" -> R.drawable.img_moment
    else -> R.drawable.img_misc
}

@Composable
private fun RewindBackground(content: @Composable () -> Unit) {
    val bg = Brush.verticalGradient(
        listOf(
            Color(0xFF060612),
            Color(0xFF0B0B1A),
            Color(0xFF060612)
        )
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        // contrast scrim
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x99000000))
        )

        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}

private fun drawableDataUri(context: android.content.Context, resId: Int): String {
    val drawable = ContextCompat.getDrawable(context, resId) ?: return ""

    val bitmap: Bitmap = if (drawable is BitmapDrawable) {
        drawable.bitmap
    } else {
        Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        ).also { bmp ->
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    return "data:image/png;base64,$b64"
}