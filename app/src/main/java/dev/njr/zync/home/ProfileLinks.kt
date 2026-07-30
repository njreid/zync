package dev.njr.zync.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager

/**
 * Opening an agenda event's URL in the RIGHT profile.
 *
 * Android gives a normal launcher no way to hand a *data* URL straight to another profile's
 * app: [android.content.pm.LauncherApps.startMainActivity] and `CrossProfileApps` only launch a
 * MAIN activity, dropping the URL. The one path that crosses the profile boundary with the URL
 * intact is the system resolver — so for a WORK event we present it via [Intent.createChooser].
 * On a managed device whose work profile has cross-profile intent filters (the enterprise
 * default for web links), the work app (e.g. work Zoom) shows up in the resolver's Work tab and
 * the system launches it in the work profile with the meeting URL. A personal event — or a
 * device with no work profile — opens directly with no extra tap.
 */
object ProfileLinks {
    fun open(context: Context, url: String, work: Boolean): Boolean = runCatching {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (work && hasWorkProfile(context)) {
            context.startActivity(
                Intent.createChooser(view, "Open in work profile").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            context.startActivity(view)
        }
        true
    }.getOrDefault(false)

    private fun hasWorkProfile(context: Context): Boolean = runCatching {
        (context.getSystemService(UserManager::class.java)?.userProfiles?.size ?: 1) > 1
    }.getOrDefault(false)
}
