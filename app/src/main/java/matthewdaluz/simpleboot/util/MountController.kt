// MountController.kt
package matthewdaluz.simpleboot.util

import android.content.Context
import android.preference.PreferenceManager
import com.topjohnwu.superuser.Shell
import matthewdaluz.simpleboot.model.MountResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MountMethod {
    CONFIGFS,
    LEGACY,
    LOOPBACK
}

object MountController {

    private val CONFIGFS_PATHS = arrayOf(
        "/config/usb_gadget/g1",
        "/sys/kernel/config/usb_gadget/g1",
        "/sys/usb_gadget/g1"
    )
    private const val UDC_PATH = "/sys/class/udc"
    private const val MASS_STORAGE_FUNC = "mass_storage.0"
    private const val LEGACY_USB_PATH = "/sys/class/android_usb/android0"
    private const val LEGACY_MASS_STORAGE_PATH = "$LEGACY_USB_PATH/f_mass_storage/lun/file"
    private const val LOOPBACK_DEVICE = "/dev/block/loop7"

    private fun logToFile(context: Context, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message\n"
        LogManager.logToFile(context, logMessage)
    }

    fun mount(context: Context, isoPath: String, method: MountMethod, lun: Int = 0): MountResult {
        logToFile(context, "MountController.mount() called with path: $isoPath, method: $method")

        if (!File(isoPath).exists()) {
            logToFile(context, "Mount failed: File does not exist at $isoPath")
            return MountResult(false, "File does not exist.")
        }

        val existing = MountStateStore.load(context)
        if (existing != null && existing.filePath == isoPath) {
            logToFile(context, "Mount failed: Image already mounted (${existing.loopDevice})")
            return MountResult(false, "Image is already mounted.")
        }

        // Disable Android USB stack and ADB
        Shell.cmd(
            "setprop sys.usb.config none",
            "setprop sys.usb.state none"
        ).exec()

        logToFile(context, "USB stack and ADB disabled for clean mount")

        return when (method) {
            MountMethod.CONFIGFS -> mountUsingConfigFs(context, isoPath, lun)
            MountMethod.LEGACY -> mountUsingLegacy(context, isoPath, lun)
            MountMethod.LOOPBACK -> mountUsingLoopback(context, isoPath)
        }
    }

    fun unmount(context: Context): MountResult {
        logToFile(context, "MountController.unmount() called")

        val mount = MountStateStore.load(context)
            ?: return MountResult(false, "Nothing is currently mounted.")

        logToFile(context, "Unmounting ${mount.filePath} from ${mount.loopDevice}")

        val cleanupCmds = mutableListOf<String>().apply {
            CONFIGFS_PATHS.forEach { path ->
                add("echo '' > $path/UDC || true")
                add("rm -rf $path/functions/$MASS_STORAGE_FUNC || true")
            }
            add("echo 0 > $LEGACY_USB_PATH/enable || true")
            add("losetup -d ${mount.loopDevice} || true")
            add("setprop sys.usb.config mass_storage,adb")
            add("setprop sys.usb.state mass_storage,adb")
        }

        logToFile(context, "Executing cleanup commands...")
        val result = Shell.cmd(*cleanupCmds.toTypedArray()).exec()

        return if (result.isSuccess) {
            MountStateStore.clear(context)
            LogManager.logUnmount(context, File(mount.filePath).name, mount.loopDevice)
            MountResult(true, "Unmounted successfully.", mount.loopDevice)
        } else {
            MountResult(false, (result.out + result.err).joinToString("\n"))
        }
    }

    private fun mountUsingConfigFs(
        context: Context,
        isoPath: String,
        lun: Int
    ): MountResult {
        val loopDevice = getAvailableLoopDevice(context)
            ?: return MountResult(false, "No available loop device found.")

        val configFsPath = findConfigFsPath(context)
            ?: return MountResult(false, "ConfigFS not found")

        val lunFile = "$configFsPath/functions/$MASS_STORAGE_FUNC/lun.$lun/file"
        val udc = getUdcName(context) ?: return MountResult(false, "No UDC controller found")

        ensureModulesLoaded(context)

        val readOnly = if (PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("mount_read_only", false)) 1 else 0

        val cmds = mutableListOf(
            "echo '' > $configFsPath/UDC",
            "losetup -d $loopDevice || true",
            if (readOnly == 1) "losetup -r $loopDevice \"$isoPath\"" else "losetup $loopDevice \"$isoPath\"",
            "rm -rf $configFsPath/functions/$MASS_STORAGE_FUNC",
            "mkdir -p $configFsPath/functions/$MASS_STORAGE_FUNC",
            "mkdir -p $configFsPath/configs/c.1",
            "echo 1 > $configFsPath/functions/$MASS_STORAGE_FUNC/stall",
            "echo 1 > $configFsPath/functions/$MASS_STORAGE_FUNC/cdrom",
            "echo $readOnly > $configFsPath/functions/$MASS_STORAGE_FUNC/ro",
            "echo \"$loopDevice\" > $lunFile",
            "ln -s $configFsPath/functions/$MASS_STORAGE_FUNC $configFsPath/configs/c.1/",
            "echo 0x05ac > $configFsPath/idVendor",
            "echo 0x8290 > $configFsPath/idProduct",
            "echo $udc > $configFsPath/UDC",
            "setprop sys.usb.config mass_storage,adb",
            "setprop sys.usb.state mass_storage,adb"
        )

        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return if (result.isSuccess) {
            MountStateStore.save(context, isoPath, loopDevice, lun.toString())
            LogManager.logMount(context, File(isoPath).name, "$loopDevice via configfs")
            MountResult(true, "Mounted successfully using configfs.", loopDevice)
        } else {
            cleanupMount(context, loopDevice)
            MountResult(false, result.err.joinToString("\n"))
        }
    }

    private fun mountUsingLegacy(
        context: Context,
        isoPath: String,
        lun: Int
    ): MountResult {
        val loopDevice = getAvailableLoopDevice(context)
            ?: return MountResult(false, "No available loop device found.")

        ensureModulesLoaded(context)
        val readOnly = if (PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("mount_read_only", false)) 1 else 0

        val cmds = listOf(
            "echo 0 > $LEGACY_USB_PATH/enable",
            "echo mass_storage > $LEGACY_USB_PATH/functions",
            "losetup -d $loopDevice || true",
            if (readOnly == 1) "losetup -r $loopDevice \"$isoPath\"" else "losetup $loopDevice \"$isoPath\"",
            "echo \"$loopDevice\" > $LEGACY_MASS_STORAGE_PATH",
            "echo 1 > $LEGACY_USB_PATH/f_mass_storage/lun/cdrom",
            "echo $readOnly > $LEGACY_USB_PATH/f_mass_storage/lun/ro",
            "echo 1 > $LEGACY_USB_PATH/enable",
            "setprop sys.usb.config mass_storage,adb",
            "setprop sys.usb.state mass_storage,adb"
        )

        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return if (result.isSuccess) {
            MountStateStore.save(context, isoPath, loopDevice, lun.toString())
            LogManager.logMount(context, File(isoPath).name, "$loopDevice via legacy")
            MountResult(true, "Mounted successfully using legacy method.", loopDevice)
        } else {
            cleanupMount(context, loopDevice)
            MountResult(false, result.err.joinToString("\n"))
        }
    }

    private fun mountUsingLoopback(
        context: Context,
        isoPath: String
    ): MountResult {
        val readOnly = if (PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("mount_read_only", false)) 1 else 0

        val cmds = listOf(
            "losetup -d $LOOPBACK_DEVICE || true",
            if (readOnly == 1) "losetup -r $LOOPBACK_DEVICE \"$isoPath\""
            else "losetup $LOOPBACK_DEVICE \"$isoPath\""
        )

        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return if (result.isSuccess) {
            MountStateStore.save(context, isoPath, LOOPBACK_DEVICE, "0")
            LogManager.logMount(context, File(isoPath).name, "$LOOPBACK_DEVICE via loopback")
            MountResult(true, "Mounted successfully using loopback method.", LOOPBACK_DEVICE)
        } else {
            cleanupMount(context, LOOPBACK_DEVICE)
            MountResult(false, result.err.joinToString("\n"))
        }
    }

    private fun cleanupMount(context: Context, loopDevice: String?) {
        val cmds = mutableListOf<String>().apply {
            CONFIGFS_PATHS.forEach { path ->
                add("echo '' > $path/UDC || true")
                add("rm -rf $path/functions/$MASS_STORAGE_FUNC || true")
            }
            add("echo 0 > $LEGACY_USB_PATH/enable || true")
            loopDevice?.let { add("losetup -d $it || true") }
            add("setprop sys.usb.config mass_storage,adb")
            add("setprop sys.usb.state mass_storage,adb")
        }

        Shell.cmd(*cmds.toTypedArray()).exec()
    }

    private fun getAvailableLoopDevice(context: Context): String? {
        val losetup = Shell.cmd("losetup -f").exec()
        return losetup.out.firstOrNull()?.trim()
    }

    private fun findConfigFsPath(context: Context): String? {
        return CONFIGFS_PATHS.firstOrNull { File(it).exists() }
    }

    private fun getUdcName(context: Context): String? {
        val files = File(UDC_PATH).listFiles() ?: return null
        return files.firstOrNull()?.name
    }

    private fun ensureModulesLoaded(context: Context) {
        val modules = listOf("libcomposite", "usb_f_mass_storage", "g_mass_storage")
        modules.forEach { Shell.cmd("modprobe $it || true").exec() }
    }
}