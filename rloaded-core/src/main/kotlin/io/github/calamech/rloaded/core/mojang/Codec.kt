package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.core.Outcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** JSON decoding of Mojang metadata, with failures as values. */
internal object Codec {
    val json = Json { ignoreUnknownKeys = true }

    inline fun <reified T> decode(
        source: String,
        text: String,
    ): Outcome<T, MetadataError> = catching(source) { json.decodeFromString<T>(text) }

    inline fun <reified T> decode(
        source: String,
        element: JsonElement,
    ): Outcome<T, MetadataError> = catching(source) { json.decodeFromJsonElement<T>(element) }

    inline fun <T> catching(
        source: String,
        decode: () -> T,
    ): Outcome<T, MetadataError> =
        try {
            Outcome.Ok(decode())
        } catch (e: SerializationException) {
            Outcome.Err(MetadataError.Malformed(source, e.message ?: "invalid JSON"))
        } catch (e: IllegalArgumentException) {
            Outcome.Err(MetadataError.Malformed(source, e.message ?: "invalid JSON"))
        }
}
