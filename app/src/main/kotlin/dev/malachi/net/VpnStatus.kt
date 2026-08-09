package dev.malachi.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why the filter isn't running, when it isn't.
 *
 * Every one of these looks identical from a settings screen — a switch that is on and a phone
 * that isn't being filtered — so each is named separately, and each maps to exactly one thing
 * the user can do about it.
 */
enum class TunnelProblem {
    NONE,

    /** Coming up. Not a problem; the UI must not paint it as one. */
    STARTING,

    /** VPN permission was refused, or was never granted. Needs the user to say yes. */
    NO_CONSENT,

    /** Another VPN app holds the one tunnel Android allows. */
    DISPLACED,

    /**
     * Another app is set as the device's always-on VPN. Android then refuses to hand the tunnel
     * over at all, so the consent dialog is a dead end and only the system's VPN settings can
     * clear it — which is why this is a separate state and not a flavour of [DISPLACED].
     */
    ALWAYS_ON_ELSEWHERE,

    /** "Only these apps" is selected and the list is empty, so there is nothing to filter. */
    NO_APPS_SELECTED,

    /** establish() failed for a reason we can't attribute. [FilterStatus.detail] has it. */
    FAILED,
}

/**
 * What the filter is actually doing, as opposed to what the user asked for.
 */
data class FilterStatus(
    val tunnelUp: Boolean = false,
    val problem: TunnelProblem = TunnelProblem.NONE,
    val detail: String = "",

    /** True while a retry is pending, so the UI can say "retrying" instead of looking dead. */
    val retrying: Boolean = false,

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
    /** True when the user has to do something before the filter can possibly run. */
    val needsUser: Boolean
        get() = problem == TunnelProblem.NO_CONSENT ||
            problem == TunnelProblem.ALWAYS_ON_ELSEWHERE ||
            problem == TunnelProblem.NO_APPS_SELECTED
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

    internal fun down(
        problem: TunnelProblem = TunnelProblem.NONE,
        detail: String = "",
        retrying: Boolean = false,
    ) {
        _status.value = _status.value.copy(
            tunnelUp = false,
            problem = problem,
            detail = detail,
            retrying = retrying,
        )
    }

    internal fun privateDns(active: Boolean, host: String?) {
        _status.value = _status.value.copy(privateDnsActive = active, privateDnsHost = host)
    }

    /**
     * Recorded from the UI when the system's consent dialog comes back refused. Without it the
     * switch would spring back with no explanation, which is indistinguishable from the app
     * being broken — and was.
     */
    fun consentRefused() {
        _status.value = FilterStatus(tunnelUp = false, problem = TunnelProblem.NO_CONSENT)
    }

    fun alwaysOnElsewhere() {
        _status.value = FilterStatus(tunnelUp = false, problem = TunnelProblem.ALWAYS_ON_ELSEWHERE)
    }

    /** Clears a stale problem when the user asks for the filter again. */
    fun starting() {
        _status.value = _status.value.copy(
            tunnelUp = false,
            problem = TunnelProblem.STARTING,
            detail = "",
            retrying = false,
        )
    }
}
