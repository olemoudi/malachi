package dev.malachi.update

import dev.malachi.data.UpdateChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The channel rules, which are asymmetric and have to stay that way on purpose.
 *
 * Android refuses to install a lower version code over a higher one, so leaving the stable
 * channel is immediate and coming back is a wait. Every sentence the settings screen shows about
 * either direction is derived from here, so the screen cannot come to disagree with what the
 * downloader will actually do.
 */
class UpdatePolicyTest {

    @Test
    fun `each channel has its own suffix and neither answers for the other`() {
        assertEquals("-beta", UpdatePolicy.expectedSuffix(UpdateChannel.STABLE))
        assertEquals("-alpha", UpdatePolicy.expectedSuffix(UpdateChannel.TESTING))

        assertTrue(UpdatePolicy.belongsToChannel("1.0.0-beta", UpdateChannel.STABLE))
        assertTrue(UpdatePolicy.belongsToChannel("1.1.0-alpha", UpdateChannel.TESTING))
        assertFalse(UpdatePolicy.belongsToChannel("1.1.0-alpha", UpdateChannel.STABLE))
        assertFalse(UpdatePolicy.belongsToChannel("1.0.0-beta", UpdateChannel.TESTING))
    }

    @Test
    fun `a build from before channels existed belongs to neither`() {
        // 0.9.x carried no suffix. Accepting it into both channels would be the one mistake this
        // check exists to prevent, wearing the clothes of backwards compatibility.
        assertFalse(UpdatePolicy.belongsToChannel("0.9.23", UpdateChannel.STABLE))
        assertFalse(UpdatePolicy.belongsToChannel("0.9.23", UpdateChannel.TESTING))
    }

    @Test
    fun `leaving the stable channel happens at once`() {
        // The testing channel is kept at or ahead of stable, so there is always something newer
        // to move to and the forced check finds it.
        assertEquals(
            ChannelSwitch.Immediate("1.1.0-alpha"),
            UpdatePolicy.switching(installedVersionCode = 44, channelVersionCode = 45, channelVersionName = "1.1.0-alpha"),
        )
    }

    @Test
    fun `coming back waits, because the platform will not go backwards`() {
        // The case the screen has to explain rather than retry: nothing is broken, and nothing
        // will happen until the stable channel passes what is installed.
        assertEquals(
            ChannelSwitch.WaitsForNextRelease("1.0.0-beta"),
            UpdatePolicy.switching(installedVersionCode = 45, channelVersionCode = 44, channelVersionName = "1.0.0-beta"),
        )
    }

    @Test
    fun `being exactly current is not a wait`() {
        // The case that is nearly every phone: on the stable channel, running what the stable
        // channel serves. It used to answer WaitsForNextRelease along with the genuinely
        // stranded, and the screen paints that in red — so an up-to-date phone was told it was
        // ahead of its own channel and would rejoin at some later release.
        assertEquals(ChannelSwitch.AlreadyOnIt, UpdatePolicy.switching(44, 44, "1.0.0-beta"))
    }

    @Test
    fun `a channel nobody has asked yet says so rather than guessing`() {
        // "Behind you" and "not looked yet" are different sentences, and showing the first when
        // the second is true tells somebody they are stuck when they are merely early.
        assertEquals(ChannelSwitch.Unknown, UpdatePolicy.switching(44, 0, ""))
    }

    // ---- what may be installed ----------------------------------------------------------

    @Test
    fun `an APK from the other channel is refused however the manifest described it`() {
        // A manifest is a document on the internet; the suffix is in the APK. A promotion that
        // pointed the stable channel at a test build would otherwise move every stable phone
        // onto the testing lineage, silently, and there would be no way back.
        val reason = rejectionReason(
            archivePackage = "dev.malachi",
            archiveVersionCode = 45,
            expectedPackage = "dev.malachi",
            installedVersionCode = 44,
            archiveVersionName = "1.1.0-alpha",
            expectedChannel = UpdateChannel.STABLE,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("stable"), reason)
    }

    @Test
    fun `an APK from the channel it was asked for is accepted`() {
        assertNull(
            rejectionReason(
                archivePackage = "dev.malachi",
                archiveVersionCode = 45,
                expectedPackage = "dev.malachi",
                installedVersionCode = 44,
                archiveVersionName = "1.1.0-alpha",
                expectedChannel = UpdateChannel.TESTING,
            ),
        )
    }

    @Test
    fun `the older checks still come first`() {
        // Ordering matters: "not an APK at all" and "belongs to someone else" are worse findings
        // than a channel mismatch and must not be masked by one.
        assertEquals(
            "not a readable APK",
            rejectionReason(null, null, "dev.malachi", 44, "1.0.0-beta", UpdateChannel.STABLE),
        )
        assertTrue(
            rejectionReason("com.other.app", 45, "dev.malachi", 44, "1.0.0-beta", UpdateChannel.STABLE)!!
                .contains("com.other.app"),
        )
    }

    @Test
    fun `a caller that names no channel is unchanged`() {
        // The pre-channel signature still has to mean what it meant, or every existing test of
        // it is testing something else.
        assertNull(rejectionReason("dev.malachi", 45, "dev.malachi", 44))
    }
}
