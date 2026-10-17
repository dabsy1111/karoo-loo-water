package io.github.dan.loowater

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A graphical data field showing distance + direction to the nearest open place.
 * The whole field is tappable: a tap opens Karoo's pin screen with Navigate.
 */
class FinderDataType(
    extension: String,
    private val category: Category,
) : DataTypeImpl(extension, category.typeId) {

    /** Also expose the distance (metres) as a numeric stream, for anything that wants it. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            NearbyFinder.state(category).collect { state ->
                when (state) {
                    is FinderState.Found -> emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to state.distanceM)),
                        ),
                    )
                    is FinderState.WaitingForGps -> emitter.onNext(StreamState.Searching)
                    else -> emitter.onNext(StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (config.preview) {
            // Page editor: show a static sample so it's recognisable.
            emitter.updateView(render(context, config, FieldText.Display(sampleDistance(), "NE · open", false)))
            return
        }
        NearbyFinder.viewAttached(category)
        val job = CoroutineScope(Dispatchers.Default).launch {
            // collect (not collectLatest): StateFlow conflates during the delay, so the newest state
            // is always drawn; collectLatest would cancel the delay and hit ViewEmitter's 900 ms drop.
            NearbyFinder.state(category).collect { state ->
                emitter.updateView(render(context, config, display(state)))
                // Karoo drops view updates closer than ~1 s apart; pace ourselves.
                delay(1_000)
            }
        }
        emitter.setCancellable {
            job.cancel()
            NearbyFinder.viewDetached(category)
        }
    }

    private fun sampleDistance() = Geo.formatDistance(850.0, NearbyFinder.imperial)

    private fun display(state: FinderState): FieldText.Display = FieldText.of(state).let {
        // One-field layout: direction goes on the second line.
        FieldText.Display(it.primary, listOf(it.arrow, it.secondary).filter(String::isNotEmpty).joinToString(" · "), it.warn)
    }

    private fun render(context: Context, config: ViewConfig, d: FieldText.Display): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.view_finder)
        views.setImageViewResource(R.id.icon, category.iconRes)
        views.setTextViewText(R.id.primary, d.primary)
        views.setTextViewText(R.id.secondary, d.secondary)
        views.setTextColor(
            R.id.secondary,
            context.getColor(if (d.warn) R.color.warn else R.color.field_subtext),
        )

        // Match Karoo's own number size for this field, leaving room for the second line.
        val primarySp = (config.textSize * 0.8f).coerceIn(16f, 54f)
        views.setTextViewTextSize(R.id.primary, TypedValue.COMPLEX_UNIT_SP, primarySp)

        // Tiny fields (1/6 height or less) only have room for one line.
        val tiny = config.gridSize.second <= 10
        views.setViewVisibility(R.id.secondary, if (tiny) View.GONE else View.VISIBLE)

        val gravity = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        }
        views.setInt(R.id.root, "setGravity", gravity or Gravity.CENTER_VERTICAL)
        views.setInt(R.id.text_block, "setGravity", gravity)

        views.setOnClickPendingIntent(R.id.root, TapReceiver.pendingIntent(context, category))
        return views
    }
}
