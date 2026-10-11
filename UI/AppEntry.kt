package com.taowu.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.taowu.backend.BackendContainer
import com.taowu.backend.ResolveMode
import com.taowu.backend.model.Track
import com.taowu.ui.theme.UiColors

/** 路由名：三个底部栏目 + 捕获流程的子页。 */
object Routes {
    const val CAPTURE = "capture"
    const val COLLECT = "collect"
    const val CONTENT = "content"
    const val SETTINGS = "settings"
    const val RESOLVE = "resolve"
    const val SERIES = "series"
    const val EPISODES = "episodes"
    const val PLAYER = "player"
}

/**
 * UI 入口：底部三栏（捕获 / 内容 / 设置）+ 捕获子流程（解析 / 分集 / 整部 / 播放）。
 */
@Composable
fun AppEntry(container: BackendContainer) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    var pendingUrl by remember { mutableStateOf("") }
    var pendingMode by remember { mutableStateOf(ResolveMode.GENERAL) }
    var playingTrack by remember { mutableStateOf<Track?>(null) }
    var playCtx by remember { mutableStateOf<PlayContext?>(null) }

    val isTopTab = route == Routes.CAPTURE || route == Routes.COLLECT ||
        route == Routes.CONTENT || route == Routes.SETTINGS

    fun goPlay(track: Track, ctx: PlayContext?) {
        playingTrack = track
        playCtx = ctx
        nav.navigate(Routes.PLAYER)
    }

    Scaffold(
        containerColor = UiColors.bg,
        bottomBar = {
            if (isTopTab) {
                BottomBar(route ?: Routes.CAPTURE) { target ->
                    nav.navigate(target) {
                        popUpTo(Routes.CAPTURE) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.CAPTURE,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.CAPTURE) {
                CaptureScreen(
                    onSubmit = { url, mode, action ->
                        pendingUrl = url
                        pendingMode = mode
                        nav.navigate(
                            when (action) {
                                CapAction.RESOLVE -> Routes.RESOLVE
                                CapAction.EPISODES -> Routes.EPISODES
                                CapAction.SERIES -> Routes.SERIES
                            }
                        )
                    },
                )
            }
            composable(Routes.COLLECT) {
                CollectScreen()
            }
            composable(Routes.CONTENT) {
                ContentScreen(
                    container = container,
                    onPlay = { track, ctx -> goPlay(track, ctx) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container)
            }
            composable(Routes.RESOLVE) {
                ResolveScreen(
                    container = container,
                    url = pendingUrl,
                    mode = pendingMode,
                    onBack = { nav.popBackStack() },
                    onPlay = { track, ctx -> goPlay(track, ctx) },
                    onSeries = { nav.navigate(Routes.SERIES) },
                )
            }
            composable(Routes.SERIES) {
                SeriesScreen(
                    container = container,
                    url = pendingUrl,
                    mode = pendingMode,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.EPISODES) {
                EpisodesScreen(
                    container = container,
                    url = pendingUrl,
                    mode = pendingMode,
                    onBack = { nav.popBackStack() },
                    onPlay = { track, ctx -> goPlay(track, ctx) },
                )
            }
            composable(Routes.PLAYER) {
                val track = playingTrack
                if (track == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    PlayerScreen(
                        container = container,
                        track = track,
                        series = playCtx,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
