package dev.malachi.update

/**
 * Install sessions this app abandoned on purpose, so their status callbacks can be told apart
 * from a real install's.
 *
 * Abandoning a *committed* session — and a leaked "tap to install" session is exactly that —
 * delivers STATUS_FAILURE_ABORTED to [InstallReceiver], asynchronously, some milliseconds later.
 * The receiver's job on a failure is to say so and to delete the downloaded APK, and both are
 * precisely wrong when the session that failed is one [Updater.install] abandoned to make room
 * for the install it is starting right now: the screen reports a failure for a session that was
 * committed successfully, and the delete can land between the download and the session write,
 * which is a FileNotFoundException and no update at all.
 *
 * Process-wide because the two sides are a class built per check and a receiver declared in the
 * manifest. Bounded, and claimed rather than merely read, because a set nothing removes from is
 * a leak — and an id whose broadcast never arrives (a process killed in between) must not sit
 * there keeping a later session out.
 */
internal object AbandonedSessions {

    /** More than any one check can create; a handful of leaked sessions is already a bad week. */
    private const val MAX_REMEMBERED = 8

    private val ids = ArrayDeque<Int>()

    /** Records that [sessionId] was abandoned by us, rather than by the system or the user. */
    fun remember(sessionId: Int) = synchronized(ids) {
        if (sessionId in ids) return@synchronized
        ids.addLast(sessionId)
        while (ids.size > MAX_REMEMBERED) ids.removeFirst()
    }

    /** True once, and only for a session we abandoned ourselves. */
    fun claim(sessionId: Int): Boolean = synchronized(ids) { ids.remove(sessionId) }
}
