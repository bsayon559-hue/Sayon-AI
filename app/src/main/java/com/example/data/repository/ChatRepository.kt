package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ChatRepository(private val db: FirebaseFirestore) {

    // Resolves database ID dynamically from generated firebase_applet_config.xml
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before performing database operations.")
    }

    /**
     * Observes all chat sessions for the current user in real time, ordered by most recently updated.
     */
    fun observeSessions(userId: String): Flow<List<ChatSession>> = flow {
        val path = "users/$userId/sessions"
        emitAll(
            db.collection("users").document(userId).collection("sessions")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(ChatSession::class.java) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Creates a new chat session.
     */
    suspend fun createSession(userId: String, title: String): String {
        val uid = requireUserId()
        require(uid == userId) { "Cannot create session for another user" }
        val docId = UUID.randomUUID().toString()
        val docRef = db.collection("users").document(uid).collection("sessions").document(docId)

        val payload = mapOf(
            "userId" to uid,
            "title" to title.take(100),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            docRef.set(payload).await()
            return docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    /**
     * Updates session title and last message preview.
     */
    suspend fun updateSession(userId: String, sessionId: String, title: String? = null, lastMessage: String? = null) {
        val uid = requireUserId()
        require(uid == userId) { "Cannot update session for another user" }
        val docRef = db.collection("users").document(uid).collection("sessions").document(sessionId)

        val updates = mutableMapOf<String, Any>(
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (title != null) {
            updates["title"] = title.take(100)
        }
        if (lastMessage != null) {
            updates["lastMessage"] = lastMessage.take(500)
        }

        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    /**
     * Deletes a chat session and its subcollection messages.
     */
    suspend fun deleteSession(userId: String, sessionId: String) {
        val uid = requireUserId()
        require(uid == userId) { "Cannot delete session for another user" }
        val sessionRef = db.collection("users").document(uid).collection("sessions").document(sessionId)

        try {
            // Delete messages in subcollection first
            val messagesSnapshot = sessionRef.collection("messages").get().await()
            for (doc in messagesSnapshot.documents) {
                doc.reference.delete().await()
            }
            sessionRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, sessionRef.path)
            throw e
        }
    }

    /**
     * Observes messages in a specific session in ascending chronological order.
     */
    fun observeMessages(userId: String, sessionId: String): Flow<List<ChatMessage>> = flow {
        val path = "users/$userId/sessions/$sessionId/messages"
        emitAll(
            db.collection("users").document(userId)
                .collection("sessions").document(sessionId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(ChatMessage::class.java) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Adds a message to a session.
     */
    suspend fun addMessage(
        userId: String,
        sessionId: String,
        sender: String,
        content: String
    ): String {
        val uid = requireUserId()
        require(uid == userId) { "Cannot write message for another user" }
        val docId = UUID.randomUUID().toString()
        val docRef = db.collection("users").document(uid)
            .collection("sessions").document(sessionId)
            .collection("messages").document(docId)

        val payload = mapOf(
            "userId" to uid,
            "sessionId" to sessionId,
            "sender" to sender,
            "content" to content.take(15000),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            docRef.set(payload).await()
            // Also update parent session's lastMessage and updatedAt
            updateSession(userId = uid, sessionId = sessionId, lastMessage = content.take(200))
            return docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }
}
