package dev.malachi.data

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A backup written to, and read back from, a real document provider.
 *
 * The format is tested without a device ([dev.malachi.data.BackupTest]); what cannot be is the
 * part that only exists on a phone — a `content://` document the user picked, opened through
 * `ContentResolver`. That is where the failure this guards against lives, and it is a failure
 * nobody discovers until the day their old phone is gone: a file that was written but not
 * truncated, or written with an encoding that comes back as something else, or not written at
 * all while the app said "saved".
 *
 * The document here comes from MediaStore's Downloads collection rather than the file picker,
 * because a picker cannot be driven from a test — but it is the same `ContentResolver`, the same
 * `openOutputStream(uri, "wt")`, and the same [BackupStore] the app itself uses.
 */
@RunWith(AndroidJUnit4::class)
class BackupFileTest {

    private lateinit var store: BackupStore
    private val written = mutableListOf<Uri>()

    private val settings = MalachiSettings(
        userBlocked = setOf("ads.example.com", "tracker.example.com"),
        userAllowed = setOf("cdn.example.com"),
        appRules = listOf(AppRule("t.example.com", "com.example.game", block = true)),
        listChoices = mapOf("oisd-big" to true, "adaway" to false),
        upstream = UpstreamDns.QUAD9,
        blockAnswer = BlockAnswerMode.NXDOMAIN,
    )

    @Before
    fun setUp() {
        store = BackupStore(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)
    }

    @After
    fun tearDown() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        written.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    /** A real document, the way the file picker would hand one over. */
    private fun newDocument(name: String): Uri {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        assertNotNull("could not create a document to write a backup into", uri)
        written += uri!!
        return uri
    }

    @Test
    fun aBackupSurvivesTheRoundTripThroughADocument() {
        val uri = newDocument("malachi-test-roundtrip-${System.nanoTime()}.json")
        val backup = Backup.of(settings, appVersion = "test", nowMs = 1_700_000_000_000)

        assertTrue("the backup was not written", store.write(uri, Backup.encode(backup)))

        val text = store.read(uri)
        assertNotNull("the backup could not be read back", text)
        val restored = Backup.decode(text!!).getOrNull()
        assertNotNull("what came back was not a backup", restored)
        assertEquals(backup, restored)

        // And the decisions actually land where they belong.
        val settingsAfter = restored!!.restoredInto(MalachiSettings())
        assertEquals(settings.userBlocked, settingsAfter.userBlocked)
        assertEquals(settings.appRules, settingsAfter.appRules)
        assertEquals(settings.listChoices, settingsAfter.listChoices)
        assertEquals(UpstreamDns.QUAD9, settingsAfter.upstream)
    }

    @Test
    fun writingOverALongerBackupLeavesNothingOfIt() {
        // The bug this exists for. Open a document for writing without truncating and the new
        // content overwrites only the first bytes; the tail of the old file stays. The result is
        // valid-looking JSON followed by rubbish, and it fails to parse on the day it is needed.
        val uri = newDocument("malachi-test-truncate-${System.nanoTime()}.json")

        val big = Backup.of(
            settings.copy(userBlocked = (1..500).map { "very-long-domain-name-number-$it.example.com" }.toSet()),
            appVersion = "test",
            nowMs = 0,
        )
        assertTrue(store.write(uri, Backup.encode(big)))

        val small = Backup.of(MalachiSettings(userBlocked = setOf("one.example.com")), appVersion = "test", nowMs = 0)
        assertTrue(store.write(uri, Backup.encode(small)))

        val text = store.read(uri)!!
        val restored = Backup.decode(text).getOrNull()
        assertNotNull("the short backup was left with the tail of the long one", restored)
        assertEquals(setOf("one.example.com"), restored!!.userBlocked)
        assertEquals(small, restored)
    }

    @Test
    fun nonAsciiRulesComeBackUnchanged() {
        // Internationalised domains and an app label are not ASCII, and a backup that mangles
        // them silently is worse than one that fails.
        val uri = newDocument("malachi-test-utf8-${System.nanoTime()}.json")
        val accented = MalachiSettings(
            userBlocked = setOf("münchen.example.com", "señal.example.es", "日本.example.jp"),
        )

        assertTrue(store.write(uri, Backup.encode(Backup.of(accented, "test", 0))))
        val restored = Backup.decode(store.read(uri)!!).getOrNull()

        assertEquals(accented.userBlocked, restored?.userBlocked)
    }

    @Test
    fun readingSomethingThatIsNotABackupFailsInsteadOfRestoringNothing() {
        val uri = newDocument("malachi-test-garbage-${System.nanoTime()}.json")
        assertTrue(store.write(uri, "this is a photo, not a backup"))

        assertNull(Backup.decode(store.read(uri)!!).getOrNull())
    }

    @Test
    fun readingADocumentThatIsNotThereFailsQuietly() {
        // A backup on a cloud drive that has since been deleted, or a permission that expired
        // between the picker and the read. It must come back null, not throw into a coroutine.
        val gone = Uri.parse("content://dev.malachi.test.missing/42")

        assertNull(store.read(gone))
        assertEquals(false, store.write(gone, "{}"))
    }
}
