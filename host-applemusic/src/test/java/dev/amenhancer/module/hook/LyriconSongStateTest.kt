package dev.amenhancer.module.hook

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class LyriconSongStateTest {
    private fun lyrics(id: String, text: String) = Song(id = id, duration = 3000,
        lyrics = listOf(RichLyricLine(begin = 0, end = 3000, text = text)))

    @Test fun `late previous track lyrics cannot overwrite the current track`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.metadata(Song(id = "1", name = "One"))
        state.metadata(Song(id = "2", name = "Two"))
        state.lyrics(lyrics("1", "old"))
        assertEquals(2, output.size)
        assertEquals("2", output.last()?.id)
        state.lyrics(lyrics("2", "new"))
        assertEquals("new", output.last()?.lyrics?.single()?.text)
        assertEquals("Two", output.last()?.name)
    }

    @Test fun `lyrics arriving before metadata are cached and restored on reconnect`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.lyrics(lyrics("1", "ready"))
        assertTrue(output.isEmpty())
        assertTrue(state.metadata(Song(id = "1", artist = "Artist")))
        assertTrue(state.hasLyrics)
        assertEquals(3000L, output.last()?.duration)
        assertFalse(state.metadata(Song(id = "1", artist = "Corrected")))
        assertEquals("ready", output.last()?.lyrics?.single()?.text)
        assertEquals("Corrected", output.last()?.artist)
        val count = output.size
        state.lyrics(lyrics("1", "ready"))
        assertEquals(count, output.size)
        state.metadata(null)
        assertNull(output.last())
        state.lyrics(lyrics("1", "late"))
        assertNull(output.last())
    }

    @Test fun `older tracks are evicted from the bounded lyric cache`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        for (id in 1..17) state.lyrics(lyrics(id.toString(), "track"))
        state.metadata(Song(id = "1"))
        assertFalse(state.hasLyrics)
        state.metadata(Song(id = "17"))
        assertTrue(state.hasLyrics)
    }

    @Test fun `installed AM replacement wins over a late official download`() {
        val output = mutableListOf<Song?>()
        val state = LyriconSongState(output::add)
        state.metadata(Song(id = "1"))
        state.lyrics(lyrics("1", "manual"), installed = true)
        state.lyrics(lyrics("1", "official"))
        assertEquals("manual", output.last()?.lyrics?.single()?.text)
        state.lyrics(lyrics("1", "updated translation"), installed = true)
        assertEquals("updated translation", output.last()?.lyrics?.single()?.text)
    }
}
