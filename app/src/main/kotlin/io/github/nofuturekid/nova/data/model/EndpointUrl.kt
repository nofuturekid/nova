package io.github.nofuturekid.nova.data.model

/** A connection endpoint expressed as host[:port] + an SSL (https) toggle. */
data class EndpointUrl(val host: String, val ssl: Boolean) {
    companion object {
        /** host[:port] + ssl → stored URL. Blank host → "" (endpoint unset). */
        fun compose(host: String, ssl: Boolean): String {
            val h = host.trim()
            if (h.isEmpty()) return ""
            return (if (ssl) "https://" else "http://") + h
        }

        /** Stored URL → host[:port] + ssl. Blank URL takes [defaultSsl]. */
        fun parse(url: String, defaultSsl: Boolean): EndpointUrl {
            val u = url.trim()
            if (u.isEmpty()) return EndpointUrl("", defaultSsl)
            val ssl = u.startsWith("https://", ignoreCase = true)
            val host = u
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("HTTPS://").removePrefix("HTTP://")
                .substringBefore('/')
            return EndpointUrl(host, ssl)
        }
    }
}
