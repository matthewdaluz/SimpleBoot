// LogManager.kt

package matthewdaluz.simpleboot.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * LogManager is responsible for logging all mount/unmount actions and general messages,
 * saving them into daily log files, and optionally exporting them for sharing.
 */
object LogManager {

    // Date format used in log entries
    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm"
    // Date format used in log file names
    private const val FILE_DATE_PATTERN = "yyyyMMdd"
    private val formatter = DateTimeFormatter.ofPattern(DATE_PATTERN)

    /**
     * Logs a mount event with timestamp and device details.
     *
     * @param context    Application context
     * @param fileName   Name of the mounted file
     * @param loopDevice Associated loop device
     */
    fun logMount(context: Context, fileName: String, loopDevice: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val log = "[$timestamp] Mounted: $fileName to $loopDevice\n"
        writeLog(context, log)
    }

    /**
     * Logs an unmount event with timestamp and device details.
     *
     * @param context    Application context
     * @param fileName   Name of the unmounted file
     * @param loopDevice Associated loop device
     */
    fun logUnmount(context: Context, fileName: String, loopDevice: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val log = "[$timestamp] Unmounted: $fileName from $loopDevice\n"
        writeLog(context, log)
    }

    /**
     * Generic log message writer, prepending the current timestamp.
     *
     * @param context Application context
     * @param message Message to log
     */
    fun logToFile(context: Context, message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val log = "[$timestamp] $message\n"
        writeLog(context, log)
    }

    /**
     * Writes a log entry into a log file named by the current date.
     *
     * @param context Application context
     * @param entry   Full log entry to append
     */
    private fun writeLog(context: Context, entry: String) {
        // Create log directory inside app-specific external storage
        val logDir = File(context.getExternalFilesDir(null), "SimpleBootLogs")
        if (!logDir.exists()) logDir.mkdirs()

        // Create a log file with the current date in the name
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(FILE_DATE_PATTERN))
        val logFile = File(logDir, "mount_log_$date.txt")

        // Append the entry to the log file
        logFile.appendText(entry)
    }

    /**
     * Exports the current day's log file as an Intent to share it via apps.
     *
     * @param context Application context
     * @return        Share Intent or null if no file exists
     */
    fun exportLogFile(context: Context): Intent? {
        val logDir = File(context.getExternalFilesDir(null), "SimpleBootLogs")
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(FILE_DATE_PATTERN))
        val file = File(logDir, "mount_log_$date.txt")
        if (!file.exists()) return null

        // Create a content URI using FileProvider
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".provider",
            file
        )

        // Create a send intent to share the log file
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
