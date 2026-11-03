package com.music.bitchord

import com.music.bitchord.download.MediaTagger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** A tag write replaces the file whole, so a failure cannot leave a half-written track committed. */
class MediaTaggerWriteTest {

    /**
     * The write must land as a replacement, not an in-place rewrite, and it
     * must not leave its working file behind for the store to commit beside
     * the track.
     */
    @Test
    fun `a tag write replaces the target and leaves nothing beside it`() {
        val root = packageDir()
        val target = File(root, "track.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        MediaTagger.writeBeside(target, byteArrayOf(4, 5, 6, 7))

        assertArrayEquals(byteArrayOf(4, 5, 6, 7), target.readBytes())
        assertTrue(root.list().orEmpty().none { it.endsWith(".tmp") })
    }

    private fun packageDir(): File =
        createTempDirectory("tagwrite").toFile().also(File::deleteOnExit)
}
