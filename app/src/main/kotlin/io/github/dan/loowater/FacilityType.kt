package io.github.dan.loowater

/**
 * Toilet Map facility types the rider can switch on and off. Codes must match
 * TYPE_CODES in data/toiletmap.py; the build writes them into every tile row.
 */
data class FacilityType(val code: String, val label: String, val onByDefault: Boolean = true) {
    companion object {
        /** Toilets that are only in OpenStreetMap, so their type isn't known. */
        const val UNLISTED = "unlisted"

        val all = listOf(
            FacilityType("park", "Parks and reserves"),
            FacilityType("beach", "Beaches"),
            FacilityType("sport", "Sporting facilities"),
            FacilityType("pool", "Swimming pools"),
            FacilityType("rest", "Rest areas"),
            FacilityType("carpark", "Car parks"),
            FacilityType("servo", "Service stations"),
            FacilityType("train", "Train stations"),
            FacilityType("bus", "Bus stations"),
            FacilityType("jetty", "Jetties"),
            FacilityType("community", "Community buildings"),
            FacilityType("camp", "Camping grounds"),
            FacilityType("caravan", "Caravan parks"),
            FacilityType("cemetery", "Cemeteries"),
            FacilityType("other", "Other (Toilet Map)"),
            FacilityType(UNLISTED, "Not in the Toilet Map (OpenStreetMap only)"),
            // Indoors: you'd have to leave the bike, so off unless switched on.
            FacilityType("shopping", "Shopping centres", onByDefault = false),
            FacilityType("food", "Food outlets", onByDefault = false),
            FacilityType("airport", "Airports", onByDefault = false),
        )

        val defaultDisabled: Set<String> = all.filterNot { it.onByDefault }.map { it.code }.toSet()
    }
}
