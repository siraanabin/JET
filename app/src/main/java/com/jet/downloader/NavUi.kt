package com.jet.downloader

import android.widget.FrameLayout
import android.webkit.WebView

/**
 * Variant-specific navigation UI. The main source set deliberately has no Compose dependency.
 */
interface NavUi {
    fun attach(activity: MainActivity, container: FrameLayout, webView: WebView)
    fun onPageLoaded()
    fun handleBack(): Boolean
}

object NavUiFactory {
    fun create(): NavUi = try {
        Class.forName("com.jet.downloader.navigation.VariantNavUi")
            .getDeclaredConstructor()
            .newInstance() as NavUi
    } catch (_: Exception) {
        object : NavUi {
            override fun attach(activity: MainActivity, container: FrameLayout, webView: WebView) = Unit
            override fun onPageLoaded() = Unit
            override fun handleBack(): Boolean = false
        }
    }
}
