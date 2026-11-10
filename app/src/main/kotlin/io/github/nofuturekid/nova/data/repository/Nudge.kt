package io.github.nofuturekid.nova.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * A "fetch again now" request for the polling loops. A counter, not an event:
 * a poll loop remembers the count it saw BEFORE it started fetching and, when
 * it comes to wait, wakes at once if the count moved. A nudge that arrives
 * while a fetch is in flight is therefore never lost (a `SharedFlow` with no
 * subscriber drops it), and several nudges in a row collapse into one refetch.
 *
 * [fire] returns the new generation; a poll loop reports each finished fetch
 * through [complete] with the generation it saw before fetching, so a caller
 * can [awaitCompleted] until data fetched after its request has arrived.
 */
internal class Nudge {
    private val _count = MutableStateFlow(0)
    private val _completed = MutableStateFlow(0)

    val count: StateFlow<Int> = _count

    fun fire(): Int = _count.updateAndGet { it + 1 }

    fun complete(generation: Int) {
        _completed.update { maxOf(it, generation) }
    }

    suspend fun awaitCompleted(generation: Int) {
        _completed.first { it >= generation }
    }

    private inline fun MutableStateFlow<Int>.updateAndGet(f: (Int) -> Int): Int {
        while (true) {
            val prev = value
            val next = f(prev)
            if (compareAndSet(prev, next)) return next
        }
    }
}
