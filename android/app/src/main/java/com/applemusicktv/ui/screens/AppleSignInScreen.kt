package com.applemusicktv.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
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
fun AppleSignInScreen(
    onToken: (String) -> Unit,
    onClose: () -> Unit,
    /** Fetches the MusicKit developer token (scraped web bearer) from the proxy. Null → fall back to
     *  loading the full music.apple.com login site instead of the "Connect" page. */
    fetchDevToken: (suspend () -> String?)? = null,
) {
    var status by remember { mutableStateOf("Loading Apple Music…") }
    var captured by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Resolve the developer token before building the WebView so the factory knows which page to load.
    var devToken by remember { mutableStateOf<String?>(null) }
    var devTokenResolved by remember { mutableStateOf(fetchDevToken == null) }
    LaunchedEffect(Unit) {
        if (fetchDevToken != null) {
            devToken = runCatching { fetchDevToken() }.getOrNull()
            devTokenResolved = true
        }
    }
    if (!devTokenResolved) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            Text("Preparing sign-in…", color = Color.White, fontSize = 14.sp)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF111114)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Connect to Apple Music", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(status, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
                Button(onClick = onClose) { Text("Close") }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().apply { setAcceptCookie(true) }
                    // Root holds the main WebView plus any auth popup, which MUST be its own child
                    // WebView — Amazon's WebView crashes ("Parent WebView cannot host its own popup
                    // window") if onCreateWindow points the popup transport back at the parent.
                    val root = android.widget.FrameLayout(ctx)

                    fun configure(wv: WebView) {
                        wv.settings.userAgentString = SAFARI_UA
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.javaScriptCanOpenWindowsAutomatically = true
                        wv.settings.setSupportMultipleWindows(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        // Fire TV D-pad: without these the WebView never takes focus, so the remote can't
                        // move into form fields (email/password). setNeedInitialFocus lets it auto-focus
                        // the first field on load.
                        wv.isFocusable = true
                        wv.isFocusableInTouchMode = true
                        wv.settings.setNeedInitialFocus(true)
                    }

                    // ── Native input bridge ────────────────────────────────────────────────────
                    // Amazon's WebView keyboard mangles web text fields (first char doubled, backspace
                    // broken, passwords barely register). So we DON'T type into the web field. Instead a
                    // native EditText (which the Fire TV keyboard handles perfectly) floats at the bottom;
                    // whatever you type there is mirrored into the focused web input via JS, using the
                    // native value-setter + input/change events so Apple's React form registers it.
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    val nativeInput = EditText(ctx).apply {
                        visibility = android.view.View.GONE
                        hint = "Type here — mirrors into the field above"
                        setHintTextColor(android.graphics.Color.parseColor("#777777"))
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#151517"))
                        setPadding(28, 22, 28, 22)
                        imeOptions = EditorInfo.IME_ACTION_DONE or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    }
                    // Which WebView currently owns the focused field (popup wins once it's up).
                    var activeWv: WebView? = null
                    var syncing = false
                    nativeInput.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) {
                            if (syncing) return
                            val v = s?.toString() ?: ""
                            val json = org.json.JSONObject.quote(v)
                            activeWv?.evaluateJavascript("window.__amSet && window.__amSet($json)", null)
                        }
                    })
                    nativeInput.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            activeWv?.evaluateJavascript("window.__amEnter && window.__amEnter()", null)
                            imm.hideSoftInputFromWindow(nativeInput.windowToken, 0)
                            nativeInput.visibility = android.view.View.GONE
                            true
                        } else false
                    }
                    // Bridge the web page calls when a text field gains/loses focus.
                    val bridge = object {
                        @JavascriptInterface fun onFieldFocus(type: String, value: String) {
                            nativeInput.post {
                                nativeInput.inputType = when (type) {
                                    "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                                    "email"    -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                                    "tel"      -> InputType.TYPE_CLASS_PHONE
                                    "number"   -> InputType.TYPE_CLASS_NUMBER
                                    else       -> InputType.TYPE_CLASS_TEXT
                                }
                                syncing = true
                                nativeInput.setText(value)
                                nativeInput.setSelection(value.length)
                                syncing = false
                                nativeInput.visibility = android.view.View.VISIBLE
                                nativeInput.requestFocus()
                                imm.showSoftInput(nativeInput, InputMethodManager.SHOW_IMPLICIT)
                                status = if (type == "password") "Type your password below" else "Type below — it fills the field above"
                            }
                        }
                        @JavascriptInterface fun onFieldBlur() {
                            nativeInput.post {
                                imm.hideSoftInputFromWindow(nativeInput.windowToken, 0)
                                nativeInput.visibility = android.view.View.GONE
                            }
                        }
                    }

                    fun hookInputs(wv: WebView) {
                        activeWv = wv
                        wv.evaluateJavascript(INPUT_HOOK_JS, null)
                    }

                    val main = NoExtractWebView(ctx)
                    configure(main)
                    main.addJavascriptInterface(bridge, "AMNative")
                    main.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { status = "Loading…" }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            status = if (devToken != null) "Press Connect, then sign in with your Apple ID"
                                     else "Sign in with your Apple ID"
                            hookInputs(main)
                        }
                    }
                    main.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                            cm ?: return false
                            android.util.Log.i("AMConnect", "[js] ${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}")
                            if (cm.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                                status = "JS: ${cm.message().take(80)}"
                            return true
                        }
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
                        ): Boolean {
                            android.util.Log.i("AMConnect", "onCreateWindow (auth popup opening)")
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            // A separate popup WebView, laid over the main one, for Apple's sign-in.
                            val popup = NoExtractWebView(ctx)
                            configure(popup)
                            popup.addJavascriptInterface(bridge, "AMNative")
                            popup.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) { hookInputs(popup) }
                            }
                            popup.webChromeClient = object : WebChromeClient() {
                                override fun onCloseWindow(window: WebView?) {
                                    runCatching { root.removeView(popup); popup.destroy() }
                                    activeWv = main
                                }
                            }
                            root.addView(popup, android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            activeWv = popup
                            popup.requestFocus()
                            return true
                        }
                    }

                    val dt = devToken
                    if (dt != null) {
                        main.loadDataWithBaseURL("https://music.apple.com/", connectHtml(dt), "text/html", "UTF-8", null)
                    } else {
                        main.loadUrl(LOGIN_URL)
                    }
                    root.addView(main, android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                    // Native input bar pinned to the bottom, above the webviews.
                    root.addView(nativeInput, android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.BOTTOM })
                    webView = main
                    root
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

/**
 * WebView that forbids the fullscreen IME "extract" edit box. On Fire TV (landscape) the on-screen
 * keyboard otherwise goes fullscreen and types into its own ExtractEditText, so text never lands in
 * the real HTML field ("text shows behind the keyboard, not in the field"). NO_FULLSCREEN +
 * NO_EXTRACT_UI keeps input flowing straight to the focused web input.
 */
private class NoExtractWebView(ctx: Context) : WebView(ctx) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return ic
    }
}

/**
 * Injected into every page: tracks the focused text input and mirrors native-typed text back into it.
 * `window.__amSet` uses the prototype value setter so React-controlled inputs (Apple's sign-in) see the
 * change; `window.__amEnter` submits. `focusin`/`focusout` tell the native layer to show/hide its bar.
 */
private const val INPUT_HOOK_JS = """
(function(){
  if (window.__amHooked) return; window.__amHooked = true;
  function isText(el){
    if(!el) return false;
    if(el.tagName==='TEXTAREA') return true;
    if(el.tagName!=='INPUT') return false;
    var t=(el.type||'text').toLowerCase();
    return ['text','password','email','tel','number','search','url'].indexOf(t)>=0;
  }
  window.__amSet = function(v){
    var el=window.__amTarget; if(!el) return;
    try {
      var proto = el.tagName==='TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto,'value');
      if (d && d.set) d.set.call(el, v); else el.value = v;
    } catch(e){ el.value = v; }
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  };
  window.__amEnter = function(){
    var el=window.__amTarget; if(!el) return;
    ['keydown','keypress','keyup'].forEach(function(t){
      el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
    });
    if (el.form && el.form.requestSubmit) { try { el.form.requestSubmit(); } catch(e){} }
  };
  document.addEventListener('focusin', function(e){
    if (isText(e.target)) {
      window.__amTarget = e.target;
      var t=(e.target.type||'text').toLowerCase();
      try { AMNative.onFieldFocus(t, e.target.value||''); } catch(err){}
    }
  }, true);
  document.addEventListener('focusout', function(e){
    if (isText(e.target)) { try { AMNative.onFieldBlur(); } catch(err){} }
  }, true);
})();
"""

/** iPhone Safari — the mobile Apple ID sign-in uses simpler fields that behave better with the Fire
 *  TV on-screen keyboard than the desktop page's custom input widgets. */
private const val SAFARI_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

private const val LOGIN_URL = "https://music.apple.com/us/login"

/**
 * The on-TV "Connect to Apple Music" page: loads MusicKit JS v3, configures it with the scraped web
 * developer token, and on button press calls `music.authorize()` — Apple's official consent + sign-in
 * flow — which resolves with the media-user-token. We stash it on `window.MusicKit` where [TOKEN_JS]
 * (the poller) already looks. Big remote-friendly button; the Apple ID / 2FA screen that opens is
 * Apple's own, typed with the on-screen keyboard.
 */
private fun connectHtml(devToken: String): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  html,body{margin:0;height:100%;background:#000;color:#fff;font-family:-apple-system,Helvetica,Arial,sans-serif}
  .wrap{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;gap:22px;text-align:center;padding:24px}
  h1{font-size:26px;margin:0;font-weight:600}
  p{font-size:15px;color:#bbb;margin:0;max-width:640px;line-height:1.4}
  #go{font-size:20px;font-weight:600;color:#fff;background:#FA233B;border:none;border-radius:40px;padding:16px 42px;cursor:pointer}
  #go:focus{outline:3px solid #fff;outline-offset:3px}
  #err{color:#ff8a8a;font-size:13px;min-height:16px}
</style></head><body>
<div class="wrap">
  <h1>Connect to Apple Music</h1>
  <p>Press Connect, then sign in with your Apple ID. A verification code may be sent to your other Apple devices.</p>
  <button id="go" autofocus>Connect</button>
  <div id="err"></div>
</div>
<script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js" data-web-components async
        onerror="window.__mkErr=1;console.log('musickit.js FAILED to load')"></script>
<script>
  var DEV_TOKEN = "${devToken.replace("\"", "\\\"")}";
  var ready = false;
  function setErr(m){ var e=document.getElementById('err'); if(e) e.textContent = m || ''; console.log('status: '+m); }
  console.log('connect page loaded, devTokenLen='+DEV_TOKEN.length);
  async function ensure(){
    if(!window.MusicKit) throw new Error('MusicKit not loaded (script blocked?)');
    console.log('configuring MusicKit…');
    return await MusicKit.configure({
      developerToken: DEV_TOKEN,
      app: { name: 'Apple Music TV', build: '1.0' }
    });
  }
  document.addEventListener('musickitloaded', function(){ ready=true; console.log('musickitloaded'); setErr('Ready — press Connect'); });
  // Fallback: some builds don't fire musickitloaded reliably; poll for the global.
  var t=setInterval(function(){ if(window.MusicKit){ ready=true; clearInterval(t); console.log('MusicKit global present'); } }, 400);
  document.getElementById('go').addEventListener('click', async function(ev){
    ev.preventDefault();
    setErr('Connecting…');
    try {
      var music = await ensure();
      console.log('calling authorize()');
      var mut = await music.authorize();       // opens Apple sign-in, resolves with the MUT
      console.log('authorize returned, mutLen='+(mut?(''+mut).length:0));
      if (mut) { window.__amMut = mut; }        // TOKEN_JS also reads MusicKit.getInstance().musicUserToken
      setErr(mut ? 'Signed in' : 'No token returned');
    } catch(e){ console.log('authorize error: '+(e&&e.message?e.message:e)); setErr('' + (e && e.message ? e.message : e)); }
    return false;
  });
</script>
</body></html>
"""

/**
 * Reads the media-user-token from wherever the web player left it: the `media-user-token` cookie
 * (visible to JS when not HttpOnly), any localStorage key containing it, or MusicKit's live instance.
 * Returns "" until present. Kept as a single expression so evaluateJavascript returns the value.
 */
private const val TOKEN_JS = """
(function(){
  try { if (window.__amMut && (''+window.__amMut).length > 20) return window.__amMut; } catch(e){}
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
