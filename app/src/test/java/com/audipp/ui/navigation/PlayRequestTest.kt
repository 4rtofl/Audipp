package com.audipp.ui.navigation

import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayRequestTest {
    /** Runs listeners at once, like the main executor would, and keeps what they throw instead of crashing. */
    private val thrown = CopyOnWriteArrayList<Throwable>()
    private val executor = Executor { runnable ->
        try {
            runnable.run()
        } catch (failure: Throwable) {
            thrown += failure
        }
    }

    @Test
    fun cancellingTheWaitCancelsTheRequestWithoutThrowingInTheListener() = runBlocking {
        val future = SettableFuture.create<SessionResult>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { future.awaitPlayRequest(executor) }
        waiting.cancel()
        assertTrue(future.isCancelled)
        assertTrue(waiting.isCancelled)
        assertEquals(emptyList<Throwable>(), thrown)
    }

    @Test
    fun aFailedRequestBecomesDisconnectedWithoutThrowingInTheListener() = runBlocking {
        val future = SettableFuture.create<SessionResult>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { future.awaitPlayRequest(executor) }
        future.setException(IllegalStateException("session released"))
        assertEquals(PlayRequest.Disconnected, withTimeout(5_000) { waiting.await() })
        assertEquals(emptyList<Throwable>(), thrown)
    }

    @Test
    fun theServiceAnswerMapsToAcceptedOrRejected() = runBlocking {
        val accepted = SettableFuture.create<SessionResult>().apply { set(SessionResult(SessionResult.RESULT_SUCCESS)) }
        assertEquals(PlayRequest.Accepted, accepted.awaitPlayRequest(executor))
        val rejected = SettableFuture.create<SessionResult>().apply { set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)) }
        assertTrue(rejected.awaitPlayRequest(executor) is PlayRequest.Rejected)
        assertEquals(emptyList<Throwable>(), thrown)
    }
}
