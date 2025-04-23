// MainActivity.kt

package matthewdaluz.simpleboot

// Import necessary Android and Compose libraries
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
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for root access - essential for SimpleBoot's functionality
        if (!Shell.getShell().isRoot) {
            Toast.makeText(this, "Root access is required!", Toast.LENGTH_LONG).show()
            finish() // Close the app if root isn't available
        }

        // Set up Compose UI with theme that adapts to system dark mode
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
    // Context and coroutine scope for background operations
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State variables for the UI
    var isoList by remember { mutableStateOf<List<IsoFile>>(emptyList()) } // List of ISO files
    var currentMount by remember { mutableStateOf<MountStateStore.MountInfo?>(null) } // Currently mounted ISO
    val snackbarHostState = remember { SnackbarHostState() } // For showing snackbar messages
    var statusText by remember { mutableStateOf("Status: Idle") } // Status display text
    var showMountMenu by remember { mutableStateOf(false) } // Controls mount options dialog visibility
    var selectedIso by remember { mutableStateOf<IsoFile?>(null) } // Currently selected ISO for mounting
    var adbEnabled by remember { mutableStateOf(true) } // ADB toggle state
    var usbChargingEnabled by remember { mutableStateOf(true) } // USB charging toggle state

    // Effect that runs once when the screen is first displayed
    LaunchedEffect(Unit) {
        // Check for file system access permissions
        if (!Environment.isExternalStorageManager()) {
            // Request all files access permission if not granted
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:${context.packageName}".toUri()
            }
            context.startActivity(intent)
        } else {
            // If permissions are granted, set up directories and load data
            StorageManager.ensureDirectories()
            isoList = StorageManager.getIsoFileList()
            currentMount = MountStateStore.load(context)
        }
    }

    /**
     * Toggles ADB (Android Debug Bridge) functionality
     * @param enabled Whether to enable or disable ADB
     */
    fun toggleAdb(enabled: Boolean) {
        coroutineScope.launch {
            // Execute shell command to toggle ADB
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

    /**
     * Toggles USB charging functionality
     * @param enabled Whether to enable or disable USB charging
     */
    fun toggleUsbCharging(enabled: Boolean) {
        coroutineScope.launch {
            // Execute shell command to toggle USB charging
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

    /**
     * Handles mounting/unmounting of ISO files
     * @param iso The ISO file to mount/unmount
     * @param method The mounting method to use
     */
    fun handleMount(iso: IsoFile, method: MountMethod) {
        coroutineScope.launch {
            val isMounted = currentMount?.filePath == iso.path

            // If already mounted, unmount it
            if (isMounted) {
                val result = MountController.unmount(context)
                if (result.success) {
                    currentMount = null
                    statusText = "Unmounted: ${iso.name}"
                    snackbarHostState.showSnackbar(statusText)
                    isoList = StorageManager.getIsoFileList() // Refresh list
                } else {
                    val message = result.message.ifBlank { "Unknown error occurred." }
                    snackbarHostState.showSnackbar("Failed to unmount: $message")
                }
                return@launch
            }

            // Check if another ISO is already mounted
            if (currentMount != null) {
                snackbarHostState.showSnackbar("Another ISO is already mounted.")
                return@launch
            }

            // Mount the selected ISO
            val result = MountController.mount(context, iso.path, method)
            if (result.success) {
                currentMount = MountStateStore.load(context)
                statusText = "Mounted (${method.name}): ${iso.name}"
                snackbarHostState.showSnackbar(statusText)
                isoList = StorageManager.getIsoFileList() // Refresh list
            } else {
                val message = result.message.ifBlank { "Unknown error occurred." }
                snackbarHostState.showSnackbar("Failed to mount: $message")
            }
        }
    }

    // Main UI layout using Material3 Scaffold
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
            // Top row with toggle buttons and export log button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    // ADB toggle button
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

                    // USB charging toggle button
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

                    // Duplicate USB charging toggle button (This is a bug I think.)
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

                // Button to export logs
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

            // Status text display
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // List of ISO files
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(isoList) { iso ->
                    val isMounted = currentMount?.filePath == iso.path

                    // Card for each ISO file
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

                // Show message if no ISOs are found
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

    // Mount options dialog
    if (showMountMenu && selectedIso != null) {
        AlertDialog(
            onDismissRequest = { showMountMenu = false },
            title = { Text("Mount Options") },
            text = {
                Column {
                    Text("Select mount method for ${selectedIso?.name}")
                    Spacer(modifier = Modifier.height(16.dp))
                    // Show a button for each mount method
                    MountMethod.entries.forEach { method ->
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