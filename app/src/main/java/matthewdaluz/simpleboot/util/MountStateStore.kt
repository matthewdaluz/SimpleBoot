// MountStateStore.kt

package matthewdaluz.simpleboot.util

import android.content.Context
import androidx.core.content.edit

/**
 * Object that manages persistent storage of mount state information
 * using Android SharedPreferences.
 */
object MountStateStore {
    // Preference file name and keys for stored values
    private const val PREFS = "SimpleBootPrefs"
    private const val KEY_FILE_PATH = "mounted_file_path"
    private const val KEY_LOOP_DEVICE = "loop_device"
    private const val KEY_MOUNT_TIME = "mount_time"
    private const val KEY_LUN_USED = "lun_used"  // Note: This was a string literal in save()/load()

    /**
     * Data class representing mount state information.
     * @property filePath Path to the mounted ISO file
     * @property loopDevice Loop device used for mounting
     * @property mountTime Timestamp when mount occurred
     * @property lun Logical Unit Number used for USB gadget configuration
     */
    data class MountInfo(
        val filePath: String,
        val loopDevice: String,
        val mountTime: Long,
        val lun: String
    )

    /**
     * Saves mount information to persistent storage.
     * @param context Android context
     * @param filePath Path to the mounted ISO file
     * @param loopDevice Loop device used
     * @param lun Logical Unit Number for USB gadget
     */
    fun save(context: Context, filePath: String, loopDevice: String, lun: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_FILE_PATH, filePath)
            putString(KEY_LOOP_DEVICE, loopDevice)
            putLong(KEY_MOUNT_TIME, System.currentTimeMillis())  // Store current time as mount time
            putString(KEY_LUN_USED, lun)  // Note: Using constant would be better here
        }
    }

    /**
     * Loads mount information from persistent storage.
     * @param context Android context
     * @return MountInfo if valid state exists, null otherwise
     */
    fun load(context: Context): MountInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return with(prefs) {
            // Retrieve all stored values
            val filePath = getString(KEY_FILE_PATH, null)
            val loopDevice = getString(KEY_LOOP_DEVICE, null)
            val mountTime = getLong(KEY_MOUNT_TIME, -1)
            val lun = getString(KEY_LUN_USED, null)  // Note: Using constant would be better here

            // Only return MountInfo if all required values are present and valid
            if (filePath != null && loopDevice != null && mountTime > 0 && lun != null) {
                MountInfo(filePath, loopDevice, mountTime, lun)
            } else {
                null
            }
        }
    }

    /**
     * Clears all mount state information from persistent storage.
     * @param context Android context
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                clear()  // Remove all preferences
            }
    }
}