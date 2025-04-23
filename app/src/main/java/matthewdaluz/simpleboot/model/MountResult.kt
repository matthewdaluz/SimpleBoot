package matthewdaluz.simpleboot.model

data class MountResult(
    val success: Boolean,
    val message: String,
    val loopDevice: String? = null
)
