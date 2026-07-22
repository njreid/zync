package dev.njr.zync.core.content

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The string content of a register value, or null if the field is absent or cleared.
 * `JsonNull` IS a `JsonPrimitive` whose content is the literal "null", so it must be
 * filtered — this is the single guard every read model / store / operator shares, so
 * they can't drift on the subtlety.
 */
fun JsonElement?.stringContent(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
