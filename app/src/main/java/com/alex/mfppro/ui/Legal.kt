package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.Repo

/** Private alpha license. Not an open source license. */
val LICENSE_TEXT = """
Delta 1kx Alpha Tester License
Copyright © 2026 $CREATOR. All rights reserved.

1. License
$CREATOR gives you a personal, non-exclusive, non-transferable and revocable license to install and use this pre-release build of Delta 1kx on devices you own or control, only to try and test it. Nothing else is granted. The app is not sold to you; $CREATOR keeps all rights in it.

2. What you may not do
You may not sell, rent, lend, sublicense, share, publish or otherwise pass on the app or its installation file. You may not copy, modify, decompile or reverse engineer it, except where the law allows this despite this clause. You may not remove or change these notices.

3. Alpha software
This is an early test build. It may contain bugs, change without notice, stop working, or be withdrawn at any time. $CREATOR does not have to provide support, fixes or updates. Later versions may come under different terms, including paid terms.

4. How it is distributed
The app is handed directly to invited alpha testers. It is not distributed through Google Play or any other app store and has not been through any store review. Installing it means allowing an app from outside a store; only install a file you received directly from $CREATOR. You install and use it at your own risk.

5. Not affiliated
Delta 1kx is independent. It is not made, endorsed, sponsored or supported by MyFundedPerpetuals (MFP), or by any exchange, venue or data provider it connects to. Their names and marks belong to their owners and are used only to describe compatibility.

6. Trading and data
The app can place, change and cancel real orders on your MFP account with the API key you give it. Every trade, and its outcome, is your own decision and responsibility. Nothing in the app is financial, investment or trading advice. Prices, PnL, drawdown, leverage, rule, risk and notification information can be wrong, delayed or missing; check MFP's official platform before you rely on it. Notifications can arrive late or not at all.

7. Your API key
You are responsible for your API key and everything done with it. The app stores it encrypted on your device and sends it only to the MFP API. Remove it in Settings, or revoke it at MFP, whenever you want.

8. No warranty
The app is provided "as is" and "as available", without warranty of any kind, express or implied, including any warranty of merchantability, fitness for a particular purpose, accuracy, or non-infringement.

9. Limitation of liability
To the fullest extent the law allows, $CREATOR is not liable for any direct, indirect, incidental, special or consequential loss or damage arising from using or being unable to use the app. This includes trading losses, missed, duplicated or wrong orders, missed alerts, breached or failed accounts, lost profits and lost data, even if $CREATOR was told such loss was possible.

10. End of the license
This license ends automatically if you break it, or when $CREATOR withdraws the build or your tester access. When it ends, stop using the app and uninstall it.

11. Changes
These terms can change with a new build. When they do, the app asks you to accept them again before you keep using your API key.

Open source components in the app keep their own licenses; see Open source licenses.
""".trimIndent()

/** One bundled third-party component. [file] names its license text in res/raw. */
data class OssItem(val name: String, val by: String, val license: String, val url: String, val file: Int)

val OSS = listOf(
    OssItem("Jetpack Compose (UI, Foundation, Material 3, Material Icons)", "The Android Open Source Project", "Apache 2.0", "https://developer.android.com/jetpack/androidx", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("AndroidX Activity, Core, Lifecycle, ProfileInstaller", "The Android Open Source Project", "Apache 2.0", "https://developer.android.com/jetpack/androidx", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("AndroidX Security Crypto", "The Android Open Source Project", "Apache 2.0", "https://developer.android.com/jetpack/androidx/releases/security", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("Tink", "Google LLC", "Apache 2.0", "https://github.com/tink-crypto/tink-java", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("Kotlin standard library", "JetBrains s.r.o. and Kotlin contributors", "Apache 2.0", "https://github.com/JetBrains/kotlin", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("kotlinx.coroutines", "JetBrains s.r.o. and contributors", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("kotlinx.serialization", "JetBrains s.r.o. and contributors", "Apache 2.0", "https://github.com/Kotlin/kotlinx.serialization", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("OkHttp and Okio", "Square, Inc.", "Apache 2.0", "https://square.github.io/okhttp/", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("Coil", "Coil Contributors", "Apache 2.0", "https://github.com/coil-kt/coil", com.alex.mfppro.R.raw.license_apache_2_0),
    OssItem("Inter (font)", "The Inter Project Authors", "SIL Open Font License 1.1", "https://github.com/rsms/inter", com.alex.mfppro.R.raw.license_ofl_inter),
    OssItem("JetBrains Mono (font)", "The JetBrains Mono Project Authors", "SIL Open Font License 1.1", "https://github.com/JetBrains/JetBrainsMono", com.alex.mfppro.R.raw.license_ofl_jbmono),
)

const val DATA_SOURCES =
    "Market data comes from the MFP API and from public market data at Binance, Bybit, OKX and Yahoo Finance. " +
        "Logos and flags are loaded from Binance, Financial Modeling Prep, flagcdn and Google's favicon service. " +
        "None of these providers is connected with Delta 1kx."

fun appVersion(ctx: android.content.Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
}

fun rawText(ctx: android.content.Context, id: Int): String =
    ctx.resources.openRawResource(id).bufferedReader().use { it.readText() }

/**
 * The alpha terms a tester accepts before an API key is used: the points in
 * plain words, a link to the full license, and an "I agree" tick that unlocks
 * the confirm button.
 */
@Composable
fun TermsDialog(
    title: String,
    confirm: String,
    dismiss: String,
    onLicense: (() -> Unit)?,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    onAgree: () -> Unit,
) {
    var agreed by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(
        onDismiss,
        androidx.compose.ui.window.DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(C.panel).border(1.dp, C.accent.copy(alpha = 0.55f), shape).padding(18.dp)) {
            Text("ALPHA TESTER TERMS", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text("By adding your MFP API key you agree that:", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(10.dp)).background(C.bg)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TERMS_POINTS.forEach { t ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("•", color = C.accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(t, color = C.text, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                onLicense?.let {
                    Text("Read the full license", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp).clickable(onClick = it).padding(vertical = 4.dp))
                }
            }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { agreed = !agreed }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(if (agreed) C.accent else Color.Transparent)
                        .border(1.5.dp, if (agreed) C.accent else C.muted, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (agreed) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                Text("I have read and agree to these terms", color = C.text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text(dismiss, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1.4f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(if (agreed) C.accent else C.line)
                        .clickable(enabled = agreed, onClick = onAgree),
                    contentAlignment = Alignment.Center,
                ) { Text(confirm, color = if (agreed) Color.White else C.muted, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Asks testers who already had a key (or got new terms in an update) to accept
 * the current terms. Declining removes the key.
 */
@Composable
fun TermsGate(repo: Repo) {
    val key by repo.apiKey.collectAsStateWithLifecycle()
    var accepted by remember { mutableStateOf(repo.prefs.termsAccepted) }
    var license by remember { mutableStateOf(false) }
    // Re-read on every key change: adding a key in Settings accepts the terms there first.
    if (key == null || maxOf(accepted, repo.prefs.termsAccepted) >= TERMS_VERSION) return
    if (license) {
        LicenseDialog { license = false }
        return
    }
    TermsDialog(
        title = if (accepted == 0) "Before you trade with Delta 1kx" else "The terms have changed",
        confirm = "Agree", dismiss = "Remove key", dismissible = false,
        onLicense = { license = true },
        onDismiss = { repo.setApiKey(null) },
    ) { repo.prefs.acceptTerms(TERMS_VERSION); accepted = TERMS_VERSION }
}

/** The full license in a scrolling dialog, for reading from the terms prompt. */
@Composable
fun LicenseDialog(onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose) {
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(C.panel).border(1.dp, C.line, shape).padding(18.dp)) {
            Text(
                LICENSE_TEXT, color = C.text, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            )
            Box(
                Modifier.padding(top = 12.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("Back", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
