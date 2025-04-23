// MountResult.kt

package matthewdaluz.simpleboot.model

/**
 * Represents the result of a mount or unmount operation.
 *
 * @param success     Whether the operation was successful.
 * @param message     A user-friendly message explaining the outcome.
 * @param loopDevice  Optional: the loop device used (if applicable).
 */
data class MountResult(
    val success: Boolean,         // True if the operation succeeded, false otherwise
    val message: String,          // Message detailing the result (success or error)
    val loopDevice: String? = null // Loop device path, only provided on success
)
