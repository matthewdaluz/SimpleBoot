// MainActivity.kt
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
import matthewdaluz.simpleboot.util.LogManager
import matthewdaluz.simpleboot.util.MountController
import matthewdaluz.simpleboot.util.MountMethod
import matthewdaluz.simpleboot.util.MountStateStore
import matthewdaluz.simpleboot.util.StorageManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shell.getShell().isRoot) {
            Toast.makeText(this, "Root access is required!", Toast.LENGTH_LONG).show()
            finish()
        }

        setContent {
            SimpleBootTheme(darkTheme = isSystemInDarkTheme()) {
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

    LaunchedEffect(Unit) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } else {
            StorageManager.ensureDirectories()
            isoList = StorageManager.getIsoFileList()
            currentMount = MountStateStore.load(context)
        }
    }

    fun toggleAdb(enabled: Boolean) {
        coroutineScope.launch {
            val result = Shell.cmd(
                if (enabled) {
                    "setprop sys.usb.config mass_storage,adb"
                } else {
                    "setprop sys.usb.config mass_storage"
                }
            ).exec()

            if (result.isSuccess) {
                adbEnabled = enabled
                snackbarHostState.showSnackbar("ADB ${if (enabled) "enabled" else "disabled"}")
            } else {
                snackbarHostState.showSnackbar("Failed to toggle ADB")
            }
        }
    }

    fun toggleUsbCharging(enabled: Boolean) {
        coroutineScope.launch {
            val result = Shell.cmd(
                if (enabled) {
                    "echo 1 > /sys/class/power_supply/usb/device/charge"
                } else {
                    "echo 0 > /sys/class/power_supply/usb/device/charge"
                }
            ).exec()

            if (result.isSuccess) {
                usbChargingEnabled = enabled
                snackbarHostState.showSnackbar("USB charging ${if (enabled) "enabled" else "disabled"}")
            } else {
                snackbarHostState.showSnackbar("Failed to toggle USB charging")
            }
        }
    }

    fun handleMount(iso: IsoFile, method: MountMethod) {
        coroutineScope.launch {
            val isMounted = currentMount?.filePath == iso.path

            if (isMounted) {
                val result = MountController.unmount(context)
                if (result.success) {
                    currentMount = null
                    statusText = "Unmounted: ${iso.name}"
                    snackbarHostState.showSnackbar(statusText)
                    isoList = StorageManager.getIsoFileList()
                } else {
                    val message = result.message.ifBlank { "Unknown error occurred." }
                    snackbarHostState.showSnackbar("Failed to unmount: $message")
                }
                return@launch
            }

            if (currentMount != null) {
                snackbarHostState.showSnackbar("Another ISO is already mounted.")
                return@launch
            }

            val result = MountController.mount(context, iso.path, method)
            if (result.success) {
                currentMount = MountStateStore.load(context)
                statusText = "Mounted (${method.name}): ${iso.name}"
                snackbarHostState.showSnackbar(statusText)
                isoList = StorageManager.getIsoFileList()
            } else {
                val message = result.message.ifBlank { "Unknown error occurred." }
                snackbarHostState.showSnackbar("Failed to mount: $message")
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SimpleBoot - v1.0b") }) },
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
                            Icons.Filled.Adb,  // Changed from Icons.Default.Adb to Icons.Filled.Adb
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
                            Icons.Filled.Usb,  // Changed from Icons.Default.Usb to Icons.Filled.Usb
                            contentDescription = "Toggle USB Charging",
                            tint = if (usbChargingEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    IconToggleButton(
                        checked = usbChargingEnabled,
                        onCheckedChange = { toggleUsbCharging(it) }
                    ) {
                        Icon(
                            Icons.Default.Usb,
                            contentDescription = "Toggle USB Charging",
                            tint = if (usbChargingEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Button(
                    onClick = {
                        val intent = LogManager.exportLogFile(context)
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "Share SimpleBoot Log"))
                        } else {
                            Toast.makeText(context, "No log file available", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Export Log")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

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
                Column {
                    Text("Select mount method for ${selectedIso?.name}")
                    Spacer(modifier = Modifier.height(16.dp))
                    MountMethod.values().forEach { method ->
                        Button(
                            onClick = {
                                handleMount(selectedIso!!, method)
                                showMountMenu = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(method.name)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMountMenu = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}