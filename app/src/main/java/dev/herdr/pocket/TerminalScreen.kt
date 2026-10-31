package dev.herdr.pocket

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import android.view.inputmethod.InputMethodManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import android.view.HapticFeedbackConstants
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val machine = state.machines[ref.hostId]
    val offline = !state.demo && machine?.connected != true
    Box(Modifier.fillMaxSize().background(Surface).displayCutoutPadding()) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) TerminalTabs(vm, state, space)
            // A new connection session recreates the view, which attaches the terminal again.
            key(ref, state.nativeDisplay, machine?.session) {
                if (state.nativeDisplay) NativeTerminal(vm, state.screenText, state.nativeAlternateScreen, state.fontSize, Modifier.fillMaxWidth().weight(1f))
                else TerminalCanvas(vm, Modifier.fillMaxWidth().weight(1f)) { focusTerminal = it }
            }
            if (offline) OfflineStrip(vm.hostName(ref.hostId), machine?.connecting == true) { vm.reconnect(ref.hostId) }
            if (!offline && (!fullscreen || controls || keyboardVisible || showComposer)) TerminalKeys(vm, state)
            if (showComposer) Row(Modifier.fillMaxWidth().background(Chrome).padding(10.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(message, { message = it }, placeholder = { Text("Message this agent…", fontSize = 12.sp) }, modifier = Modifier.weight(1f).focusRequester(composerFocus), maxLines = 4, shape = RoundedCornerShape(12.dp))
                IconButton(onClick = { val draft = message; vm.sendMessage(draft) { if (message == draft) message = "" } }, enabled = message.isNotBlank() && !state.demo && !state.sending && state.terminalStatus.startsWith("Live")) {
                    if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Send, "Send message", tint = Accent)
                }
                IconButton(onClick = { showComposer = false; keyboard?.hide() }) { Icon(Icons.Outlined.Close, "Close composer", tint = Muted) }
            }
            if (!fullscreen || controls) Row(Modifier.fillMaxWidth().background(Chrome).padding(horizontal = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.terminalStatus, color = if (state.terminalStatus.startsWith("Live")) Muted else Accent, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text(terminal?.title ?: ref.address, modifier = Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable private fun OfflineStrip(host: String, connecting: Boolean, onReconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Blocked.copy(alpha = .12f)).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (connecting) Working else Blocked, 7); Spacer(Modifier.width(10.dp))
        Text(if (connecting) "Reconnecting to $host…" else "Disconnected from $host", fontSize = 13.sp, color = Ink,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (connecting) CircularProgressIndicator(Modifier.padding(14.dp).size(18.dp), strokeWidth = 2.dp)
        else TextButton(onClick = onReconnect) { Text("Reconnect") }
    }
}

private data class BarKey(val label: String, val bytes: String, val description: String = label, val repeats: Boolean = false)
private val barKeys = listOf(
    BarKey("tab", "\t", "Tab"), BarKey("⇧tab", "\u001b[Z", "Shift Tab"),
    BarKey("↑", "\u001b[A", "Up", true), BarKey("↓", "\u001b[B", "Down", true),
    BarKey("←", "\u001b[D", "Left", true), BarKey("→", "\u001b[C", "Right", true),
    BarKey("enter", "\r", "Enter"), BarKey("^C", "\u0003", "Control C"),
    BarKey("home", "\u001b[H", "Home"), BarKey("end", "\u001b[F", "End"),
    BarKey("pg up", "\u001b[5~", "Page up", true), BarKey("pg dn", "\u001b[6~", "Page down", true),
    BarKey("/", "/", "Slash"), BarKey("-", "-", "Dash"), BarKey("|", "|", "Pipe"), BarKey("~", "~", "Tilde"))

@Composable private fun TerminalKeys(vm: PocketViewModel, state: PocketState) {
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val enabled = !state.demo
    fun tap(action: () -> Unit) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action() }
    Row(Modifier.fillMaxWidth().background(Chrome).horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyCap("esc", "Escape", enabled) { tap { vm.pressKey("\u001b".toByteArray()) } }
        KeyCap("ctrl", if (state.modifiers.ctrl) "Control, armed for the next key" else "Control", enabled, active = state.modifiers.ctrl) { tap(vm::toggleCtrl) }
        KeyCap("alt", if (state.modifiers.alt) "Alt, armed for the next key" else "Alt", enabled, active = state.modifiers.alt) { tap(vm::toggleAlt) }
        barKeys.forEach { key -> KeyCap(key.label, key.description, enabled, repeats = key.repeats) { tap { vm.pressKey(key.bytes.toByteArray()) } } }
        KeyCap(null, "Paste clipboard", enabled, icon = Icons.Outlined.ContentPaste) { tap { clipboard.getText()?.text?.let(vm::paste) } }
    }
}

/** A key that types on tap and, for cursor-style keys, repeats while held. Dragging scrolls the bar without typing. */
@Composable private fun KeyCap(label: String?, description: String, enabled: Boolean, active: Boolean = false, repeats: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    val repeated = remember { booleanArrayOf(false) }
    Surface(shape = RoundedCornerShape(7.dp), color = if (active) Accent else Raised, modifier = Modifier.height(42.dp).widthIn(min = 42.dp)
        .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = description; onClick { if (enabled) press(); enabled } }
        .pointerInput(enabled, repeats) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    if (repeats) {
                        repeated[0] = false
                        val job = scope.launch { delay(380); repeated[0] = true; while (isActive) { press(); delay(55) } }
                        tryAwaitRelease(); job.cancel()
                    }
                },
                onTap = { if (!repeated[0]) press() })
        }) {
        Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
            val tint = if (active) Chrome else if (enabled) Ink else Muted
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = tint)
            else Text(label.orEmpty(), color = tint, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

/** Opens a terminal's web link in the browser. A link whose text may differ from its target is confirmed first. */
@Composable fun rememberLinkOpener(): (String, Boolean) -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<String?>(null) }
    asking?.let { url ->
        AlertDialog(onDismissRequest = { asking = null }, title = { Text("Open this link?") },
            text = { SelectionContainer { Text(url, fontSize = 13.sp, lineHeight = 19.sp, color = Muted) } },
            confirmButton = { TextButton(onClick = { asking = null; openInBrowser(context, url) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } })
    }
    return remember(context) { { url, confirm -> Links.browsable(url)?.let { if (confirm) asking = it else openInBrowser(context, it) } } }
}

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

@Composable fun NativeTerminal(vm: PocketViewModel, text: String, alternate: Boolean, fontSize: Int, modifier: Modifier) {
    // While pinching, the size previews locally and is saved when the fingers lift.
    var pinchSize by remember { mutableStateOf<Int?>(null) }
    val size = pinchSize ?: fontSize
    val currentSize by rememberUpdatedState(fontSize)
    val lineSp = (size * 18f / 13f).sp
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = lineSp, letterSpacing = 0.sp, color = Color(0xffd2d4d9))
    val measure = rememberTextMeasurer()
    val width = measure.measure("M", style).size.width.coerceAtLeast(1)
    val density = LocalDensity.current
    val lineHeight = with(density) { lineSp.toPx() }
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
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pinching = pinchSize != null
    LaunchedEffect(viewport, width, lineHeight, pinching) {
        // Resize the remote program once, after the pinch, not for every preview size.
        if (viewport == IntSize.Zero || pinching) return@LaunchedEffect
        delay(120) // Settle keyboard and pinch changes before resizing the remote terminal.
        vm.nativeReady(((viewport.width - padding) / width).toInt(), ((viewport.height - padding) / lineHeight).toInt())
    }
    val pinch = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = currentSize
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    pinchSize = (start * zoom).roundToInt().coerceIn(FONT_SIZES)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            val chosen = pinchSize
            if (chosen != null && chosen != start) vm.setFontSize(chosen) else pinchSize = null
        }
    }
    LaunchedEffect(fontSize) { pinchSize = null }
    val openLink = rememberLinkOpener()
    val shown = text.ifBlank { "Connecting…" }
    val linked = remember(shown) {
        buildAnnotatedString {
            append(shown)
            val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
            for (range in Links.find(shown)) {
                val url = shown.substring(range)
                addLink(LinkAnnotation.Url(url, style) { openLink(url, false) }, range.first, range.last + 1)
            }
        }
    }
    Box(modifier.background(Surface).onSizeChanged { viewport = it }.then(pinch).padding(8.dp)) {
        SelectionContainer {
            Text(linked, style = style, softWrap = false,
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
    val openLink by rememberUpdatedState(rememberLinkOpener())
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
            @JavascriptInterface fun fontSize(size: Int) { handler.post { if (!disposed) vm.setFontSize(size) } }
            // The page never navigates; links it reports are checked and handed to the browser.
            @JavascriptInterface fun openUrl(url: String, confirm: Boolean) { handler.post { if (!disposed) openLink(url, confirm) } }
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
        webView.loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html?font=${vm.state.value.fontSize}")
        onDispose {
            disposed = true; vm.detach(); vm.resetTerminal = null; vm.terminalBytes = null; focus(null)
            webView.removeJavascriptInterface("Pocket"); webView.stopLoading(); webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}
