package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createSession_authenticatedUser_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = ChatRepository(firestore)

        val sessionId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createSession(aliceUid, "Alice Chat")
        }
        assertNotNull(sessionId)
        assertTrue(sessionId.isNotBlank())
    }

    @Test
    fun observeSessions_authenticatedUser_emitsCreatedSession() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = ChatRepository(firestore)

        val sessionId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createSession(aliceUid, "Realtime Test Chat")
        }

        val emittedList = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSessions(aliceUid).first { list ->
                list.any { it.id == sessionId }
            }
        }
        assertTrue(emittedList.any { it.id == sessionId })
    }

    @Test
    fun addMessage_authenticatedUser_emitsMessage() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = ChatRepository(firestore)

        val sessionId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createSession(aliceUid, "Message Thread")
        }

        val messageId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addMessage(aliceUid, sessionId, "user", "Hello Sayon AI")
        }
        assertNotNull(messageId)

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(aliceUid, sessionId).first { list ->
                list.any { it.id == messageId }
            }
        }
        assertEquals(1, messages.count { it.id == messageId })
        assertEquals("Hello Sayon AI", messages.first { it.id == messageId }.content)
    }

    @Test
    fun observeSessions_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = ChatRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeSessions("any_user").first()
            }
            fail("Expected exception for unauthenticated access")
        } catch (e: Exception) {
            val firestoreException = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", firestoreException)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice@example.com"
        const val BOB_EMAIL = "bob@example.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
