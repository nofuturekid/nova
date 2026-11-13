package io.github.nofuturekid.nova.data.api

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #320 — Unraid 7.4 refuses requests above fixed limits (cost 1,000, depth 20,
 * 100 aliases, 5,000 GraphQL tokens, 1 MiB body; see [GraphqlRequestLimits]).
 * The refusal reaches the user as a failed screen, long after the query that
 * grew too large was merged. This test measures every operation NOVA ships by
 * the server's own formulas and fails in CI when one passes [GraphqlRequestLimits.MARGIN]
 * of any limit, so the query is slimmed down while it is still a pull request.
 */
class GraphqlRequestLimitsTest {
    private val margin = GraphqlRequestLimits.MARGIN

    private data class Measure(val label: String, val value: Long, val limit: Int) {
        val threshold: Long get() = (limit * GraphqlRequestLimits.MARGIN).toLong()
    }

    private fun measures(m: OperationMetrics) = listOf(
        Measure("cost", m.cost, GraphqlRequestLimits.MAX_COST),
        Measure("depth", m.depth.toLong(), GraphqlRequestLimits.MAX_DEPTH),
        Measure("aliases", m.aliases.toLong(), GraphqlRequestLimits.MAX_ALIASES),
        Measure("tokens", m.tokens.toLong(), GraphqlRequestLimits.MAX_TOKENS),
        Measure("request document bytes", m.bodyBytes.toLong(), GraphqlRequestLimits.MAX_BODY_BYTES),
    )

    private fun violations(all: List<OperationMetrics>): List<String> = all.flatMap { m ->
        measures(m).filter { it.value > it.threshold }.map {
            "${m.kind} ${m.name}: ${it.label} ${it.value} exceeds ${it.threshold} " +
                "(${(margin * 100).toInt()}% of the Unraid 7.4 limit ${it.limit})"
        }
    }

    // ── the formula must be the server's formula ─────────────────────────

    private fun one(src: String) = measureGraphqlDocument(src).single()

    @Test fun cost_countsEveryFieldOnce_andRejectsAboveTheLimitLikeTheServer() {
        // WHY: upstream request-protection.spec.ts accepts the limit and rejects limit+1; if our
        // numbers drifted by one the margin check would give false comfort.
        assertEquals(1_000L, one("{ ${"value ".repeat(1_000)} }").cost)
        assertEquals(1_001L, one("{ ${"value ".repeat(1_001)} }").cost)
    }

    @Test fun cost_objectFieldIsOnePlusItsChildren() {
        assertEquals(1L + 3L, one("{ a { x y z } }").cost)
        assertEquals(2L + 2L, one("{ a { x } b { y } }").cost) // two object fields, two leaves
    }

    @Test fun cost_fragmentSpreadCostsSpreadPlusFragmentObjectPlusFields() {
        // flattenFragments: spread (scalar cost 1) + fragment definition (object cost 1) + its fields.
        assertEquals(1L + 1L + 2L, one("{ ...F } fragment F on Q { x y }").cost)
    }

    @Test fun cost_firstAndLastIntegerLiteralsMultiply_otherLimitArgumentsDoNot() {
        assertEquals((1L + 1L) * 50, one("{ a(first: 50) { x } }").cost)
        // WHY: NOVA's GetNotificationList passes `limit: 200`; the server does not multiply by it.
        assertEquals(1L + 1L, one("{ a(limit: 200) { x } }").cost)
        // A variable is not an IntValue, so the server uses multiplier 1.
        assertEquals(1L + 1L, one("query(\$n: Int) { a(first: \$n) { x } }").cost)
    }

    @Test fun depth_matchesServerBoundary() {
        fun nested(levels: Int) = "{ ${"child { ".repeat(levels)}value${" }".repeat(levels)} }"
        assertEquals(20, one(nested(19)).depth) // 19 objects + the leaf = depth 20: still allowed
        assertEquals(21, one(nested(20)).depth) // upstream: "rejects deeply nested fields"
    }

    @Test fun depth_fragmentsAndInlineFragmentsAddNoLevel() {
        assertEquals(2, one("{ a { ...F } } fragment F on A { b }").depth)
        assertEquals(2, one("{ a { ... on A { b } } }").depth)
        // upstream: "does not count named fragments as field depth"
        val chain = (0..20).joinToString(" ") { i ->
            "fragment F$i on Query { ${if (i == 20) "value" else "...F${i + 1}"} }"
        }
        assertEquals(1, one("{ ...F0 } $chain").depth)
    }

    @Test fun aliases_countedAtTheLimitBoundaryAndInsideFragments() {
        fun aliased(n: Int) = "{ ${(0 until n).joinToString(" ") { "a$it: value" }} }"
        assertEquals(100, one(aliased(100)).aliases)
        assertEquals(101, one(aliased(101)).aliases)
        assertEquals(2, one("{ ...F ...F } fragment F on Q { x: value }").aliases)
        // allowList is [] on the server: even an aliased __typename counts.
        assertEquals(1, one("{ t: __typename }").aliases)
    }

    @Test fun tokens_countPunctuationAndVariablesButNotCommentsOrCommas() {
        // query Q ( $ id : ID ! ) { a ( x : 1 ) } = 17 tokens in graphql-js.
        assertEquals(17, one("# comment\nquery Q(\$id: ID!) { a(x: 1) }").tokens)
        assertEquals(one("{ a b c }").tokens, one("{ a, b, c }").tokens)
        assertEquals(8, one("{ a(x: \"s, {}\") }").tokens) // { a ( x : "..." ) } - the string is one token
    }

    @Test fun aTooLargeQueryIsCaught() {
        // WHY: proves the gate can fail - 800 fields cost 800, above the 70 % margin (700)
        // but below the server limit (1,000), which is exactly the case it exists for.
        val big = one("query Big { ${"value ".repeat(800)} }")
        val found = violations(listOf(big))
        assertTrue(found.toString(), found.any { it.contains("Big") && it.contains("cost 800") })
    }

    // ── what NOVA ships ──────────────────────────────────────────────────

    private fun graphqlDir(): File =
        listOf(File("src/main/graphql"), File("app/src/main/graphql")).firstOrNull { it.isDirectory }
            ?: error("app/src/main/graphql not found from ${File(".").absolutePath}")

    /** The document exactly as Apollo sends it: generated code, so added `__typename`s are included. */
    private fun wireDocument(kind: String, name: String): String {
        val suffix = when (kind) {
            "query" -> "Query"
            "mutation" -> "Mutation"
            else -> "Subscription"
        }
        val cls = Class.forName("io.github.nofuturekid.nova.graphql.$name$suffix")
        // Apollo 5 generates `companion object { val OPERATION_DOCUMENT: String get() = "..." }`.
        val companion = cls.getField("Companion").get(null)
        return companion.javaClass.getMethod("getOPERATION_DOCUMENT").invoke(companion) as String
    }

    @Test fun everyShippedOperation_staysWithinTheMarginOfTheUnraidRequestLimits() {
        val files = graphqlDir().walkTopDown().filter { it.isFile && it.extension == "graphql" }.sortedBy { it.path }.toList()
        assertTrue("No .graphql documents found", files.isNotEmpty())

        // Discovery from the source documents (so a new operation cannot be forgotten), numbers
        // from the generated wire document (so what the server sees is what is measured).
        val declared = files.flatMap { parseGraphqlDocument(it.readText()).operations }
        assertTrue("No operations found", declared.isNotEmpty())
        val measured = declared.map { op ->
            val name = requireNotNull(op.name) { "Anonymous ${op.kind} in the shipped documents" }
            measureGraphqlDocument(wireDocument(op.kind, name)).single()
        }

        val table = buildString {
            appendLine()
            appendLine("GraphQL request limits (Unraid 7.4; margin ${(margin * 100).toInt()}%)")
            appendLine("%-34s %-13s %7s %6s %7s %6s %6s".format("operation", "kind", "cost", "depth", "aliases", "tokens", "bytes"))
            appendLine(
                "%-34s %-13s %7s %6s %7s %6s %6s".format(
                    "limit (threshold)", "",
                    "${GraphqlRequestLimits.MAX_COST}(${(GraphqlRequestLimits.MAX_COST * margin).toInt()})",
                    "${GraphqlRequestLimits.MAX_DEPTH}(${(GraphqlRequestLimits.MAX_DEPTH * margin).toInt()})",
                    "${GraphqlRequestLimits.MAX_ALIASES}(${(GraphqlRequestLimits.MAX_ALIASES * margin).toInt()})",
                    "${GraphqlRequestLimits.MAX_TOKENS}(${(GraphqlRequestLimits.MAX_TOKENS * margin).toInt()})",
                    "1MiB",
                ),
            )
            measured.forEach {
                appendLine("%-34s %-13s %7d %6d %7d %6d %6d".format(it.name, it.kind, it.cost, it.depth, it.aliases, it.tokens, it.bodyBytes))
            }
        }
        println(table)

        val found = violations(measured)
        assertTrue(
            "Operations over the safety margin of the Unraid 7.4 request limits " +
                "(slim the query down before the server starts refusing it):\n" + found.joinToString("\n") + "\n" + table,
            found.isEmpty(),
        )
    }
}
