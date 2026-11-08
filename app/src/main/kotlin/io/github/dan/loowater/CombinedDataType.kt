package io.github.dan.loowater

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One field with both: toilet on the left, water on the right (stacked if the field is
 * narrow). Designed for a full-width, quarter-height slot. Each half is its own tap target
 * and starts navigation to that half's place.
 */
class CombinedDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    private class Half(
        val category: Category,
        val half: Int,
        val labelRow: Int,
        val primary: Int,
        val unit: Int,
        val arrow: Int,
        val secondary: Int,
    )

    private val halves = listOf(
        Half(Category.TOILET, R.id.toilet_half, R.id.toilet_label_row, R.id.toilet_primary, R.id.toilet_unit, R.id.toilet_arrow, R.id.toilet_secondary),
        Half(Category.WATER, R.id.water_half, R.id.water_label_row, R.id.water_primary, R.id.water_unit, R.id.water_arrow, R.id.water_secondary),
    )

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // We draw our own "TOILET" / "WATER" labels, so hide Karoo's field header.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        if (config.preview) {
            val sample = mapOf(
                Category.TOILET to FieldText.Display(Geo.formatDistance(850.0, NearbyFinder.imperial), "open", false, "↗"),
                Category.WATER to FieldText.Display(Geo.formatDistance(320.0, NearbyFinder.imperial), "open", false, "←"),
            )
            emitter.updateView(render(context, config, sample))
            return
        }

        Category.entries.forEach { NearbyFinder.viewAttached(it) }
        val job = CoroutineScope(Dispatchers.Default).launch {
            combine(NearbyFinder.state(Category.TOILET), NearbyFinder.state(Category.WATER)) { toilet, water ->
                mapOf(Category.TOILET to FieldText.of(toilet), Category.WATER to FieldText.of(water))
            }
                // Battery: only redraw when the words on screen change, not on every GPS fix.
                .distinctUntilChanged()
                .collect { shown ->
                emitter.updateView(render(context, config, shown))
                // Karoo drops view updates closer than ~1 s apart; pace ourselves.
                delay(1_000)
            }
        }
        emitter.setCancellable {
            job.cancel()
            Category.entries.forEach { NearbyFinder.viewDetached(it) }
        }
    }

    private fun render(context: Context, config: ViewConfig, shown: Map<Category, FieldText.Display>): RemoteViews {
        // Full-width fields (like the one above a climb graph) go side by side; narrower ones stack.
        val wide = config.gridSize.first >= 45
        val views = RemoteViews(context.packageName, if (wide) R.layout.view_combined_wide else R.layout.view_combined_tall)

        val (width, height) = config.viewSize
        val halfWidth = if (wide) width / 2f else width.toFloat()
        val halfHeight = if (wide) height.toFloat() else height / 2f
        val padding = 16 * context.resources.displayMetrics.density
        // Karoo style: big number, small unit. Sized so "10.5km ↗" fits across the half
        // and the label and status lines still fit above and below.
        val numberPx = minOf(halfHeight * 0.45f, (halfWidth - padding) / 3.9f).coerceAtLeast(14f)
        val unitPx = numberPx * 0.45f
        val showLabel = halfHeight >= 80
        val showStatus = halfHeight >= 105

        for (h in halves) {
            val d = shown.getValue(h.category)
            // "850 m" -> "850" big + "m" small; "--", "…", "none" have no unit.
            val number = d.primary.substringBeforeLast(' ')
            val unit = if (' ' in d.primary) d.primary.substringAfterLast(' ') else ""
            views.setTextViewText(h.primary, number)
            views.setTextViewTextSize(h.primary, TypedValue.COMPLEX_UNIT_PX, numberPx)
            views.setTextViewText(h.unit, unit)
            views.setTextViewTextSize(h.unit, TypedValue.COMPLEX_UNIT_PX, unitPx)
            views.setViewVisibility(h.unit, if (unit.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(h.arrow, d.arrow)
            views.setTextViewTextSize(h.arrow, TypedValue.COMPLEX_UNIT_PX, numberPx)
            views.setViewVisibility(h.arrow, if (d.arrow.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(h.secondary, d.secondary)
            views.setTextColor(h.secondary, context.getColor(if (d.warn) R.color.warn else R.color.field_subtext))
            views.setViewVisibility(h.secondary, if (showStatus) View.VISIBLE else View.GONE)
            views.setViewVisibility(h.labelRow, if (showLabel) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(h.half, TapReceiver.pendingIntent(context, h.category))
        }
        return views
    }

    companion object {
        const val TYPE_ID = "nearest-both"
    }
}
