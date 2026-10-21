package org.jellyfin.mobile.utils

import java.net.URI

/**
 * Finesse serves its web app at `<base>/finesse/` and the Jellyfin server behind it at
 * `<base>/jellyfin`. The app stores the Jellyfin address (what the native player and Android Auto
 * talk to) and shows the Finesse web app.
 */
object FinesseUrls {
    private const val JELLYFIN_PATH = "/jellyfin"
    private const val FINESSE_PATH = "/finesse"
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443

    /** What someone typed (their Finesse address, with or without `/finesse`) → the Jellyfin address behind it. */
    fun jellyfinAddress(entered: String): String {
        val base = entered.trim().trimEnd('/')
            .removeSuffixIgnoreCase(FINESSE_PATH)
            .removeSuffixIgnoreCase(JELLYFIN_PATH)
        return base + JELLYFIN_PATH
    }

    /** The stored Jellyfin address → the Finesse web app. */
    fun webappAddress(jellyfinHostname: String): String =
        jellyfinHostname.trimEnd('/').removeSuffixIgnoreCase(JELLYFIN_PATH) + "$FINESSE_PATH/"

    /**
     * Server discovery also tries Jellyfin's own port (8096). Jellyfin answers there too, but
     * Finesse doesn't, so when the person typed a port, only that port counts.
     */
    fun samePort(entered: String, candidate: String): Boolean {
        val typed = portOf(entered) ?: return true
        return portOf(candidate) == typed
    }

    private fun portOf(address: String): Int? {
        val withScheme = if ("://" in address) address else "http://$address"
        val uri = runCatching { URI(withScheme.trim()) }.getOrNull() ?: return null
        return when {
            uri.port != -1 -> uri.port
            "://" !in address -> null // nothing typed: any port
            uri.scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
            else -> HTTP_PORT
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
