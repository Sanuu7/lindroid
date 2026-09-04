package dev.lindroid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lindroid.app.desktop.DesktopActivity
import dev.lindroid.app.runtime.DesktopSessionStatus
import dev.lindroid.app.runtime.DesktopSetupStatus
import dev.lindroid.app.runtime.DistroFlavor
import dev.lindroid.app.runtime.LxContainer
import dev.lindroid.app.runtime.InstallState
import dev.lindroid.app.runtime.SessionStatus
import dev.lindroid.app.runtime.UninstallBus
import dev.lindroid.app.runtime.UninstallTarget
import dev.lindroid.app.shizuku.ShizukuMode
import dev.lindroid.app.ui.FilesPage
import dev.lindroid.app.ui.LindroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LindroidViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LindroidTheme {
                Surface(Modifier.fillMaxSize()) {
                    LindroidApp(viewModel)
                }
            }
        }
    }
}

private enum class AppPage(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    DESKTOP("Desktop", Icons.Default.Computer),
    TERMINAL("Terminal", Icons.Outlined.Terminal),
    FILES("Files", Icons.Outlined.Folder),
    SETTINGS("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LindroidApp(viewModel: LindroidViewModel) {
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val output by viewModel.terminalOutput.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val shizukuMode by viewModel.shizukuMode.collectAsStateWithLifecycle()
    val desktopSetupStatus by viewModel.desktopSetupStatus.collectAsStateWithLifecycle()
    val desktopSetupLog by viewModel.desktopSetupLog.collectAsStateWithLifecycle()
    val desktopSetupError by viewModel.desktopSetupError.collectAsStateWithLifecycle()
    val desktopSessionStatus by viewModel.desktopSessionStatus.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val activeContainer by viewModel.activeContainer.collectAsStateWithLifecycle()
    val containerStates by viewModel.containerStates.collectAsStateWithLifecycle()
    val sharedEntries by viewModel.sharedEntries.collectAsStateWithLifecycle()
    val sharedPath by viewModel.sharedPath.collectAsStateWithLifecycle()
    val sharedNotice by viewModel.sharedNotice.collectAsStateWithLifecycle()
    val uninstallRunning by UninstallBus.running.collectAsStateWithLifecycle()
    val uninstallMessage by UninstallBus.message.collectAsStateWithLifecycle()
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    var pendingForegroundAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingExport by remember { mutableStateOf<SharedEntry?>(null) }
    val context = LocalContext.current

    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) viewModel.importSharedFiles(uris)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingExport
        if (uri != null && entry != null) viewModel.exportSharedFile(entry, uri)
        pendingExport = null
    }
    LaunchedEffect(selectedPage) {
        if (selectedPage == AppPage.FILES.ordinal) viewModel.refreshSharedFiles()
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingForegroundAction?.invoke()
        pendingForegroundAction = null
    }
    val runForegroundAction: (() -> Unit) -> Unit = { action ->
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingForegroundAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    val startSession = {
        runForegroundAction { viewModel.startSession() }
        selectedPage = AppPage.TERMINAL.ordinal
    }
    val installDesktop = { runForegroundAction { viewModel.installDesktop() } }
    val openDesktop = { context.startActivity(Intent(context, DesktopActivity::class.java)) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Spacer(Modifier.width(10.dp))
                        Text("Lindroid", fontWeight = FontWeight.Black)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page.ordinal,
                        onClick = { selectedPage = page.ordinal },
                        icon = { Icon(page.icon, contentDescription = null) },
                        label = { Text(page.label) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = AppPage.entries[selectedPage],
            label = "page",
            modifier = Modifier.padding(padding),
        ) { page ->
            when (page) {
                AppPage.HOME -> HomePage(
                    installState = installState,
                    sessionStatus = sessionStatus,
                    desktopSetupStatus = desktopSetupStatus,
                    desktopSessionStatus = desktopSessionStatus,
                    containers = containers,
                    containerStates = containerStates,
                    activeContainer = activeContainer,
                    onInstall = viewModel::installRootfs,
                    onInstallDesktop = installDesktop,
                    onOpenDesktop = openDesktop,
                    onStartTerminal = startSession,
                    onSelectContainer = viewModel::selectContainer,
                    onCreateContainer = viewModel::createContainer,
                )
                AppPage.DESKTOP -> DesktopPage(
                    container = activeContainer,
                    linuxInstalled = installState is InstallState.Installed,
                    setupStatus = desktopSetupStatus,
                    sessionStatus = desktopSessionStatus,
                    setupLog = desktopSetupLog,
                    setupError = desktopSetupError,
                    onInstallLinux = { selectedPage = AppPage.HOME.ordinal },
                    onInstallDesktop = installDesktop,
                    onOpenDesktop = openDesktop,
                )
                AppPage.TERMINAL -> TerminalPage(
                    installed = installState is InstallState.Installed,
                    output = output,
                    status = sessionStatus,
                    error = sessionError,
                    title = activeContainer?.let { "${it.name} terminal" } ?: "Terminal",
                    onStart = startSession,
                    onStop = viewModel::stopSession,
                    onSend = viewModel::sendCommand,
                    onClear = viewModel::clearTerminal,
                    onInstall = { selectedPage = AppPage.HOME.ordinal },
                )
                AppPage.FILES -> FilesPage(
                    entries = sharedEntries,
                    path = sharedPath,
                    notice = sharedNotice,
                    onRefresh = viewModel::refreshSharedFiles,
                    onOpenDirectory = viewModel::openSharedDirectory,
                    onUp = viewModel::upSharedDirectory,
                    onImport = { importFiles.launch(arrayOf("*/*")) },
                    onExport = { entry ->
                        pendingExport = entry
                        saveFile.launch(entry.name)
                    },
                    onDelete = viewModel::deleteSharedEntry,
                    onDismissNotice = viewModel::clearSharedNotice,
                )
                AppPage.SETTINGS -> SettingsPage(
                    installState,
                    activeContainer,
                    containers,
                    containerStates,
                    desktopSetupStatus,
                    shizukuMode,
                    uninstallRunning,
                    uninstallMessage,
                    viewModel::requestShizuku,
                    onRemoveDesktop = viewModel::removeDesktop,
                    onRemoveContainer = viewModel::removeActiveContainer,
                    onDeleteContainer = viewModel::deleteContainer,
                    onSelectContainer = viewModel::selectContainer,
                )
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("L", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun HomePage(
    installState: InstallState,
    sessionStatus: SessionStatus,
    desktopSetupStatus: DesktopSetupStatus,
    desktopSessionStatus: DesktopSessionStatus,
    containers: List<LxContainer>,
    containerStates: Map<String, InstallState>,
    activeContainer: LxContainer?,
    onInstall: () -> Unit,
    onInstallDesktop: () -> Unit,
    onOpenDesktop: () -> Unit,
    onStartTerminal: () -> Unit,
    onSelectContainer: (String) -> Unit,
    onCreateContainer: (String, DistroFlavor) -> Unit,
) {
    val flavor = activeContainer?.flavor ?: DistroFlavor.DEBIAN
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = if (installState is InstallState.Installed) "Linux, right in\nyour pocket." else "Bring Linux\nto Android.",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            "A private Linux userland powered by PRoot${if (flavor.needsEmulation) " and QEMU" else ""}. No root required; your files stay inside Lindroid.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (installState) {
            InstallState.NotInstalled -> InstallCard(flavor, onInstall)
            is InstallState.Installing -> InstallingCard(installState)
            InstallState.Installed -> ReadyCard(
                flavor,
                sessionStatus,
                desktopSetupStatus,
                desktopSessionStatus,
                onInstallDesktop,
                onOpenDesktop,
                onStartTerminal,
            )
            is InstallState.Failed -> ErrorCard(installState.message, onInstall)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureChip(Icons.Outlined.Architecture, "ARM64 native")
            FeatureChip(Icons.Default.Security, "Rootless")
            FeatureChip(Icons.Outlined.Android, "Android kernel")
        }

        ContainersSection(
            containers = containers,
            containerStates = containerStates,
            activeContainer = activeContainer,
            onSelectContainer = onSelectContainer,
            onCreateContainer = onCreateContainer,
        )

        Text("Your Linux machine", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard(Icons.Default.ViewInAr, "${flavor.desktopLabel} desktop", "A real graphical Linux workspace inside the container.", Modifier.weight(1f))
            FeatureCard(Icons.Outlined.Folder, "Persistent", "Keep apps, settings and files between sessions.", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainersSection(
    containers: List<LxContainer>,
    containerStates: Map<String, InstallState>,
    activeContainer: LxContainer?,
    onSelectContainer: (String) -> Unit,
    onCreateContainer: (String, DistroFlavor) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Containers", style = MaterialTheme.typography.titleLarge)
        containers.forEach { container ->
            val state = containerStates[container.id]
            ContainerRow(
                container = container,
                active = container.id == activeContainer?.id,
                installed = state is InstallState.Installed,
                onSelect = { onSelectContainer(container.id) },
            )
        }
        OutlinedButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("New container")
        }
    }
    if (showCreate) {
        CreateContainerDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, flavor ->
                showCreate = false
                onCreateContainer(name, flavor)
            },
        )
    }
}

@Composable
private fun ContainerRow(
    container: LxContainer,
    active: Boolean,
    installed: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ViewInAr,
                null,
                Modifier.size(24.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(container.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${container.flavor.label} • ${if (installed) "installed" else "not installed"}" +
                        if (container.flavor.needsEmulation) " • x86_64 emulated" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                AssistChip(onClick = {}, label = { Text("Active") })
            }
        }
    }
}

@Composable
private fun CreateContainerDialog(onDismiss: () -> Unit, onCreate: (String, DistroFlavor) -> Unit) {
    var name by remember { mutableStateOf("") }
    var flavor by remember { mutableStateOf(DistroFlavor.DEBIAN) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New container") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("My Linux box") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DistroFlavor.entries.forEach { candidate ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = flavor == candidate, onClick = { flavor = candidate })
                        Column(Modifier.weight(1f)) {
                            Text(candidate.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                candidate.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, flavor) }) { Text("Create") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun InstallCard(flavor: DistroFlavor, onInstall: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(topStart = 44.dp, topEnd = 18.dp, bottomEnd = 44.dp, bottomStart = 18.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.CloudDownload, null, Modifier.size(36.dp))
            Text("Install ${flavor.label}", style = MaterialTheme.typography.headlineMedium)
            Text(flavor.description + " Downloads a verified official image.")
            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                Text("Download and install")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallingCard(state: InstallState.Installing) {
    val target = state.fraction ?: 0.08f
    val animated by animateFloatAsState(target.coerceIn(0f, 1f), label = "install progress")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LoadingIndicator(Modifier.size(54.dp))
            Text("Setting up Debian", style = MaterialTheme.typography.headlineMedium)
            Text(state.message, color = MaterialTheme.colorScheme.onSecondaryContainer)
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Keep Lindroid open while the filesystem is unpacked.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReadyCard(
    flavor: DistroFlavor,
    terminalStatus: SessionStatus,
    desktopSetupStatus: DesktopSetupStatus,
    desktopSessionStatus: DesktopSessionStatus,
    onInstallDesktop: () -> Unit,
    onOpenDesktop: () -> Unit,
    onStartTerminal: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 44.dp, bottomEnd = 18.dp, bottomStart = 44.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (desktopSetupStatus == DesktopSetupStatus.INSTALLED) "The ${flavor.label} desktop is ready" else "${flavor.label} is ready",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        if (desktopSetupStatus == DesktopSetupStatus.INSTALLED) {
                            "${flavor.desktopLabel} • ${flavor.label}${if (flavor.needsEmulation) " • x86_64 emulated" else " • ARM64"}"
                        } else {
                            "Add ${flavor.desktopLabel} for the full desktop experience"
                        },
                    )
                }
            }
            when (desktopSetupStatus) {
                DesktopSetupStatus.NOT_INSTALLED,
                DesktopSetupStatus.FAILED,
                -> Button(onClick = onInstallDesktop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Install graphical desktop")
                }
                DesktopSetupStatus.INSTALLING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Installing ${flavor.desktopLabel}… You can leave this screen open.")
                }
                DesktopSetupStatus.INSTALLED -> Button(onClick = onOpenDesktop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Computer, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (desktopSessionStatus == DesktopSessionStatus.RUNNING) "Return to Linux desktop" else "Launch Linux desktop")
                }
            }
            OutlinedButton(
                onClick = onStartTerminal,
                enabled = terminalStatus != SessionStatus.STARTING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Terminal, null)
                Spacer(Modifier.width(8.dp))
                Text(if (terminalStatus == SessionStatus.RUNNING) "Open terminal" else "Terminal tools")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DesktopPage(
    container: LxContainer?,
    linuxInstalled: Boolean,
    setupStatus: DesktopSetupStatus,
    sessionStatus: DesktopSessionStatus,
    setupLog: String,
    setupError: String?,
    onInstallLinux: () -> Unit,
    onInstallDesktop: () -> Unit,
    onOpenDesktop: () -> Unit,
) {
    val flavor = container?.flavor ?: DistroFlavor.DEBIAN
    val name = container?.name ?: "this container"
    val logScroll = rememberScrollState()
    LaunchedEffect(setupLog) { logScroll.scrollTo(logScroll.maxValue) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Your Linux desktop", style = MaterialTheme.typography.displaySmall)
        Text(
            "Launch a full ${flavor.label} ${flavor.desktopLabel} workspace with its own panel, desktop, window manager and Linux applications.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(topStart = 44.dp, topEnd = 18.dp, bottomEnd = 44.dp, bottomStart = 18.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.Computer, null, Modifier.size(48.dp))
                when {
                    !linuxInstalled -> {
                        Text("Not installed yet", style = MaterialTheme.typography.headlineMedium)
                        Text("Install the base Linux filesystem for $name before adding its graphical desktop.")
                        Button(onClick = onInstallLinux) { Text("Set up the container") }
                    }
                    setupStatus == DesktopSetupStatus.NOT_INSTALLED || setupStatus == DesktopSetupStatus.FAILED -> {
                        Text("Install ${flavor.desktopLabel}", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (flavor == DistroFlavor.MINT) {
                                "Downloads the verified Mint desktop image in minutes, or falls back to an emulated APT install. Allow roughly 3.5 GB of free space."
                            } else {
                                "One-time APT download. Allow roughly 700 MB of free space. Includes XFCE, TigerVNC, fonts and a Linux terminal."
                            },
                        )
                        setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = onInstallDesktop, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (setupStatus == DesktopSetupStatus.FAILED) "Retry desktop setup" else "Install graphical desktop")
                        }
                    }
                    setupStatus == DesktopSetupStatus.INSTALLING -> {
                        LoadingIndicator(Modifier.size(54.dp).align(Alignment.CenterHorizontally))
                        Text("Installing the desktop", style = MaterialTheme.typography.headlineMedium)
                        Text("APT is downloading and configuring ${flavor.desktopLabel}. This can take several minutes${if (flavor.needsEmulation) " longer under emulation" else ""}.")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Surface(color = Color(0xFF0A1008), contentColor = Color(0xFFD9FFB1), shape = MaterialTheme.shapes.medium) {
                            Text(
                                setupLog.takeLast(5_000),
                                Modifier.fillMaxWidth().height(180.dp).verticalScroll(logScroll).padding(14.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    else -> {
                        Text("${flavor.label} ${flavor.desktopLabel}", style = MaterialTheme.typography.headlineMedium)
                        Text(if (sessionStatus == DesktopSessionStatus.RUNNING) "Your graphical Linux session is running." else "Ready to boot your graphical Linux workspace.")
                        Button(onClick = onOpenDesktop, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (sessionStatus == DesktopSessionStatus.RUNNING) "Return to desktop" else "Launch desktop")
                        }
                    }
                }
            }
        }
        Text("The desktop runs locally", style = MaterialTheme.typography.titleLarge)
        Text(
            "The display is available only on this device through an authenticated loopback connection. No remote server or cloud VM is involved.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Installation stopped", style = MaterialTheme.typography.headlineMedium)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun FeatureChip(icon: ImageVector, label: String) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TerminalPage(
    installed: Boolean,
    output: String,
    status: SessionStatus,
    error: String?,
    title: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onInstall: () -> Unit,
) {
    if (!installed) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Install Debian first", style = MaterialTheme.typography.headlineMedium)
                Text("The terminal will become available when the Linux filesystem is ready.")
                Button(onClick = onInstall) { Text("Go to setup") }
            }
        }
        return
    }

    var command by rememberSaveable { mutableStateOf("") }
    val terminalScroll = rememberScrollState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(output) { terminalScroll.scrollTo(terminalScroll.maxValue) }

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(statusLabel(status), style = MaterialTheme.typography.bodyMedium, color = statusColor(status))
            }
            Row {
                IconButton(onClick = onClear) { Icon(Icons.Default.ClearAll, "Clear terminal") }
                if (status == SessionStatus.RUNNING || status == SessionStatus.STARTING) {
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop session") }
                } else {
                    IconButton(onClick = onStart) { Icon(Icons.Default.PlayArrow, "Start session") }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = Color(0xFF0A1008),
            contentColor = Color(0xFFD9FFB1),
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 12.dp, bottomEnd = 30.dp, bottomStart = 12.dp),
        ) {
            Text(
                text = output.ifBlank { if (status == SessionStatus.RUNNING) "Connected.\n" else "Press play to start Debian.\n" },
                modifier = Modifier.fillMaxSize().verticalScroll(terminalScroll).padding(18.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("uname -a", "cat /etc/os-release", "apt update", "df -h").forEach { shortcut ->
                AssistChip(onClick = { command = shortcut }, label = { Text(shortcut, fontFamily = FontFamily.Monospace) })
            }
        }

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            enabled = status == SessionStatus.RUNNING,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter a command") },
            leadingIcon = { Icon(Icons.Default.Code, null) },
            trailingIcon = {
                IconButton(
                    onClick = {
                        onSend(command)
                        command = ""
                        focusManager.clearFocus()
                    },
                    enabled = command.isNotBlank() && status == SessionStatus.RUNNING,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Run command") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onSend(command)
                command = ""
            }),
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
        )
    }
}

@Composable
private fun SettingsPage(
    installState: InstallState,
    activeContainer: LxContainer?,
    containers: List<LxContainer>,
    containerStates: Map<String, InstallState>,
    desktopSetupStatus: DesktopSetupStatus,
    shizukuMode: ShizukuMode,
    uninstallRunning: Boolean,
    uninstallMessage: String?,
    requestShizuku: () -> Unit,
    onRemoveDesktop: () -> Unit,
    onRemoveContainer: () -> Unit,
    onDeleteContainer: (String) -> Unit,
    onSelectContainer: (String) -> Unit,
) {
    var confirmTarget by remember { mutableStateOf<UninstallTarget?>(null) }
    var deleteContainerTarget by remember { mutableStateOf<LxContainer?>(null) }
    val context = LocalContext.current
    val flavor = activeContainer?.flavor ?: DistroFlavor.DEBIAN
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Containers", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each container is a separate Linux system. They share nothing but the Files folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                containers.forEach { container ->
                    val installed = containerStates[container.id] is InstallState.Installed
                    val active = container.id == activeContainer?.id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(container.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${container.flavor.label} • ${if (installed) "installed" else "not installed"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            AssistChip(onClick = {}, label = { Text("Active") })
                        } else {
                            AssistChip(onClick = { onSelectContainer(container.id) }, label = { Text("Use") })
                            IconButton(onClick = { deleteContainerTarget = container }) {
                                Icon(Icons.Default.Delete, "Delete ${container.name}")
                            }
                        }
                    }
                }
            }
        }
        SettingsCard(
            icon = Icons.Default.Memory,
            title = "Linux engine",
            value = if (installState is InstallState.Installed) "PRoot • ${flavor.label}${if (flavor.needsEmulation) " • QEMU x86_64" else " • ARM64"}" else "Not installed",
            supporting = "Rootless userland using Android's existing Linux kernel.",
        )
        SettingsCard(
            icon = Icons.Default.Computer,
            title = "Graphical desktop",
            value = when (desktopSetupStatus) {
                DesktopSetupStatus.INSTALLED -> "${flavor.desktopLabel} with TigerVNC"
                DesktopSetupStatus.INSTALLING -> "Installing…"
                DesktopSetupStatus.FAILED -> "Setup needs attention"
                DesktopSetupStatus.NOT_INSTALLED -> "Not installed"
            },
            supporting = "Rendered inside Lindroid by its built-in display client.",
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Security, null)
                Text("Shizuku enhancement", style = MaterialTheme.typography.titleLarge)
                Text(shizukuLabel(shizukuMode))
                if (shizukuMode == ShizukuMode.WAITING_FOR_PERMISSION) {
                    FilledTonalButton(onClick = requestShizuku) { Text("Grant Lindroid access") }
                }
                Text(
                    "Shizuku is optional. ADB mode does not provide root or turn PRoot into Docker.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        SettingsCard(
            icon = Icons.Outlined.Folder,
            title = "Shared files",
            value = context.filesDir.resolve("shared").absolutePath,
            supporting = "Available inside every container at /root/storage.",
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Remove Linux", style = MaterialTheme.typography.titleLarge)
                Text(
                    "These act on ${activeContainer?.name ?: "the active container"}. Removing only the desktop keeps the terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                uninstallMessage?.let { Text(it) }
                val desktopGone = desktopSetupStatus != DesktopSetupStatus.INSTALLED
                val debianGone = installState !is InstallState.Installed
                Button(
                    onClick = { confirmTarget = UninstallTarget.DESKTOP },
                    enabled = !desktopGone && !uninstallRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (uninstallRunning) "Working" else "Remove graphical desktop") }
                OutlinedButton(
                    onClick = { confirmTarget = UninstallTarget.DEBIAN },
                    enabled = !debianGone && !uninstallRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove ${activeContainer?.name ?: "the container"} and all its files") }
            }
        }
        confirmTarget?.let { target ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmTarget = null },
                title = {
                    Text(if (target == UninstallTarget.DESKTOP) "Remove the desktop?" else "Remove ${activeContainer?.name ?: "the container"}?")
                },
                text = {
                    Text(
                        if (target == UninstallTarget.DESKTOP) "${flavor.desktopLabel} and its display files go away. Your terminal files stay."
                        else "This erases the whole Linux system and all files inside it. Other containers are not touched.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (target == UninstallTarget.DESKTOP) onRemoveDesktop() else onRemoveContainer()
                            confirmTarget = null
                        },
                    ) { Text("Remove") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { confirmTarget = null }) { Text("Keep") }
                },
            )
        }
        deleteContainerTarget?.let { target ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteContainerTarget = null },
                title = { Text("Delete ${target.name}?") },
                text = { Text("This erases ${target.name} and every file inside it. Other containers are not touched.") },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteContainer(target.id)
                            deleteContainerTarget = null
                        },
                    ) { Text("Delete") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { deleteContainerTarget = null }) { Text("Keep") }
                },
            )
        }
        HorizontalDivider()
        SettingsCard(
            icon = Icons.Outlined.Info,
            title = "About",
            value = "Lindroid 0.1.0-alpha",
            supporting = "PRoot is GPL-2.0. Container images are downloaded from the official Docker image registry.",
        )
        Text(
            "This is a Linux userland, not a virtual machine. Docker, kernel modules and systemd are not supported in rootless mode. The Mint container runs x86_64 software through QEMU, which is slower than native ARM64.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, value: String, supporting: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun statusColor(status: SessionStatus) = when (status) {
    SessionStatus.RUNNING -> MaterialTheme.colorScheme.primary
    SessionStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabel(status: SessionStatus) = when (status) {
    SessionStatus.STOPPED -> "Stopped"
    SessionStatus.STARTING -> "Starting…"
    SessionStatus.RUNNING -> "Running locally"
    SessionStatus.FAILED -> "Needs attention"
}

private fun shizukuLabel(mode: ShizukuMode) = when (mode) {
    ShizukuMode.UNAVAILABLE -> "Not running. Lindroid works without it"
    ShizukuMode.WAITING_FOR_PERMISSION -> "Running. Permission needed"
    ShizukuMode.ADB -> "Connected with ADB shell privileges"
    ShizukuMode.ROOT -> "Connected with root privileges"
}
