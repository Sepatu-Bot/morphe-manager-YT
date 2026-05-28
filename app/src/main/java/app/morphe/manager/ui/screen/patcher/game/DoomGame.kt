/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.patcher.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.util.rememberAdaptiveFilePicker
import kotlinx.coroutines.*
import org.koin.compose.koinInject
import java.io.File

@Stable
class DoomMiniGameState : MiniGameStateBase {
    override val score = 0
    override val highScore = 0
    override fun restart() {}
}

object DoomLib {
    init { System.loadLibrary("doom") }

    external fun init(wadPath: String)
    external fun tick()
    // Copies the latest rendered frame into the given Bitmap; returns true when a new frame arrived
    external fun copyFrame(bitmap: Bitmap): Boolean
    // pressed = 1 for key-down, 0 for key-up
    external fun sendKey(pressed: Int, doomKey: Int)

    const val KEY_RIGHT    = 0xae
    const val KEY_LEFT     = 0xac
    const val KEY_UP       = 0xad
    const val KEY_DOWN     = 0xaf
    const val KEY_STRAFE_L = 0xa0
    const val KEY_STRAFE_R = 0xa1
    const val KEY_USE      = 0xa2
    const val KEY_FIRE     = 0xa3
    const val KEY_ESC      = 27
    const val KEY_ENTER    = 13
}

// WADs tried in order; place doom1.wad in assets to use the original game instead of Freedoom.
private val WAD_CANDIDATES = listOf("doom1.wad", "freedoom1.wad")

private fun copyWadToFilesDir(context: Context, customWadName: String): String? {
    if (customWadName.isNotEmpty()) {
        val custom = File(context.filesDir, customWadName)
        if (custom.exists()) return custom.absolutePath
    }
    for (name in WAD_CANDIDATES) {
        try {
            val dest = File(context.filesDir, name)
            if (!dest.exists()) {
                context.assets.open(name).use { src ->
                    dest.outputStream().use { src.copyTo(it) }
                }
            }
            return dest.absolutePath
        } catch (_: Exception) {
            continue
        }
    }
    return null
}

@Composable
fun DoomGame(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val scope = rememberCoroutineScope()
    val bitmap = remember { createBitmap(640, 400) }
    var frameVersion by remember { mutableIntStateOf(0) }
    var wadMissing by remember { mutableStateOf(false) }
    val savedWadName by prefs.miniGameDoomWadName.getAsState()
    var copying by remember { mutableStateOf(false) }
    var applyOnRestart by remember { mutableStateOf(false) }

    val wadPicker = rememberAdaptiveFilePicker(
        mimeTypes = arrayOf("*/*"),
        customPickerMimeTypes = arrayOf("application/vnd.doom.wad"),
        onResult = { uri ->
            uri ?: return@rememberAdaptiveFilePicker
            scope.launch {
                copying = true
                applyOnRestart = false
                // NonCancellable ensures the file is fully written even if the user navigates away
                val success = withContext(Dispatchers.IO + NonCancellable) {
                    try {
                        val dest = File(context.filesDir, "custom.wad")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        prefs.miniGameDoomWadName.update("custom.wad")
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                copying = false
                if (success) applyOnRestart = true
            }
        }
    )

    LaunchedEffect(applyOnRestart) {
        if (applyOnRestart) {
            delay(4000)
            applyOnRestart = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val wadPath = copyWadToFilesDir(context, prefs.miniGameDoomWadName.get())
            if (wadPath == null) {
                wadMissing = true
                return@withContext
            }
            DoomLib.init(wadPath)
            while (isActive) {
                DoomLib.tick()
                if (DoomLib.copyFrame(bitmap)) frameVersion++
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (wadMissing) {
            Text(
                text = stringResource(R.string.mini_game_doom_wad_missing),
                color = Color.Red,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            // Game canvas - scales 640x400 to fill the available area
            val dstRect = remember { RectF() }
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                dstRect.set(0f, 0f, size.width, size.height)
                @Suppress("UNUSED_EXPRESSION") frameVersion
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(bitmap, null, dstRect, null)
                }
            }

            DoomControls(
                modifier = Modifier.fillMaxSize(),
                customWadActive = savedWadName.isNotEmpty(),
                copying = copying,
                applyOnRestart = applyOnRestart,
                onPickWad = { wadPicker() }
            )
        }
    }
}

@Composable
private fun DoomControls(
    modifier: Modifier,
    customWadActive: Boolean,
    copying: Boolean,
    applyOnRestart: Boolean,
    onPickWad: () -> Unit
) {
    Box(modifier = modifier) {
        // D-pad + strafe (bottom-left)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DoomButton("↑",  DoomLib.KEY_UP)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DoomButton("←", DoomLib.KEY_LEFT)
                Spacer(Modifier.size(48.dp))
                DoomButton("→", DoomLib.KEY_RIGHT)
            }
            DoomButton("↓",  DoomLib.KEY_DOWN)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DoomButton("S←", DoomLib.KEY_STRAFE_L)
                Spacer(Modifier.size(48.dp))
                DoomButton("S→", DoomLib.KEY_STRAFE_R)
            }
        }

        // Action buttons (bottom-right)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DoomButton("USE",  DoomLib.KEY_USE)
            DoomButton("FIRE", DoomLib.KEY_FIRE)
        }

        // Top-right: WAD picker, OK (menu confirm), ESC
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (applyOnRestart) {
                Text(
                    text = stringResource(R.string.mini_game_doom_wad_pending),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            DoomIconButton(
                onClick = onPickWad,
                tint = if (customWadActive) Color(0xFF80CBC4) else Color.White
            ) {
                if (copying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            DoomButton("OK",  DoomLib.KEY_ENTER)
            DoomButton("ESC", DoomLib.KEY_ESC)
        }
    }
}

@Composable
private fun DoomButton(
    label: String,
    doomKey: Int,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .pointerInput(doomKey) {
                detectTapGestures(
                    onPress = {
                        DoomLib.sendKey(1, doomKey)
                        tryAwaitRelease()
                        DoomLib.sendKey(0, doomKey)
                    }
                )
            }
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DoomIconButton(
    onClick: () -> Unit,
    tint: Color = Color.White,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}
