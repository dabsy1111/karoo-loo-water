package io.github.dan.loowater

import android.content.Context
import android.content.SharedPreferences

/**
 * Rider choices and data status, saved on the Karoo. Shared by the extension service
 * (which reads them) and the settings screen (which changes them); both run in the
 * app's one process.
 */
object Settings {
    private const val PREFS = "loowater"
    private const val KEY_DISABLED = "disabled_types"
    private const val KEY_LAST_DOWNLOAD = "last_download_ms"
    private const val KEY_BUILT = "data_built_epoch"
    private const val KEY_SOURCE = "toilet_map_source"

    private var prefs: SharedPreferences? = null

    @Volatile var disabledTypes: Set<String> = FacilityType.defaultDisabled
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        disabledTypes = p.getStringSet(KEY_DISABLED, null)?.toSet() ?: FacilityType.defaultDisabled
    }

    fun isEnabled(code: String) = code !in disabledTypes

    fun setEnabled(code: String, enabled: Boolean) {
        val next = if (enabled) disabledTypes - code else disabledTypes + code
        disabledTypes = next
        prefs?.edit()?.putStringSet(KEY_DISABLED, next)?.apply()
        NearbyFinder.onSettingsChanged()
    }

    /**
     * Whether [place] may be offered for [category]. Standalone fountains and taps have no
     * Toilet Map type, so the type switches never hide them from the water field; drinking
     * water at a toilet follows that toilet's type.
     */
    fun allows(place: Place, category: Category): Boolean {
        val code = place.type.ifEmpty { FacilityType.UNLISTED }
        if (category == Category.WATER && place.type.isEmpty() && Category.TOILET !in place.categories) return true
        return code !in disabledTypes
    }

    /** When tiles were last downloaded to this Karoo (epoch ms, 0 = never). */
    var lastDownloadMs: Long
        get() = prefs?.getLong(KEY_LAST_DOWNLOAD, 0L) ?: 0L
        set(value) {
            prefs?.edit()?.putLong(KEY_LAST_DOWNLOAD, value)?.apply()
        }

    /** Last data-build time seen online (epoch seconds, 0 = never checked). */
    val dataBuiltEpoch: Long get() = prefs?.getLong(KEY_BUILT, 0L) ?: 0L
    val toiletMapSource: String? get() = prefs?.getString(KEY_SOURCE, null)

    fun saveIndex(index: DataIndex) {
        prefs?.edit()
            ?.putLong(KEY_BUILT, index.builtEpoch)
            ?.putString(KEY_SOURCE, index.toiletMapSource)
            ?.apply()
    }
}
