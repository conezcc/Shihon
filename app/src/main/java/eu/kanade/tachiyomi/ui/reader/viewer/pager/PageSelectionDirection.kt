package eu.kanade.tachiyomi.ui.reader.viewer.pager

/** Returns null for an initial selection or when a list rebuild removed the previous item. */
internal fun isForwardPageSelection(
    items: List<Any>,
    previous: Any?,
    position: Int,
    rightToLeft: Boolean,
): Boolean? {
    val previousPosition = items.indexOf(previous)
    if (previousPosition < 0 || position !in items.indices || previousPosition == position) return null
    return (position > previousPosition) != rightToLeft
}
