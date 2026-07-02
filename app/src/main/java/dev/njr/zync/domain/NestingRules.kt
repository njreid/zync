package dev.njr.zync.domain

import dev.njr.zync.data.NodeKind

object NestingRules {
    /** parent == null means root level. Spec §3.1. */
    fun canNest(child: NodeKind, parent: NodeKind?): Boolean = when (child) {
        NodeKind.FOLDER -> parent == null || parent == NodeKind.FOLDER
        NodeKind.PROJECT -> parent == NodeKind.FOLDER
        NodeKind.TASK -> parent != null
    }
}
