package com.wildguard.app.llm.agent

import com.wildguard.app.llm.provider.LlmProvider
import kotlinx.coroutines.withTimeout

class AgentRunner(
    private val registry: ToolRegistry,
    private val logger: AgentLogger,
    private val maxIterations: Int = 10,
    private val wallClockTimeoutMs: Long = 60_000L
) {

    suspend fun run(
        userMessage: String,
        presetTools: List<String>,
        systemPrompt: String,
        provider: LlmProvider,
        ctx: ToolContext,
        state: ConversationState,
        onTurnAppended: (AgentTurn) -> Unit = {}
    ): Result<Unit> = try {
        val userTurn = AgentTurn.User(content = userMessage)
        state.append(userTurn)
        logger.appendTurn(userTurn)
        onTurnAppended(userTurn)

        val schemas = registry.schemasFor(presetTools)

        withTimeout(wallClockTimeoutMs) {
            var iterations = 0
            while (iterations < maxIterations) {
                iterations++
                val messages = state.toProviderMessages(systemPrompt)

                val response = provider.generateWithTools(messages, schemas, systemPrompt)

                if (response.error != null) {
                    val errorTurn = AgentTurn.Error(message = response.error)
                    state.append(errorTurn)
                    logger.appendTurn(errorTurn)
                    onTurnAppended(errorTurn)
                    return@withTimeout
                }

                if (response.hasToolCalls) {
                    val thoughtTurn = AgentTurn.AssistantThought(
                        thought = response.text ?: "",
                        toolCalls = response.toolCalls,
                        tokensUsed = response.tokensUsed
                    )
                    state.append(thoughtTurn)
                    logger.appendTurn(thoughtTurn)
                    onTurnAppended(thoughtTurn)

                    for (call in response.toolCalls) {
                        val dispatchResult = registry.dispatch(
                            name = call.name,
                            args = call.args,
                            ctx = ctx
                        )
                        val resultTurn = AgentTurn.ToolResult(
                            toolCallId = call.id,
                            toolName = call.name,
                            result = dispatchResult.result,
                            elapsedMs = dispatchResult.elapsedMs,
                            isError = dispatchResult.isError
                        )
                        state.append(resultTurn)
                        logger.appendTurn(resultTurn)
                        onTurnAppended(resultTurn)
                    }
                } else {
                    val finalTurn = AgentTurn.Final(
                        content = response.text ?: "",
                        tokensUsed = response.tokensUsed
                    )
                    state.append(finalTurn)
                    logger.appendTurn(finalTurn)
                    onTurnAppended(finalTurn)
                    return@withTimeout
                }
            }

            val capTurn = AgentTurn.Final(content = "[Stopped at $maxIterations-iteration cap]")
            state.append(capTurn)
            logger.appendTurn(capTurn)
            onTurnAppended(capTurn)
        }
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        val timeoutTurn = AgentTurn.Error(message = "Agent timed out after ${wallClockTimeoutMs / 1000}s")
        state.append(timeoutTurn)
        logger.appendTurn(timeoutTurn)
        Result.failure(e)
    } catch (e: Exception) {
        val errorTurn = AgentTurn.Error(message = e.message ?: "Unknown error")
        state.append(errorTurn)
        logger.appendTurn(errorTurn)
        Result.failure(e)
    }
}
