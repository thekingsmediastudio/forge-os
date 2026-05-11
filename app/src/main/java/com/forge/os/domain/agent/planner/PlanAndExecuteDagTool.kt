package com.forge.os.domain.agent.planner

import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agents.AgentContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanAndExecuteDagTool @Inject constructor(
    private val dagPlanner: DagPlanner,
    private val dagExecutor: DagExecutor
) {
    val definition: ToolDefinition = com.forge.os.domain.agent.tool(
        name = "plan_and_execute_dag",
        description = "Takes a highly complex, multi-step goal and breaks it down into a Directed Acyclic Graph (DAG) of sub-tasks. Executes independent tasks concurrently and dependent tasks sequentially. Use this ONLY when a task requires extensive parallel research, multiple distinct agents working simultaneously, or is too complex to handle sequentially in a single ReAct loop. DO NOT use for simple tasks.",
        properties = com.forge.os.domain.agent.params(
            "complex_goal" to "string:The comprehensive goal to achieve. Include all necessary context, requirements, and formatting rules that the sub-agents will need to succeed."
        ),
        required = listOf("complex_goal")
    )

    suspend fun execute(args: JsonObject): String {
        val goal = args["complex_goal"]?.jsonPrimitive?.content
            ?: return "Error: complex_goal is required."

        return try {
            val dag = dagPlanner.generatePlan(goal)
            
            // Pass the current agent's trace ID down so the snapshot debugger 
            // correctly nests the DAG's sub-agents under the current tool call.
            val currentTraceId = currentCoroutineContext()[AgentContext]?.agentId
            
            val result = dagExecutor.execute(dag, currentTraceId)
            result
        } catch (e: Exception) {
            "DAG Planning/Execution Failed: ${e.message}\nConsider breaking down the task manually or asking the user for clarification."
        }
    }
}
