package io.github.nofuturekid.nova.data.model

/** A connection endpoint expressed as host[:port] + an SSL (https) toggle. */
data class EndpointUrl(val host: String, val ssl: Boolean) {
    companion object {
        /** Strip a leading http(s):// scheme (case-insensitive) and any path. */
        fun normalizeHost(raw: String): String = raw.trim().stripScheme().substringBefore('/')

        /** host[:port] + ssl → stored URL. Blank host → "" (endpoint unset).
         *  A scheme/path pasted into [host] is stripped so we never double up. */
        fun compose(host: String, ssl: Boolean): String {
            val h = normalizeHost(host)
            if (h.isEmpty()) return ""
            return (if (ssl) "https://" else "http://") + h
        }

        /** Stored URL → host[:port] + ssl. Blank URL takes [defaultSsl]. */
        fun parse(url: String, defaultSsl: Boolean): EndpointUrl {
            val u = url.trim()
            if (u.isEmpty()) return EndpointUrl("", defaultSsl)
            val ssl = u.lowercase().startsWith("https://")
            return EndpointUrl(u.stripScheme().substringBefore('/'), ssl)
        }

        private fun String.stripScheme(): String {
            val lower = lowercase()
            return when {
                lower.startsWith("https://") -> substring("https://".length)
                lower.startsWith("http://") -> substring("http://".length)
                else -> this
            }
        }
    }
}
