package com.forge.os.domain.agent.planner

import kotlinx.serialization.Serializable

@Serializable
enum class NodeStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}

@Serializable
data class DagNode(
    val id: String,
    val goal: String,
    val dependencies: List<String> = emptyList(),
    val status: NodeStatus = NodeStatus.PENDING,
    val result: String? = null,
    val error: String? = null
)

@Serializable
data class TaskDag(
    val nodes: List<DagNode> = emptyList()
) {
    /**
     * Returns a list of node IDs that have no pending/failed dependencies and are currently PENDING.
     */
    fun getExecutableNodes(): List<DagNode> {
        return nodes.filter { node ->
            node.status == NodeStatus.PENDING &&
            node.dependencies.all { depId ->
                nodes.find { it.id == depId }?.status == NodeStatus.COMPLETED
            }
        }
    }

    /**
     * Returns true if any node has FAILED.
     */
    fun hasFailures(): Boolean {
        return nodes.any { it.status == NodeStatus.FAILED }
    }

    /**
     * Returns true if all nodes are COMPLETED.
     */
    fun isComplete(): Boolean {
        return nodes.isNotEmpty() && nodes.all { it.status == NodeStatus.COMPLETED }
    }

    /**
     * Check if the graph has cycles using DFS.
     */
    fun hasCycles(): Boolean {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun dfs(nodeId: String): Boolean {
            if (recursionStack.contains(nodeId)) return true
            if (visited.contains(nodeId)) return false

            visited.add(nodeId)
            recursionStack.add(nodeId)

            val node = nodes.find { it.id == nodeId }
            node?.dependencies?.forEach { dep ->
                if (dfs(dep)) return true
            }

            recursionStack.remove(nodeId)
            return false
        }

        for (node in nodes) {
            if (dfs(node.id)) return true
        }

        return false
    }

    /**
     * Updates a node and returns a new TaskDag.
     */
    fun updateNode(nodeId: String, update: (DagNode) -> DagNode): TaskDag {
        return copy(nodes = nodes.map { if (it.id == nodeId) update(it) else it })
    }
}
