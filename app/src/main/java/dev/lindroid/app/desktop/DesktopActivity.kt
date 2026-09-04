package dev.lindroid.app.desktop

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lindroid.app.runtime.DesktopSessionBus
import dev.lindroid.app.runtime.DesktopSessionService
import dev.lindroid.app.runtime.DesktopSessionStatus
import dev.lindroid.app.ui.LindroidTheme
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DesktopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        DesktopSessionService.start(this)

        setContent {
            LindroidTheme {
                DesktopScreen(
                    onClose = { finish() },
                    onStop = {
                        DesktopSessionService.stop(this)
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DesktopScreen(onClose: () -> Unit, onStop: () -> Unit) {
    val status by DesktopSessionBus.status.collectAsStateWithLifecycle()
    val password by DesktopSessionBus.password.collectAsStateWithLifecycle()
    val message by DesktopSessionBus.message.collectAsStateWithLifecycle()
    var reloadKey by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (status) {
            DesktopSessionStatus.RUNNING -> password?.let {
                DesktopWebView(it, reloadKey)
            }
            DesktopSessionStatus.FAILED -> DesktopStatus(
                title = "Desktop could not start",
                message = message,
                showSpinner = false,
                action = { Button(onClick = onClose) { Text("Back to Lindroid") } },
            )
            DesktopSessionStatus.STOPPED,
            DesktopSessionStatus.STARTING,
            -> DesktopStatus(
                title = "Booting Debian XFCE",
                message = message,
                showSpinner = true,
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status == DesktopSessionStatus.RUNNING) {
                    FilledTonalIconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Default.Refresh, "Reconnect display")
                    }
                }
                FilledTonalIconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, "Stop desktop")
                }
                FilledTonalIconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Return to Lindroid")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DesktopStatus(
    title: String,
    message: String,
    showSpinner: Boolean,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showSpinner) LoadingIndicator(Modifier.size(64.dp)) else Icon(
            Icons.Default.Computer,
            null,
            Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.size(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let {
            Spacer(Modifier.size(20.dp))
            it()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DesktopWebView(password: String, reloadKey: Int) {
    val encoded = URLEncoder.encode(password, StandardCharsets.UTF_8.name())
    val url = "http://127.0.0.1:${LocalDesktopServer.HTTP_PORT}/vnc.html" +
        "?autoconnect=true&reconnect=true&resize=scale&host=127.0.0.1" +
        "&port=${LocalDesktopServer.HTTP_PORT}&encrypt=false&path=websockify" +
        "&password=$encoded&show_dot=true&keep_device_awake=true"
    var webView: WebView? by remember { androidx.compose.runtime.mutableStateOf(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(url)
                webView = this
            }
        },
        update = { view ->
            if (reloadKey > 0) view.loadUrl(url)
        },
    )

    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) webView?.loadUrl(url)
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
        }
    }
}
