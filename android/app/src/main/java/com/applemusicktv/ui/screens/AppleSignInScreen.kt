package com.applemusicktv.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * On-TV Apple Music sign-in. Loads `music.apple.com` in a WebView so the user logs in with their
 * Apple ID (2FA and all) inside an in-app browser window, then extracts the **media-user-token** the
 * web player stores after a real login and hands it back via [onToken].
 *
 * Why a WebView we control (not "open Apple in your browser"): the token lives in music.apple.com's
 * OWN origin, so only a surface we host can read it back. And it is read via injected JavaScript, NOT
 * Android's `CookieManager.getCookie` — that API cannot see HttpOnly cookies, whereas MusicKit JS
 * (and therefore `document.cookie` / `localStorage` in-page) can, which is where we look.
 *
 * ON-DEVICE TUNING POINTS (verify when testing on the Fire TV):
 *  - Apple sometimes rejects embedded WebViews ("this browser is not supported"); a desktop Safari
 *    user-agent usually gets past it, set below.
 *  - MusicKit's sign-in may open a popup (window.open) for idmsa.apple.com; [onCreateWindow] routes
 *    that back into this same WebView so the flow stays in one window.
 *  - If the token key differs, widen the extractor JS in [TOKEN_JS].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppleSignInScreen(onToken: (String) -> Unit, onClose: () -> Unit) {
    var status by remember { mutableStateOf("Loading Apple Music…") }
    var captured by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF111114)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to Apple Music", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(status, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
                Button(onClick = onClose) { Text("Close") }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                    }
                    WebView(ctx).apply {
                        // Desktop Safari UA — embedded WebViews are otherwise sometimes refused by Apple.
                        settings.userAgentString = SAFARI_UA
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                status = "Loading…"
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                status = "Sign in with your Apple ID"
                            }
                        }
                        // Route MusicKit's auth popup (window.open → idmsa.apple.com) into THIS WebView
                        // rather than dropping it, which is what breaks the sign-in in a plain WebView.
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = view
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        loadUrl(LOGIN_URL)
                        webView = this
                    }
                },
            )
        }
    }

    // Poll the page for the token the web player stores once login completes. Cheap (one JS eval
    // every ~1.5s) and only runs until the token appears or the screen is closed.
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        while (!captured) {
            delay(1500)
            wv.evaluateJavascript(TOKEN_JS) { raw ->
                val token = raw?.trim('"')?.replace("\\\"", "\"").orEmpty()
                if (!captured && token.isNotBlank() && token != "null" && token.length > 20) {
                    captured = true
                    status = "Signed in — saving token…"
                    CookieManager.getInstance().flush()
                    onToken(token)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); destroy() }
            webView = null
        }
    }
}

/** macOS Safari — embedded WebViews are otherwise sometimes refused by Apple's sign-in. */
private const val SAFARI_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"

private const val LOGIN_URL = "https://music.apple.com/us/login"

/**
 * Reads the media-user-token from wherever the web player left it: the `media-user-token` cookie
 * (visible to JS when not HttpOnly), any localStorage key containing it, or MusicKit's live instance.
 * Returns "" until present. Kept as a single expression so evaluateJavascript returns the value.
 */
private const val TOKEN_JS = """
(function(){
  try { var m = document.cookie.match(/media-user-token=([^;]+)/); if (m && m[1]) return m[1]; } catch(e){}
  try {
    for (var i=0;i<localStorage.length;i++){
      var k = localStorage.key(i);
      if (k && k.toLowerCase().indexOf('media-user-token') >= 0) {
        var v = localStorage.getItem(k); if (v) return v;
      }
    }
  } catch(e){}
  try {
    if (window.MusicKit && MusicKit.getInstance && MusicKit.getInstance().musicUserToken)
      return MusicKit.getInstance().musicUserToken;
  } catch(e){}
  return "";
})();
"""
