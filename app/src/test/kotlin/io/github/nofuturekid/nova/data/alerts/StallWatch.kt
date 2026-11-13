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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * WHY: on 2026-11-13 one full local run hung for over 30 minutes inside [AlertStateStoreTest]
 * (a `runBlocking` on the Robolectric main thread that never returned). The thread dump showed
 * every thread idle and no lock held, which says a coroutine is suspended but not where, and the
 * run was killed before it said anything else. A test that waits for ever tells nobody anything.
 *
 * This rule turns such a stall into a fast failure that explains itself: when the test has not
 * finished after [limitMs] it writes a dump to `build/stall-dumps/` and to stderr and ends the
 * test JVM, so Gradle reports a failed test run within a minute. The dump holds the JVM uptime,
 * the watched test classes that have started in this JVM so far (in order, classes without the rule are not listed), every thread's stack, the main
 * looper, the internals of the DataStore the store sits on (is its coordinator mutex locked, what
 * is queued for its write actor) and every live coroutine with its state, dispatcher, suspension
 * point and creation stack (kotlinx-coroutines-debug), each with the test class during which it was
 * created. The coroutine part decides between the open hypotheses of #359. H1: an edit whose
 * caller dispatcher cannot run keeps the DataStore mutex locked; the dump then shows a coroutine
 * that holds the mutex (stack through `SingleProcessCoordinator.lock`) suspended in
 * `transformAndWrite`, an edit caller bound to a dispatcher that is not served (for example a
 * looper with a pending message in the main looper's queue) and the coordinator mutex locked.
 * H1 has a second shape: the transform of the test's own edit is lost on its own `runBlocking`;
 * then one holder exists and the only edit caller is on the BlockingEventLoop. H2: a resumption
 * into the main thread's blocking event loop never arrives while nothing holds the mutex; the
 * dump then shows the test's `runBlocking` coroutine suspended, no holder, an unlocked mutex and
 * no edit caller. The event loop queue of the `runBlocking` cannot tell the two apart (it is
 * empty in the H1 validation run as well). `AlertStateStoreTest.setUp` ends with an unconditional
 * `clear()` edit that did finish in the original hang, so under H1 the blocking edit entered the
 * write actor after `setUp`, from a coroutine that was still running then.
 *
 * What the dump cannot tell: with two or more edit callers on unserved dispatchers it does not say
 * which one is in flight (the queued message's caller context is below the depth the object dump
 * reaches); it does not show the value of the in-memory data, so within H2 it cannot separate "the
 * answer was never completed" from "it was completed but the resumption was lost"; and the probes
 * keep coroutines in a weak map, so a coroutine on an abandoned dispatcher that nothing else
 * references can be missing from the coroutine list (the mutex state and the actor queue are
 * still shown).
 *
 * It ends the JVM instead of only failing the test because the DataStore is one object per
 * Robolectric sandbox: after a stall every later test that touches it would wait for ever as well.
 * Every test class that uses that DataStore carries this rule, so a coroutine leaked by an earlier
 * class is caught in the class where it bites. Its creation stack names the class it came from only
 * if the stack passes through test code, which a coroutine started from another coroutine does not;
 * so the dump also records, per coroutine, the watched class during which it was created (from the
 * coroutine counter noted when each class started).
 *
 * The limit is a diagnostic, not a fix, and not a retry: it must stay far above any honest run of
 * one test (they take well under a second). A JUnit `Timeout` rule is not used on purpose: it runs
 * the test on another thread, and a Robolectric test has to stay on its main thread.
 */
class StallWatch(private val limitMs: Long = 60_000) : TestRule {

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            Probes.install()
            noteClassStarted(description.className, Probes.lastCoroutineId())
            val testThread = Thread.currentThread()
            val started = System.nanoTime()
            val done = Object()
            val finished = AtomicBoolean(false)
            val watchdog = Thread({
                synchronized(done) {
                    // A loop on the deadline: a spurious wakeup must not end the JVM early.
                    while (!finished.get()) {
                        val left = limitMs - (System.nanoTime() - started) / 1_000_000
                        if (left <= 0) break
                        done.wait(left)
                    }
                }
                if (!finished.get()) {
                    // The dump runs on its own thread with a bound: if reading some part of it blocks, the JVM still ends.
                    var file: Result<File>? = null
                    val writer = Thread({ file = runCatching { writeDump(description, testThread, (System.nanoTime() - started) / 1_000_000) } }, "stall-watch-dump")
                        .apply { isDaemon = true; start() }
                    writer.join(DUMP_BUDGET_MS)
                    if (writer.isAlive) file = Result.failure(IllegalStateException("dump not finished after $DUMP_BUDGET_MS ms"))
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
        sb.append("watched test classes started in this JVM so far, in order (JVM uptime ms when each first reached this rule; classes without the rule are not listed; the coroutine id is the highest one alive at that moment):\n")
        for (entry in startedClasses()) sb.append("  ").append(entry).append('\n')
        val mx = ManagementFactory.getThreadMXBean()
        sb.append("test thread '${testThread.name}' cpu ms: ${mx.getThreadCpuTime(testThread.id) / 1_000_000}\n")
        sb.append("main looper idle: ${runCatching { shadowOf(Looper.getMainLooper()).isIdle }}\n")
        sb.append("main looper queue: ${runCatching { looperQueue() }.getOrElse { "not readable: $it" }}\n")

        sb.append("\n--- threads ---\n")
        for ((t, stack) in Thread.getAllStackTraces()) {
            sb.append("\"${t.name}\" ${t.state}\n")
            stack.take(30).forEach { sb.append("    at $it\n") }
        }

        sb.append("\n--- coroutines (kotlinx-coroutines-debug) ---\n")
        runCatching { dumpCoroutines(sb) }.onFailure { sb.append("coroutine dump failed: $it\n") }

        sb.append("\n--- DataStore behind SettingsStore ---\n")
        runCatching {
            val store = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
            val ds = SettingsStore::class.java.getDeclaredField("ds").apply { isAccessible = true }.get(store)
            dumpObject(sb, ds, 4, "", HashSet())
        }.onFailure { sb.append("could not read it: $it\n") }

        val name = "${description.className}.${description.methodName}.txt"
        val file = File(File(System.getProperty("user.dir"), "build/stall-dumps").apply { mkdirs() }, name)
        file.writeText(sb.toString())
        // A second copy next to the HTML test report: CI already uploads that directory, also when the job fails.
        System.getProperty(COPY_DIR_PROPERTY)?.let { runCatching { File(it).apply { mkdirs() }.resolve(name).writeText(sb.toString()) } }
        System.err.println(sb)
        return file
    }

    /** The main looper's pending messages. A message here that is never taken is a looper that is not served. */
    private fun looperQueue(): String {
        val out = StringBuilder()
        Looper.getMainLooper().dump({ out.append(it).append(" | ") }, "")
        return out.toString().ifBlank { "(empty)" }
    }

    private fun dumpCoroutines(sb: StringBuilder) {
        Probes.failure?.let {
            sb.append("DebugProbes could not be installed: $it\n")
            return
        }
        // In creation order (the probes return them sorted by their creation sequence number).
        val all = Probes.dump()
        fun id(c: Probed) = "#${all.indexOf(c)}"
        sb.append("${all.size} live coroutines created since the probes were installed (first watched test class of this JVM).\n")
        sb.append("Facts per coroutine: state (CREATED = never started, SUSPENDED = waiting, RUNNING), the dispatcher it\n")
        sb.append("is bound to, where it is suspended, where it was created. A CREATED coroutine on a dispatcher that\n")
        sb.append("is not served never starts.\n")
        if (all.isEmpty()) sb.append("NOTE: nothing was recorded. DebugProbes swallows a failed agent attach, so this can also mean the probes are not active.\n")
        sb.append('\n')

        sb.append("SUMMARY\n")
        val never = all.filter { it.state == "CREATED" }
        sb.append("  never started (CREATED): ${never.size}\n")
        never.forEach { sb.append("    ${id(it)} on ${it.dispatcher}\n") }
        // DataStore takes its coordinator mutex in SingleProcessCoordinator.lock and runs the transform of an
        // edit, on the dispatcher of the edit's caller, inside it. A coroutine whose stack passes through
        // that lock holds the mutex; where it is suspended says what it waits for.
        val holders = all.filter { c -> c.stack().any { it.className == "androidx.datastore.core.SingleProcessCoordinator" && it.methodName == "lock" } }
        sb.append("  holds the DataStore coordinator mutex (stack passes through SingleProcessCoordinator.lock): ${holders.size}\n")
        holders.forEach { sb.append("    ${id(it)} ${it.state} on ${it.dispatcher}, suspended at ${it.suspendedAt()}\n") }
        val callers = all.filter { c -> c.stack().firstOrNull()?.className?.startsWith("androidx.datastore.core.DataStoreImpl\$updateData") == true }
        sb.append("  edits waiting for DataStore's answer (suspended in DataStoreImpl.updateData); the transform of each runs on ITS dispatcher: ${callers.size}\n")
        callers.forEach { sb.append("    ${id(it)} on ${it.dispatcher}\n") }
        val onMutex = all.filter { c -> c.stack().take(6).any { it.className.startsWith("kotlinx.coroutines.sync.") } }
        sb.append("  waiting for a Mutex: ${onMutex.size}\n")
        onMutex.forEach { sb.append("    ${id(it)} on ${it.dispatcher}, ${it.suspendedAt()}\n") }
        val blocking = all.filter { it.isBlocking }
        sb.append("  runBlocking coroutines: ${blocking.size}\n")
        blocking.forEach { sb.append("    ${id(it)} ${it.state}, ${it.suspendedAt()}\n") }
        sb.append('\n')

        fun detail(c: Probed) {
            sb.append("coroutine ${id(c)} state=${c.state} dispatcher=${c.dispatcher}\n")
            sb.append("  created during: ${createdDuring(c)}\n")
            sb.append("  context: ${c.context}\n")
            sb.append("  suspended at: ${c.suspendedAt()}\n")
            val st = c.stack()
            if (st.isEmpty()) sb.append("  stack: (none; a CREATED coroutine has not run)\n")
            else {
                sb.append("  stack:\n")
                st.take(25).forEach { sb.append("    at $it\n") }
            }
            sb.append("  created at:\n")
            val cr = c.creationStack()
            if (cr.isEmpty()) sb.append("    (not recorded)\n") else cr.take(30).forEach { sb.append("    at $it\n") }
            if (c.isBlocking) {
                sb.append("  event loop of this runBlocking:\n")
                dumpObject(sb, c.context[ContinuationInterceptor], 2, "    ", HashSet())
            }
        }
        // The coroutines named in the summary are the ones at fault, and they are usually the newest: in full, whatever the cap.
        val named = (never + holders + callers + onMutex + blocking).toSet()
        val namedInOrder = all.filter { it in named }
        sb.append("DETAILS of the ${namedInOrder.size} coroutines named in the summary (oldest first)\n\n")
        namedInOrder.forEach { detail(it) }
        // The rest: newest first, capped, with the number left out.
        val rest = all.filter { it !in named }.asReversed()
        sb.append("DETAILS of the other coroutines (newest first, at most $MAX_COROUTINES)\n\n")
        rest.take(MAX_COROUTINES).forEach { detail(it) }
        if (rest.size > MAX_COROUTINES) sb.append("... ${rest.size - MAX_COROUTINES} older coroutines not listed in detail\n")
    }

    /** The watched class during which the coroutine was created, from the coroutine counter noted when each class started. */
    private fun createdDuring(c: Probed): String {
        val id = c.coroutineId ?: return "unknown (no CoroutineId in its context)"
        val started = startedClasses().mapNotNull { e ->
            e.substringAfter("after coroutine id ", "").trim().toLongOrNull()?.let { e.substringBefore(" @") to it }
        }
        if (started.isEmpty()) return "unknown (no class start recorded)"
        val owner = started.lastOrNull { id > it.second }
        return if (owner == null) "before the first watched class started (${started.first().first}), or in a class without the rule"
        else "${owner.first}, or later in a class without the rule before the next watched class started"
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

    /** One coroutine as the probes report it. Read by reflection, see [Probes]. */
    private class Probed(private val info: Any) {
        private fun <T> call(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return info.javaClass.getMethod(name).invoke(info) as T
        }

        val state: String get() = call<Any>("getState").toString()
        /** The counter kotlinx.coroutines puts into every context while its debug mode is on (it is under -ea). Increases with creation. */
        val coroutineId: Long? get() = runCatching { COROUTINE_ID.find(context.toString())?.groupValues?.get(1)?.toLong() }.getOrNull()
        val context: CoroutineContext get() = call("getContext")
        val dispatcher: String get() = context[ContinuationInterceptor]?.toString() ?: "none"
        val isBlocking: Boolean get() = dispatcher.contains("BlockingEventLoop")
        fun stack(): List<StackTraceElement> = runCatching { call<List<StackTraceElement>>("lastObservedStackTrace") }.getOrDefault(emptyList())
        fun creationStack(): List<StackTraceElement> = runCatching { call<List<StackTraceElement>>("getCreationStackTrace") }.getOrDefault(emptyList())
        fun suspendedAt(): String {
            val st = stack()
            return st.firstOrNull { !it.className.startsWith("kotlinx.coroutines.") && !it.className.startsWith("kotlin.") }?.toString()
                ?: st.firstOrNull()?.toString() ?: "(no frame: not started)"
        }
    }

    /**
     * The coroutine probes, installed once per JVM, and only through the application class loader.
     * Why not directly: Robolectric loads kotlinx.coroutines into its own sandbox class loader but
     * leaves the Kotlin standard library, which holds `DebugProbesKt` (the hook every coroutine
     * creation calls), in the application class loader. The hook is rewritten to call the
     * `DebugProbesImpl` of the loader that holds it. A `DebugProbes.install()` made from the sandbox
     * therefore succeeds and records nothing (0 coroutines). Installed through the application
     * class loader the hook reports every coroutine of the sandbox too, and the stack frames are
     * plain `StackTraceElement`s and `CoroutineContext`s, which both loaders share. Only the
     * coroutines' `Job` is not recognised across the two loaders; the dump does not use it.
     * A failure is kept and printed in the dump, it never fails a test.
     */
    private object Probes {
        @Volatile var failure: String? = null
        private var installed = false
        private var probes: Any? = null
        private var probesClass: Class<*>? = null

        @Synchronized fun install() {
            if (installed) return
            installed = true
            try {
                val cls = Class.forName("kotlinx.coroutines.debug.DebugProbes", true, Unit::class.java.classLoader)
                val instance = cls.getField("INSTANCE").get(null)
                cls.getMethod("setEnableCreationStackTraces", Boolean::class.javaPrimitiveType).invoke(instance, true)
                cls.getMethod("install").invoke(instance)
                probes = instance
                probesClass = cls
            } catch (t: Throwable) {
                failure = (t.cause ?: t).toString()
            }
        }

        /** Highest coroutine id alive now (0 if none): every coroutine created later has a higher one. -1 if the probes are not usable. */
        fun lastCoroutineId(): Long =
            if (probesClass == null) -1 else runCatching { dump().maxOfOrNull { it.coroutineId ?: 0L } ?: 0L }.getOrDefault(-1)

        fun dump(): List<Probed> =
            (probesClass!!.getMethod("dumpCoroutinesInfo").invoke(probes) as List<*>).map { Probed(it!!) }
    }

    private companion object {
        const val STALL_EXIT_CODE = 70
        const val MAX_COROUTINES = 150
        const val DUMP_BUDGET_MS = 30_000L
        val COROUTINE_ID = Regex("""CoroutineId\((\d+)\)""")

        /** Set by Gradle (app/build.gradle.kts): where CI already collects the test report. */
        const val COPY_DIR_PROPERTY = "nova.stallDumpCopyDir"
        const val CLASSES_PROPERTY = "nova.stallWatch.startedClasses"

        /** In a system property, not a field: the sandbox class loader can be replaced, the JVM cannot. */
        fun noteClassStarted(className: String, lastCoroutineId: Long) = synchronized(System.getProperties()) {
            val now = ManagementFactory.getRuntimeMXBean().uptime
            val known = System.getProperty(CLASSES_PROPERTY).orEmpty().split('\n').filter { it.isNotBlank() }
            if (known.none { it.substringBefore(" @") == className }) {
                System.setProperty(CLASSES_PROPERTY, (known + "$className @ $now ms, coroutines created after coroutine id $lastCoroutineId").joinToString("\n"))
            }
        }

        fun startedClasses(): List<String> = System.getProperty(CLASSES_PROPERTY).orEmpty().split('\n').filter { it.isNotBlank() }
    }
}
