package com.example.data.repository

import android.util.Log
import com.example.data.model.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

private const val TAG = "SayonAiRepository"

class AiRepository {

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are Sayon AI, a brilliant, friendly, highly intuitive, and insightful personal AI companion. " +
                    "Your name is Sayon AI. You assist users with thoughtful conversation, creative ideas, problem solving, " +
                    "coding, learning, analysis, and productivity. Keep your tone engaging, modern, empathetic, and clear. " +
                    "Format code snippets with markdown syntax and explain complex topics simply when needed."
                )
            }
        )
    }

    /**
     * Streams the generated AI response chunk-by-chunk for low latency and smooth typing animation.
     */
    fun streamResponse(
        prompt: String,
        recentHistory: List<ChatMessage> = emptyList()
    ): Flow<String> = flow {
        try {
            // Build conversation history (up to last 10 messages for rich conversational context)
            val historyContent = recentHistory.takeLast(10).map { msg ->
                val role = if (msg.sender == "user") "user" else "model"
                content(role) {
                    text(msg.content)
                }
            }

            val chat = model.startChat(history = historyContent)

            withTimeout(60_000L) {
                chat.sendMessageStream(prompt).collect { chunk ->
                    val chunkText = chunk.text
                    if (!chunkText.isNullOrEmpty()) {
                        emit(chunkText)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Sayon AI streaming response timed out", e)
            throw IllegalStateException("Sayon AI took longer than expected to respond. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sayon AI generation failed", e)
            throw e
        }
    }
}
