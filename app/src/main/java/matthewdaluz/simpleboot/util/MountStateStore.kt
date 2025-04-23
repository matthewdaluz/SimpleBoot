package matthewdaluz.simpleboot.util

import android.content.Context
import androidx.core.content.edit

object MountStateStore {
    private const val PREFS = "SimpleBootPrefs"
    private const val KEY_FILE_PATH = "mounted_file_path"
    private const val KEY_LOOP_DEVICE = "loop_device"
    private const val KEY_MOUNT_TIME = "mount_time"

    data class MountInfo(
        val filePath: String,
        val loopDevice: String,
        val mountTime: Long,
        val lun: String
    )


    fun save(context: Context, filePath: String, loopDevice: String, lun: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit() {
            putString(KEY_FILE_PATH, filePath)
                .putString(KEY_LOOP_DEVICE, loopDevice)
                .putLong(KEY_MOUNT_TIME, System.currentTimeMillis())
                .putString("lun_used", lun)
        }
    }


    fun load(context: Context): MountInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val filePath = prefs.getString(KEY_FILE_PATH, null)
        val loopDevice = prefs.getString(KEY_LOOP_DEVICE, null)
        val mountTime = prefs.getLong(KEY_MOUNT_TIME, -1)
        val lun = prefs.getString("lun_used", null)

        return if (filePath != null && loopDevice != null && mountTime > 0 && lun != null) {
            MountInfo(filePath, loopDevice, mountTime, lun)
        } else null
    }


    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit() { clear() }
    }
}
