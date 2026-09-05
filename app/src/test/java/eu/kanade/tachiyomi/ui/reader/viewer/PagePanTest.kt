package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PagePanTest {

    @Test
    fun `tall page moves down before turning forward`() {
        val direction = selectPagePanDirection(
            PagePanRemaining(left = 0f, top = 0f, right = 0f, bottom = 1200f),
            horizontalDirection = PagePanDirection.RIGHT,
            verticalDirection = PagePanDirection.DOWN,
        )

        assertEquals(PagePanDirection.DOWN, direction)
    }

    @Test
    fun `tall page moves up before turning backward`() {
        val direction = selectPagePanDirection(
            PagePanRemaining(left = 0f, top = 1200f, right = 0f, bottom = 0f),
            horizontalDirection = PagePanDirection.LEFT,
            verticalDirection = PagePanDirection.UP,
        )

        assertEquals(PagePanDirection.UP, direction)
    }

    @Test
    fun `wide right to left page follows reading direction`() {
        val direction = selectPagePanDirection(
            PagePanRemaining(left = 900f, top = 0f, right = 0f, bottom = 0f),
            horizontalDirection = PagePanDirection.LEFT,
            verticalDirection = PagePanDirection.DOWN,
        )

        assertEquals(PagePanDirection.LEFT, direction)
    }

    @Test
    fun `page turns when no image area remains`() {
        val direction = selectPagePanDirection(
            PagePanRemaining(left = 0f, top = 0f, right = 0f, bottom = 0f),
            horizontalDirection = PagePanDirection.RIGHT,
            verticalDirection = PagePanDirection.DOWN,
        )

        assertNull(direction)
    }

    @Test
    fun `backward browsing starts at the page bottom end`() {
        val position = pageEntryPosition(
            forward = false,
            forwardHorizontalDirection = PagePanDirection.RIGHT,
        )

        assertEquals(PagePanDirection.RIGHT, position.horizontal)
        assertEquals(PagePanDirection.DOWN, position.vertical)
    }

    @Test
    fun `entering next right to left page starts at its top right`() {
        val position = pageEntryPosition(
            forward = true,
            forwardHorizontalDirection = PagePanDirection.LEFT,
        )

        assertEquals(PagePanDirection.RIGHT, position.horizontal)
        assertEquals(PagePanDirection.UP, position.vertical)
    }
}
