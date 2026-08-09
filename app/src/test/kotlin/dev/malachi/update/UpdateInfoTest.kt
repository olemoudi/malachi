package dev.malachi.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateInfoTest {

    private val apk = "https://github.com/olemoudi/malachi/releases/latest/download/malachi.apk"

    @Test
    fun `a well formed version file parses`() {
        val info = UpdateInfo.parse("""{"versionCode":7,"versionName":"0.7.0","apk":"$apk"}""")
        assertEquals(7, info?.versionCode)
        assertEquals("0.7.0", info?.versionName)
    }

    @Test
    fun `a version file with extra fields still parses`() {
        // CI may start publishing more than this app knows about; that must not stop updates.
        assertEquals(7, UpdateInfo.parse("""{"versionCode":7,"notes":"hello"}""")?.versionCode)
    }

    @Test
    fun `garbage yields null rather than an exception`() {
        assertNull(UpdateInfo.parse("not json"))
        assertNull(UpdateInfo.parse(""))
        assertNull(UpdateInfo.parse("""{"versionName":"1.0"}""")) // versionCode is required
    }

    @Test
    fun `only a strictly newer build with an apk is an update`() {
        val info = UpdateInfo(versionCode = 7, apk = apk)
        assertTrue(info.isNewerThan(6))
        assertFalse(info.isNewerThan(7))
        assertFalse(info.isNewerThan(8))
        // A release that published no APK is not something to install.
        assertFalse(UpdateInfo(versionCode = 7).isNewerThan(6))
    }

    @Test
    fun `the downloader refuses an apk url from anywhere else`() {
        assertTrue(trustedApkUrl(apk))
        assertFalse(trustedApkUrl("https://example.com/malachi.apk"))
        assertFalse(trustedApkUrl("http://github.com/olemoudi/malachi/x.apk"))
        // A different repository under the same host is still somebody else's release.
        assertFalse(trustedApkUrl("https://github.com/someone/malachi/releases/latest/download/malachi.apk"))
        assertFalse(trustedApkUrl(""))
    }
}
