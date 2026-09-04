package dev.lindroid.app.desktop

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Keyboard
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var reconnectKey by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (status) {
            DesktopSessionStatus.RUNNING -> password?.let {
                NativeDesktop(password = it, reconnectKey = reconnectKey)
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
                    FilledTonalIconButton(onClick = { reconnectKey++ }) {
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NativeDesktop(password: String, reconnectKey: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewRef by remember { mutableStateOf<NativeDesktopView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(true) }

    fun connect(view: NativeDesktopView) {
        error = null
        connecting = true
        scope.launch(Dispatchers.IO) {
            val client = RfbClient(password = password)
            try {
                client.connect()
                withContext(Dispatchers.Main) {
                    view.attach(client)
                    viewRef = view
                    connecting = false
                }
            } catch (e: Exception) {
                client.close()
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Could not connect to the display"
                    connecting = false
                }
            }
        }
    }

    DisposableEffect(password, reconnectKey) {
        val old = viewRef
        onDispose {
            old?.client?.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                NativeDesktopView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    connect(this)
                }
            },
            update = { view ->
                if (reconnectKey > 0) {
                    view.client?.close()
                    connect(view)
                }
            },
        )

        if (connecting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(Modifier.size(48.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("Connecting to display", color = Color.White)
                }
            }
        }

        error?.let {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Display connection dropped", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(Modifier.size(8.dp))
                    Text(it, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        FilledTonalIconButton(
            onClick = {
                viewRef?.let { view ->
                    view.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(view, 0)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Keyboard, "Show keyboard")
        }
    }

    LaunchedEffect(reconnectKey) {
        if (reconnectKey > 0) {
            viewRef?.let { view ->
                view.client?.close()
                connect(view)
            }
        }
    }
}
