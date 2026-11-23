package io.github.axon07.channelbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A TV guide source attached to some playlists only links and fills those playlists' channels. */
class EpgScopeTest {

    private fun candidate(id: String, epgId: String?, vararg names: String) =
        EpgMatcher.Candidate(id, epgId?.lowercase(), names.toList())

    @Test
    fun scopedGuideOnlyFillsItsPlaylistsChannels() {
        val m = EpgMatcher(
            listOf(
                candidate("p1-cbc", "cbc.ca", "CBC"),
                candidate("p2-cbc", null, "CBC"),
                candidate("p2-ctv", "ctv.ca", "CTV"),
            )
        )
        // A guide attached to playlist 1 only.
        m.scope = setOf("p1-cbc")
        m.onChannel("cbc.ca", listOf("CBC"))
        m.onChannel("ctv.ca", listOf("CTV"))
        assertTrue(m.wants("cbc.ca"))
        assertFalse(m.wants("ctv.ca"))
        assertEquals("cbc.ca", m.assigned["p1-cbc"])
        assertNull(m.assigned["p2-cbc"])
        assertNull(m.assigned["p2-ctv"])
        m.releaseEmpty(setOf("cbc.ca"))

        // The next guide is for every playlist: playlist 2's channels match now.
        m.scope = null
        m.onChannel("ctv.ca", listOf("CTV"))
        m.onChannel("cbc.other", listOf("CBC"))
        assertTrue(m.wants("ctv.ca"))
        assertEquals("ctv.ca", m.assigned["p2-ctv"])
        assertEquals("cbc.other", m.assigned["p2-cbc"])
        // Playlist 1's channel keeps the guide it already got.
        assertEquals("cbc.ca", m.assigned["p1-cbc"])
    }

    @Test
    fun guideSourcesSurviveTheJsonRoundTrip() {
        val src = EpgSource(id = "g1", name = "Local", url = "https://example.com/epg.xml.gz", enabled = false, shiftMinutes = -90, playlists = listOf("a", "b"), provider = "a")
        assertEquals(src, EpgSource.fromJson(src.toJson()))
        assertTrue(src.usedBy("a"))
        assertFalse(src.usedBy("c"))
        // A default source is used by every playlist.
        assertTrue(src.copy(isDefault = true).usedBy("c"))
        assertFalse(src.copy(playlists = emptyList()).usedBy("c"))
        // Saved before "Default source" existed: no playlists meant every playlist.
        val old = kotlinx.serialization.json.JsonObject(src.copy(playlists = emptyList()).toJson().filterKeys { it != "default" })
        assertTrue(EpgSource.fromJson(old)!!.isDefault)
    }
}
