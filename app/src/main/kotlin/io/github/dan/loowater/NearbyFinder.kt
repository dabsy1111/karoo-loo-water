package io.github.dan.loowater

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.PerformHardwareAction
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What a data field should show. */
sealed class FinderState {
    data object WaitingForGps : FinderState()
    data object Searching : FinderState()
    data class Found(
        val place: Place,
        val distanceM: Double,
        val bearingDeg: Double,
        val heading: Double?,
        val hours: OpeningHours.Status,
    ) : FinderState()
    data class NoneFound(val radiusM: Int) : FinderState()
    data class Failed(val reason: String) : FinderState()
}

/**
 * Shared brain for both fields. Owns the location feed, the cache of data tiles,
 * and tap handling. One instance per extension process.
 */
object NearbyFinder {

    /** Keep every tile within this distance of the rider loaded. */
    private const val COVER_RADIUS_M = 8_000.0
    /** Data is rebuilt weekly; refreshing once a day is plenty. */
    private const val TILE_MAX_AGE_MS = 24 * 60 * 60_000L
    private const val RETRY_AFTER_FAILURE_MS = 60_000L

    /**
     * Auto-navigate: after opening the pin screen, press the same buttons a rider would
     * (bottom-right accepts the pin, top-right moves to Navigate, bottom-right starts it).
     */
    private const val PIN_SCREEN_OPEN_MS = 1_500L
    private const val BETWEEN_PRESSES_MS = 350L
    private const val CONFIRM_NAV_WITHIN_MS = 4_000L

    private class Tile(val places: List<Place>, val fetchedAtMs: Long)

    private lateinit var appContext: Context
    private lateinit var karoo: KarooSystemService
    private lateinit var client: TileClient

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val consumerIds = mutableListOf<String>()

    private val tiles = ConcurrentHashMap<String, Tile>()
    private val fetchLock = Any()
    @Volatile private var fetchJob: Job? = null
    @Volatile private var lastFailureMs = 0L
    @Volatile private var lastSuccessMs = 0L
    @Volatile private var lastFailureWasServer = false

    private val states = Category.entries.associateWith { MutableStateFlow<FinderState>(FinderState.WaitingForGps) }
    private val viewers = Category.entries.associateWith { AtomicInteger(0) }
    /** Set by a tap that arrived before data did; route as soon as it lands. */
    private val navigateWhenReady = Category.entries.associateWith { AtomicBoolean(false) }

    /** Shared by both fields so the rider only gets one "no connection" popup. */
    @Volatile private var offlineAlertShown = false

    @Volatile private var location: LatLng? = null
    @Volatile private var heading: Double? = null
    @Volatile private var navState: OnNavigationState.NavigationState = OnNavigationState.NavigationState.Idle
    private val autoNavRunning = AtomicBoolean(false)
    @Volatile var imperial: Boolean = false
        private set

    fun state(category: Category): StateFlow<FinderState> = states.getValue(category).asStateFlow()

    fun start(context: Context, karooSystem: KarooSystemService) {
        appContext = context.applicationContext
        Settings.init(appContext)
        karoo = karooSystem
        client = TileClient(karooSystem)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        consumerIds += karoo.addConsumer<OnLocationChanged> { loc ->
            location = LatLng(loc.lat, loc.lng)
            heading = loc.orientation
            refresh()
        }
        consumerIds += karoo.addConsumer<OnNavigationState> { navState = it.state }
        consumerIds += karoo.addConsumer<UserProfile> { profile ->
            imperial = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
        }

        // Opening hours change with the clock even when you're stopped.
        scope.launch {
            while (isActive) {
                delay(60_000)
                refresh()
            }
        }
    }

    fun stop() {
        consumerIds.forEach { karoo.removeConsumer(it) }
        consumerIds.clear()
        scope.cancel()
    }

    fun viewAttached(category: Category) {
        viewers.getValue(category).incrementAndGet()
        refresh()
    }

    fun viewDetached(category: Category) {
        viewers.getValue(category).decrementAndGet()
    }

    /** Field tapped or bonus button pressed: route to the best current option. */
    fun onTap(category: Category) {
        // A stale PendingIntent can start a fresh process where the service (and start()) never ran.
        if (!::karoo.isInitialized) return
        when (val state = recompute(category)) {
            is FinderState.Found -> navigateTo(category, state.place)
            is FinderState.NoneFound -> alert(category, "None found", noneFoundDetail(state))
            else -> {
                navigateWhenReady.getValue(category).set(true)
                when {
                    !TileClient.configured -> alert(category, "Not set up", "Data URL missing in gradle.properties")
                    location == null -> alert(category, "No GPS fix yet", "Will route once location is known")
                    else -> {
                        alert(category, "Searching…", "Will route to the nearest one when found")
                        lastFailureMs = 0 // a tap is an explicit retry
                        fetchMissing(force = true)
                    }
                }
            }
        }
    }

    /** A type switch changed on the settings screen: re-pick straight away. */
    fun onSettingsChanged() {
        Category.entries.forEach { recompute(it) }
    }

    /** "Reload data" on the settings screen: drop cached tiles and fetch them again. */
    fun reloadData() {
        tiles.clear()
        lastFailureMs = 0
        lastSuccessMs = 0
        if (::karoo.isInitialized) {
            Category.entries.forEach { recompute(it) }
            fetchMissing(force = true)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun refresh() {
        Category.entries.forEach { recompute(it) }
        val wanted = Category.entries.any { viewers.getValue(it).get() > 0 || navigateWhenReady.getValue(it).get() }
        if (wanted) fetchMissing(force = false)
    }

    /** Picks the nearest place that isn't confidently closed from the loaded tiles. */
    @Synchronized
    private fun recompute(category: Category): FinderState {
        val here = location
        val next: FinderState = when {
            !TileClient.configured -> FinderState.Failed("not set up")
            here == null -> FinderState.WaitingForGps
            else -> best(category, here) ?: run {
                val allLoaded = TileClient.keysAround(here, COVER_RADIUS_M).all { tiles.containsKey(it) }
                when {
                    fetchJob?.isActive == true -> FinderState.Searching
                    allLoaded -> FinderState.NoneFound(COVER_RADIUS_M.toInt())
                    lastFailureMs > lastSuccessMs ->
                        FinderState.Failed(if (lastFailureWasServer) "data error" else "offline")
                    else -> FinderState.Searching
                }
            }
        }
        states.getValue(category).value = next
        return next
    }

    private fun best(category: Category, here: LatLng): FinderState.Found? {
        val now = Calendar.getInstance()
        return tiles.values
            .asSequence()
            .flatMap { it.places.asSequence() }
            .filter { category in it.categories && Settings.allows(it, category) }
            .map { place -> place to Geo.distanceM(here, place.location) }
            .sortedBy { it.second }
            // Hours are only evaluated until the first place that isn't confidently closed.
            .map { (place, dist) ->
                Triple(place, dist, OpeningHours.status(place.openingHours, now, place.location.lat, place.location.lng))
            }
            .firstOrNull { it.third != OpeningHours.Status.CLOSED }
            ?.let { (place, dist, hours) ->
                FinderState.Found(place, dist, Geo.bearingDeg(here, place.location), heading, hours)
            }
    }

    private fun fetchMissing(force: Boolean) {
        if (!TileClient.configured) return
        val here = location ?: return
        val now = System.currentTimeMillis()
        synchronized(fetchLock) {
            if (fetchJob?.isActive == true) return
            if (!force && now - lastFailureMs < RETRY_AFTER_FAILURE_MS) return
            val needed = TileClient.keysAround(here, COVER_RADIUS_M).filter { key ->
                val tile = tiles[key]
                tile == null || now - tile.fetchedAtMs > TILE_MAX_AGE_MS
            }
            if (needed.isEmpty()) return
            // Lazy start so the job is stored before it can run and clear itself.
            val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(needed) }
            fetchJob = job
            job.start()
        }
        Category.entries.forEach { recompute(it) }
    }

    private suspend fun runFetch(keys: List<String>) {
        var failure: FetchException? = null
        try {
            for (key in keys) {
                val places = client.fetch(key)
                tiles[key] = Tile(places, System.currentTimeMillis())
                Timber.i("Tile $key: ${places.size} places")
                // Show results from the nearest tiles straight away.
                Category.entries.forEach { recompute(it) }
            }
        } catch (e: TimeoutCancellationException) {
            failure = FetchException("Timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            failure = e
        } catch (e: Exception) {
            failure = FetchException(e.message ?: e.toString(), serverError = true)
        } finally {
            synchronized(fetchLock) { fetchJob = null }
        }

        if (failure == null) {
            lastSuccessMs = System.currentTimeMillis()
            Settings.lastDownloadMs = lastSuccessMs
            offlineAlertShown = false // connection is back; warn again next time it drops
        } else {
            Timber.w(failure, "Tile download failed")
            lastFailureMs = System.currentTimeMillis()
            lastFailureWasServer = failure.serverError
            val tapWaiting = navigateWhenReady.values.any { it.get() }
            // A waiting tap gets its own message below, so only pop up here for background updates.
            if (!failure.serverError && !tapWaiting && !offlineAlertShown) {
                offlineAlertShown = true
                alert(Category.TOILET, "No connection", "Toilet & water search needs Wi-Fi or your phone")
            }
        }

        for (category in Category.entries) {
            val state = recompute(category)
            if (!navigateWhenReady.getValue(category).get()) continue
            // Leave the tap pending while more tiles are still needed and nothing has failed.
            if (state is FinderState.Searching && failure == null) continue
            navigateWhenReady.getValue(category).set(false)
            when (state) {
                is FinderState.Found -> navigateTo(category, state.place)
                is FinderState.NoneFound -> alert(category, "None found", noneFoundDetail(state))
                is FinderState.Failed -> if (lastFailureWasServer) {
                    alert(category, "Search failed", "Couldn't read the toilet & water data")
                } else {
                    offlineAlertShown = true
                    alert(category, "No connection", "Toilet & water search needs Wi-Fi or your phone")
                }
                else -> Unit
            }
        }
        // Moving fast can need more tiles than one pass fetched.
        if (failure == null) refresh()
    }

    private fun noneFoundDetail(state: FinderState.NoneFound) =
        "Nothing open within ${Geo.formatDistance(state.radiusM.toDouble(), imperial)}"

    private fun navigateTo(category: Category, place: Place) {
        // Already following a route (or another destination): only open the pin, so a
        // toilet stop never silently replaces the planned ride. You choose on the pin screen.
        if (autoNavRunning.get()) return // a second tap mid-sequence would scramble the presses
        val idle = navState is OnNavigationState.NavigationState.Idle
        showPin(category, place)
        if (idle && autoNavRunning.compareAndSet(false, true)) {
            scope.launch {
                try {
                    autoNavigate(category, place)
                } finally {
                    autoNavRunning.set(false)
                }
            }
        }
    }

    private suspend fun autoNavigate(category: Category, place: Place) {
        delay(PIN_SCREEN_OPEN_MS)
        karoo.dispatch(PerformHardwareAction.BottomRightPress) // accept the pin
        delay(BETWEEN_PRESSES_MS)
        karoo.dispatch(PerformHardwareAction.TopRightPress) // move to Navigate
        delay(BETWEEN_PRESSES_MS)
        karoo.dispatch(PerformHardwareAction.BottomRightPress) // start navigation

        // Check it worked; if not, tell the rider to finish it by hand.
        val deadline = System.currentTimeMillis() + CONFIRM_NAV_WITHIN_MS
        while (System.currentTimeMillis() < deadline) {
            if (navState is OnNavigationState.NavigationState.NavigatingToDestination) {
                Timber.i("Auto-navigation to ${place.id} started")
                return
            }
            delay(250)
        }
        Timber.w("Auto-navigation didn't start; nav state is $navState")
        alert(category, "Couldn't start navigation", "Tap the field to try again")
    }

    private fun showPin(category: Category, place: Place) {
        val label = appContext.getString(category.shortLabelRes)
        karoo.dispatch(
            LaunchPinDrop(
                Symbol.POI(
                    id = "loowater-${place.id}",
                    lat = place.location.lat,
                    lng = place.location.lng,
                    type = category.poiType,
                    name = place.name ?: label,
                ),
            ),
        )
    }

    private fun alert(category: Category, title: String, detail: String) {
        karoo.dispatch(
            InRideAlert(
                id = "loowater-${category.name}",
                icon = category.iconRes,
                title = title,
                detail = detail,
                autoDismissMs = 4_000,
                backgroundColor = R.color.alert_bg,
                textColor = R.color.alert_text,
            ),
        )
    }
}
