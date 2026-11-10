package com.alex.mfppro.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.alex.mfppro.data.Nicknames

fun main() {
    // Before anything reads it: account nicknames as snapshot state so every panel renames at once.
    Nicknames.factory = { mutableStateMapOf() }
    val dir = dataDir()
    val repo = desktopRepo(dir)
    val layout = JsonFileStore(dir.resolve("layout.json"), protect = false)

    application {
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1600.dp, 960.dp))
        // Minimised = in the background: same grace period and socket pausing as the phone app.
        LaunchedEffect(state) { snapshotFlow { state.isMinimized }.collect { repo.setVisible(!it) } }
        val zoom = remember { Zoom(layout) }
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Delta 1kx",
            icon = painterResource("ic_launcher.png"),
            // Ctrl + / Ctrl - / Ctrl 0 zoom the whole interface, like a browser.
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown || !(e.isCtrlPressed || e.isMetaPressed)) false
                else when (e.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { zoom.inc(); true }
                    Key.Minus, Key.NumPadSubtract -> { zoom.dec(); true }
                    Key.Zero, Key.NumPad0 -> { zoom.reset(); true }
                    else -> false
                }
            },
        ) {
            DesktopTheme { Workspace(repo, layout, zoom = zoom) }
        }
    }
}
