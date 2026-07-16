package dev.njr.zync.core.content

/**
 * The shared field-name vocabulary for content nodes (review finding #13, scoped:
 * constants only, no domain framework). One definition for `core` consumers,
 * `:web` commands/read model, and capture — typos stop compiling.
 */
object Fields {
    const val KIND = "kind"
    const val TITLE = "title"
    const val NOTES = "notes"
    const val STATUS = "status"
    const val NAME = "name" // contexts

    /** Hide-until (GTD defer): epoch millis; task resurfaces at this time. */
    const val DEFER_UNTIL = "deferUntil"

    /** Hard due date: epoch millis pinned to UTC NOON of the due day (see DueDates). */
    const val DUE_DATE = "dueDate"

    /** A person's display name (any contact link stays device-local, never synced). */
    const val PERSON = "person"
}
