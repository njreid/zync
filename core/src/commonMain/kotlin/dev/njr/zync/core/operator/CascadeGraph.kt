package dev.njr.zync.core.operator

/**
 * One operator's declared dataflow surface, for static cascade analysis:
 * [reads] are the fields its read scope consults (and that feed its
 * [InputVersion]); [writes] are its write-scope fields; [refireable] is true
 * when the operator can fire repeatedly for the same entity
 * ([TriggerKind.EntityChangesInScope]) — an enters-scope operator fires at
 * most once per entity, so it can never sustain a loop.
 */
data class OperatorIo(
    val id: String,
    val reads: Set<String>,
    val writes: Set<String>,
    val refireable: Boolean,
)

/**
 * Termination guarantee, part 1 (spec §7): detect cycles between operators
 * from their *declared* scopes before any of them runs. There is an edge
 * A → B when a field A may write is a field B reads **and** B can re-fire;
 * a cycle in that graph is a configuration that could ping-pong forever.
 * Self-edges are ignored — an operator's own output never re-triggers it
 * (the actor check in the runtime). Fuel budgets remain the runtime backstop
 * for anything this static view can't see.
 */
object CascadeGraph {
    /** Returns one cycle as an id path (`[a, b, a]`), or null if acyclic. */
    fun findCycle(operators: List<OperatorIo>): List<String>? {
        val edges = operators.associate { from ->
            from.id to operators.filter { to ->
                to.id != from.id && to.refireable && from.writes.any { it in to.reads }
            }.map { it.id }
        }
        val state = HashMap<String, Int>() // 0 unvisited, 1 on stack, 2 done
        val stack = ArrayList<String>()

        fun dfs(node: String): List<String>? {
            state[node] = 1
            stack += node
            for (next in edges.getValue(node)) {
                when (state[next] ?: 0) {
                    0 -> dfs(next)?.let { return it }
                    1 -> return stack.subList(stack.indexOf(next), stack.size) + next
                }
            }
            stack.removeAt(stack.lastIndex)
            state[node] = 2
            return null
        }

        for (op in operators) {
            if ((state[op.id] ?: 0) == 0) dfs(op.id)?.let { return it }
        }
        return null
    }
}
