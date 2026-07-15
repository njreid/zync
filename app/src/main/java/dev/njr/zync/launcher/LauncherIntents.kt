package dev.njr.zync.launcher

import android.content.Intent

/**
 * The intents behind the action bar's app slots (launcher spec L1). Pure builders —
 * category-based selectors resolve to the user's default app for each role; the
 * caller handles `ActivityNotFoundException` (some devices lack e.g. a calendar).
 */
object LauncherIntents {
    /** The default SMS/RCS messages app. */
    fun messages(): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The default calendar app. */
    fun calendar(): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The dialer, ready for a number. */
    fun phone(): Intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
