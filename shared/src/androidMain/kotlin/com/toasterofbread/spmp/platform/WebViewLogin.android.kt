package com.toasterofbread.spmp.platform

import LocalPlayerState
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import dev.toastbits.composekit.platform.composable.BackHandler
import dev.toastbits.composekit.utils.common.isDark
import dev.toastbits.composekit.utils.composable.OnChangedEffect
import dev.toastbits.composekit.utils.composable.SubtleLoadingIndicator
import com.toasterofbread.spmp.service.playercontroller.PlayerState
import kotlinx.coroutines.runBlocking

actual fun isWebViewLoginSupported(): Boolean = true

class WebResourceRequestReader(private val request: WebResourceRequest): WebViewRequest {
    override val url: String
        get() = request.url.toString()
    override val isRedirect: Boolean
        get() = request.isRedirect
    override val method: String
        get() = request.method
    override val requestHeaders: Map<String, String>
        get() = request.requestHeaders
}

private fun clearStorage() {
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun WebViewLogin(
    initial_url: String,
    modifier: Modifier,
    onClosed: () -> Unit,
    shouldShowPage: (url: String) -> Boolean,
    loading_message: String?,
    onRequestIntercepted: suspend (WebViewRequest, openUrl: (String) -> Unit, getCookie: (String) -> String) -> Unit
) {
    val player: PlayerState = LocalPlayerState.current
    var web_view: WebView? by remember { mutableStateOf(null) }
    val is_dark: Boolean by remember { derivedStateOf { player.theme.background.isDark() } }

    var requested_url: String? by remember { mutableStateOf(null) }
    OnChangedEffect(requested_url) {
        requested_url?.also {
            web_view?.loadUrl(it)
        }
    }

    OnChangedEffect(web_view, is_dark) {
        web_view?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                settings.isAlgorithmicDarkeningAllowed = is_dark
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                settings.forceDark = if (is_dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clearStorage()
        }
    }

    BackHandler(web_view?.canGoBack() == true) {
        val web: WebView = web_view ?: return@BackHandler

        val back_forward_list = web.copyBackForwardList()
        if (back_forward_list.currentIndex > 0) {
            val previous_url = back_forward_list.getItemAtIndex(back_forward_list.currentIndex - 1).url
            if (previous_url == initial_url) {
                onClosed()
                clearStorage()
                return@BackHandler
            }
        }

        web.goBack()
    }

    var show_webview by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center) {
        AnimatedVisibility(!show_webview, enter = fadeIn(), exit = fadeOut()) {
            SubtleLoadingIndicator(message = loading_message)
        }

        AndroidView(
            modifier = modifier.graphicsLayer {
                alpha = if (show_webview) 1f else 0f
            },
            factory = { context ->
                WebView(context).apply {
                    clearStorage()

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)

                            if (!shouldShowPage(url)) {
                                show_webview = false
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)

                            if (url != null && shouldShowPage(url)) {
                                show_webview = true
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            runBlocking {
                                onRequestIntercepted(
                                    WebResourceRequestReader(request),
                                    {
                                        requested_url = it
                                    },
                                    { url ->
                                        CookieManager.getInstance().getCookie(url)
                                    }
                                )
                            }
                            return null
                        }
                    }

                    loadUrl(initial_url)
                    web_view = this
                }
            }
        )
    }
}
