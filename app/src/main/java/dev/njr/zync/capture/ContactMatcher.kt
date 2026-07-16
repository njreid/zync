package dev.njr.zync.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Best-effort phone-contact match for an extracted first name (capture spec): the
 * chip shows the matched display name; the op log stores ONLY the name (the contact
 * link never syncs). Silent no-op without READ_CONTACTS — the raw name still works.
 */
object ContactMatcher {
    fun match(context: Context, name: String): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || name.isBlank()) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("$name%"),
                "${ContactsContract.Contacts.TIMES_CONTACTED} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
