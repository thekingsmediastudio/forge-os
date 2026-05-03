package com.forge.os.domain.agent.planner

import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.config.ConfigRepository
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DagPlanner @Inject constructor(
    private val apiManager: AiApiManager,
    private val configRepository: ConfigRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val systemPrompt = """
        You are an expert task planning agent. Your job is to take a complex user request and break it down into a Directed Acyclic Graph (DAG) of discrete, executable sub-tasks.
        
        Rules:
        1. Break the task into logical sub-goals.
        2. Identify dependencies. If Task B requires the output of Task A, then Task B depends on Task A.
        3. Independent tasks should have NO dependencies so they can run in parallel.
        4. Keep the graph as shallow as possible. Do not over-complicate.
        5. Output ONLY valid JSON matching the following schema. Do not wrap it in markdown block quotes.
        
        Schema:
        {
          "nodes": [
            {
              "id": "node_1", // A unique string identifier
              "goal": "Search for X", // The precise instruction for the sub-agent
              "dependencies": [] // Array of parent node IDs
            },
            {
              "id": "node_2",
              "goal": "Write a report based on the context",
              "dependencies": ["node_1"]
            }
          ]
        }
    """.trimIndent()

    suspend fun generatePlan(prompt: String): TaskDag {
        val config = configRepository.get().modelRouting
        val spec = apiManager.resolveSpec(config.plannerProvider, config.plannerModel)
        
        Timber.i("DagPlanner generating plan using model: ${spec?.effectiveModel ?: "default"}")

        val response = apiManager.chatWithFallback(
            messages = listOf(ApiMessage(role = "user", content = prompt)),
            systemPrompt = systemPrompt,
            spec = spec,
            mode = com.forge.os.domain.companion.Mode.PLANNER
        )

        val rawContent = response.content?.trim() ?: throw IllegalStateException("Planner returned empty response")
        
        // Clean markdown backticks if the model ignored instructions
        val jsonStr = if (rawContent.startsWith("```")) {
            rawContent.substringAfter("```json").substringAfter("```").substringBeforeLast("```").trim()
        } else {
            rawContent
        }

        try {
            val dag = json.decodeFromString<TaskDag>(jsonStr)
            
            if (dag.hasCycles()) {
                throw IllegalStateException("Generated DAG contains cycles")
            }
            if (dag.nodes.isEmpty()) {
                throw IllegalStateException("Generated DAG has no nodes")
            }
            
            Timber.i("DagPlanner successfully generated graph with ${dag.nodes.size} nodes.")
            return dag
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse DAG JSON: $jsonStr")
            throw IllegalStateException("Failed to parse DAG: ${e.message}")
        }
    }
}
