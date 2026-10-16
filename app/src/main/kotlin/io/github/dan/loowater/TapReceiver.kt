package io.github.dan.loowater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/** Receives taps on the data fields (via PendingIntent) and hands them to [NearbyFinder]. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val category = intent.getStringExtra(EXTRA_CATEGORY)
            ?.let { name -> Category.entries.firstOrNull { it.name == name } }
            ?: return
        Timber.i("Tap on $category field")
        NearbyFinder.onTap(category)
    }

    companion object {
        const val ACTION_TAP = "io.github.dan.loowater.TAP"
        const val EXTRA_CATEGORY = "category"
    }
}
