package dev.malachi.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Telling our own abandonment apart from an install that really failed.
 *
 * The bug this exists for is silent and intermittent: abandoning a leaked "tap to install"
 * session fires STATUS_FAILURE_ABORTED at the receiver, whose response is to report a failure
 * and delete the downloaded APK — while the session that replaced it is still reading that very
 * file. Half the time it is a wrong sentence on a screen; the other half it is no update at all.
 */
class AbandonedSessionsTest {

    @Test
    fun `a session we abandoned is claimed once and only once`() {
        AbandonedSessions.remember(7)

        assertTrue(AbandonedSessions.claim(7), "the first status for it is ours to swallow")
        assertFalse(AbandonedSessions.claim(7), "a second one is not: that session is finished with")
    }

    @Test
    fun `a session we did not abandon is never claimed`() {
        // The user pressing Cancel in the system's install dialog reports the same status as our
        // own abandonment. Swallowing that one would leave the screen saying an install is still
        // downloading and the APK sitting in the cache.
        AbandonedSessions.remember(11)

        assertFalse(AbandonedSessions.claim(12))
        assertFalse(AbandonedSessions.claim(-1), "nothing was abandoned, so there is nothing to swallow")
    }

    @Test
    fun `remembering the same session twice does not spend one of the slots`() {
        repeat(20) { AbandonedSessions.remember(30) }

        assertTrue(AbandonedSessions.claim(30))
        assertFalse(AbandonedSessions.claim(30))
    }

    @Test
    fun `the oldest ids are forgotten rather than accumulated forever`() {
        // A broadcast that never arrives — a process killed between the abandon and the callback
        // — must not leave an id in here for the life of the process. Twenty is far more than
        // any real run produces, and the newest are the ones still worth swallowing.
        val ids = (100..119).toList()
        ids.forEach { AbandonedSessions.remember(it) }

        val survivors = ids.filter { AbandonedSessions.claim(it) }
        assertTrue(survivors.size <= 8, "the set is bounded")
        assertEquals(ids.takeLast(survivors.size), survivors, "and it is the newest that survive")
    }
}
