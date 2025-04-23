package matthewdaluz.simpleboot.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LogManager {

    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm"
    private const val FILE_DATE_PATTERN = "yyyyMMdd"
    private val formatter = DateTimeFormatter.ofPattern(DATE_PATTERN)

    fun logMount(context: Context, fileName: String, loopDevice: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val log = "[$timestamp] Mounted: $fileName to $loopDevice\n"
        writeLog(context, log)
    }

    fun logUnmount(context: Context, fileName: String, loopDevice: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val log = "[$timestamp] Unmounted: $fileName from $loopDevice\n"
        writeLog(context, log)
    }

    fun logToFile(context: Context, message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val log = "[$timestamp] $message\n"
        writeLog(context, log)
    }

    private fun writeLog(context: Context, entry: String) {
        val logDir = File(context.getExternalFilesDir(null), "SimpleBootLogs")
        if (!logDir.exists()) logDir.mkdirs()

        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(FILE_DATE_PATTERN))
        val logFile = File(logDir, "mount_log_$date.txt")
        logFile.appendText(entry)
    }

    fun exportLogFile(context: Context): Intent? {
        val logDir = File(context.getExternalFilesDir(null), "SimpleBootLogs")
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(FILE_DATE_PATTERN))
        val file = File(logDir, "mount_log_$date.txt")
        if (!file.exists()) return null

        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".provider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
