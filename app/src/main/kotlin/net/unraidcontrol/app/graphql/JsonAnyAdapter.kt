package net.unraidcontrol.app.graphql

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.json.readAny
import com.apollographql.apollo.api.json.writeAny

/**
 * Adapter for the Unraid `JSON` GraphQL scalar.
 *
 * The Unraid Connect server serialises this scalar as inline JSON values —
 * objects, arrays, primitives — NOT pre-stringified text. Mapping `JSON`
 * to `kotlin.String` (the previous default) caused Apollo's String adapter
 * to throw on every snapshot poll because it found arrays/objects instead
 * of a quoted string.
 *
 * `kotlin.Any?` plus this adapter delivers Maps/Lists/primitives exactly
 * as Kotlin would deserialise from JSON, which is what the call site
 * actually wants (e.g. parseMountsArray then casts to `List<*>` and each
 * entry to `Map<*, *>`).
 */
object JsonAnyAdapter : Adapter<Any?> {
    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): Any? =
        reader.readAny()

    override fun toJson(writer: JsonWriter, customScalarAdapters: CustomScalarAdapters, value: Any?) {
        writer.writeAny(value)
    }
}
