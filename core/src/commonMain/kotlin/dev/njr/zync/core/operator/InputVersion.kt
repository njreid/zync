package dev.njr.zync.core.operator

import dev.njr.zync.core.clock.Hlc

/**
 * The operator idempotency identity "V" (spec §7): a firing is keyed by
 * `(operatorId, entityId, inputVersion)`, and re-fires only when the version
 * changes (re-entrancy for late-arriving offline history).
 *
 * The version is a deterministic fold of exactly what the operator *reads*:
 * the HLCs of the entity's read-scoped registers, the HLCs of its tag rows,
 * and its integrated parent. Scoping the version to read fields (rather than
 * every register) is the "material-change gating" from the spec pressure test
 * (§9a): edits to fields an operator never looks at don't re-run the LLM, and
 * an operator's own writes never change its own input version.
 *
 * Shared in `core` so any replica computes the identical key for the same state.
 */
object InputVersion {
    /**
     * Canonical version string for one entity as seen by one operator.
     *
     * @param fields  register HLCs keyed by field name, restricted to the fields
     *                the operator's read scope declares.
     * @param tags    tag-row HLCs keyed by context id (present and absent rows —
     *                a removal is a change too).
     * @param parent  the integrated parent id, or null for root.
     */
    fun of(fields: Map<String, Hlc>, tags: Map<String, Hlc>, parent: String?): String {
        val parts = ArrayList<String>(fields.size + tags.size + 1)
        fields.entries.sortedBy { it.key }.mapTo(parts) { "f:${it.key}=${it.value.pack()}" }
        tags.entries.sortedBy { it.key }.mapTo(parts) { "t:${it.key}=${it.value.pack()}" }
        parts += "p:${parent ?: "-"}"
        return parts.joinToString("|")
    }
}
