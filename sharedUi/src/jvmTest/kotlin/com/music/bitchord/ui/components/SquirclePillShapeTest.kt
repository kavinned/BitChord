package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the degenerate size is checked here.
 *
 * The path-reuse contract — the clip and the background share one build per
 * frame — needs a real `Path` to observe, and `Path()` is Skia-backed on this
 * target: `jvmTest` has no `skiko-windows-x64` native, so building one throws
 * `LibraryLoadException`. That half is verified on device instead; the number
 * in `profiling.md` is the frame cost it removes.
 */
class SquirclePillShapeTest {

    private fun SquirclePillShape.outline(width: Float, height: Float): Outline =
        createOutline(Size(width, height), 0f, 0f, 0f, 0f, LayoutDirection.Ltr)

    @Test
    fun aZeroSizedPillIsAPlainRectangle() {
        val shape = SquirclePillShape()

        // Before the cache: a zero size must not build a path and must not be
        // remembered as the size an outline was last built for.
        assertTrue(shape.outline(40f, 0f) is Outline.Rectangle)
    }
}
