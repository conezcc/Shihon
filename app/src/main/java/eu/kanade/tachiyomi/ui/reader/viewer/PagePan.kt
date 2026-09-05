package eu.kanade.tachiyomi.ui.reader.viewer

enum class PagePanDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

internal data class PagePanRemaining(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun inDirection(direction: PagePanDirection): Float {
        return when (direction) {
            PagePanDirection.LEFT -> left
            PagePanDirection.RIGHT -> right
            PagePanDirection.UP -> top
            PagePanDirection.DOWN -> bottom
        }
    }

    fun canPan(direction: PagePanDirection): Boolean {
        return inDirection(direction) > PAGE_PAN_THRESHOLD
    }
}

internal data class PageEntryPosition(
    val horizontal: PagePanDirection,
    val vertical: PagePanDirection,
)

internal fun pageEntryPosition(
    forward: Boolean,
    forwardHorizontalDirection: PagePanDirection,
): PageEntryPosition {
    val startHorizontal = when (forwardHorizontalDirection) {
        PagePanDirection.LEFT -> PagePanDirection.RIGHT
        else -> PagePanDirection.LEFT
    }
    return if (forward) {
        PageEntryPosition(startHorizontal, PagePanDirection.UP)
    } else {
        PageEntryPosition(forwardHorizontalDirection, PagePanDirection.DOWN)
    }
}

internal fun selectPagePanDirection(
    remaining: PagePanRemaining,
    horizontalDirection: PagePanDirection,
    verticalDirection: PagePanDirection,
): PagePanDirection? {
    val horizontalAvailable = remaining.canPan(horizontalDirection)
    val verticalAvailable = remaining.canPan(verticalDirection)
    val horizontalOverflow = remaining.left + remaining.right
    val verticalOverflow = remaining.top + remaining.bottom
    return when {
        verticalOverflow > horizontalOverflow && verticalAvailable -> verticalDirection
        horizontalAvailable -> horizontalDirection
        verticalAvailable -> verticalDirection
        else -> null
    }
}

private const val PAGE_PAN_THRESHOLD = 1F
