package io.github.dan.loowater

import android.app.Activity
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The extension's own screen, opened from the Karoo's Extensions page: data status
 * and on/off switches for each Toilet Map facility type.
 */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val karoo by lazy { KarooSystemService(this) }

    private lateinit var builtView: TextView
    private lateinit var downloadedView: TextView
    private lateinit var sourceView: TextView

    /** null = not checked yet, false = last check failed. */
    private var onlineCheck: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_settings)

        builtView = findViewById(R.id.data_built)
        downloadedView = findViewById(R.id.data_downloaded)
        sourceView = findViewById(R.id.data_source)

        findViewById<Button>(R.id.reload).setOnClickListener {
            NearbyFinder.reloadData()
            Toast.makeText(this, "Data will download again next time the fields are on screen", Toast.LENGTH_LONG).show()
            checkOnline()
        }

        buildTypeSwitches(findViewById(R.id.type_list))
        karoo.connect()
    }

    override fun onResume() {
        super.onResume()
        showStatus()
        checkOnline()
    }

    override fun onDestroy() {
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    private fun buildTypeSwitches(container: LinearLayout) {
        val minHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics).toInt()
        for (type in FacilityType.all) {
            val row = Switch(this).apply {
                text = type.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(getColor(R.color.screen_text))
                this.minHeight = minHeight
                isChecked = Settings.isEnabled(type.code)
                setOnCheckedChangeListener { _, on -> Settings.setEnabled(type.code, on) }
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** Fetches index.json to find when the data was last built on GitHub. */
    private fun checkOnline() {
        if (!TileClient.configured) {
            onlineCheck = false
            showStatus()
            return
        }
        scope.launch {
            onlineCheck = try {
                Settings.saveIndex(TileClient(karoo).fetchIndex())
                true
            } catch (e: TimeoutCancellationException) {
                false
            } catch (e: CancellationException) {
                throw e // screen closed
            } catch (e: Exception) {
                false // offline, or a bad reply
            }
            showStatus()
        }
    }

    private fun showStatus() {
        val built = Settings.dataBuiltEpoch * 1000
        builtView.text = when {
            !TileClient.configured -> "Data address not set (gradle.properties)"
            built == 0L && onlineCheck == null -> "Data built: checking…"
            built == 0L -> "Data built: unknown (offline)"
            onlineCheck == false -> "Data built: ${dateTime(built)} (offline, last seen)"
            else -> "Data built: ${dateTime(built)}"
        }

        val downloaded = Settings.lastDownloadMs
        downloadedView.text = if (downloaded == 0L) {
            "Downloaded to this Karoo: not yet"
        } else {
            val ago = DateUtils.getRelativeTimeSpanString(downloaded, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            "Downloaded to this Karoo: $ago"
        }

        sourceView.text = Settings.toiletMapSource?.let { "Toilet Map: $it" } ?: ""
    }

    private fun dateTime(ms: Long): String =
        SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(ms))
}
