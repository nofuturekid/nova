package io.github.nofuturekid.nova.data.alerts

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.nofuturekid.nova.data.local.SettingsStore
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WHY: on 2026-11-13 one full local run hung for over 30 minutes inside [AlertStateStoreTest]
 * (a `runBlocking` on the Robolectric main thread that never returned). The thread dump showed
 * every thread idle and no lock held, which says a coroutine is suspended but not where, and the
 * run was killed before it said anything else. A test that waits for ever tells nobody anything.
 *
 * This rule turns such a stall into a fast failure that explains itself: when the test has not
 * finished after [limitMs] it writes a dump (JVM uptime, every thread's stack, the main looper,
 * the internals of the DataStore the store sits on) to `app/build/stall-dumps/` and to stderr and
 * ends the test JVM, so Gradle reports a failed test run within a minute. It ends the JVM instead
 * of only failing the test because the DataStore is one object per Robolectric sandbox: after a
 * stall every later test that touches it would wait for ever as well.
 *
 * The limit is a diagnostic, not a fix, and not a retry: it must stay far above any honest run of
 * one test (they take well under a second). A JUnit `Timeout` rule is not used on purpose: it runs
 * the test on another thread, and a Robolectric test has to stay on its main thread.
 */
class StallWatch(private val limitMs: Long = 60_000) : TestRule {

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val testThread = Thread.currentThread()
            val started = System.nanoTime()
            val done = Object()
            val finished = AtomicBoolean(false)
            val watchdog = Thread({
                synchronized(done) { if (!finished.get()) done.wait(limitMs) }
                if (!finished.get()) {
                    val file = runCatching { writeDump(description, testThread, (System.nanoTime() - started) / 1_000_000) }
                    System.err.println("${description.displayName} stalled for $limitMs ms; dump: $file; ending the test JVM")
                    System.err.flush()
                    Runtime.getRuntime().halt(STALL_EXIT_CODE)
                }
            }, "stall-watch").apply { isDaemon = true; start() }
            try {
                base.evaluate()
            } finally {
                synchronized(done) { finished.set(true); done.notifyAll() }
                watchdog.join(5_000)
            }
        }
    }

    private fun writeDump(description: Description, testThread: Thread, elapsedMs: Long): File {
        val sb = StringBuilder()
        sb.append("STALL in ${description.displayName} after $elapsedMs ms\n")
        sb.append("JVM uptime ms: ${ManagementFactory.getRuntimeMXBean().uptime}\n")
        val mx = ManagementFactory.getThreadMXBean()
        sb.append("test thread '${testThread.name}' cpu ms: ${mx.getThreadCpuTime(testThread.id) / 1_000_000}\n")
        sb.append("main looper idle: ${runCatching { shadowOf(Looper.getMainLooper()).isIdle }}\n")
        sb.append("\n--- threads ---\n")
        for ((t, stack) in Thread.getAllStackTraces()) {
            sb.append("\"${t.name}\" ${t.state}\n")
            stack.take(30).forEach { sb.append("    at $it\n") }
        }
        sb.append("\n--- DataStore behind SettingsStore ---\n")
        runCatching {
            val store = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
            val ds = SettingsStore::class.java.getDeclaredField("ds").apply { isAccessible = true }.get(store)
            dumpObject(sb, ds, 4, "", HashSet())
        }.onFailure { sb.append("could not read it: $it\n") }

        val dir = File(System.getProperty("user.dir"), "build/stall-dumps").apply { mkdirs() }
        val file = File(dir, "${description.className}.${description.methodName}.txt")
        file.writeText(sb.toString())
        System.err.println(sb)
        return file
    }

    private fun dumpObject(sb: StringBuilder, o: Any?, depth: Int, indent: String, seen: MutableSet<Int>) {
        if (o == null || depth < 0 || !seen.add(System.identityHashCode(o))) return
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching {
                    f.isAccessible = true
                    val v = f.get(o)
                    val plain = v == null || v is String || v is Number || v is Boolean
                    val shown = if (plain) "$v" else v!!.javaClass.name + " " + runCatching { v.toString() }.getOrDefault("?").take(300)
                    sb.append(indent).append(c.simpleName).append('.').append(f.name).append(" = ").append(shown).append('\n')
                    if (!plain && !v!!.javaClass.name.startsWith("java.lang")) dumpObject(sb, v, depth - 1, "$indent  ", seen)
                }
            }
            c = c.superclass
        }
    }

    private companion object {
        const val STALL_EXIT_CODE = 70
    }
}
