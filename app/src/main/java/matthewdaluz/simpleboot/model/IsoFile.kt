// IsoFile.kt

package matthewdaluz.simpleboot.model

/**
 * Represents an ISO or IMG file available for mounting.
 *
 * @param name  The display name of the file (e.g., "ubuntu.iso").
 * @param path  Full absolute path to the file on the device.
 * @param size  File size in bytes.
 */
data class IsoFile(
    val name: String,  // File name (used for display)
    val path: String,  // Full file system path to the ISO/IMG
    val size: Long     // File size in bytes
)
