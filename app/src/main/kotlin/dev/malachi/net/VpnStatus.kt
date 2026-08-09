package dev.malachi.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the filter isn't running, when it isn't. */
enum class TunnelProblem {
    NONE,

    /** VPN permission has never been granted, or was withdrawn. */
    NO_CONSENT,

    /** Another VPN app holds the one tunnel Android allows. */
    DISPLACED,

    /** "Only these apps" is selected and the list is empty, so there is nothing to filter. */
    NO_APPS_SELECTED,

    /** establish() failed for a reason we can't attribute. [FilterStatus.detail] has it. */
    FAILED,
}

/**
 * What the filter is actually doing, as opposed to what the user asked for.
 *
 * Asking for a tunnel and having one are different things, and every way they diverge looks the
 * same from the settings screen: the switch is on and nothing is being filtered. Android allows
 * exactly one VPN at a time, consent can be withheld or withdrawn, and a system-wide Private DNS
 * setting routes lookups somewhere Malachi will never see them. All of it is published here so
 * the home screen can say which one is happening instead of showing a green light over a filter
 * that is off.
 */
data class FilterStatus(
    val tunnelUp: Boolean = false,
    val problem: TunnelProblem = TunnelProblem.NONE,
    val detail: String = "",

    /**
     * The system's Private DNS (DNS-over-TLS) is on. Lookups then leave the device encrypted to
     * a resolver of the user's choosing, which is good for privacy and fatal for filtering:
     * Malachi never sees them. Nothing here can fix that, so the UI says so plainly.
     */
    val privateDnsActive: Boolean = false,
    val privateDnsHost: String? = null,

    /** Human-readable upstream in use, for the home screen ("system", "1.1.1.1", …). */
    val upstream: String = "",
) {
    val paused: Boolean get() = !tunnelUp && problem == TunnelProblem.NONE
}

/** Process-wide filter status; the tunnel writes, the UI reads. */
object VpnStatus {

    private val _status = MutableStateFlow(FilterStatus())
    val status: StateFlow<FilterStatus> = _status.asStateFlow()

    internal fun up(upstream: String, privateDnsActive: Boolean, privateDnsHost: String?) {
        _status.value = FilterStatus(
            tunnelUp = true,
            upstream = upstream,
            privateDnsActive = privateDnsActive,
            privateDnsHost = privateDnsHost,
        )
    }

    internal fun down(problem: TunnelProblem = TunnelProblem.NONE, detail: String = "") {
        _status.value = _status.value.copy(tunnelUp = false, problem = problem, detail = detail)
    }

    internal fun privateDns(active: Boolean, host: String?) {
        _status.value = _status.value.copy(privateDnsActive = active, privateDnsHost = host)
    }
}
