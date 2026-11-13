package io.github.nofuturekid.nova.data.api

import kotlin.math.max

/**
 * Test-only measuring tool for GraphQL documents: the same numbers the Unraid
 * API (7.4+) checks before it runs a request (#320).
 *
 * Source of the limits and of the formulas: unraid/api,
 * `api/src/unraid-api/graph/request-protection.ts` (commit 8e587fe, 2026-11-04),
 * which configures `@escape.tech/graphql-armor` 3.2.0 and the `graphql` 16.11.0
 * parser. The formulas below are re-implemented from the plugins' published
 * code, not from memory:
 *
 *  - cost:    `@escape.tech/graphql-armor-cost-limit` 2.4.3 (`CostLimitVisitor.computeComplexity`)
 *  - depth:   `@escape.tech/graphql-armor-max-depth` 2.4.2 (`MaxDepthVisitor.countDepth`)
 *  - aliases: `@escape.tech/graphql-armor-max-aliases` 2.6.2 (`MaxAliasesVisitor.countAliases`)
 *  - tokens:  `graphql` 16.11.0 `Parser.advanceLexer` (`parseOptions.maxTokens`)
 *
 * The one deliberate simplification: the plugins memoise fragment results in a
 * per-validation map; for documents that pass GraphQL's own "no fragment
 * cycles" rule that memo returns exactly what a fresh walk returns, so it is
 * left out here. Everything else (flattened fragments, no introspection
 * special case, `first`/`last` multiplier) follows the server's configuration.
 *
 * The cost needs no schema knowledge: with object cost 1, scalar cost 1 and
 * depth factor 1 the plugin does not look at types, only at whether a field has
 * a selection set. The only argument it reads is an integer literal `first` or
 * `last` (a multiplier); NOVA uses neither (`limit: 200` is not one), so the
 * number reported here is exact, not an upper bound. A variable `first`/`last`
 * would count as multiplier 1 on the server too.
 */
internal object GraphqlRequestLimits {
    // request-protection.ts: costLimit { maxCost: 1_000, objectCost: 1, scalarCost: 1,
    // depthCostFactor: 1, flattenFragments: true, ignoreIntrospection: false }
    const val MAX_COST = 1_000
    const val OBJECT_COST = 1L
    const val SCALAR_COST = 1L
    const val DEPTH_COST_FACTOR = 1L

    // request-protection.ts: maxDepth { n: 20, flattenFragments: true, ignoreIntrospection: false }
    const val MAX_DEPTH = 20

    // request-protection.ts: maxAliases { n: 100, allowList: [] }
    const val MAX_ALIASES = 100

    // request-protection.ts: maxGraphqlTokens = 5_000 (graph.module.ts parseOptions for HTTP,
    // createGraphqlWsOptions.onSubscribe for WebSocket subscribe messages).
    const val MAX_TOKENS = 5_000

    // No bodyLimit is set anywhere in the API (main.ts builds `new FastifyAdapter({ trustProxy })`),
    // so Fastify 5's default of 1 MiB applies to the JSON request body.
    const val MAX_BODY_BYTES = 1_048_576

    /** NOVA's own safety margin: no shipped operation may use more than this share of any limit. */
    const val MARGIN = 0.7
}

internal enum class TokenKind { PUNCT, NAME, INT, FLOAT, STRING }

internal data class GqlToken(val kind: TokenKind, val text: String)

internal sealed interface Selection

internal data class FieldSelection(
    val alias: String?,
    val name: String,
    /** The `first`/`last` multiplier of the cost plugin; 1 when absent or not an integer literal. */
    val multiplier: Int,
    /** null for a leaf field (no selection set). */
    val selections: List<Selection>?,
) : Selection

internal data class InlineFragmentSelection(val selections: List<Selection>) : Selection

internal data class FragmentSpreadSelection(val name: String) : Selection

internal data class OperationDefinition(val kind: String, val name: String?, val selections: List<Selection>)

internal data class FragmentDefinition(val name: String, val selections: List<Selection>)

internal class ParsedDocument(
    val operations: List<OperationDefinition>,
    val fragments: Map<String, FragmentDefinition>,
    val tokenCount: Int,
)

/** Hand-written lexer: the Apollo compiler is a Gradle-plugin dependency and not on the test classpath. */
internal fun tokenizeGraphql(src: String): List<GqlToken> {
    val out = mutableListOf<GqlToken>()
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '﻿' -> i++
            c == '#' -> while (i < src.length && src[i] != '\n' && src[i] != '\r') i++
            c == '.' -> {
                require(src.startsWith("...", i)) { "Unexpected '.' at $i" }
                out += GqlToken(TokenKind.PUNCT, "...")
                i += 3
            }
            c in "!$&():=@[]{|}" -> {
                out += GqlToken(TokenKind.PUNCT, c.toString())
                i++
            }
            c == '"' -> {
                val start = i
                if (src.startsWith("\"\"\"", i)) {
                    i += 3
                    while (!src.startsWith("\"\"\"", i) || src[i - 1] == '\\') {
                        i++
                        require(i < src.length) { "Unterminated block string at $start" }
                    }
                    i += 3
                } else {
                    i++
                    while (src[i] != '"') {
                        i += if (src[i] == '\\') 2 else 1
                        require(i < src.length) { "Unterminated string at $start" }
                    }
                    i++
                }
                out += GqlToken(TokenKind.STRING, src.substring(start, i))
            }
            c == '-' || c.isDigit() -> {
                val start = i
                i++
                while (i < src.length && (src[i].isDigit() || src[i] in ".eE+-")) i++
                val text = src.substring(start, i)
                val isFloat = text.any { it == '.' || it == 'e' || it == 'E' }
                out += GqlToken(if (isFloat) TokenKind.FLOAT else TokenKind.INT, text)
            }
            c == '_' || c in 'a'..'z' || c in 'A'..'Z' -> {
                val start = i
                while (i < src.length && (src[i] == '_' || src[i].isLetterOrDigit())) i++
                out += GqlToken(TokenKind.NAME, src.substring(start, i))
            }
            else -> error("Unexpected character '$c' at $i")
        }
    }
    return out
}

/** The graphql-js token count: every token except comments, whitespace, commas and the end marker. */
internal fun countGraphqlTokens(src: String): Int = tokenizeGraphql(src).size

/** Parses operations and fragments; type-system definitions are not supported (NOVA ships none in documents). */
internal fun parseGraphqlDocument(src: String): ParsedDocument {
    val parser = GqlParser(tokenizeGraphql(src))
    return parser.parseDocument()
}

private class GqlParser(private val tokens: List<GqlToken>) {
    private var pos = 0

    private fun peek(): GqlToken? = tokens.getOrNull(pos)
    private fun isPunct(p: String) = peek()?.let { it.kind == TokenKind.PUNCT && it.text == p } == true
    private fun isName(n: String? = null) = peek()?.let { it.kind == TokenKind.NAME && (n == null || it.text == n) } == true

    private fun next(): GqlToken = tokens.getOrNull(pos++) ?: error("Unexpected end of document")

    private fun expectPunct(p: String) {
        val t = next()
        require(t.kind == TokenKind.PUNCT && t.text == p) { "Expected '$p' but found '${t.text}' (token ${pos - 1})" }
    }

    private fun expectName(): String {
        val t = next()
        require(t.kind == TokenKind.NAME) { "Expected a name but found '${t.text}' (token ${pos - 1})" }
        return t.text
    }

    fun parseDocument(): ParsedDocument {
        val operations = mutableListOf<OperationDefinition>()
        val fragments = linkedMapOf<String, FragmentDefinition>()
        while (peek() != null) {
            when {
                isPunct("{") -> operations += OperationDefinition("query", null, parseSelectionSet())
                isName("fragment") -> {
                    next()
                    val name = expectName()
                    require(isName("on")) { "Fragment $name: expected 'on'" }
                    next()
                    expectName()
                    skipDirectives()
                    fragments[name] = FragmentDefinition(name, parseSelectionSet())
                }
                isName("query") || isName("mutation") || isName("subscription") -> {
                    val kind = next().text
                    val name = if (isName()) next().text else null
                    if (isPunct("(")) skipVariableDefinitions()
                    skipDirectives()
                    operations += OperationDefinition(kind, name, parseSelectionSet())
                }
                else -> error("Unexpected token '${peek()?.text}' at the top level (token $pos)")
            }
        }
        return ParsedDocument(operations, fragments, tokens.size)
    }

    private fun skipVariableDefinitions() {
        expectPunct("(")
        while (!isPunct(")")) {
            expectPunct("$")
            expectName()
            expectPunct(":")
            skipType()
            if (isPunct("=")) {
                next()
                parseValue()
            }
            skipDirectives()
        }
        expectPunct(")")
    }

    private fun skipType() {
        if (isPunct("[")) {
            next()
            skipType()
            expectPunct("]")
        } else {
            expectName()
        }
        if (isPunct("!")) next()
    }

    private fun skipDirectives() {
        while (isPunct("@")) {
            next()
            expectName()
            if (isPunct("(")) parseArguments()
        }
    }

    private fun parseSelectionSet(): List<Selection> {
        expectPunct("{")
        val out = mutableListOf<Selection>()
        while (!isPunct("}")) out += parseSelection()
        expectPunct("}")
        return out
    }

    private fun parseSelection(): Selection {
        if (isPunct("...")) {
            next()
            if (isName() && !isName("on")) {
                val name = next().text
                skipDirectives()
                return FragmentSpreadSelection(name)
            }
            if (isName("on")) {
                next()
                expectName()
            }
            skipDirectives()
            return InlineFragmentSelection(parseSelectionSet())
        }
        var name = expectName()
        var alias: String? = null
        if (isPunct(":")) {
            next()
            alias = name
            name = expectName()
        }
        val multiplier = if (isPunct("(")) parseArguments() else 1
        skipDirectives()
        val selections = if (isPunct("{")) parseSelectionSet() else null
        return FieldSelection(alias, name, multiplier, selections)
    }

    /** Returns the cost plugin's multiplier: the first `first`/`last` argument decides, an integer literal counts. */
    private fun parseArguments(): Int {
        expectPunct("(")
        var multiplier = 1
        var decided = false
        while (!isPunct(")")) {
            val name = expectName()
            expectPunct(":")
            val value = parseValue()
            if (!decided && (name == "first" || name == "last")) {
                decided = true
                if (value.kind == TokenKind.INT) multiplier = max(value.text.toIntOrNull() ?: 1, 1)
            }
        }
        expectPunct(")")
        return multiplier
    }

    private fun parseValue(): GqlToken {
        val t = next()
        when {
            t.kind == TokenKind.PUNCT && t.text == "$" -> expectName()
            t.kind == TokenKind.PUNCT && t.text == "[" -> {
                while (!isPunct("]")) parseValue()
                expectPunct("]")
            }
            t.kind == TokenKind.PUNCT && t.text == "{" -> {
                while (!isPunct("}")) {
                    expectName()
                    expectPunct(":")
                    parseValue()
                }
                expectPunct("}")
            }
            t.kind == TokenKind.PUNCT -> error("Unexpected '${t.text}' in a value")
        }
        return t
    }
}

internal data class OperationMetrics(
    val name: String,
    val kind: String,
    val tokens: Int,
    val depth: Int,
    val aliases: Int,
    val cost: Long,
    val bodyBytes: Int,
)

/** Measures every operation of [src] by the server's formulas; fragments of the same document are resolved. */
internal fun measureGraphqlDocument(src: String): List<OperationMetrics> {
    val doc = parseGraphqlDocument(src)
    val bytes = src.toByteArray(Charsets.UTF_8).size
    return doc.operations.map { op ->
        OperationMetrics(
            name = op.name ?: "(anonymous)",
            kind = op.kind,
            tokens = doc.tokenCount,
            depth = depthOf(op.selections, 0, doc.fragments),
            aliases = aliasesOf(op.selections, doc.fragments),
            cost = op.selections.sumOf { rootCost(it, doc.fragments) },
            bodyBytes = bytes,
        )
    }
}

// graphql-armor-cost-limit: computeComplexity for the operation's own children (no depth factor).
private fun rootCost(sel: Selection, fragments: Map<String, FragmentDefinition>): Long = when (sel) {
    is FieldSelection -> fieldCost(sel, fragments)
    is InlineFragmentSelection -> GraphqlRequestLimits.OBJECT_COST + childTerms(sel.selections, fragments)
    is FragmentSpreadSelection -> spreadCost(sel, fragments)
}

private fun fieldCost(f: FieldSelection, fragments: Map<String, FragmentDefinition>): Long {
    val children = f.selections ?: return GraphqlRequestLimits.SCALAR_COST
    return (GraphqlRequestLimits.OBJECT_COST + childTerms(children, fragments)) * f.multiplier
}

// flattenFragments: fields below a selection set are weighted by the depth factor, fragments are added as they are.
private fun childTerms(children: List<Selection>, fragments: Map<String, FragmentDefinition>): Long =
    children.sumOf { child ->
        when (child) {
            is FieldSelection -> GraphqlRequestLimits.DEPTH_COST_FACTOR * fieldCost(child, fragments)
            is InlineFragmentSelection ->
                GraphqlRequestLimits.OBJECT_COST + childTerms(child.selections, fragments)
            is FragmentSpreadSelection -> spreadCost(child, fragments)
        }
    }

private fun spreadCost(s: FragmentSpreadSelection, fragments: Map<String, FragmentDefinition>): Long {
    val def = fragments[s.name] ?: error("Fragment ${s.name} is spread but not defined in the document")
    return GraphqlRequestLimits.SCALAR_COST + GraphqlRequestLimits.OBJECT_COST + childTerms(def.selections, fragments)
}

// graphql-armor-max-depth, flattenFragments: a field sits one level below its parent; inline
// fragments and spreads add no level of their own.
private fun depthOf(children: List<Selection>, parentDepth: Int, fragments: Map<String, FragmentDefinition>): Int {
    var depth = parentDepth
    for (child in children) {
        depth = max(
            depth,
            when (child) {
                is FieldSelection -> {
                    val level = parentDepth + 1
                    child.selections?.let { depthOf(it, level, fragments) } ?: level
                }
                is InlineFragmentSelection -> depthOf(child.selections, parentDepth, fragments)
                is FragmentSpreadSelection -> {
                    val def = fragments[child.name] ?: error("Fragment ${child.name} is spread but not defined")
                    parentDepth + depthOf(def.selections, 0, fragments)
                }
            },
        )
    }
    return depth
}

// graphql-armor-max-aliases with allowList []: every aliased field counts, also inside fragments.
private fun aliasesOf(children: List<Selection>, fragments: Map<String, FragmentDefinition>): Int =
    children.sumOf { child ->
        when (child) {
            is FieldSelection ->
                (if (child.alias != null) 1 else 0) + (child.selections?.let { aliasesOf(it, fragments) } ?: 0)
            is InlineFragmentSelection -> aliasesOf(child.selections, fragments)
            is FragmentSpreadSelection -> {
                val def = fragments[child.name] ?: error("Fragment ${child.name} is spread but not defined")
                aliasesOf(def.selections, fragments)
            }
        }
    }
