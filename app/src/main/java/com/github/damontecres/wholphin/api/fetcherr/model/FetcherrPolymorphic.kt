@file:OptIn(ExperimentalSerializationApi::class)

package com.github.damontecres.wholphin.api.fetcherr.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Fetcherr's spec describes these as `oneOf` without a discriminator mapping, which the OpenAPI
 * kotlin generator flattens into a single class with every variant's properties marked required.
 * They are mapped to these hand-written types instead, see `schemaMappings` in app/build.gradle.kts.
 *
 * They use the `type` property as the discriminator (kotlinx's default), except [ListSource]
 * which uses `kind` and [SearchResult] which uses `result_type`.
 */

/**
 * The part of a title a request wants
 */
@Serializable
sealed interface Scope {
    @Serializable
    @SerialName("movie")
    data object Movie : Scope

    /** Every aired episode of a season */
    @Serializable
    @SerialName("season")
    data class Season(
        val season: Int,
    ) : Scope

    @Serializable
    @SerialName("episodes")
    data class Episodes(
        val season: Int,
        val episodes: List<Int>,
    ) : Scope
}

/**
 * What part of a title a release (or a file in it) covers
 */
@Serializable
sealed interface Coverage {
    /** No season or episode marker */
    @Serializable
    @SerialName("movie")
    data object Movie : Coverage

    /** Whole seasons. Empty [seasons] with [complete] means every season. */
    @Serializable
    @SerialName("season_pack")
    data class SeasonPack(
        val seasons: List<Int>,
        val complete: Boolean,
    ) : Coverage

    @Serializable
    @SerialName("episodes")
    data class Episodes(
        val season: Int,
        val episodes: List<Int>,
    ) : Coverage
}

/**
 * Where a discover list's titles come from on TMDB
 */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ListSource {
    /** [media] null means movies and series together */
    @Serializable
    @SerialName("trending")
    data class Trending(
        val media: TitleKind? = null,
        val window: TimeWindow? = null,
    ) : ListSource

    @Serializable
    @SerialName("popular")
    data class Popular(
        val media: TitleKind,
    ) : ListSource

    @Serializable
    @SerialName("top_rated")
    data class TopRated(
        val media: TitleKind,
    ) : ListSource

    /** Movies in theatres */
    @Serializable
    @SerialName("now_playing")
    data object NowPlaying : ListSource

    /** Upcoming movies */
    @Serializable
    @SerialName("upcoming")
    data object Upcoming : ListSource

    /** Series with an episode in the next 7 days */
    @Serializable
    @SerialName("on_the_air")
    data object OnTheAir : ListSource

    /** Series with an episode today */
    @Serializable
    @SerialName("airing_today")
    data object AiringToday : ListSource

    /** TMDB discover, shaped by the list's filters */
    @Serializable
    @SerialName("discover")
    data class Discover(
        val media: TitleKind,
    ) : ListSource
}

/**
 * A message of `GET /api/ws/events`, sent as one JSON text frame
 */
@Serializable
sealed interface Event {
    /** A request was created or changed state, also sent for each matching request on connect */
    @Serializable
    @SerialName("request_updated")
    data class RequestUpdated(
        val request: RequestSummary,
    ) : Event

    /** Download progress of a request, at most once per qBittorrent poll */
    @Serializable
    @SerialName("request_progress")
    data class RequestProgress(
        @SerialName("request_id") val requestId: Long,
        val key: String,
        /** Fraction from 0.0 to 1.0 over all of the request's torrents */
        val progress: Double,
        /** Bytes per second */
        @SerialName("dl_speed") val dlSpeed: Long,
        @SerialName("eta_secs") val etaSecs: Long? = null,
    ) : Event

    @Serializable
    @SerialName("request_deleted")
    data class RequestDeleted(
        @SerialName("request_id") val requestId: Long,
        val key: String,
    ) : Event

    /** A title, season or episode appeared in or disappeared from the media server */
    @Serializable
    @SerialName("availability_changed")
    data class AvailabilityChanged(
        val key: String,
        val availability: Availability,
        val season: Int? = null,
        val episode: Int? = null,
        @SerialName("jellyfin_item_id") val jellyfinItemId: String? = null,
    ) : Event
}

/**
 * A search hit: a movie/series [Card] or a [PersonCard]
 *
 * The JSON is the card's own properties plus a `result_type`, so this delegates to the existing
 * card serializers rather than redeclaring their properties.
 */
@Serializable(with = SearchResultSerializer::class)
sealed interface SearchResult {
    val key: String

    data class Title(
        val card: Card,
    ) : SearchResult {
        override val key: String get() = card.key
    }

    data class Person(
        val person: PersonCard,
    ) : SearchResult {
        override val key: String get() = person.key
    }
}

object SearchResultSerializer : KSerializer<SearchResult> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.github.damontecres.wholphin.api.fetcherr.model.SearchResult")

    override fun deserialize(decoder: Decoder): SearchResult {
        val input =
            decoder as? JsonDecoder
                ?: throw SerializationException("SearchResult can only be decoded from JSON")
        val element = input.decodeJsonElement().jsonObject
        return when (val type = element[RESULT_TYPE]?.jsonPrimitive?.content) {
            "title" -> SearchResult.Title(input.json.decodeFromJsonElement(Card.serializer(), element))
            "person" -> SearchResult.Person(input.json.decodeFromJsonElement(PersonCard.serializer(), element))
            else -> throw SerializationException("Unknown search result_type: $type")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: SearchResult,
    ) {
        val output =
            encoder as? JsonEncoder
                ?: throw SerializationException("SearchResult can only be encoded to JSON")
        val (fields, type) =
            when (value) {
                is SearchResult.Title -> output.json.encodeToJsonElement(Card.serializer(), value.card) to "title"
                is SearchResult.Person -> output.json.encodeToJsonElement(PersonCard.serializer(), value.person) to "person"
            }
        output.encodeJsonElement(JsonObject(fields.jsonObject + (RESULT_TYPE to JsonPrimitive(type))))
    }

    private const val RESULT_TYPE = "result_type"
}
