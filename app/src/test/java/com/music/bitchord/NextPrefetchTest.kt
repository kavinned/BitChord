package com.music.bitchord

import com.music.bitchord.data.NerdStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The next-track prefetch line: what it says at each stage of the download. */
class NextPrefetchTest {

    @Before
    fun setUp() {
        NerdStats.forgetLastSession()
    }

    private fun prefetch(cachedBytes: Long, totalBytes: Long, pinned: Boolean = true) = NerdStats.NextPrefetch(
        mediaId = "next-track",
        title = "Ice On My Teeth",
        sourceName = "Nebula",
        format = "FLAC",
        cachedBytes = cachedBytes,
        totalBytes = totalBytes,
        pinned = pinned,
    )

    @Test
    fun `a pin with no bytes yet is pinned`() {
        assertEquals("pinned", prefetch(0, 0).describe())
    }

    @Test
    fun `bytes with no known total still show progress`() {
        assertEquals("1.0 MB cached", prefetch(1_000_000, 0).describe())
    }

    @Test
    fun `nothing pinned says none`() {
        assertEquals("none", prefetch(0, 0, pinned = false).describe())
    }

    @Test
    fun `a partial copy shows both sides of the fraction and the percentage`() {
        assertEquals("1.0/4.0 MB (25%)", prefetch(1_000_000, 4_000_000).describe())
    }

    @Test
    fun `a whole copy says complete and its size`() {
        assertEquals("complete (4.0 MB)", prefetch(4_000_000, 4_000_000).describe())
    }

    @Test
    fun `publishing and clearing the state round-trips`() {
        val value = prefetch(1_000_000, 4_000_000)
        NerdStats.publishNextPrefetch(value)
        assertEquals(value, NerdStats.nextPrefetch.value)

        NerdStats.publishNextPrefetch(null)
        assertNull(NerdStats.nextPrefetch.value)
    }

    @Test
    fun `a forgotten session clears the prefetch state`() {
        NerdStats.publishNextPrefetch(prefetch(1_000_000, 4_000_000))

        NerdStats.forgetLastSession()

        assertNull(NerdStats.nextPrefetch.value)
    }
}
