package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.api.seerr.infrastructure.Serializer
import com.github.damontecres.wholphin.api.seerr.model.MediaInfo
import com.github.damontecres.wholphin.data.model.SEERR_POLL_ACTIVE
import com.github.damontecres.wholphin.data.model.SEERR_POLL_IDLE
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrDownload
import com.github.damontecres.wholphin.data.model.SeerrDownloadState
import com.github.damontecres.wholphin.data.model.parseTimeLeft
import com.github.damontecres.wholphin.data.model.seerrPollInterval
import com.github.damontecres.wholphin.services.SeerrService
import io.mockk.mockk
import kotlinx.serialization.json.decodeFromStream
import org.junit.Assert
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the mapping of Seerr's `mediaInfo.downloadStatus` into [SeerrDownload]
 *
 * The two fixtures are verbatim captures from a real Jellyseerr instance: a movie downloading via
 * Radarr, and a series with five concurrent Sonarr entries.
 */
class TestSeerrDownloads {
    // `downloads` is pure, so the injected dependencies are never touched
    private val service = SeerrService(mockk(), mockk(), mockk())

    private fun readMediaInfo(name: String): MediaInfo {
        val stream =
            javaClass.classLoader?.getResourceAsStream(name)
                ?: throw IllegalStateException("Missing fixture $name")
        return stream.use { Serializer.kotlinxSerializationJson.decodeFromStream(it) }
    }

    @Test
    fun `movie download is mapped`() {
        val downloads = service.downloads(readMediaInfo("seerr_media_info_movie.json"))
        Assert.assertEquals(1, downloads.size)
        val download = downloads.single()

        // 8431496209 exceeds Int.MAX_VALUE, so this asserts the spec's `format: int64`
        Assert.assertEquals(8431496209L, download.sizeBytes)
        Assert.assertEquals(8424975377L, download.sizeLeftBytes)
        Assert.assertEquals(6520832L, download.downloadedBytes)
        Assert.assertEquals(SeerrDownloadState.DOWNLOADING, download.state)
        Assert.assertEquals("8E19C6F725C4851E25E94756EA35814AD46543E5", download.downloadId)
        Assert.assertEquals(5.hours + 57.minutes + 46.seconds, download.timeLeft)
        Assert.assertTrue(download.hasProgress)
        Assert.assertEquals(0.00077f, download.progress, 0.0001f)

        // Movies carry no episode
        Assert.assertNull(download.episodeTitle)
        Assert.assertNull(download.seasonNumber)
    }

    @Test
    fun `movie has no 4k downloads`() {
        val mediaInfo = readMediaInfo("seerr_media_info_movie.json")
        Assert.assertTrue(service.downloads(mediaInfo, is4k = true).isEmpty())
    }

    @Test
    fun `tv downloads are sorted by episode`() {
        val downloads = service.downloads(readMediaInfo("seerr_media_info_tv.json"))
        Assert.assertEquals(5, downloads.size)
        // Seerr returns these in queue order: E03, E04, E01, E05, E02
        Assert.assertEquals(listOf(1, 2, 3, 4, 5), downloads.map { it.episodeNumber })
        Assert.assertEquals(List(5) { 1 }, downloads.map { it.seasonNumber })
    }

    @Test
    fun `tv download uses the sonarr episode title`() {
        val downloads = service.downloads(readMediaInfo("seerr_media_info_tv.json"))
        val episode3 = downloads.single { it.episodeNumber == 3 }

        // Sonarr sends `title` where the spec's TMDB `Episode` schema declares `name`. If the
        // spec is ever changed to reuse `Episode`, this silently becomes null.
        Assert.assertEquals("Read the Room", episode3.episodeTitle)
        Assert.assertEquals("Lucky 2026 S01E03 480p x264-mSD", episode3.releaseTitle)
        Assert.assertEquals(SeerrDownloadState.WARNING, episode3.state)
        Assert.assertTrue(episode3.state.isProblem)
    }

    @Test
    fun `zero size download does not divide by zero`() {
        val downloads = service.downloads(readMediaInfo("seerr_media_info_tv.json"))
        val episode4 = downloads.single { it.episodeNumber == 4 }

        Assert.assertEquals(0L, episode4.sizeBytes)
        Assert.assertEquals(0f, episode4.progress, 0f)
        Assert.assertFalse(episode4.hasProgress)
        Assert.assertEquals(SeerrDownloadState.QUEUED, episode4.state)
    }

    @Test
    fun `absent timeLeft is null`() {
        val downloads = service.downloads(readMediaInfo("seerr_media_info_tv.json"))
        // Only the "downloading" entry carries timeLeft
        Assert.assertNull(downloads.single { it.episodeNumber == 1 }.timeLeft)
        Assert.assertEquals(
            60.days + 1.hours + 15.minutes + 25.seconds,
            downloads.single { it.episodeNumber == 2 }.timeLeft,
        )
    }

    @Test
    fun `null media info yields no downloads`() {
        Assert.assertTrue(service.downloads(null).isEmpty())
    }

    @Test
    fun `parses time left formats`() {
        Assert.assertEquals(5.hours + 57.minutes + 46.seconds, parseTimeLeft("05:57:46"))
        Assert.assertEquals(60.days + 1.hours + 15.minutes + 25.seconds, parseTimeLeft("60.01:15:25"))
        Assert.assertEquals(0.seconds, parseTimeLeft("00:00:00"))
        Assert.assertEquals(1.hours + 2.minutes + 3.seconds, parseTimeLeft("01:02:03.456"))
        Assert.assertNull(parseTimeLeft(null))
        Assert.assertNull(parseTimeLeft(""))
        Assert.assertNull(parseTimeLeft("soon"))
        Assert.assertNull(parseTimeLeft("1:2"))
    }

    @Test
    fun `unknown download states fall back`() {
        Assert.assertEquals(SeerrDownloadState.UNKNOWN, SeerrDownloadState.from("downloadClientUnavailable"))
        Assert.assertEquals(SeerrDownloadState.UNKNOWN, SeerrDownloadState.from(null))
        Assert.assertEquals(SeerrDownloadState.DOWNLOADING, SeerrDownloadState.from("DOWNLOADING"))
    }

    private fun download(
        state: SeerrDownloadState,
        size: Long = 100L,
        sizeLeft: Long = 100L,
    ) = SeerrDownload(
        downloadId = "id",
        releaseTitle = null,
        episodeTitle = null,
        state = state,
        sizeBytes = size,
        sizeLeftBytes = sizeLeft,
        timeLeft = null,
        rawTimeLeft = null,
        seasonNumber = null,
        episodeNumber = null,
    )

    @Test
    fun `poll interval is active only when bytes are moving`() {
        Assert.assertEquals(
            SEERR_POLL_ACTIVE,
            seerrPollInterval(
                SeerrAvailability.PROCESSING,
                listOf(download(SeerrDownloadState.DOWNLOADING, sizeLeft = 50L)),
            ),
        )
    }

    @Test
    fun `poll interval is idle when the queue is stalled`() {
        // The real TV fixture: entries exist but every one is at 0%
        val stalled = service.downloads(readMediaInfo("seerr_media_info_tv.json"))
        Assert.assertEquals(SEERR_POLL_IDLE, seerrPollInterval(SeerrAvailability.PROCESSING, stalled))
    }

    @Test
    fun `poll interval is idle when awaiting an indexer grab`() {
        Assert.assertEquals(SEERR_POLL_IDLE, seerrPollInterval(SeerrAvailability.PENDING, emptyList()))
        Assert.assertEquals(SEERR_POLL_IDLE, seerrPollInterval(SeerrAvailability.PROCESSING, emptyList()))
    }

    @Test
    fun `poll stops in terminal states`() {
        Assert.assertNull(seerrPollInterval(SeerrAvailability.AVAILABLE, emptyList()))
        Assert.assertNull(seerrPollInterval(SeerrAvailability.UNKNOWN, emptyList()))
        Assert.assertNull(seerrPollInterval(SeerrAvailability.DELETED, emptyList()))
        Assert.assertNull(seerrPollInterval(SeerrAvailability.BLOCKLISTED, emptyList()))
        Assert.assertNull(seerrPollInterval(SeerrAvailability.PARTIALLY_AVAILABLE, emptyList()))
    }

    @Test
    fun `poll continues for a partially available series still downloading`() {
        // A series with one season available and another still downloading is partially
        // available, but very much still changing
        Assert.assertEquals(
            SEERR_POLL_ACTIVE,
            seerrPollInterval(
                SeerrAvailability.PARTIALLY_AVAILABLE,
                listOf(download(SeerrDownloadState.DOWNLOADING, sizeLeft = 1L)),
            ),
        )
    }
}
