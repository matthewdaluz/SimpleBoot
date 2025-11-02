package matthewdaluz.simpleboot.util

import android.R.attr.mode
import android.content.Context
import com.topjohnwu.superuser.Shell
import matthewdaluz.simpleboot.model.MountResult
import matthewdaluz.simpleboot.util.LogManager.logToFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Supported mount methods
enum class MountMethod {
    AUTO,       // Try ConfigFS -> Legacy -> Loopback
    CONFIGFS,
    LEGACY,
    LOOPBACK,
    PIXEL       // ConfigFS tuned for Google Pixel (Tensor/GKI)
}

// How the gadget should present to the host
// TODO: Remove CD_ROM entirely.
enum class UsbMode {
    USB_HDD,    // ro=1, cdrom=0
    CD_ROM      // ro=1, cdrom=1
}

object MountController {

    private val CONFIGFS_ROOTS = arrayOf("/sys/kernel/config", "/config")
    private const val GADGET_NAME = "g1"
    private val MASS_STORAGE_FUNC_CANDIDATES = arrayOf("mass_storage.0", "mass_storage.usb0", "msc.0")
    private const val UDC_PATH = "/sys/class/udc"
    private const val LEGACY_USB_PATH = "/sys/class/android_usb/android0"
    private const val LEGACY_MASS_STORAGE_PATH = "$LEGACY_USB_PATH/f_mass_storage/lun/file"

    // --------------------------------------------------------------------------
// Helper functions for ConfigFS path resolution
// --------------------------------------------------------------------------
    private fun resolveFuncPath(gadgetPath: String): String {
        val candidates = MASS_STORAGE_FUNC_CANDIDATES.map { "$gadgetPath/functions/$it" }
        for (path in candidates) {
            if (shDirExists(path)) {
                logToFile(context = null as Context?, "[resolveFuncPath] Using existing: $path")
                return path
            }
        }
        // Return the first candidate (it will be created later)
        val fallback = candidates.first()
        logToFile(context = null as Context?, "[resolveFuncPath] None found, fallback: $fallback")
        return fallback
    }

    private fun resolveLunDir(funcPath: String, lun: Int): String {
        val indexed = "$funcPath/lun.$lun"
        if (shDirExists(indexed)) return indexed
        val single = "$funcPath/lun"
        return if (shDirExists(single)) single else indexed
    }


    // ---------- Logging ----------
    private fun logToFile(context: Context, message: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        LogManager.logToFile(context, "[$ts] $message")
    }

    private fun logCmd(context: Context, label: String, vararg commands: String) {
        val res = Shell.cmd(*commands).exec()
        val out = res.out.joinToString("\n")
        val err = res.err.joinToString("\n")
        logToFile(context, "[CMD:$label] ${commands.joinToString(" && ")}")
        if (out.isNotBlank()) logToFile(context, "[OUT:$label] ${out.take(4000)}")
        if (err.isNotBlank()) logToFile(context, "[ERR:$label] ${err.take(4000)}")
    }

    // ---------- Shell helpers ----------
    private fun shDirExists(path: String): Boolean {
        val r = Shell.cmd("[ -d \"$path\" ] && echo OK || true").exec()
        val exists = r.out.firstOrNull()?.trim() == "OK"
        return exists
    }

    private fun shEnsureDir(path: String) {
        Shell.cmd("mkdir -p \"$path\" || true").exec()
    }

    private fun shFirstChildDir(path: String): String? {
        val r = Shell.cmd("ls -1 \"$path\" 2>/dev/null | head -n1 || true").exec()
        val name = r.out.firstOrNull()?.trim().orEmpty()
        return if (name.isNotEmpty()) "$path/$name" else null
    }

    private fun pickConfigDir(gadgetPath: String): String {
        val r = Shell.cmd("ls -1 \"$gadgetPath/configs\" 2>/dev/null | head -n1 || true").exec()
        val first = r.out.firstOrNull()?.trim()
        return if (!first.isNullOrEmpty()) first else "b.1"
    }

    // ---------- Loop device helpers ----------
    private fun resolveLosetup(context: Context): String? {
        val candidates = listOf(
            "losetup",
            "/system/bin/losetup",
            "toybox losetup",
            "/system/bin/toybox losetup",
            "busybox losetup",
            "/system/xbin/busybox losetup"
        )
        for (c in candidates) {
            logToFile(context, "[resolveLosetup] Testing candidate: $c")
            val r = Shell.cmd("$c -h || $c --help || true").exec()
            val ok = (r.out + r.err).joinToString("\n").contains("loop", ignoreCase = true)
            if (ok) {
                logToFile(context, "[resolveLosetup] Using '$c'")
                return c
            }
        }
        logToFile(context, "[resolveLosetup] No valid losetup binary found.")
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun ensureLoopSupport(context: Context) {
        logToFile(context, "[ensureLoopSupport] Ensuring loop devices exist")
        Shell.cmd(
            "echo 64 > /sys/module/loop/parameters/max_loop || true",
            "mknod /dev/block/loop-control c 10 237 2>/dev/null || true"
        ).exec()
        val mk = (0..15).map { i -> "mknod -m 660 /dev/block/loop$i b 7 $i 2>/dev/null || true" }
        Shell.cmd(*mk.toTypedArray()).exec()
    }

    private fun getAvailableLoopDevice(context: Context, losetup: String): String? {
        ensureLoopSupport(context)
        var r = Shell.cmd("$losetup -f").exec()
        var dev = r.out.firstOrNull()?.trim().orEmpty()
        if (dev.isEmpty()) {
            logToFile(context, "[getAvailableLoopDevice] No loop device, retrying after recreation")
            ensureLoopSupport(context)
            r = Shell.cmd("$losetup -f").exec()
            dev = r.out.firstOrNull()?.trim().orEmpty()
        }
        logToFile(context, "[getAvailableLoopDevice] Selected loop device: $dev")
        return dev.ifEmpty { null }
    }

    // ---------- Environment prep ----------
    private fun ensureModulesLoaded(context: Context) {
        logToFile(context, "[ensureModulesLoaded] Loading kernel modules")
        val modules = listOf("loop", "libcomposite", "usb_f_mass_storage", "g_mass_storage")
        modules.forEach { mod -> logCmd(context, "modprobe-$mod", "modprobe $mod || true") }
        logCmd(context, "lsmod", "lsmod | head -n 50 || true")
    }

    private fun ensureConfigFsMounted(context: Context) {
        logToFile(context, "[ensureConfigFsMounted] Checking ConfigFS mount")
        // Mount to /sys/kernel/config if not mounted anywhere
        Shell.cmd(
            "mount | grep -q ' type configfs ' || mount -t configfs configfs /sys/kernel/config || true"
        ).exec()
        // Log both locations for visibility
        logCmd(context, "configfs-state",
            "mount | grep configfs || true",
            "ls -l /sys/kernel/config || true",
            "ls -l /config || true"
        )
    }

    private fun detectConfigFsPath(context: Context): String? {
        ensureConfigFsMounted(context)
        for (root in CONFIGFS_ROOTS) {
            if (!shDirExists(root)) continue
            // Make sure we’re on a root that actually has a usb_gadget subtree
            val gadgetRoot = "$root/usb_gadget"
            shEnsureDir(gadgetRoot)
            // Debug listing
            logCmd(context, "ls-$root", "ls -l $root || true", "ls -l $gadgetRoot || true")

            shFirstChildDir(gadgetRoot)?.let {
                logToFile(context, "[detectConfigFsPath] Reusing gadget: $it")
                return it
            }
            val gadgetPath = "$gadgetRoot/$GADGET_NAME"
            shEnsureDir(gadgetPath)
            if (shDirExists(gadgetPath)) {
                logToFile(context, "[detectConfigFsPath] Created gadget: $gadgetPath")
                return gadgetPath
            }
        }
        logToFile(context, "[detectConfigFsPath] Failed to find any valid ConfigFS root")
        return null
    }

    private fun getUdcName(context: Context): String? {
        val r = Shell.cmd("ls -1 $UDC_PATH 2>/dev/null | head -n1 || true").exec()
        val udc = r.out.firstOrNull()?.trim()?.ifEmpty { null }
        logToFile(context, "[getUdcName] Found UDC: $udc")
        return udc
    }

    // ---------- Public API ----------
    fun mount(context: Context, isoPath: String, method: MountMethod, mode: UsbMode, lun: Int = 0): MountResult {
        logToFile(context, "[mount] Request -> path=$isoPath, method=$method, mode=$mode, lun=$lun")

        if (!File(isoPath).exists()) {
            logToFile(context, "[mount] ERROR: File does not exist at $isoPath")
            return MountResult(false, "File does not exist.")
        }

        MountStateStore.load(context)?.let { existing ->
            if (existing.filePath == isoPath) {
                logToFile(context, "[mount] Aborted - same image already mounted (${existing.loopDevice})")
                return MountResult(false, "Image is already mounted.")
            }
        }

        logToFile(context, "[mount] Disabling Android USB stack")
        Shell.cmd("setprop sys.usb.config none", "setprop sys.usb.state none").exec()

        val result = when (method) {
            MountMethod.AUTO -> mountAuto(context, isoPath, lun)
            MountMethod.CONFIGFS -> mountUsingConfigFs(context, isoPath, lun)
            MountMethod.LEGACY -> mountUsingLegacy(context, isoPath, lun)
            MountMethod.LOOPBACK -> mountUsingLoopback(context, isoPath)
            MountMethod.PIXEL -> mountUsingPixel(context, isoPath, lun)
        }

        logToFile(context, "[mount] Result -> success=${result.success}, message='${result.message}'")
        return result
    }

    fun unmount(context: Context): MountResult {
        logToFile(context, "[unmount] Requested")
        val mount = MountStateStore.load(context)
            ?: return MountResult(false, "Nothing is currently mounted.").also {
                logToFile(context, "[unmount] Nothing mounted")
            }

        logToFile(context, "[unmount] Cleaning up gadget and loop device ${mount.loopDevice}")
        val cmds = mutableListOf<String>()

        CONFIGFS_ROOTS.forEach { root ->
            val gadgetPath = "$root/usb_gadget/$GADGET_NAME"
            cmds += listOf(
                "echo '' > $gadgetPath/UDC || true",
                "find $gadgetPath/configs -type l -delete || true",
                "rm -rf $gadgetPath/functions/mass_storage.* || true"
            )
        }

        cmds += listOf(
            "echo 0 > $LEGACY_USB_PATH/enable || true",
            "echo '' > $LEGACY_MASS_STORAGE_PATH || true",
            "losetup -d ${mount.loopDevice} || true",
            "toybox losetup -d ${mount.loopDevice} || true",
            "busybox losetup -d ${mount.loopDevice} || true",
            "setprop sys.usb.config mass_storage,adb",
            "setprop sys.usb.state mass_storage,adb"
        )

        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return if (result.isSuccess) {
            logToFile(context, "[unmount] Success - cleared state, restoring USB config")
            MountStateStore.clear(context)
            LogManager.logUnmount(context, File(mount.filePath).name, mount.loopDevice)
            MountResult(true, "Unmount successful.")
        } else {
            val output = (result.out + result.err).joinToString("\n")
            logToFile(context, "[unmount] Failure:\n$output")
            MountResult(false, output.ifBlank { "Shell command failed (no output)." })
        }
    }

    // ---------- Strategies ----------
    private fun mountAuto(context: Context, isoPath: String, lun: Int): MountResult {
        logToFile(context, "[mountAuto] Attempting ConfigFS first (USB_HDD)")
        val cfg = mountUsingConfigFs(context, isoPath, lun)
        if (cfg.success) return cfg

        logToFile(context, "[mountAuto] ConfigFS failed: ${cfg.message}, trying Legacy")
        val legacy = mountUsingLegacy(context, isoPath, lun)
        if (legacy.success) return legacy

        logToFile(context, "[mountAuto] Legacy failed: ${legacy.message}, falling back to Loopback")
        return mountUsingLoopback(context, isoPath)
    }

    @Suppress("unused")
    fun mount(context: Context, isoPath: String, method: MountMethod, lun: Int = 0): MountResult {
        logToFile(context, "[mount] Request -> path=$isoPath, method=$method, lun=$lun")

        val result = when (method) {
            MountMethod.AUTO -> mountAuto(context, isoPath, lun)
            MountMethod.CONFIGFS -> mountUsingConfigFs(context, isoPath, lun)
            MountMethod.LEGACY -> mountUsingLegacy(context, isoPath, lun)
            MountMethod.LOOPBACK -> mountUsingLoopback(context, isoPath)
            MountMethod.PIXEL -> mountUsingPixel(context, isoPath, lun)
        }

        logToFile(context, "[mount] Result -> success=${result.success}, message='${result.message}'")
        return result
    }

    // ---------- ConfigFS ----------
    private fun mountUsingConfigFs(context: Context, isoPath: String, lun: Int): MountResult {
        logToFile(context, "[mountUsingConfigFs] Starting for $isoPath (USB_HDD, lun=$lun)")
        ensureModulesLoaded(context)
        ensureConfigFsMounted(context)

        // Flags for USB_HDD mode (fixed)
        val roFlag = 1
        val cdFlag = 0

        val losetup = resolveLosetup(context) ?: return MountResult(false, "No losetup binary found.")
        val loop = getAvailableLoopDevice(context, losetup) ?: return MountResult(false, "No available loop device.")
        val gadgetPath = detectConfigFsPath(context) ?: return MountResult(false, "ConfigFS not found.")
        val configDir = pickConfigDir(gadgetPath)
        val udc = getUdcName(context) ?: return MountResult(false, "No UDC controller found.")

        // Get correct function + LUN paths dynamically
        val funcPath = resolveFuncPath(gadgetPath)
        val lunDir = resolveLunDir(funcPath, lun)
        Shell.cmd("mkdir -p $funcPath $lunDir || true").exec()

        // -------------------------------------------------------------------------
        // Command list
        // -------------------------------------------------------------------------
        val cmds = mutableListOf(
            // Clean-slate preamble
            "echo '' > $gadgetPath/UDC || true",
            "find $gadgetPath/configs -type l -delete || true",
            "rm -rf $gadgetPath/functions/ffs.adb || true",
            "rm -rf $gadgetPath/functions/mass_storage.* || true",
            // Recreate structure
            "mkdir -p $gadgetPath/strings/0x409",
            "mkdir -p $gadgetPath/configs/$configDir",
            "mkdir -p $funcPath $lunDir",
            // Set device descriptors
            "test -s $gadgetPath/idVendor || echo 0x18d1 > $gadgetPath/idVendor",
            "test -s $gadgetPath/idProduct || echo 0x4e21 > $gadgetPath/idProduct",
            "echo SimpleBoot > $gadgetPath/strings/0x409/manufacturer",
            "echo SimpleBoot USB > $gadgetPath/strings/0x409/product",
            "echo SB$(date +%s) > $gadgetPath/strings/0x409/serialnumber",
            // Mode and flags (fixed for USB_HDD)
            "echo 0 > $funcPath/stall || true",
            "echo $cdFlag > $lunDir/cdrom || true",
            "echo $roFlag > $lunDir/ro || true",
            "echo 1 > $lunDir/removable || true",
            "echo 1 > $lunDir/nofua || true",
            "echo '' > $lunDir/file || true",
            // Direct ISO bind + automatic fallback to loop
            "if echo \"$isoPath\" > $lunDir/file 2>/dev/null; then " +
                    "  echo '[bind] ISO file bound directly' >&2; " +
                    "else " +
                    "  $losetup -d $loop || true; " +
                    "  $losetup -r $loop \"$isoPath\"; " +
                    "  echo \"$loop\" > $lunDir/file; " +
                    "  echo '[bind] Using loop device' >&2; " +
                    "fi",
            // Link function into configuration and enable gadget
            "ln -s $funcPath $gadgetPath/configs/$configDir/ || true",
            "sync", "sleep 1", "echo $udc > $gadgetPath/UDC"
        )

        // -------------------------------------------------------------------------
        // Execute and handle result
        // -------------------------------------------------------------------------
        val result = Shell.cmd(*cmds.toTypedArray()).exec()

        return if (result.isSuccess) {
            logToFile(context, "[mountUsingConfigFs] Success -> $loop")
            MountStateStore.save(context, isoPath, loop, lun.toString())
            LogManager.logMount(context, File(isoPath).name, "$loop via configfs (USB_HDD)")
            MountResult(true, "Mounted using ConfigFS (USB_HDD).", loop)
        } else {
            val output = (result.out + result.err).joinToString("\n")
            logToFile(context, "[mountUsingConfigFs] Failure:\n$output")
            cleanupMount(context, loop)
            MountResult(false, output.ifBlank { "Shell command failed (no output)." })
        }
    }

    // ---------- Pixel ----------
    private fun mountUsingPixel(context: Context, isoPath: String, lun: Int): MountResult {
    logToFile(context, "[mountUsingPixel] Starting Pixel mount flow for $isoPath ($mode, lun=$lun)")
        ensureModulesLoaded(context)
        ensureConfigFsMounted(context)

        val roFlag = 1
        val cdFlag = 0
        val losetup = resolveLosetup(context) ?: return MountResult(false, "No losetup binary found.")
        val loop = getAvailableLoopDevice(context, losetup) ?: return MountResult(false, "No available loop device.")
        val gadgetPath = detectConfigFsPath(context) ?: return MountResult(false, "ConfigFS not found.")
        val configDir = pickConfigDir(gadgetPath)
        val udc = getUdcName(context) ?: return MountResult(false, "No UDC controller found.")

        // Resolve correct mass-storage path
        val funcPath = resolveFuncPath(gadgetPath)
        val lunDir = resolveLunDir(funcPath, lun)
        Shell.cmd("mkdir -p $funcPath $lunDir || true").exec()

        // -------------------------------------------------------------------------
        // Command list
        // -------------------------------------------------------------------------
        val cmds = mutableListOf(
            // Clean previous gadget state
            "echo '' > $gadgetPath/UDC || true",
            "find $gadgetPath/configs -type l -delete || true",
            "rm -rf $gadgetPath/functions/ffs.adb || true",
            "rm -rf $gadgetPath/functions/mass_storage.* || true",

            // Recreate base directories
            "mkdir -p $gadgetPath/strings/0x409",
            "mkdir -p $gadgetPath/configs/$configDir",
            "mkdir -p $funcPath $lunDir",

            // Vendor/Product IDs
            "test -s $gadgetPath/idVendor || echo 0x18d1 > $gadgetPath/idVendor",
            "test -s $gadgetPath/idProduct || echo 0x4e21 > $gadgetPath/idProduct",
            "echo SimpleBoot > $gadgetPath/strings/0x409/manufacturer",
            "echo SimpleBoot USB > $gadgetPath/strings/0x409/product",
            "echo SB$(date +%s) > $gadgetPath/strings/0x409/serialnumber",

            // Storage mode flags
            "echo 0 > $funcPath/stall || true",
            "echo $cdFlag > $lunDir/cdrom || true",
            "echo $roFlag > $lunDir/ro || true",
            "echo 1 > $lunDir/removable || true",
            "echo 1 > $lunDir/nofua || true",

            // Direct ISO bind + automatic fallback to loop
            "echo '' > $lunDir/file || true",
            "if echo \"$isoPath\" > $lunDir/file 2>/dev/null; then " +
                    "  echo '[bind] ISO file bound directly' >&2; " +
                    "else " +
                    "  $losetup -d $loop || true; " +
                    "  $losetup -r $loop \"$isoPath\"; " +
                    "  echo \"$loop\" > $lunDir/file; " +
                    "  echo '[bind] Using loop device' >&2; " +
                    "fi",

            // Link, sync, and enable gadget
            "ln -s $funcPath $gadgetPath/configs/$configDir/ || true",
            "sync", "sleep 1", "echo $udc > $gadgetPath/UDC"
        )

        // -------------------------------------------------------------------------
        // Execute and handle result
        // -------------------------------------------------------------------------
        val result = Shell.cmd(*cmds.toTypedArray()).exec()

        return if (result.isSuccess) {
            logToFile(context, "[mountUsingPixel] Success -> $loop")
            MountStateStore.save(context, isoPath, loop, lun.toString())
            LogManager.logMount(context, File(isoPath).name, "$loop via pixel ($mode)")
            MountResult(true, "Mounted using Pixel method.", loop)
        } else {
            val output = (result.out + result.err).joinToString("\n")
            logToFile(context, "[mountUsingPixel] Failure:\n$output")
            cleanupMount(context, loop)
            MountResult(false, output.ifBlank { "Shell command failed (no output)." })
        }
    }

    // ---------- Legacy ----------
    private fun mountUsingLegacy(context: Context, isoPath: String, lun: Int): MountResult {
        logToFile(context, "[mountUsingLegacy] Starting for $isoPath")
        ensureModulesLoaded(context)
        val roFlag = 1
        val cdFlag = 0
        val losetup = resolveLosetup(context) ?: return MountResult(false, "No losetup binary found.")
        val loop = getAvailableLoopDevice(context, losetup) ?: return MountResult(false, "No available loop device.")

        val cmds = listOf(
            "echo 0 > $LEGACY_USB_PATH/enable",
            "mkdir -p $LEGACY_USB_PATH/f_mass_storage/lun || true",
            "$losetup -d $loop || true",
            "$losetup -r $loop \"$isoPath\"",
            "echo \"$loop\" > $LEGACY_MASS_STORAGE_PATH",
            "echo $cdFlag > $LEGACY_USB_PATH/f_mass_storage/lun/cdrom || true",
            "echo $roFlag > $LEGACY_USB_PATH/f_mass_storage/lun/ro || true",
            "echo 1 > $LEGACY_USB_PATH/f_mass_storage/lun/removable || true",
            "echo 1 > $LEGACY_USB_PATH/enable"
        )

        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return if (result.isSuccess) {
            logToFile(context, "[mountUsingLegacy] Success -> $loop")
            MountStateStore.save(context, isoPath, loop, lun.toString())
            LogManager.logMount(context, File(isoPath).name, "$loop via legacy ($mode)")
            MountResult(true, "Mounted using Legacy method.", loop)
        } else {
            val output = (result.out + result.err).joinToString("\n")
            logToFile(context, "[mountUsingLegacy] Failure:\n$output")
            cleanupMount(context, loop)
            MountResult(false, output.ifBlank { "Shell command failed (no output)." })
        }
    }

    // ---------- Loopback ----------
    private fun mountUsingLoopback(context: Context, isoPath: String): MountResult {
        logToFile(context, "[mountUsingLoopback] Starting loopback mount for $isoPath")
        val losetup = resolveLosetup(context) ?: return MountResult(false, "No losetup binary found.")
        val loop = getAvailableLoopDevice(context, losetup) ?: return MountResult(false, "No available loop device.")

        val cmds = listOf("$losetup -d $loop || true", "$losetup -r $loop \"$isoPath\"")
        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return if (result.isSuccess) {
            logToFile(context, "[mountUsingLoopback] Success -> $loop")
            MountStateStore.save(context, isoPath, loop, "0")
            LogManager.logMount(context, File(isoPath).name, "$loop via loopback")
            MountResult(true, "Loopback mounted.", loop)
        } else {
            val output = (result.out + result.err).joinToString("\n")
            logToFile(context, "[mountUsingLoopback] Failure:\n$output")
            cleanupMount(context, loop)
            MountResult(false, output.ifBlank { "Shell command failed (no output)." })
        }
    }

    // ---------- Cleanup ----------
    private fun cleanupMount(context: Context, loopDevice: String?) {
        logToFile(context, "[cleanupMount] Performing cleanup for loop=$loopDevice")
        val cmds = mutableListOf<String>()
        CONFIGFS_ROOTS.forEach { root ->
            val gadgetPath = "$root/usb_gadget/$GADGET_NAME"
            cmds += listOf(
                "echo '' > $gadgetPath/UDC || true",
                "find $gadgetPath/configs -type l -delete || true",
                "rm -rf $gadgetPath/functions/mass_storage.* || true"
            )
        }
        cmds += listOf(
            "echo 0 > $LEGACY_USB_PATH/enable || true",
            "echo '' > $LEGACY_MASS_STORAGE_PATH || true"
        )
        loopDevice?.let { loop ->
            cmds += listOf(
                "losetup -d $loop || true",
                "toybox losetup -d $loop || true",
                "busybox losetup -d $loop || true"
            )
        }
        cmds += listOf(
            "setprop sys.usb.config mass_storage,adb",
            "setprop sys.usb.state mass_storage,adb"
        )
        Shell.cmd(*cmds.toTypedArray()).exec()
        logToFile(context, "[cleanupMount] Cleanup complete - USB restored")
    }

}
