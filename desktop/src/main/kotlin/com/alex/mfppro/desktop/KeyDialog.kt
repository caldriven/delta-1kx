package com.alex.mfppro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.alex.mfppro.data.Repo
import com.alex.mfppro.ui.TERMS_POINTS
import com.alex.mfppro.ui.TERMS_VERSION
import kotlinx.coroutines.launch

/**
 * The phone's alpha terms with its two phone-only points (store install, key kept on the phone)
 * swapped for what holds on Windows.
 */
private val DESKTOP_TERMS = TERMS_POINTS.filterNot { "phone" in it || "Google Play" in it } + listOf(
    "You got this build directly as an alpha tester, and you install and run it at your own risk.",
    "Your key is stored encrypted for your Windows user on this PC (Windows DPAPI) and is only sent to the MFP API.",
)

fun maskKey(k: String) = if (k.length > 14) k.take(8) + "…" + k.takeLast(4) else "…"

/** Add, replace or remove the API key, accepting the alpha terms on the way. */
@Composable
fun KeyDialog(repo: Repo, onClose: () -> Unit) {
    val current by repo.apiKey.collectAsState()
    var key by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(repo.prefs.termsAccepted >= TERMS_VERSION) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(600.dp, 680.dp)),
        title = "Delta 1kx · API key",
        resizable = false,
    ) {
        DesktopTheme {
            Column(
                Modifier.fillMaxSize().background(D.panel).padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Connect your MFP account", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Paste an API key from MyFundedPerpetuals. Keys starting fp_test_ use the sandbox. " +
                        "Without a key the terminal still shows public market data.",
                    style = label.copy(fontSize = 13.sp),
                )
                current?.let { Text("Current key: " + maskKey(it), style = num) }
                Field(key, { key = it.trim(); status = null }, Modifier.fillMaxWidth(), placeholder = "fp_live_… or fp_test_…", secret = true)

                Text("ALPHA TESTER TERMS", color = D.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                DESKTOP_TERMS.forEach { t ->
                    Row { Text("•  ", style = label.copy(fontSize = 13.sp)); Text(t, fontSize = 13.sp, color = D.text) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(agreed, { agreed = it }, colors = CheckboxDefaults.colors(checkedColor = D.accent, uncheckedColor = D.muted))
                    Text("I have read and agree to these terms", fontSize = 13.sp)
                }
                status?.let { Text(it, color = if (busy) D.muted else D.down, fontSize = 13.sp) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) Btn("Remove key", color = D.downDim, textColor = D.down, enabled = !busy) { repo.setApiKey(null); key = "" }
                    Spacer(Modifier.weight(1f))
                    Btn(if (current == null) "Not now" else "Close", enabled = !busy, onClick = onClose)
                    if (key.isBlank() && current != null && repo.prefs.termsAccepted < TERMS_VERSION) {
                        Btn("Accept terms", color = D.accent, textColor = Color.Black, enabled = agreed) {
                            repo.prefs.acceptTerms(TERMS_VERSION); onClose()
                        }
                    } else {
                        Btn("Test and save", color = D.accent, textColor = Color.Black, enabled = key.isNotBlank() && agreed && !busy) {
                            scope.launch {
                                busy = true; status = "Checking the key with MFP…"
                                val r = runCatching { repo.rest.accounts(key) }
                                busy = false
                                r.onSuccess { list ->
                                    repo.prefs.acceptTerms(TERMS_VERSION)
                                    repo.setApiKey(key)
                                    repo.postMessage("Key saved: ${list.size} account${if (list.size == 1) "" else "s"}")
                                    onClose()
                                }.onFailure { status = "MFP turned the key down: ${it.message}" }
                            }
                        }
                    }
                }
            }
        }
    }
}
