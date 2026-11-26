package app.wazealert.driver

data class Alert(
    val id: String,
    val type: String,          // CAMERA, POLICE, HAZARD, ACCIDENT
    val subtype: String,       // CAMERA_SPEED, CAMERA_REDLIGHT, USER, COMMUNITY
    val street: String,
    val lat: Double,
    val lon: Double,
    val thumbsUp: Int = 0,
    var distanceM: Double = 0.0,
    val dirs: IntArray? = null,
    val limitKmh: Int = 0,
) {
    val isCommunity get() = id.startsWith("srv")

    val label: String
        get() = when {
            type == "POLICE" -> "Police"
            type == "HAZARD" -> "Hazard"
            type == "ACCIDENT" -> "Accident"
            subtype == "CAMERA_REDLIGHT" -> "Red-light camera"
            type == "CAMERA" -> "Speed camera"
            else -> type.lowercase().replaceFirstChar { it.uppercase() }
        }
}
