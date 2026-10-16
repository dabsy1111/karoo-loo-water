package io.github.dan.loowater

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class LooWaterExtension : KarooExtension(EXTENSION_ID, "1.0") {

    private val karooSystem by lazy { KarooSystemService(this) }

    override val types by lazy {
        Category.entries.map { FinderDataType(extension, it) }
    }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        karooSystem.connect { connected -> Timber.i("Karoo system connected=$connected") }
        NearbyFinder.start(this, karooSystem)
    }

    override fun onDestroy() {
        NearbyFinder.stop()
        karooSystem.disconnect()
        super.onDestroy()
    }

    /** Hardware bonus buttons (SRAM AXS / Blips) can trigger the same routing as a tap. */
    override fun onBonusAction(actionId: String) {
        Category.fromActionId(actionId)?.let { NearbyFinder.onTap(it) }
    }

    /** Marks the current toilet / water targets on the map page. */
    override fun startMap(emitter: Emitter<MapEffect>) {
        val ids = Category.entries.associateWith { "loowater-map-${it.name}" }
        val job = CoroutineScope(Dispatchers.Default).launch {
            val targets = Category.entries.map { cat ->
                NearbyFinder.state(cat).map { cat to (it as? FinderState.Found)?.place }
            }
            combine(targets) { it.toList() }
                // Only redraw when a target actually changes, not on every distance tick.
                .distinctUntilChanged { a, b -> a.map { it.second?.id } == b.map { it.second?.id } }
                .collect { pairs ->
                    val shown = pairs.mapNotNull { (cat, place) ->
                        place?.let {
                            Symbol.POI(
                                id = ids.getValue(cat),
                                lat = it.location.lat,
                                lng = it.location.lng,
                                type = cat.poiType,
                                name = it.name ?: getString(cat.shortLabelRes),
                            )
                        }
                    }
                    val hidden = pairs.filter { it.second == null }.map { ids.getValue(it.first) }
                    if (hidden.isNotEmpty()) emitter.onNext(HideSymbols(hidden))
                    if (shown.isNotEmpty()) emitter.onNext(ShowSymbols(shown))
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val EXTENSION_ID = "loowater"
    }
}
