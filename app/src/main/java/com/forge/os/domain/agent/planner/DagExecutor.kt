package com.forge.os.domain.agent.planner

import com.forge.os.domain.agents.DelegationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DagExecutor @Inject constructor(
    private val delegationManager: DelegationManager
) {
    /**
     * Executes the DAG, returning the final concatenated result of all nodes.
     * Throws an exception if a node fails and cannot be recovered.
     */
    suspend fun execute(initialDag: TaskDag, parentTraceId: String? = null): String = coroutineScope {
        var currentDag = initialDag
        val completedResults = mutableMapOf<String, String>()

        while (!currentDag.isComplete()) {
            if (currentDag.hasFailures()) {
                throw IllegalStateException("DAG execution aborted due to node failure.")
            }

            val readyNodes = currentDag.getExecutableNodes()

            if (readyNodes.isEmpty()) {
                // If we aren't complete, have no failures, and no ready nodes... we are stuck.
                // This shouldn't happen unless there's a bug in the DAG generation (e.g. cycle missed)
                // or all active nodes are RUNNING.
                // We'll just wait a bit and re-evaluate, since running nodes will update the DAG when done.
                if (currentDag.nodes.none { it.status == NodeStatus.RUNNING }) {
                    throw IllegalStateException("DAG deadlock: No nodes running and no nodes ready.")
                }
                delay(500)
                continue
            }

            // Mark ready nodes as RUNNING
            readyNodes.forEach { node ->
                currentDag = currentDag.updateNode(node.id) { it.copy(status = NodeStatus.RUNNING) }
            }

            // Execute them concurrently
            val deferredResults = readyNodes.map { node ->
                async {
                    Timber.i("DagExecutor: Starting node ${node.id} - ${node.goal}")
                    
                    // Build context from dependencies
                    val dependencyContext = if (node.dependencies.isEmpty()) {
                        ""
                    } else {
                        buildString {
                            appendLine("--- DEPENDENCY OUTPUTS ---")
                            node.dependencies.forEach { depId ->
                                appendLine("[From $depId]:")
                                appendLine(completedResults[depId] ?: "No output")
                                appendLine()
                            }
                        }
                    }

                    val outcome = delegationManager.spawnAndAwait(
                        goal = node.goal,
                        context = dependencyContext,
                        parentId = parentTraceId, // Pass the trace ID down for the snapshot debugger!
                        tags = listOf("dag_node", node.id)
                    )

                    Pair(node.id, outcome)
                }
            }

            // Await the batch of concurrently started nodes
            val results = deferredResults.awaitAll()

            // Update the DAG state
            results.forEach { (nodeId, outcome) ->
                if (outcome.success) {
                    val resultText = outcome.agent.result ?: outcome.transcript
                    completedResults[nodeId] = resultText
                    currentDag = currentDag.updateNode(nodeId) { 
                        it.copy(status = NodeStatus.COMPLETED, result = resultText) 
                    }
                    Timber.i("DagExecutor: Node $nodeId completed.")
                } else {
                    currentDag = currentDag.updateNode(nodeId) { 
                        it.copy(status = NodeStatus.FAILED, error = outcome.agent.error) 
                    }
                    Timber.e("DagExecutor: Node $nodeId failed: ${outcome.agent.error}")
                }
            }
        }

        // Execution complete! Build a combined summary.
        buildString {
            appendLine("DAG Execution Complete. Final state:")
            currentDag.nodes.forEach { node ->
                appendLine("=== Node: ${node.id} (${node.goal}) ===")
                appendLine(node.result ?: "No result")
                appendLine()
            }
        }
    }
}
