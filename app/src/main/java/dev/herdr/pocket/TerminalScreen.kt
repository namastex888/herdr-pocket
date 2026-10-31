package dev.herdr.pocket

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

@Composable fun TerminalScreen(vm: PocketViewModel, state: PocketState, terminal: CoreTerminal?, space: PocketSpace?, onNavigation: () -> Unit) {
    val ref = state.selected ?: return
    var message by remember(ref) { mutableStateOf("") }
    var showComposer by remember(ref) { mutableStateOf(false) }
    var controls by remember(ref) { mutableStateOf(false) }
    var focusTerminal by remember { mutableStateOf<(() -> Unit)?>(null) }
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val fullscreen = state.terminalFullscreen
    androidx.activity.compose.BackHandler(controls || showComposer) {
        if (showComposer) { showComposer = false; keyboard?.hide() } else controls = false
    }
    LaunchedEffect(showComposer) { if (showComposer) { composerFocus.requestFocus(); keyboard?.show() } }
    Box(Modifier.fillMaxSize().background(Surface).displayCutoutPadding()) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) TerminalTabs(vm, state, space)
            key(ref, state.nativeDisplay) {
                if (state.nativeDisplay) NativeTerminal(vm, state.screenText, state.nativeAlternateScreen, Modifier.fillMaxWidth().weight(1f))
                else TerminalCanvas(vm, Modifier.fillMaxWidth().weight(1f)) { focusTerminal = it }
            }
            if (!fullscreen || controls || keyboardVisible || showComposer) TerminalKeys(vm, state.demo)
            if (showComposer) Row(Modifier.fillMaxWidth().background(Chrome).padding(10.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(message, { message = it }, placeholder = { Text("Message this agent…", fontSize = 12.sp) }, modifier = Modifier.weight(1f).focusRequester(composerFocus), maxLines = 4, shape = RoundedCornerShape(12.dp))
                IconButton(onClick = { val draft = message; vm.sendMessage(draft) { if (message == draft) message = "" } }, enabled = message.isNotBlank() && !state.demo && !state.sending && state.terminalStatus.startsWith("Live")) {
                    if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Send, "Send message", tint = Accent)
                }
                IconButton(onClick = { showComposer = false; keyboard?.hide() }) { Icon(Icons.Outlined.Close, "Close composer", tint = Muted) }
            }
            if (!fullscreen || controls) Row(Modifier.fillMaxWidth().background(Chrome).padding(horizontal = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.terminalStatus, color = if (state.terminalStatus.startsWith("Live")) Muted else Accent, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { showComposer = !showComposer; controls = false }) { Icon(Icons.Outlined.ChatBubbleOutline, "Toggle message composer", Modifier.size(20.dp), tint = if (showComposer) Accent else Muted) }
                IconButton(onClick = { controls = false; if (state.nativeDisplay) showComposer = true else focusTerminal?.invoke() }) { Icon(Icons.Outlined.Keyboard, "Open terminal keyboard", Modifier.size(22.dp), tint = Accent) }
            }
        }
        if (fullscreen && !controls) Surface(color = Chrome.copy(alpha = .80f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            IconButton(onClick = { controls = true }) { Icon(Icons.Outlined.MoreHoriz, "Terminal controls", tint = Muted) }
        }
        if (fullscreen && controls) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Chrome.copy(alpha = .97f))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::backToList) { Icon(Icons.Outlined.ArrowBack, "Back to agents") }
                IconButton(onClick = { controls = false; onNavigation() }) { Icon(Icons.Outlined.Menu, "Open navigation") }
                Text(terminal?.title ?: ref.address, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                IconButton(onClick = { controls = false; vm.setTerminalFullscreen(false) }) { Icon(Icons.Outlined.FullscreenExit, "Exit full screen") }
                IconButton(onClick = { controls = false }) { Icon(Icons.Outlined.Close, "Hide terminal controls") }
            }
            TerminalTabs(vm, state, space)
            HorizontalDivider(color = Border)
        }
    }
}

@Composable private fun TerminalTabs(vm: PocketViewModel, state: PocketState, space: PocketSpace?) {
    val ref = state.selected ?: return
    if (space != null && space.tabs.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Chrome).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        space.tabs.forEach { tab ->
            val active = ref.address in tab.terminals
            Column(Modifier.widthIn(min = 90.dp).clickable(enabled = tab.terminals.isNotEmpty()) { vm.selectTerminal(TerminalRef(ref.hostId, tab.terminals.first())) }) {
                Row(Modifier.padding(14.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(13.dp), tint = if (active) Accent else Muted)
                    Spacer(Modifier.width(7.dp)); Text(tab.name.ifBlank { state.machines[ref.hostId]?.terminals?.find { it.address in tab.terminals }?.title ?: "Terminal" }, color = if (active) Ink else Muted, fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Accent else Color.Transparent))
            }
        }
    }
    val currentTab = space?.tabs?.find { ref.address in it.terminals }
    if (currentTab != null && currentTab.terminals.size > 1) Row(Modifier.fillMaxWidth().background(Chrome).horizontalScroll(rememberScrollState()).padding(10.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        currentTab.terminals.forEach { address ->
            val t = state.machines[ref.hostId]?.terminals?.find { it.address == address }
            FilterChip(selected = address == ref.address, onClick = { vm.selectTerminal(TerminalRef(ref.hostId, address)) }, label = { Text(t?.title ?: address, fontSize = 10.sp) })
        }
    }
}

@Composable private fun TerminalKeys(vm: PocketViewModel, demo: Boolean) {
    Row(Modifier.fillMaxWidth().background(Chrome).horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        val keys = listOf("esc" to "\u001b", "tab" to "\t", "ctrl+c" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "pg up" to "\u001b[5~", "pg dn" to "\u001b[6~", "enter" to "\r")
        keys.forEach { (label, value) ->
            Surface(shape = RoundedCornerShape(7.dp), color = Raised, modifier = Modifier.height(42.dp).clickable(enabled = !demo) { vm.type(value.toByteArray()) }) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Text(label, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Composable fun NativeTerminal(vm: PocketViewModel, text: String, alternate: Boolean, modifier: Modifier) {
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, color = Color(0xffd2d4d9))
    val measure = rememberTextMeasurer()
    val width = measure.measure("M", style).size.width.coerceAtLeast(1)
    val density = LocalDensity.current
    val lineHeight = with(density) { 18.sp.toPx() }
    val padding = with(density) { 16.dp.toPx() }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(text, alternate) {
        val follow = alternate || vertical.value >= vertical.maxValue - (lineHeight * 2).toInt()
        if (follow) { withFrameNanos { }; vertical.scrollTo(vertical.maxValue) }
    }
    val gestures = if (alternate) Modifier.pointerInput(alternate, lineHeight) {
        var pending = 0f
        detectVerticalDragGestures(
            onDragStart = { pending = 0f },
            onVerticalDrag = { change, amount ->
                change.consume(); pending -= amount
                val lines = (pending / lineHeight).toInt()
                if (kotlin.math.abs(lines) >= 3) { vm.scrollNative(lines); pending = 0f }
            }
        )
    } else Modifier.verticalScroll(vertical)
    Box(modifier.background(Surface).onSizeChanged { size ->
        vm.nativeReady(((size.width - padding) / width).toInt(), ((size.height - padding) / lineHeight).toInt())
    }.padding(8.dp)) {
        SelectionContainer {
            Text(text.ifBlank { "Connecting…" }, style = style, softWrap = false,
                modifier = Modifier.fillMaxSize().then(gestures).horizontalScroll(horizontal))
        }
        if (!alternate && vertical.value < vertical.maxValue - lineHeight * 2) {
            SmallFloatingActionButton(onClick = { scope.launch { vertical.animateScrollTo(vertical.maxValue) } }, modifier = Modifier.align(Alignment.BottomEnd), containerColor = Raised) {
                Icon(Icons.Outlined.ArrowDownward, "Latest output", tint = Accent)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable fun TerminalCanvas(vm: PocketViewModel, modifier: Modifier, focus: ((() -> Unit)?) -> Unit) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val webView = remember { WebView(context) }
    DisposableEffect(webView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        var disposed = false
        val selectedAtCreation = vm.state.value.selected
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webView.setBackgroundColor(0xff17191d.toInt())
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Requests are intercepted below; only bundled assets can load.
            blockNetworkLoads = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
                loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest) = true
        }
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun failed(message: String) {
                if (BuildConfig.DEBUG) android.util.Log.e("HerdrTerminal", message.take(500))
                handler.post { if (!disposed) vm.rendererFailed() }
            }
            @JavascriptInterface fun ready(cols: Int, rows: Int) { handler.post { if (!disposed) vm.terminalReady(cols, rows) } }
            @JavascriptInterface fun resized(cols: Int, rows: Int) { handler.post { if (!disposed) vm.resized(cols, rows) } }
            @JavascriptInterface fun input(value: String) {
                if (value.length > 2 * 1024 * 1024) return
                runCatching { Base64.decode(value, Base64.NO_WRAP) }.onSuccess {
                    if (!disposed && vm.state.value.selected == selectedAtCreation) vm.type(it)
                }
            }
            @JavascriptInterface fun painted(revision: Long, channel: Int, count: Int) { if (!disposed) vm.acknowledge(revision, channel, count) }
        }, "Pocket")
        vm.resetTerminal = { revision -> handler.post { if (!disposed) webView.evaluateJavascript("window.resetTerminal($revision)", null) } }
        vm.terminalBytes = { revision, channel, bytes ->
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + 24 * 1024)
                val encoded = Base64.encodeToString(bytes.copyOfRange(offset, end), Base64.NO_WRAP)
                handler.post { if (!disposed) webView.evaluateJavascript("window.output($revision,$channel,${JSONObject.quote(encoded)})", null) }
                offset = end
            }
        }
        focus {
            webView.requestFocus()
            webView.evaluateJavascript("window.focusTerminal()") {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
        onDispose {
            disposed = true; vm.detach(); vm.resetTerminal = null; vm.terminalBytes = null; focus(null)
            webView.removeJavascriptInterface("Pocket"); webView.stopLoading(); webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}
