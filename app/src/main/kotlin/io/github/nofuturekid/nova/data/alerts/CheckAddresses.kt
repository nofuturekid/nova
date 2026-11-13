package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.api.TlsTrust
import io.github.nofuturekid.nova.data.api.trustFor
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Server

/** One address a background check tries, with the TLS trust that belongs to it. */
data class CheckAddress(val url: String, val trust: TlsTrust)

/**
 * The addresses a background check tries for [server], in order: the local URL, then the
 * remote URL when it is set and different.
 *
 * The in-app Local/Remote switch is deliberately ignored: it can be stale and says nothing
 * about where the phone is now. Trust comes from the one place that decides it, [trustFor],
 * with Local for the first address and Remote for the second, so a pinned self-signed
 * certificate is never accepted on the remote address (ADR-0041). [pin] is the stored
 * fingerprint of the local certificate, if any.
 */
fun checkAddresses(server: Server, pin: String?): List<CheckAddress> {
    val local = server.localUrl.trim()
    val remote = server.remoteUrl.trim()
    return buildList {
        if (local.isNotEmpty()) {
            add(CheckAddress(local, trustFor(ConnectionMode.Local, server.trustSelfSignedLocal, local, pin)))
        }
        if (remote.isNotEmpty() && !remote.trimEnd('/').equals(local.trimEnd('/'), ignoreCase = true)) {
            add(CheckAddress(remote, trustFor(ConnectionMode.Remote, server.trustSelfSignedLocal, remote, pin)))
        }
    }
}
