package io.github.dan.loowater

/** Turns a [FinderState] into the words a field shows. Shared by every field layout. */
object FieldText {
    /**
     * [primary] is the big text (distance), [arrow] the direction ("" if none),
     * [secondary] the small status line, [warn] colours it as a problem.
     */
    data class Display(val primary: String, val secondary: String, val warn: Boolean, val arrow: String = "")

    fun of(state: FinderState): Display = when (state) {
        is FinderState.Found -> {
            val direction = state.arrow
                ?: state.heading?.let { Geo.relativeArrow(state.bearingDeg, it) }
                ?: Geo.compassPoint(state.bearingDeg)
            val hours = when (state.hours) {
                OpeningHours.Status.OPEN -> "open"
                OpeningHours.Status.UNKNOWN -> "hours ?"
                OpeningHours.Status.CLOSED -> "closed" // filtered out upstream; kept for completeness
            }
            val fee = if (state.place.fee) " · fee" else ""
            val status = if (state.navigating) "tap to stop" else "$hours$fee"
            Display(Geo.formatDistance(state.distanceM, NearbyFinder.imperial), status, false, direction)
        }
        FinderState.WaitingForGps -> Display("--", "no GPS", false)
        FinderState.Searching -> Display("…", "searching", false)
        is FinderState.NoneFound ->
            Display("none", "within ${Geo.formatDistance(state.radiusM.toDouble(), NearbyFinder.imperial)}", true)
        is FinderState.Failed -> Display("--", "${state.reason} · tap", true)
    }
}
