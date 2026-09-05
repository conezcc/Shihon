package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import eu.kanade.tachiyomi.ui.reader.viewer.InkSubsamplingImageView

/**
 * Implementation of subsampling scale image view that ignores all touch events, because the
 * webtoon viewer handles all the gestures.
 */
class WebtoonSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : InkSubsamplingImageView(context, attrs) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }
}
