package dev.malachi.data

/**
 * What the custom DNS server box accepts: IP literals, separated by commas or spaces — the same
 * split [dev.malachi.net.TunnelPolicy.resolveUpstreams] makes, so what the dialog accepts is
 * exactly what the tunnel will ask.
 *
 * A name is refused here rather than dropped silently there. Typed in and saved, `dns.google`
 * used to be shown as the DNS server on two screens while every lookup on the phone went to
 * Cloudflare: the policy discards what it cannot parse and falls back, and nothing said so.
 * Whether a piece of text is an address is the caller's to decide ([isNumeric]), because the
 * platform's own check lives in an Android class and this has to be testable without one.
 */
object UpstreamInput {

    /** The addresses in [text], or null when it holds none, or anything that is not one. */
    fun parse(text: String, isNumeric: (String) -> Boolean): List<String>? {
        val parts = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { !isNumeric(it) }) return null
        return parts
    }
}
