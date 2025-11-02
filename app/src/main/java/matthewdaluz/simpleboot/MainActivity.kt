package matthewdaluz.simpleboot

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import matthewdaluz.simpleboot.model.IsoFile
import matthewdaluz.simpleboot.ui.theme.SimpleBootTheme
import matthewdaluz.simpleboot.util.*
import androidx.core.net.toUri
import androidx.compose.material3.MenuAnchorType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LogManager.logToFile(this, "MainActivity.onCreate() - App launched")

        if (!Shell.getShell().isRoot) {
            Toast.makeText(this, "Root access is required!", Toast.LENGTH_LONG).show()
            LogManager.logToFile(this, "Root access missing - closing app")
            finish()
        }

        setContent {
            SimpleBootTheme(darkTheme = isSystemInDarkTheme()) {
                LogManager.logToFile(this, "Compose UI started")
                AppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isoList by remember { mutableStateOf<List<IsoFile>>(emptyList()) }
    var currentMount by remember { mutableStateOf<MountStateStore.MountInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var statusText by remember { mutableStateOf("Status: Idle") }
    var showMountMenu by remember { mutableStateOf(false) }
    var selectedIso by remember { mutableStateOf<IsoFile?>(null) }
    var adbEnabled by remember { mutableStateOf(true) }
    var usbChargingEnabled by remember { mutableStateOf(true) }
    var selectedMethod by remember { mutableStateOf(MountMethod.CONFIGFS) }
    var selectedUsbMode by remember { mutableStateOf(UsbMode.USB_HDD) }

    LaunchedEffect(Unit) {
        LogManager.logToFile(context, "AppScreen launched - checking storage access")

        if (!Environment.isExternalStorageManager()) {
            LogManager.logToFile(context, "External storage permission missing - launching settings")
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:${context.packageName}".toUri()
            }
            context.startActivity(intent)
        } else {
            LogManager.logToFile(context, "External storage permission granted - loading ISOs")
            StorageManager.ensureDirectories()
            isoList = StorageManager.getIsoFileList()
            LogManager.logToFile(context, "Loaded ${isoList.size} ISO/IMG files")
            currentMount = MountStateStore.load(context)
            LogManager.logToFile(context, "Current mount: ${currentMount?.filePath ?: "None"}")
        }
    }

    fun toggleAdb(enabled: Boolean) {
        coroutineScope.launch {
            LogManager.logToFile(context, "toggleAdb($enabled) called")
            val result = Shell.cmd(
                if (enabled) "setprop sys.usb.config mass_storage,adb"
                else "setprop sys.usb.config mass_storage"
            ).exec()
            if (result.isSuccess) {
                adbEnabled = enabled
                snackbarHostState.showSnackbar("ADB ${if (enabled) "enabled" else "disabled"}")
                LogManager.logToFile(context, "ADB toggled successfully -> $enabled")
            } else {
                snackbarHostState.showSnackbar("Failed to toggle ADB")
                LogManager.logToFile(context, "Failed to toggle ADB")
            }
        }
    }

    fun toggleUsbCharging(enabled: Boolean) {
        coroutineScope.launch {
            LogManager.logToFile(context, "toggleUsbCharging($enabled) called")
            val ok = UsbChargingController.setCharging(enabled)
            if (ok) {
                usbChargingEnabled = enabled
                snackbarHostState.showSnackbar("USB charging ${if (enabled) "enabled" else "disabled"}")
                LogManager.logToFile(context, "USB charging toggled successfully -> $enabled")
            } else {
                snackbarHostState.showSnackbar("Failed to toggle charging on this device")
                LogManager.logToFile(context, "Failed to toggle USB charging")
            }
        }
    }

    fun handleMount(iso: IsoFile, method: MountMethod, mode: UsbMode) {
        coroutineScope.launch {
            LogManager.logToFile(context, "handleMount() - ISO=${iso.name}, method=$method, mode=$mode")

            val isMounted = currentMount?.filePath == iso.path
            if (isMounted) {
                LogManager.logToFile(context, "Unmounting existing ISO: ${iso.name}")
                val result = MountController.unmount(context)
                if (result.success) {
                    currentMount = null
                    statusText = "Unmounted: ${iso.name}"
                    snackbarHostState.showSnackbar(statusText)
                    isoList = StorageManager.getIsoFileList()
                    LogManager.logToFile(context, "Successfully unmounted ${iso.name}")
                } else {
                    val message = result.message.ifBlank { "Unknown error occurred." }
                    snackbarHostState.showSnackbar("Failed to unmount: $message")
                    LogManager.logToFile(context, "Unmount failed for ${iso.name}: $message")
                }
                return@launch
            }

            if (currentMount != null) {
                snackbarHostState.showSnackbar("Another ISO is already mounted.")
                LogManager.logToFile(context, "Mount blocked - another ISO already mounted.")
                return@launch
            }

            val result = MountController.mount(context, iso.path, method, mode)
            if (result.success) {
                currentMount = MountStateStore.load(context)
                statusText = "Mounted (${method.name}, ${mode.name}): ${iso.name}"
                snackbarHostState.showSnackbar(statusText)
                isoList = StorageManager.getIsoFileList()
                LogManager.logToFile(context, "Mount successful -> ${iso.name} (${method.name}/${mode.name})")
            } else {
                val message = result.message.ifBlank { "Unknown error occurred." }
                snackbarHostState.showSnackbar("Failed to mount: $message")
                LogManager.logToFile(context, "Mount failed for ${iso.name}: $message")
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SimpleBoot - v2.0") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    IconToggleButton(
                        checked = adbEnabled,
                        onCheckedChange = { toggleAdb(it) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Adb,
                            contentDescription = "Toggle ADB",
                            tint = if (adbEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    IconToggleButton(
                        checked = usbChargingEnabled,
                        onCheckedChange = { toggleUsbCharging(it) }
                    ) {
                        Icon(
                            Icons.Filled.Usb,
                            contentDescription = "Toggle USB Charging",
                            tint = if (usbChargingEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Button(
                    onClick = {
                        LogManager.logToFile(context, "Export log button clicked")
                        val intent = LogManager.exportLogFile(context)
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "Share SimpleBoot Log"))
                            LogManager.logToFile(context, "Log export intent launched")
                        } else {
                            Toast.makeText(context, "No log file available", Toast.LENGTH_SHORT).show()
                            LogManager.logToFile(context, "No log file available to export")
                        }
                    }
                ) { Text("Export Log") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var methodExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = methodExpanded,
                    onExpandedChange = { methodExpanded = !methodExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedMethod.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Mount Method") },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).weight(1f)
                    )
                    ExposedDropdownMenu(
                        expanded = methodExpanded,
                        onDismissRequest = { methodExpanded = false }
                    ) {
                        MountMethod.entries.forEach { m: MountMethod ->
                            DropdownMenuItem(
                                text = { Text(m.name) },
                                onClick = {
                                    selectedMethod = m
                                    methodExpanded = false
                                    LogManager.logToFile(context, "Mount method selected: $m")
                                }
                            )
                        }
                    }
                }

                var usbExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = usbExpanded,
                    onExpandedChange = { usbExpanded = !usbExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedUsbMode.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("USB Method") },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).weight(1f)
                    )
                    ExposedDropdownMenu(
                        expanded = usbExpanded,
                        onDismissRequest = { usbExpanded = false }
                    ) {
                        UsbMode.entries.forEach { um: UsbMode ->
                            DropdownMenuItem(
                                text = { Text(um.name) },
                                onClick = {
                                    selectedUsbMode = um
                                    usbExpanded = false
                                    LogManager.logToFile(context, "USB mode selected: $um")
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = statusText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(isoList) { iso ->
                    val isMounted = currentMount?.filePath == iso.path
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .clickable {
                                    selectedIso = iso
                                    showMountMenu = true
                                    LogManager.logToFile(context, "ISO clicked: ${iso.name}")
                                }
                        ) {
                            Text(text = iso.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (isMounted) "Mounted" else "Not mounted",
                                color = if (isMounted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (isoList.isEmpty()) {
                    item {
                        Text(
                            text = "No ISO/IMG files found in /storage/emulated/0/SimpleBootISOs.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                        LogManager.logToFile(context, "No ISO/IMG files found on device")
                    }
                }
            }
        }
    }

    if (showMountMenu && selectedIso != null) {
        AlertDialog(
            onDismissRequest = { showMountMenu = false },
            title = { Text("Mount Options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("File: ${selectedIso?.name}")
                    Text("Method: ${selectedMethod.name}")
                    Text("USB Method: ${selectedUsbMode.name}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    LogManager.logToFile(context, "Mount confirmed for ${selectedIso?.name}")
                    handleMount(selectedIso!!, selectedMethod, selectedUsbMode)
                    showMountMenu = false
                }) { Text("Mount") }
            },
            dismissButton = {
                Button(onClick = {
                    LogManager.logToFile(context, "Mount dialog cancelled")
                    showMountMenu = false
                }) { Text("Cancel") }
            }
        )
    }
}
