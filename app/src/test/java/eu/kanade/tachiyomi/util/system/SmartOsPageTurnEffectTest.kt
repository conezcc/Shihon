package eu.kanade.tachiyomi.util.system

import android.view.Surface
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartOsPageTurnEffectTest {

    @Test
    fun `water ripple direction follows display rotation when turning forward`() {
        assertEquals(65, commandCode(true, false, Surface.ROTATION_0))
        assertEquals(68, commandCode(true, false, Surface.ROTATION_90))
        assertEquals(66, commandCode(true, false, Surface.ROTATION_180))
        assertEquals(67, commandCode(true, false, Surface.ROTATION_270))
    }

    @Test
    fun `water ripple direction follows display rotation when turning backward`() {
        assertEquals(66, commandCode(false, false, Surface.ROTATION_0))
        assertEquals(67, commandCode(false, false, Surface.ROTATION_90))
        assertEquals(65, commandCode(false, false, Surface.ROTATION_180))
        assertEquals(68, commandCode(false, false, Surface.ROTATION_270))
    }

    @Test
    fun `right to left reading reverses the system direction`() {
        assertEquals(66, commandCode(true, true, Surface.ROTATION_0))
        assertEquals(65, commandCode(false, true, Surface.ROTATION_0))
    }

    @Test
    fun `water ripple speed selects the official command flag`() {
        assertEquals(129, commandCode(true, false, Surface.ROTATION_0, ReaderPreferences.WaterRippleSpeed.SLOW))
        assertEquals(65, commandCode(true, false, Surface.ROTATION_0, ReaderPreferences.WaterRippleSpeed.STANDARD))
        assertEquals(1, commandCode(true, false, Surface.ROTATION_0, ReaderPreferences.WaterRippleSpeed.FAST))
    }

    private fun commandCode(
        forward: Boolean,
        rightToLeft: Boolean,
        rotation: Int,
        speed: ReaderPreferences.WaterRippleSpeed = ReaderPreferences.WaterRippleSpeed.STANDARD,
    ) = SmartOsPageTurnEffect.waterRippleCommandCode(forward, rightToLeft, rotation, speed)
}
