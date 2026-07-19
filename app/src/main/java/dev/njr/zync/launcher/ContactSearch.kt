package dev.njr.zync.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** One matched phone contact in the search overlay. */
data class ContactHit(val id: Long, val lookupKey: String, val name: String, val photoUri: String?)

/**
 * Contact rows for the search overlay: name-substring matches, frecency-ranked
 * (TIMES_CONTACTED). Rows appear only when READ_CONTACTS is already granted —
 * the in-context permission ask lives in the overlay, not here. Everything is
 * defensive: any provider failure degrades to no contact rows.
 */
object ContactSearch {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Top [limit] contacts matching [query]; empty without permission or under 2 chars. */
    fun search(context: Context, query: String, limit: Int = 3): List<ContactHit> {
        val q = query.trim()
        if (!hasPermission(context) || q.length < 2) return emptyList()
        // TIMES_CONTACTED ranking may be rejected on newer API levels (deprecated,
        // zeroed on some builds) — fall back to a plain name sort rather than fail.
        val hits = runCatching { queryContacts(context, q, sortByFrecency = true) }
            .recoverCatching { queryContacts(context, q, sortByFrecency = false) }
            .getOrDefault(emptyList())
        return hits.take(limit)
    }

    private fun queryContacts(context: Context, q: String, sortByFrecency: Boolean): List<ContactHit> {
        val name = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        val sort = if (sortByFrecency) "${ContactsContract.Contacts.TIMES_CONTACTED} DESC, $name" else name
        return context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                name,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ),
            "$name LIKE ?",
            arrayOf("%$q%"),
            sort,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val lookupKey = c.getString(1) ?: continue
                    val display = c.getString(2) ?: continue
                    add(ContactHit(c.getLong(0), lookupKey, display, c.getString(3)))
                }
            }
        } ?: emptyList()
    }

    /** Open the contact card in the contacts app. */
    fun viewIntent(hit: ContactHit): Intent = Intent(
        Intent.ACTION_VIEW,
        ContactsContract.Contacts.getLookupUri(hit.id, hit.lookupKey),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
