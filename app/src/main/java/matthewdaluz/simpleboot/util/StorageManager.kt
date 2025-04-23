// StorageManager.kt

package matthewdaluz.simpleboot.util

import android.os.Environment
import matthewdaluz.simpleboot.model.IsoFile
import java.io.File

/**
 * Object that handles all storage-related operations for the app,
 * including directory management and ISO file listing.
 */
object StorageManager {

    /**
     * Ensures required directories exist in external storage.
     * Creates them if they don't exist.
     */
    fun ensureDirectories() {
        // Directory for storing ISO files
        val isoDir = File(Environment.getExternalStorageDirectory(), "SimpleBootISOs")
        if (!isoDir.exists()) isoDir.mkdirs()  // Create directory if it doesn't exist

        // Directory for storing log files
        val logDir = File(Environment.getExternalStorageDirectory(), "SimpleBootLogs")
        if (!logDir.exists()) logDir.mkdirs()  // Create directory if it doesn't exist
    }

    /**
     * Retrieves a list of ISO/IMG files from the SimpleBootISOs directory.
     * @return List of IsoFile objects representing the found files
     */
    fun getIsoFileList(): List<IsoFile> {
        val isoDir = File(Environment.getExternalStorageDirectory(), "SimpleBootISOs")

        // Return empty list if directory doesn't exist
        if (!isoDir.exists()) return emptyList()

        // Filter files to only include .iso and .img extensions (case insensitive)
        return isoDir.listFiles { file ->
            file.extension.lowercase() in listOf("iso", "img")
        }?.map { file ->
            // Convert each File object to our IsoFile model
            IsoFile(
                name = file.name,
                path = file.absolutePath,
                size = file.length()
            )
        } ?: emptyList()  // Return empty list if listFiles returns null
    }
}