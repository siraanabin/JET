package com.jet.downloader.navigation

import android.webkit.WebView
import android.widget.FrameLayout
import com.jet.downloader.MainActivity
import com.jet.downloader.NavUi

/**
 * Release keeps the original HTML/WebView navigation unchanged.
 */
class VariantNavUi : NavUi {
    override fun attach(activity: MainActivity, container: FrameLayout, webView: WebView) = Unit
    override fun onPageLoaded() = Unit
    override fun handleBack(): Boolean = false
}
