package io.github.nofuturekid.nova.data.alerts

import io.github.nofuturekid.nova.data.model.Server

/**
 * THE filter for which saved servers the background check looks at. Every place that enumerates
 * servers for it ([AlertCheckRunner], [AlertScheduler]) goes through here.
 *
 * The demo entry (ADR-0050) has no address, no API key and fictional data: it is never checked,
 * and a server list that holds only the demo entry counts as "nothing to check", so no job is
 * scheduled for it.
 */
fun Server.isBackgroundCheckable(): Boolean = !isDemo
