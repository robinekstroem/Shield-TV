package se.norrsken.tv.core.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Compose-kontexten ar ofta en ContextWrapper runt aktiviteten, inte
 * aktiviteten sjalv. En rak `as Activity` fungerar darfor pa vissa enheter
 * och ger null pa andra.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
