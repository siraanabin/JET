package com.jet.downloader.navigation

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.jet.downloader.MainActivity
import com.jet.downloader.NavUi

private data object HomeRoute : NavKey
private data object DownloadsRoute : NavKey
private data object LibraryRoute : NavKey
private data object SettingsRoute : NavKey

private data class TopLevelDestination(
    val route: NavKey,
    val target: String,
    val label: String,
    val icon: ImageVector,
)

class VariantNavUi : NavUi {
    private var webView: WebView? = null
    private var selectedIndex: (() -> Unit)? = null
    private var composeView: ComposeView? = null

    override fun attach(activity: MainActivity, container: FrameLayout, webView: WebView) {
        this.webView = webView
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DebugFloatingNavigation(
                    onSelect = { target ->
                        val safeTarget = target.replace("'", "")
                        this@VariantNavUi.webView?.evaluateJavascript(
                            "document.querySelector('[data-nav-target=\\\"$safeTarget\\\"]')?.click();",
                            null,
                        )
                    },
                )
            }
        }
        composeView = view
        // Added above the WebView but before the native player overlay.
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onPageLoaded() {
        // Keep the old nav's layout space reserved while hiding only its visuals in Debug.
        webView?.evaluateJavascript(
            "(function(){const n=document.querySelector('nav');if(n)n.style.visibility='hidden';})();",
            null,
        )
    }

    override fun handleBack(): Boolean {
        return false
    }
}

@Composable
private fun DebugFloatingNavigation(onSelect: (String) -> Unit) {
    val destinations = remember {
        listOf(
            TopLevelDestination(HomeRoute, "home", "Home", Icons.Filled.Home),
            TopLevelDestination(DownloadsRoute, "downloads", "Downloads", Icons.Filled.Download),
            TopLevelDestination(LibraryRoute, "library", "Library", Icons.Filled.Folder),
            TopLevelDestination(SettingsRoute, "settings", "Settings", Icons.Filled.Settings),
        )
    }
    val homeStack = remember { mutableStateListOf<NavKey>(HomeRoute) }
    val downloadsStack = remember { mutableStateListOf<NavKey>(DownloadsRoute) }
    val libraryStack = remember { mutableStateListOf<NavKey>(LibraryRoute) }
    val settingsStack = remember { mutableStateListOf<NavKey>(SettingsRoute) }
    val stacks = listOf(homeStack, downloadsStack, libraryStack, settingsStack)
    var selected by remember { mutableIntStateOf(0) }
    val activeStack = stacks[selected]

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = activeStack,
                modifier = Modifier.size(0.dp),
                onBack = {
                    if (activeStack.size > 1) activeStack.removeAt(activeStack.lastIndex)
                    else if (selected != 0) {
                        selected = 0
                        onSelect("home")
                    }
                },
                entryProvider = { key ->
                    NavEntry(key) { Spacer(Modifier.size(0.dp)) }
                },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                        ),
                    )
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                        .shadow(7.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp),
                ) {
                    BoxWithConstraints {
                        val tabWidth = maxWidth / destinations.size
                        val selectedOffset by animateDpAsState(
                            targetValue = tabWidth * selected,
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            label = "selected_tab_pill",
                        )
                        Box(
                            modifier = Modifier
                                .offset(x = selectedOffset)
                                .width(tabWidth)
                                .height(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .zIndex(0f),
                        )
                        Row(
                            modifier = Modifier,
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            destinations.forEachIndexed { index, destination ->
                                val selectedColor by animateColorAsState(
                                    targetValue = if (selected == index) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    animationSpec = tween(180),
                                    label = "tab_icon_color",
                                )
                                Box(
                                    modifier = Modifier
                                        .width(tabWidth)
                                        .height(52.dp)
                                        .zIndex(1f)
                                        .clip(CircleShape)
                                        .clickable {
                                            selected = index
                                            onSelect(destination.target)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                        tint = selectedColor,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
