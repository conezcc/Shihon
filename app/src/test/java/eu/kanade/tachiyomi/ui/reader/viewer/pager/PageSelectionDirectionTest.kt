package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PageSelectionDirectionTest {

    private data class Page(val chapter: Int, val number: Int, val secondHalf: Boolean = false)

    private val lastPage = Page(1, 100)
    private val firstPage = Page(2, 1)
    private val secondHalf = Page(2, 1, secondHalf = true)
    private val nextPage = Page(2, 2)
    private val readingOrder = listOf(lastPage, firstPage, secondHalf, nextPage)

    @Test
    fun `chapter number reset and split pages preserve direction in both reading modes`() {
        for (rtl in listOf(false, true)) {
            val items = if (rtl) readingOrder.reversed() else readingOrder
            for ((previous, next) in readingOrder.zipWithNext()) {
                assertEquals(true, isForwardPageSelection(items, previous, items.indexOf(next), rtl))
                assertEquals(false, isForwardPageSelection(items, next, items.indexOf(previous), rtl))
            }
        }
    }

    @Test
    fun `inserting preloaded pages does not change direction`() {
        for (rtl in listOf(false, true)) {
            val expanded = listOf(Page(0, 1), Page(0, 2)) + readingOrder
            val items = if (rtl) expanded.reversed() else expanded
            assertEquals(true, isForwardPageSelection(items, firstPage, items.indexOf(secondHalf), rtl))
        }
    }

    @Test
    fun `chapter transition follows its actual position`() {
        val transition = Any()
        for (rtl in listOf(false, true)) {
            val order = listOf(lastPage, transition, firstPage)
            val items = if (rtl) order.reversed() else order
            assertEquals(true, isForwardPageSelection(items, transition, items.indexOf(firstPage), rtl))
            assertEquals(false, isForwardPageSelection(items, transition, items.indexOf(lastPage), rtl))
        }
    }

    @Test
    fun `initial removed unchanged and invalid selections have no direction`() {
        assertNull(isForwardPageSelection(readingOrder, null, 0, false))
        assertNull(isForwardPageSelection(readingOrder, Page(0, 1), 0, false))
        assertNull(isForwardPageSelection(readingOrder, firstPage, 1, false))
        assertNull(isForwardPageSelection(readingOrder, firstPage, -1, false))
        assertNull(isForwardPageSelection(readingOrder, firstPage, readingOrder.size, false))
    }
}
