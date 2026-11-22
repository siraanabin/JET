@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.jet.downloader.navigation

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.LookaheadScope
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jet.downloader.MainActivity
import com.jet.downloader.NavUi
import kotlinx.serialization.Serializable

@Serializable
private data object HomeRoute : NavKey

@Serializable
private data object DownloadsRoute : NavKey

@Serializable
private data object LibraryRoute : NavKey

@Serializable
private data object SettingsRoute : NavKey

private data class TopLevelDestination(
    val target: String,
    val label: String,
    val icon: ImageVector,
)

class VariantNavUi : NavUi {
    private var webView: WebView? = null
    private var backHandler: (() -> Boolean)? = null
    private var screenChangedHandler: ((String) -> Unit)? = null

    override fun attach(activity: MainActivity, container: FrameLayout, webView: WebView) {
        this.webView = webView
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FloatingTabNavigation(
                    onBackHandler = { handler -> backHandler = handler },
                    onScreenChangedHandler = { handler -> screenChangedHandler = handler },
                    onSelect = { target ->
                        this@VariantNavUi.webView?.evaluateJavascript(
                            "(document.querySelector('.nav-btn[data-nav-target=\\\"$target\\\"]') || document.querySelector('[data-nav-target=\\\"$target\\\"]'))?.click();",
                            null,
                        )
                    },
                    onFabClick = {
                        this@VariantNavUi.webView?.evaluateJavascript(
                            "(function(){const input=document.getElementById('url-input');if(input){input.focus();input.scrollIntoView({behavior:'smooth',block:'center'});}})();",
                            null,
                        )
                    },
                )
            }
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onPageLoaded() {
        webView?.evaluateJavascript(
            "(function(){const nav=document.querySelector('nav');if(nav)nav.style.visibility='hidden';})();",
            null,
        )
    }

    override fun onScreenChanged(target: String) {
        screenChangedHandler?.invoke(target)
    }

    override fun handleBack(): Boolean = backHandler?.invoke() ?: false
}

@Composable
private fun FloatingTabNavigation(
    onBackHandler: ((() -> Boolean) -> Unit),
    onScreenChangedHandler: (((String) -> Unit) -> Unit),
    onSelect: (String) -> Unit,
    onFabClick: () -> Unit,
) {
    val destinations = remember {
        listOf(
            TopLevelDestination("home", "Home", Icons.Filled.Home),
            TopLevelDestination("downloads", "Downloads", Icons.Filled.Download),
            TopLevelDestination("library", "Library", Icons.Filled.Folder),
            TopLevelDestination("settings", "Settings", Icons.Filled.Settings),
        )
    }

    // Navigation3 owns a separate saveable back stack for every top-level tab.
    val homeBackStack = rememberNavBackStack(HomeRoute)
    val downloadsBackStack = rememberNavBackStack(DownloadsRoute)
    val libraryBackStack = rememberNavBackStack(LibraryRoute)
    val settingsBackStack = rememberNavBackStack(SettingsRoute)
    val backStacks = listOf(homeBackStack, downloadsBackStack, libraryBackStack, settingsBackStack)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val activeBackStack = backStacks[selectedIndex]

    val onBack = {
        when {
            activeBackStack.size > 1 -> {
                activeBackStack.removeAt(activeBackStack.lastIndex)
                true
            }
            selectedIndex != 0 -> {
                selectedIndex = 0
                onSelect("home")
                true
            }
            else -> false
        }
    }

    SideEffect {
        onBackHandler(onBack)
        onScreenChangedHandler { target ->
            val index = destinations.indexOfFirst { it.target == target }
            if (index >= 0) selectedIndex = index
        }
    }

    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = activeBackStack,
                onBack = { onBack() },
                entryProvider = entryProvider {
                    entry<HomeRoute> { Spacer(Modifier.fillMaxSize()) }
                    entry<DownloadsRoute> { Spacer(Modifier.fillMaxSize()) }
                    entry<LibraryRoute> { Spacer(Modifier.fillMaxSize()) }
                    entry<SettingsRoute> { Spacer(Modifier.fillMaxSize()) }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (activeBackStack.size == 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(112.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                ),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 8.dp,
                        ) {
                            Box(Modifier.padding(4.dp)) {
                                LookaheadScope {
                                    BoxWithConstraints(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(60.dp),
                                    ) {
                                        val tabWidth = maxWidth / destinations.size
                                        Box(
                                            modifier = Modifier
                                                .offset(x = tabWidth * selectedIndex)
                                                .width(tabWidth)
                                                .height(52.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                                .animateBounds(
                                                    lookaheadScope = this@LookaheadScope,
                                                    boundsTransform = { _, _ ->
                                                        MaterialTheme.motionScheme.defaultSpatialSpec()
                                                    },
                                                )
                                                .zIndex(0f),
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            destinations.forEachIndexed { index, destination ->
                                                val selectedColor by animateColorAsState(
                                                    targetValue = if (selectedIndex == index) {
                                                        MaterialTheme.colorScheme.onSecondaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                                                    label = "tab_icon_color",
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(52.dp)
                                                        .zIndex(1f)
                                                        .clip(CircleShape)
                                                        .clickable {
                                                            selectedIndex = index
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

                        FloatingActionButton(
                            onClick = onFabClick,
                            modifier = Modifier.size(56.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "New download",
                            )
                        }
                    }
                }
            }
        }
    }
}
