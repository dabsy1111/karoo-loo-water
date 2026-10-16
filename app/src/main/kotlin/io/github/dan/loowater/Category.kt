package io.github.dan.loowater

import io.hammerhead.karooext.models.Symbol

/**
 * The two things this extension finds. Which OpenStreetMap objects count as each
 * is decided when the data tiles are built (data/build_tiles.py); a tile marks
 * every place with [code].
 */
enum class Category(
    val code: Char,
    val typeId: String,
    val actionId: String,
    val poiType: String,
    val iconRes: Int,
    val shortLabelRes: Int,
) {
    TOILET(
        code = 't',
        typeId = "nearest-toilet",
        actionId = "route-toilet",
        poiType = Symbol.POI.Types.RESTROOM,
        iconRes = R.drawable.ic_toilet,
        shortLabelRes = R.string.toilet_short,
    ),
    WATER(
        code = 'w',
        typeId = "nearest-water",
        actionId = "route-water",
        poiType = Symbol.POI.Types.WATER,
        iconRes = R.drawable.ic_water,
        shortLabelRes = R.string.water_short,
    ),
    ;

    companion object {
        fun fromTypeId(typeId: String): Category? = entries.firstOrNull { it.typeId == typeId }
        fun fromActionId(actionId: String): Category? = entries.firstOrNull { it.actionId == actionId }
    }
}
