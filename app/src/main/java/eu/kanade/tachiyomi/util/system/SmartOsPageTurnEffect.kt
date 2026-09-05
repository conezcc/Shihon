package eu.kanade.tachiyomi.util.system

import android.os.Build
import android.view.Surface
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.lang.reflect.Method

/** SmartOS 4 horizontal water-ripple page-turn effect. */
object SmartOsPageTurnEffect {

    private const val EPDC_DEVICE_CLASS = "android.eink.EPDCDevice"
    private const val SMART_OS_VERSION_PROPERTY = "ro.build.versionCode"
    private const val MINIMUM_SMART_OS_MAJOR_VERSION = 4

    private val nativePostCommand: Method? by lazy {
        runCatching {
            Class.forName(EPDC_DEVICE_CLASS).getMethod("nativePostCommand", String::class.java)
        }.getOrNull()
    }

    val isSupported: Boolean by lazy {
        val isIReaderDevice = Build.MANUFACTURER.equals("iReader", ignoreCase = true) ||
            Build.BRAND.equals("iReader", ignoreCase = true)
        val smartOsMajorVersion = readSystemProperty(SMART_OS_VERSION_PROPERTY)
            ?.substringBefore('.')
            ?.toIntOrNull()

        isIReaderDevice &&
            smartOsMajorVersion != null &&
            smartOsMajorVersion >= MINIMUM_SMART_OS_MAJOR_VERSION &&
            nativePostCommand != null
    }

    fun prepare(
        forward: Boolean,
        rightToLeft: Boolean,
        displayRotation: Int,
        speed: ReaderPreferences.WaterRippleSpeed,
    ) {
        if (!isSupported) return

        val command = "next-effect-type ${waterRippleCommandCode(forward, rightToLeft, displayRotation, speed)}"
        runCatching { nativePostCommand?.invoke(null, command) }
            .onFailure { error ->
                logcat(LogPriority.ERROR, error) { "Failed to prepare SmartOS water-ripple page turn" }
            }
    }

    private fun readSystemProperty(name: String): String? {
        val reflectedValue = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, name) as? String
        }.getOrNull()
        if (!reflectedValue.isNullOrBlank()) return reflectedValue

        return runCatching {
            ProcessBuilder("/system/bin/getprop", name)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readLine() }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    internal fun waterRippleCommandCode(
        forward: Boolean,
        rightToLeft: Boolean,
        displayRotation: Int,
        speed: ReaderPreferences.WaterRippleSpeed,
    ): Int {
        val systemForward = if (rightToLeft) !forward else forward
        val direction = when (displayRotation) {
            Surface.ROTATION_90 -> if (systemForward) 4 else 3
            Surface.ROTATION_180 -> if (systemForward) 2 else 1
            Surface.ROTATION_270 -> if (systemForward) 3 else 4
            else -> if (systemForward) 1 else 2
        }
        return direction or speed.commandFlag
    }
}
