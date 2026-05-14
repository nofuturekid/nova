# ADR-0007: Apollo JSON scalar via built-in `AnyAdapter`

- **Status**: Accepted
- **Date**: 2026-05-14
- **Tags**: data, graphql

## Context

The Unraid 7 GraphQL schema uses a `JSON` custom scalar in a few places — most importantly `DockerContainer.mounts`, which returns an inline JSON array of mount objects. The server returns these as actual structured values (`[{"source": "...", "target": "..."}, ...]`), **not** as pre-stringified text.

Apollo Kotlin's default `Adapter<Any>` config didn't ship in earlier versions, and the project's first attempt — mapping `JSON` to `kotlin.String` — produced runtime crashes whenever the server returned an inline object: Apollo would feed a `Map<String, Any?>` token-stream to a `String` adapter, which threw immediately. This bit us in v0.1.13 and we reverted in v0.1.14.

A second attempt wrote a custom `JsonAnyAdapter` calling `reader.readAny()`. That worked locally but `readAny()` is `@ApolloInternal` and breaks on minor Apollo bumps.

## Decision

Map the `JSON` scalar to `kotlin.Any` using Apollo's **public, built-in `com.apollographql.apollo.api.AnyAdapter`**:

```kotlin
// app/build.gradle.kts
mapScalar("JSON", "kotlin.Any", "com.apollographql.apollo.api.AnyAdapter")
```

Downstream code in `GraphQlMapper.kt` does the type-narrowing — e.g. `parseMountsArray(any: Any?)` casts to `List<Map<*, *>>` and surfaces structured mount entries to the UI.

## Consequences

**Positive**
- Inline JSON objects/arrays deserialise correctly into Kotlin `Map` / `List`.
- We use a public API surface, not internal-marked symbols.
- No custom adapter code to maintain through Apollo upgrades.

**Trade-offs**
- The Kotlin type for `JSON`-valued fields is `Any?` — every consumer needs `safeAs` / `is` checks. Loses compile-time type info. Acceptable because `JSON`-scalars are inherently dynamic; the schema is opting out of typing on purpose.
- `parseMountsArray` and friends are the right place to centralise the cast; scattering `as List<*>` calls would be a code smell.

**Trigger to revisit**
- If Apollo Kotlin ever provides a typed `JSON` scalar (codegen from the schema with a sealed-class representation), migrate.
- If the Unraid schema replaces `JSON` with strongly-typed fields, the mapping becomes moot for those.

## Alternatives considered

- **`mapScalar("JSON", "kotlin.String")`** — what we had in v0.1.13; crashed on inline objects.
- **Custom `JsonAnyAdapter` using `reader.readAny()`** — used internal API (`@ApolloInternal`), brittle across versions.
- **kotlinx.serialization `JsonElement`** — already a dependency, but adapting Apollo's `JsonReader` to it would require writing the adapter we just decided to skip.
- **Don't request `mounts` in the snapshot at all** — what we did briefly in v0.1.12. Defers but doesn't fix the type problem and loses the data the UI needs.

## References

- `app/build.gradle.kts` — the `mapScalar` declaration.
- `app/src/main/kotlin/.../data/api/GraphQlMapper.kt` — the `parseMountsArray` helper.
- `app/src/main/graphql/.../schema.graphqls` — `DockerContainer.mounts: JSON`.
- v0.1.11 / v0.1.13 / v0.1.14 / v0.1.15 release notes — the bug history that produced this decision.
