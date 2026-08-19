package dev.malachi.update

import dev.malachi.data.UpdateChannel

/** What choosing a channel will actually do, which is not the same in both directions. */
sealed interface ChannelSwitch {

    /** There is something newer on the chosen channel; the forced check will install it. */
    data class Immediate(val versionName: String) : ChannelSwitch

    /**
     * The chosen channel is behind what is installed, so nothing can happen yet.
     *
     * Android refuses to install a lower version code over a higher one and there is no way to
     * ask it nicely — the only route is uninstalling, which takes the user's rules with it. So
     * this is not a failure to be retried, it is a wait to be explained: the phone rejoins the
     * chosen channel at its next release, and anybody in a hurry exports a backup and installs
     * the other APK by hand.
     */
    data class WaitsForNextRelease(val channelVersionName: String) : ChannelSwitch

    /**
     * The chosen channel serves exactly the build that is already running.
     *
     * Distinct from [WaitsForNextRelease] and not a pedantic distinction: the screen paints that
     * one in red, and lumping the two together told every up-to-date phone on the stable channel
     * — which is nearly all of them — that it was stranded ahead of its own channel and would
     * rejoin later. Being current is not a wait.
     */
    data object AlreadyOnIt : ChannelSwitch

    /** Nothing is known about the chosen channel yet — no manifest has been read. */
    data object Unknown : ChannelSwitch
}

/**
 * The channel rules, with none of the network.
 *
 * Every one of these used to be an implicit assumption spread between the updater, the settings
 * screen and a CI script, which is how a distribution model acquires two answers to the same
 * question. They are ordinary functions over ordinary values and they are what the screen and
 * the downloader both read.
 */
object UpdatePolicy {

    /** The suffix a build on [channel] carries in its version name. */
    fun expectedSuffix(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "-beta"
        UpdateChannel.TESTING -> "-alpha"
    }

    /**
     * Whether [versionName] belongs to [channel].
     *
     * Checked against the *downloaded APK*, not against the manifest that named it, and that is
     * the point: the manifest is a file on the internet and a mix-up in it would otherwise move
     * somebody between lineages silently — which is the one mistake having channels at all
     * exists to prevent. A version name with no suffix predates channels and belongs to neither,
     * so it is refused by both rather than accepted by both.
     */
    fun belongsToChannel(versionName: String, channel: UpdateChannel): Boolean =
        versionName.endsWith(expectedSuffix(channel))

    /**
     * What picking [channel] will do for a phone on [installedVersionCode], given what that
     * channel currently offers.
     *
     * [channelVersionCode] of 0 means no manifest has been read yet.
     */
    fun switching(
        installedVersionCode: Int,
        channelVersionCode: Int,
        channelVersionName: String,
    ): ChannelSwitch = when {
        channelVersionCode <= 0 -> ChannelSwitch.Unknown
        channelVersionCode > installedVersionCode -> ChannelSwitch.Immediate(channelVersionName)
        channelVersionCode == installedVersionCode -> ChannelSwitch.AlreadyOnIt
        else -> ChannelSwitch.WaitsForNextRelease(channelVersionName)
    }
}
