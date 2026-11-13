package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.Server

/**
 * THE filter for which saved servers the background check looks at. Every place that enumerates
 * servers for it ([AlertCheckRunner], [AlertScheduler]) goes through here.
 *
 * Today every saved server is a real one. The demo mode, which adds a demo server that has no
 * real address, must return false for it here: a demo server must never be checked, and a server
 * list that holds only a demo server must count as "nothing to check".
 */
fun Server.isBackgroundCheckable(): Boolean = true
