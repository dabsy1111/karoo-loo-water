package io.github.dan.loowater

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from a to b, degrees clockwise from north, 0..360. */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val compass = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    fun compassPoint(bearing: Double): String = compass[((bearing / 45.0).roundToInt()) % 8]

    /** Arrow relative to the rider's heading, so "↑" means straight ahead. */
    fun relativeArrow(bearing: Double, heading: Double): String {
        val rel = ((bearing - heading) % 360 + 360) % 360
        return arrows[((rel / 45.0).roundToInt()) % 8]
    }

    fun formatDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val miles = meters / 1609.344
        when {
            miles < 0.1 -> "${(meters * 3.28084 / 10).roundToInt() * 10} ft"
            miles < 10 -> "%.1f mi".format(java.util.Locale.US, miles)
            else -> "${miles.roundToInt()} mi"
        }
    } else {
        when {
            meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
            meters < 10_000 -> "%.1f km".format(java.util.Locale.US, meters / 1000)
            else -> "${(meters / 1000).roundToInt()} km"
        }
    }
}
