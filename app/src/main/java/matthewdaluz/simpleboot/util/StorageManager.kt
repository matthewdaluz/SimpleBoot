package matthewdaluz.simpleboot.util

import android.os.Environment
import matthewdaluz.simpleboot.model.IsoFile
import java.io.File

object StorageManager {
    fun ensureDirectories() {
        val isoDir = File(Environment.getExternalStorageDirectory(), "SimpleBootISOs")
        if (!isoDir.exists()) isoDir.mkdirs()

        val logDir = File(Environment.getExternalStorageDirectory(), "SimpleBootLogs")
        if (!logDir.exists()) logDir.mkdirs()
    }

    fun getIsoFileList(): List<IsoFile> {
        val isoDir = File(Environment.getExternalStorageDirectory(), "SimpleBootISOs")
        if (!isoDir.exists()) return emptyList()

        return isoDir.listFiles { file ->
            file.extension.lowercase() in listOf("iso", "img")
        }?.map { file ->
            IsoFile(file.name, file.absolutePath, file.length())
        } ?: emptyList()
    }
}
