package dev.njr.zync.domain

import dev.njr.zync.data.ContextEntity
import dev.njr.zync.data.NodeContextCrossRef
import dev.njr.zync.data.NodeEntity
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import dev.njr.zync.data.ZyncDatabase
import kotlinx.coroutines.flow.Flow

class NodeRepository(
    private val db: ZyncDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.nodeDao()

    suspend fun get(id: Long): NodeEntity? = dao.getById(id)
    fun observeChildren(parentId: Long): Flow<List<NodeEntity>> = dao.observeChildren(parentId)
    fun observeRoots(): Flow<List<NodeEntity>> = dao.observeRoots()

    suspend fun quickAddTask(title: String): Long =
        createNode(NodeKind.TASK, ZyncDatabase.INBOX_ID, title)

    suspend fun createNode(kind: NodeKind, parentId: Long?, title: String): Long {
        val parentKind = parentId?.let { requireNode(it).kind }
        require(NestingRules.canNest(kind, parentKind)) {
            "$kind cannot nest under ${parentKind ?: "root"}"
        }
        return dao.insert(NodeEntity(kind = kind, parentId = parentId, title = title, createdAt = now()))
    }

    suspend fun rename(id: Long, title: String) =
        updateMutable(id) { it.copy(title = title) }

    suspend fun setNotes(id: Long, notes: String) =
        updateMutable(id) { it.copy(notes = notes) }

    suspend fun setDefer(id: Long, until: Long?) =
        updateMutable(id) { it.copy(deferUntil = until) }

    suspend fun move(id: Long, newParentId: Long?) {
        val node = requireMutable(id)
        val parentKind = newParentId?.let { requireNode(it).kind }
        require(NestingRules.canNest(node.kind, parentKind)) {
            "${node.kind} cannot nest under ${parentKind ?: "root"}"
        }
        require(newParentId == null || !isDescendantOrSelf(newParentId, id)) {
            "Cannot move a node into its own subtree"
        }
        dao.update(node.copy(parentId = newParentId))
    }

    suspend fun complete(id: Long) =
        updateMutable(id) { it.copy(status = NodeStatus.DONE, completedAt = now()) }

    suspend fun reopen(id: Long) =
        updateMutable(id) { it.copy(status = NodeStatus.ACTIVE, completedAt = null) }

    suspend fun convertTaskToProject(taskId: Long, targetFolderId: Long) {
        val task = requireMutable(taskId)
        require(task.kind == NodeKind.TASK) { "Only tasks can become projects" }
        val target = requireNode(targetFolderId)
        require(target.kind == NodeKind.FOLDER) { "Projects must live in a folder" }
        dao.update(task.copy(kind = NodeKind.PROJECT, parentId = targetFolderId))
    }

    suspend fun trash(id: Long) =
        updateMutable(id) { it.copy(status = NodeStatus.DROPPED) }

    fun observeTasksInContext(contextId: Long): Flow<List<NodeEntity>> =
        dao.observeTasksInContext(contextId, now())

    suspend fun createContext(name: String): Long =
        db.contextDao().insert(ContextEntity(name = name))

    fun observeContexts(): Flow<List<ContextEntity>> = db.contextDao().observeAll()

    fun observeContextsFor(nodeId: Long): Flow<List<ContextEntity>> =
        db.contextDao().observeContextsFor(nodeId)

    suspend fun setContexts(nodeId: Long, contextIds: Set<Long>) {
        requireNode(nodeId)
        db.contextDao().clearTags(nodeId)
        contextIds.forEach { db.contextDao().tag(NodeContextCrossRef(nodeId, it)) }
    }

    private suspend fun requireNode(id: Long): NodeEntity =
        requireNotNull(dao.getById(id)) { "No node $id" }

    private suspend fun requireMutable(id: Long): NodeEntity {
        val node = requireNode(id)
        require(!node.builtin) { "Built-in folders cannot be modified" }
        return node
    }

    private suspend fun updateMutable(id: Long, transform: (NodeEntity) -> NodeEntity) =
        dao.update(transform(requireMutable(id)))

    /** True if [candidate] is [ancestorId] itself or inside its subtree. */
    private suspend fun isDescendantOrSelf(candidate: Long, ancestorId: Long): Boolean {
        var cursor: Long? = candidate
        while (cursor != null) {
            if (cursor == ancestorId) return true
            cursor = dao.getById(cursor)?.parentId
        }
        return false
    }
}
