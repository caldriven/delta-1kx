package com.alex.mfppro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.sp
import com.alex.mfppro.ui.C
import com.alex.mfppro.ui.BottomBar
import com.alex.mfppro.ui.NavItem
import com.alex.mfppro.ui.MfpTheme
import com.alex.mfppro.ui.PositionsScreen
import com.alex.mfppro.ui.AccountsScreen
import com.alex.mfppro.ui.OverviewScreen
import com.alex.mfppro.ui.SettingsScreen
import com.alex.mfppro.ui.TradeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as MfpApp).repo
        com.alex.mfppro.ui.Split.pct = repo.prefs.profitSplit
        com.alex.mfppro.ui.Comp.hide = repo.prefs.hideComp
        com.alex.mfppro.ui.Palette.colorBlind = repo.prefs.colorBlind
        com.alex.mfppro.ui.Palette.theme = com.alex.mfppro.ui.AppTheme.entries.firstOrNull { it.name == repo.prefs.theme } ?: com.alex.mfppro.ui.AppTheme.STANDARD
        setContent {
            // Status and navigation bar icons follow the theme (dark icons on the light theme).
            val dark = com.alex.mfppro.ui.Palette.theme.dark
            androidx.compose.runtime.LaunchedEffect(dark) {
                val bar = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
            }
            MfpTheme {
                var tab by rememberSaveable { mutableStateOf(0) }
                var marketId by rememberSaveable { mutableStateOf(repo.prefs.lastMarketId) }
                var compact by rememberSaveable { mutableStateOf(false) }
                var orderOpen by remember { mutableStateOf(false) }
                var analysis by rememberSaveable { mutableStateOf(repo.prefs.marketsAnalysis) }
                // Bar order of the paged screens: Dashboard, Markets, Trade, Journal.
                val pageTabs = remember { listOf(0, 4, 1, 2) }
                val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = maxOf(0, pageTabs.indexOf(tab))) { pageTabs.size }
                val pagerScope = androidx.compose.runtime.rememberCoroutineScope()
                var dragPos by remember { mutableStateOf<Float?>(null) }
                // Any tab change (tap, back, open market) glides the pager there.
                LaunchedEffect(tab) {
                    val i = pageTabs.indexOf(tab)
                    if (i >= 0 && dragPos == null) {
                        // Far jumps hop to the neighbour first so the screens in between are never built.
                        val cur = pager.currentPage
                        if (kotlin.math.abs(i - cur) > 1) pager.scrollToPage(if (i > cur) i - 1 else i + 1)
                        pager.animateScrollToPage(i, animationSpec = androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    }
                }
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                // Screens visited, so Android back steps through them before leaving the app.
                val history = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
                val go = { t: Int -> if (t != tab) { history.remove(t); history.add(tab); tab = t } }
                androidx.activity.compose.BackHandler(enabled = history.isNotEmpty()) { tab = history.removeAt(history.lastIndex) }
                val openMarket = { id: String -> marketId = id; repo.prefs.lastMarketId = id; go(1) }
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { repo.messages.collect { snack.showSnackbar(it, withDismissAction = true) } }
                Box(Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = C.bg,
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        BottomBar(
                            listOf(
                                NavItem("Dashboard", Icons.Filled.Dashboard, 0),
                                NavItem(if (tab == 4 && analysis) "Analysis" else "Markets", if (tab == 4 && analysis) Icons.Filled.Insights else Icons.Filled.QueryStats, 4, mode = if (tab == 4) (if (analysis) 1 else 0) else null),
                                // On Trade, this button flips between chart and position management.
                                NavItem("Trade", if (tab == 1 && compact) Icons.Filled.Tune else Icons.Filled.CandlestickChart, 1, mode = if (tab == 1) (if (compact) 1 else 0) else null),
                                NavItem("Journal", Icons.AutoMirrored.Filled.MenuBook, 2),
                                // Opens the order panel over whatever screen is showing.
                                NavItem("Order", Icons.Filled.SwapVert, 5),
                            ),
                            tab,
                            pages = pageTabs.size,
                            dragPos = dragPos,
                            onDrag = { f ->
                                dragPos = f
                                // The pager takes the nearest page plus an offset within ±0.5.
                                val page = f.roundToInt()
                                pagerScope.launch { pager.scrollToPage(page, (f - page).coerceIn(-0.5f, 0.5f)) }
                            },
                            onDragEnd = {
                                val t = (dragPos ?: 0f).roundToInt().coerceIn(0, pageTabs.size - 1)
                                dragPos = null
                                if (pageTabs[t] == tab) pagerScope.launch { pager.animateScrollToPage(t) } else go(pageTabs[t])
                            },
                        ) {
                            if (it == 5) orderOpen = true
                            // Markets, like Trade, flips between its two views when tapped again.
                            else if (it == 4 && tab == 4) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                analysis = !analysis; repo.prefs.marketsAnalysis = analysis
                            } else if (it == 1 && tab == 1) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                compact = !compact
                            } else go(it)
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        // Main screens sit side by side in a pager that only the bottom bar moves
                        // (no swiping on the screens themselves); Settings opens over them.
                        androidx.compose.animation.AnimatedContent(tab == 3, label = "settings",
                            transitionSpec = { androidx.compose.animation.fadeIn().togetherWith(androidx.compose.animation.fadeOut()) }) { inSettings ->
                            if (inSettings) SettingsScreen(repo)
                            else androidx.compose.foundation.pager.HorizontalPager(pager, userScrollEnabled = false, key = { pageTabs[it] }) { i ->
                                when (pageTabs[i]) {
                                    0 -> PositionsScreen(repo, onOpenMarket = openMarket, onOpenSettings = { go(3) })
                                    1 -> TradeScreen(repo, marketId, compact, onPickMarket = openMarket)
                                    2 -> com.alex.mfppro.ui.JournalScreen(repo, onOpenSettings = { go(3) })
                                    else -> OverviewScreen(repo, analysis, onOpenMarket = openMarket)
                                }
                            }
                        }
                        if (orderOpen) {
                            val markets by repo.markets.collectAsStateWithLifecycle()
                            val venues by repo.venues.collectAsStateWithLifecycle()
                            val m = markets.firstOrNull { it.marketId == marketId } ?: venues.firstOrNull { it.marketId == marketId } ?: markets.firstOrNull { it.symbol == "BTC" }
                            if (m == null) orderOpen = false
                            else com.alex.mfppro.ui.OrderTicket(repo, m, com.alex.mfppro.ui.TicketRequest(buy = true)) { orderOpen = false }
                        }
                    }
                }
                com.alex.mfppro.ui.Overlay.content?.invoke()
                }
            }
        }
    }
}
