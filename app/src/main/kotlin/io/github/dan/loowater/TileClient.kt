package io.github.dan.loowater

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/** A candidate destination. One place can be both a toilet and a water point. */
data class Place(
    val id: String,
    val location: LatLng,
    val name: String?,
    val openingHours: String?,
    val fee: Boolean,
    val categories: Set<Category>,
    /** Toilet Map facility type code (see [FacilityType]), "" if not in the Toilet Map. */
    val type: String = "",
)

/** Summary of the published data (index.json). */
data class DataIndex(val builtEpoch: Long, val toiletMapSource: String?)

/** [serverError] = we reached the internet but got a bad reply; false = no connection. */
class FetchException(message: String, val serverError: Boolean = false) : Exception(message)

/**
 * Downloads data tiles built weekly by the GitHub workflow (see data/build_tiles.py)
 * and published on GitHub Pages. Requests go through Karoo's HTTP bridge: Wi-Fi if
 * connected, otherwise the phone via the Hammerhead companion app.
 *
 * Tiles are 0.1 x 0.1 degree squares (about 11 km tall), named "<floor(lat*10)>_<floor(lon*10)>".
 */
class TileClient(private val karoo: KarooSystemService) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Places in one tile. A tile with nothing in it simply doesn't exist (404), which is fine. */
    suspend fun fetch(key: String): List<Place> {
        val (status, body) = get("${baseUrl()}tiles/$key.json")
        return when {
            status == 404 -> emptyList()
            status !in 200..299 -> throw FetchException("HTTP $status", serverError = true)
            body == null -> throw FetchException("Empty response", serverError = true)
            else -> try {
                parse(key, body)
            } catch (e: Exception) {
                throw FetchException("Bad tile $key: ${e.message}", serverError = true)
            }
        }
    }

    /** When the data was last built on GitHub, and which Toilet Map copy it used. */
    suspend fun fetchIndex(): DataIndex {
        val (status, body) = get("${baseUrl()}index.json")
        if (status !in 200..299 || body == null) throw FetchException("HTTP $status", serverError = true)
        val obj = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        val epoch = obj["builtEpoch"]?.jsonPrimitive?.longOrNull
            ?: obj["built"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoUtc)
            ?: throw FetchException("No build time in index.json", serverError = true)
        return DataIndex(epoch, obj["toiletMapSource"]?.jsonPrimitive?.contentOrNull)
    }

    /** "2026-10-16T12:11:43+00:00" -> epoch seconds (the build always writes UTC). */
    private fun parseIsoUtc(text: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(text.take(19))?.time?.div(1000)
    } catch (e: java.text.ParseException) {
        null
    }

    private suspend fun get(url: String): Pair<Int, ByteArray?> = withTimeout(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            var consumerId: String? = null
            fun finish(block: () -> Unit) {
                consumerId?.let { karoo.removeConsumer(it) }
                if (cont.isActive) block()
            }
            consumerId = karoo.addConsumer<OnHttpResponse>(
                OnHttpResponse.MakeHttpRequest(
                    method = "GET",
                    url = url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    // Fail fast when offline so the rider gets told, rather than queueing silently.
                    waitForConnection = false,
                ),
                onError = { msg -> finish { cont.resumeWithException(FetchException(msg)) } },
            ) { event ->
                val state = event.state
                if (state is HttpResponseState.Complete) {
                    finish {
                        val error = state.error
                        if (error != null) {
                            cont.resumeWithException(FetchException(error))
                        } else {
                            cont.resume(state.statusCode to state.body)
                        }
                    }
                }
            }
            cont.invokeOnCancellation { consumerId?.let { karoo.removeConsumer(it) } }
        }
    }

    private fun parse(key: String, body: ByteArray): List<Place> {
        val rows = json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject["p"]?.jsonArray
            ?: return emptyList()
        return rows.mapIndexedNotNull { index, element ->
            val row = element as? JsonArray ?: return@mapIndexedNotNull null
            val lat = row.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapIndexedNotNull null
            val lon = row.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapIndexedNotNull null
            val codes = row.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val cats = Category.entries.filter { it.code in codes }.toSet()
            if (cats.isEmpty()) return@mapIndexedNotNull null
            fun text(i: Int) = (row.getOrNull(i) as? JsonPrimitive)?.contentOrNull
            Place(
                id = "$key-$index-$lat,$lon",
                location = LatLng(lat, lon),
                name = text(3),
                openingHours = text(4),
                fee = (row.getOrNull(5) as? JsonPrimitive)?.intOrNull == 1,
                categories = cats,
                type = text(6).orEmpty(),
            )
        }
    }

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val USER_AGENT = "KarooLooWater/1.0 (Hammerhead Karoo extension)"
        private const val PLACEHOLDER = "YOUR-GITHUB-USERNAME"

        private fun baseUrl(): String = BuildConfig.DATA_BASE_URL.let { if (it.endsWith("/")) it else "$it/" }

        /** False until the data URL in gradle.properties has been filled in. */
        val configured: Boolean get() = !BuildConfig.DATA_BASE_URL.contains(PLACEHOLDER)

        fun keyFor(lat: Double, lng: Double): String = "${floor(lat * 10).toInt()}_${floor(lng * 10).toInt()}"

        /** Keys of every tile within [radiusM] of [here], nearest first. */
        fun keysAround(here: LatLng, radiusM: Double): List<String> {
            val dLat = radiusM / 111_320.0
            val dLng = radiusM / (111_320.0 * max(cos(Math.toRadians(here.lat)), 0.01))
            val latRange = floor((here.lat - dLat) * 10).toInt()..floor((here.lat + dLat) * 10).toInt()
            val lngRange = floor((here.lng - dLng) * 10).toInt()..floor((here.lng + dLng) * 10).toInt()
            return latRange.flatMap { a -> lngRange.map { b -> Triple(a, b, "${a}_$b") } }
                .sortedBy { (a, b, _) ->
                    Geo.distanceM(here, LatLng((a + 0.5) / 10.0, (b + 0.5) / 10.0))
                }
                .map { it.third }
        }
    }
}
