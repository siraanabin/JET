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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.jet.downloader.MainActivity
import com.jet.downloader.NavUi

private data class TopLevelDestination(
    val target: String,
    val label: String,
    val icon: ImageVector,
)

private fun tabStackSaver(): Saver<SnapshotStateList<String>, List<String>> = listSaver(
    save = { stack -> stack.toList() },
    restore = { saved -> saved.toMutableStateList() },
)

@Composable
private fun rememberTabStack(root: String): SnapshotStateList<String> =
    rememberSaveable(saver = tabStackSaver()) { mutableStateListOf(root) }

class VariantNavUi : NavUi {
    private var webView: WebView? = null
    private var backHandler: (() -> Boolean)? = null

    override fun attach(activity: MainActivity, container: FrameLayout, webView: WebView) {
        this.webView = webView
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DebugFloatingNavigation(
                    onBackHandler = { handler -> backHandler = handler },
                    onSelect = { target ->
                        this@VariantNavUi.webView?.evaluateJavascript(
                            "document.querySelector('[data-nav-target=\\\"$target\\\"]')?.click();",
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

    override fun handleBack(): Boolean = backHandler?.invoke() ?: false
}

@Composable
private fun DebugFloatingNavigation(
    onBackHandler: ((() -> Boolean) -> Unit),
    onSelect: (String) -> Unit,
    onFabClick: () -> Unit,
) {
    val destinations = listOf(
        TopLevelDestination("home", "Home", Icons.Filled.Home),
        TopLevelDestination("downloads", "Downloads", Icons.Filled.Download),
        TopLevelDestination("library", "Library", Icons.Filled.Folder),
        TopLevelDestination("settings", "Settings", Icons.Filled.Settings),
    )

    // Each top-level tab owns a separately saveable stack.
    val homeStack = rememberTabStack("home")
    val downloadsStack = rememberTabStack("downloads")
    val libraryStack = rememberTabStack("library")
    val settingsStack = rememberTabStack("settings")
    val stacks = listOf(homeStack, downloadsStack, libraryStack, settingsStack)
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val activeStack = stacks[selected]

    val onBack = {
        when {
            activeStack.size > 1 -> {
                activeStack.removeAt(activeStack.lastIndex)
                onSelect(activeStack.last())
                true
            }
            selected != 0 -> {
                selected = 0
                onSelect("home")
                true
            }
            else -> false
        }
    }
    SideEffect { onBackHandler(onBack) }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            if (activeStack.size == 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(112.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
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
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                                        modifier = Modifier.fillMaxWidth(),
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
