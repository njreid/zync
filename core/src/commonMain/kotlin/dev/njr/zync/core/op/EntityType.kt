package dev.njr.zync.core.op

import kotlinx.serialization.Serializable

/** The kind of entity an op targets (spec §2). */
@Serializable
enum class EntityType { Node, Context, Tag, Attachment }
