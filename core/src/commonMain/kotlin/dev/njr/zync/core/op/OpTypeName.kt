package dev.njr.zync.core.op

/**
 * The op's wire type name — identical to its `@SerialName` discriminator in [Op]. A single
 * source of truth for callers (audit logging, the transport-log `op_type` column) that
 * previously each independently derived this via `op::class.simpleName`, which drifts from
 * the actual serialized discriminator and isn't guaranteed stable across Kotlin versions.
 */
val Op.typeName: String
    get() = when (this) {
        is Op.SetField -> "set_field"
        is Op.Move -> "move"
        is Op.AddTag -> "add_tag"
        is Op.RemoveTag -> "remove_tag"
        is Op.AddAttachment -> "add_attachment"
        is Op.Tombstone -> "tombstone"
    }
