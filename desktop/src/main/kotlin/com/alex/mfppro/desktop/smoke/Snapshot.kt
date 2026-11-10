package com.alex.mfppro.desktop.smoke

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.alex.mfppro.data.Nicknames
import com.alex.mfppro.desktop.DesktopTheme
import com.alex.mfppro.desktop.JsonFileStore
import com.alex.mfppro.desktop.Workspace
import com.alex.mfppro.desktop.desktopRepo
import com.alex.mfppro.desktop.Ind
import com.alex.mfppro.desktop.saveInd
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Renders the workspace offscreen with live public market data (no key, no window) and saves a PNG.
 * ./gradlew :desktop:snapshot -Pout=path/to/file.png [-Pgrid=4] [-Pbands=true]
 */
fun main(args: Array<String>): Unit = runBlocking {
    val out = File(args.firstOrNull() ?: "delta1kx-snapshot.png")
    Nicknames.factory = { mutableStateMapOf() }
    val dir = Files.createTempDirectory("delta1kx-snap").toFile()
    val repo = desktopRepo(dir)
    repo.setVisible(true)
    val layout = JsonFileStore(dir.resolve("layout.json"), protect = false)
    args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { layout.edit().putString("grid", it).putString("rightTab", "Sessions").apply() }
    if (args.getOrNull(2) == "true") saveInd(layout, Ind(liqBands = true))
    val scene = ImageComposeScene(1920, 1080, Density(1f)) { DesktopTheme { Workspace(repo, layout, keyPrompt = false) } }
    // Let markets, candles, the book and trades arrive.
    repeat(150) { scene.render(System.nanoTime()); delay(100) }
    val img = scene.render(System.nanoTime())
    out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("wrote ${out.absolutePath}")
    scene.close()
    exitProcess(0)
}
