package com.example.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.repository.AiRepository
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

class ChatViewModel(
    application: Application,
    private val userId: String
) : AndroidViewModel(application) {

    private val chatRepo = ChatRepository(application)
    private val aiRepo = AiRepository()

    // Real-time chat sessions list
    val sessions: StateFlow<List<ChatSession>> = chatRepo.observeSessions(userId)
        .catch { error ->
            Log.e(TAG, "Error observing sessions", error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var messagesObservationJob: Job? = null
    private var generationJob: Job? = null

    init {
        // Automatically select first available session or create one when list changes
        viewModelScope.launch {
            sessions.collect { list ->
                if (_currentSessionId.value == null && list.isNotEmpty()) {
                    selectSession(list.first().id)
                }
            }
        }
    }

    fun selectSession(sessionId: String) {
        if (_currentSessionId.value == sessionId) return
        _currentSessionId.value = sessionId
        observeCurrentSessionMessages(sessionId)
    }

    private fun observeCurrentSessionMessages(sessionId: String) {
        messagesObservationJob?.cancel()
        messagesObservationJob = viewModelScope.launch {
            chatRepo.observeMessages(userId, sessionId)
                .catch { error ->
                    Log.e(TAG, "Error observing messages for session $sessionId", error)
                    _errorMessage.value = "Failed to load messages."
                }
                .collect { list ->
                    _messages.value = list
                }
        }
    }

    fun createNewSession(initialTitle: String = "New Chat", onCreated: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val newId = chatRepo.createSession(userId, initialTitle)
                selectSession(newId)
                onCreated?.invoke(newId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                _errorMessage.value = "Failed to create new conversation."
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatRepo.deleteSession(userId, sessionId)
                if (_currentSessionId.value == sessionId) {
                    val remaining = sessions.value.filter { it.id != sessionId }
                    if (remaining.isNotEmpty()) {
                        selectSession(remaining.first().id)
                    } else {
                        _currentSessionId.value = null
                        _messages.value = emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
                _errorMessage.value = "Failed to delete conversation."
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun sendMessage(userPrompt: String) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _streamingText.value = ""
            _errorMessage.value = null

            try {
                // Ensure active session
                var activeSessionId = _currentSessionId.value
                if (activeSessionId == null) {
                    val titleSnippet = trimmed.take(30).replace("\n", " ").trim()
                    activeSessionId = chatRepo.createSession(userId, titleSnippet)
                    selectSession(activeSessionId)
                }

                // 1. Save user prompt to Firestore
                chatRepo.addMessage(
                    userId = userId,
                    sessionId = activeSessionId,
                    sender = "user",
                    content = trimmed
                )

                // 2. Stream AI response from Sayon AI (Gemini 3.8 Flash)
                val fullResponse = StringBuilder()
                val currentHistory = _messages.value

                aiRepo.streamResponse(prompt = trimmed, recentHistory = currentHistory)
                    .collect { chunk ->
                        fullResponse.append(chunk)
                        _streamingText.value = fullResponse.toString()
                    }

                val finalAiText = fullResponse.toString().trim()
                if (finalAiText.isNotEmpty()) {
                    // 3. Save Sayon AI message to Firestore
                    chatRepo.addMessage(
                        userId = userId,
                        sessionId = activeSessionId,
                        sender = "sayon",
                        content = finalAiText
                    )
                } else {
                    _errorMessage.value = "Sayon AI returned an empty response."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sayon AI inference error", e)
                _errorMessage.value = e.localizedMessage ?: "Sayon AI could not answer right now."
            } finally {
                _streamingText.value = ""
                _isGenerating.value = false
            }
        }
    }

    fun stopGenerating() {
        generationJob?.cancel()
        _isGenerating.value = false
        _streamingText.value = ""
    }
}
