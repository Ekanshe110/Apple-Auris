package com.music.bitchord

import com.music.bitchord.data.apple.AppleRadio
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonNotFound
import com.music.bitchord.data.sources.addon.AddonTrack
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the addon's radio routes.
 *
 * The radio routes are the only place the app asks Apple for a station, and
 * their rows are ordinary catalogue rows: same ids, same playback path. These
 * tests pin that shape down, so a station row that stopped being playable
 * would fail here rather than in the listener's queue.
 */
class AppleRadioClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stations are read from the radio stations route`() = runBlocking {
        server.enqueue(
            json(
                """
                {"stations":[
                  {"id":"ra.u-1234","name":"My Station","kind":"user","subtitle":"Your station",
                   "artworkURL":"https://example.test/cover.jpg"},
                  {"id":"ra.617154362","name":"Song Station","kind":"songSeeded"}
                ]}
                """.trimIndent(),
            ),
        )

        val stations = AddonClient(server.url("/").toString()).radioStations().getOrThrow()

        assertEquals(2, stations.size)
        assertEquals("ra.u-1234", stations[0].id)
        assertEquals("My Station", stations[0].name)
        assertEquals("user", stations[0].kind)
        assertEquals("Song Station", stations[1].name)
        assertEquals("/radio/stations", server.takeRequest().path)
    }

    @Test
    fun `station tracks are asked for by station id with a limit`() = runBlocking {
        server.enqueue(
            json(
                """
                {"tracks":[
                  {"id":"6781024437","title":"Bohemian Rhapsody","artist":"Queen",
                   "album":"A Night At The Opera","duration":354.32,"format":"m4a",
                   "audioQuality":"LOSSLESS","audioMode":"STEREO","atmos":false,"isrc":"GBUM71029604"}
                ]}
                """.trimIndent(),
            ),
        )

        val tracks = AddonClient(server.url("/").toString())
            .radioStationTracks("ra.617154362", limit = 5)
            .getOrThrow()

        assertEquals(1, tracks.size)
        assertEquals("6781024437", tracks[0].id)
        val request = server.takeRequest()
        assertEquals("/radio/station/ra.617154362/tracks", request.path?.substringBefore("?"))
        assertTrue(request.path!!.contains("limit=5"))
    }

    @Test
    fun `the station of a song is read by song id`() = runBlocking {
        server.enqueue(
            json(
                """
                {"station":{"id":"ra.617154362","name":"Instant Crush Station","kind":"songSeeded"}}
                """.trimIndent(),
            ),
        )

        val station = AddonClient(server.url("/").toString())
            .radioStationForSong("617154362")
            .getOrThrow()

        assertNotNull(station)
        assertEquals("ra.617154362", station!!.id)
        assertEquals("/radio/song/617154362/station", server.takeRequest().path)
    }

    @Test
    fun `an addon without radio answers 404 rather than an empty station list`() = runBlocking {
        server.enqueue(json("""{"error":"not found"}""", status = 404))

        val result = AddonClient(server.url("/").toString()).radioStations()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AddonNotFound)
    }

    @Test
    fun `a station row keeps the apple identity the player needs`() {
        val track = AddonTrack(
            id = "617154362",
            title = "Instant Crush",
            artist = "Daft Punk",
            album = "Random Access Memories",
            duration = 337.0,
            format = "m4a",
            audioQuality = "LOSSLESS",
            audioMode = "STEREO",
            isrc = "GBDUW0000059",
        )

        val song = AppleRadio.run { track.toStationSong("apple-config") }

        assertEquals("src:apple-config::617154362", song.videoId)
        assertEquals("617154362", song.appleCatalogId)
        assertEquals("5:37", song.durationText)
        assertEquals("Daft Punk", song.artist)
        assertEquals("GBDUW0000059", song.isrc)
    }

    @Test
    fun `a station tile is told apart from a catalogue playlist`() {
        val tile = AppleRadio.tile(
            com.music.bitchord.data.sources.addon.AddonStation(
                id = "ra.u-1234",
                name = "My Station",
                subtitle = "Your station",
                artworkURL = "https://example.test/cover.jpg",
            ),
        )

        assertEquals("apple-radio:ra.u-1234", tile.browseId)
        assertEquals("ra.u-1234", tile.stationId)
        assertEquals("My Station", tile.title)
    }

    private fun json(text: String, status: Int = 200): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(text)
}
