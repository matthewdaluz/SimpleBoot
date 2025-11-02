package matthewdaluz.simpleboot.util

import android.content.Context
import androidx.core.content.edit

/**
 * Manages persistent storage of mount state information using SharedPreferences.
 * Now includes detailed debug logging for each operation.
 */
object MountStateStore {
    private const val PREFS = "SimpleBootPrefs"
    private const val KEY_FILE_PATH = "mounted_file_path"
    private const val KEY_LOOP_DEVICE = "loop_device"
    private const val KEY_MOUNT_TIME = "mount_time"
    private const val KEY_LUN_USED = "lun_used"

    data class MountInfo(
        val filePath: String,
        val loopDevice: String,
        val mountTime: Long,
        val lun: String
    )

    private fun log(message: String) {
        try {
            LogManager.logToFile(null as Context?, "[MountStateStore] $message")
        } catch (_: Exception) {
            // Ignore if logging context unavailable
        }
    }

    /**
     * Saves current mount information to shared preferences.
     */
    fun save(context: Context, filePath: String, loopDevice: String, lun: String) {
        log("Saving mount info -> filePath=$filePath, loop=$loopDevice, lun=$lun")
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_FILE_PATH, filePath)
                putString(KEY_LOOP_DEVICE, loopDevice)
                putLong(KEY_MOUNT_TIME, System.currentTimeMillis())
                putString(KEY_LUN_USED, lun)
            }
            log("Mount state saved successfully.")
        } catch (e: Exception) {
            log("Error while saving mount state: ${e.message}")
        }
    }

    /**
     * Loads previously saved mount information.
     * @return MountInfo object or null if nothing saved.
     */
    fun load(context: Context): MountInfo? {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val filePath = prefs.getString(KEY_FILE_PATH, null)
            val loopDevice = prefs.getString(KEY_LOOP_DEVICE, null)
            val mountTime = prefs.getLong(KEY_MOUNT_TIME, -1)
            val lun = prefs.getString(KEY_LUN_USED, null)

            return if (filePath != null && loopDevice != null && mountTime > 0 && lun != null) {
                log("Loaded mount info -> filePath=$filePath, loop=$loopDevice, lun=$lun, time=$mountTime")
                MountInfo(filePath, loopDevice, mountTime, lun)
            } else {
                log("No valid mount info found in SharedPreferences.")
                null
            }
        } catch (e: Exception) {
            log("Error while loading mount state: ${e.message}")
            return null
        }
    }

    /**
     * Clears stored mount information.
     */
    fun clear(context: Context) {
        log("Clearing stored mount info...")
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
            log("Mount state cleared.")
        } catch (e: Exception) {
            log("Error while clearing mount state: ${e.message}")
        }
    }
}
