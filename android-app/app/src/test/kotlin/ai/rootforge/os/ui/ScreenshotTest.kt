package ai.rootforge.os.ui

import ai.rootforge.os.core.LinkState
import ai.rootforge.os.ui.theme.RootForgeTheme
import ai.rootforge.os.usb.TargetMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun dashboard() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.Dashboard, startExpanded = true) }

    @Test
    fun adb() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.Adb) }

    @Test
    fun fastboot() = snap {
        RootForgeUi(
            previewState().copy(link = LinkState.Connected(TargetMode.FASTBOOT, "Pixel 8")),
            NoopActions,
            startAt = Destination.Fastboot,
        )
    }

    @Test
    fun modules() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.Modules) }

    @Test
    fun settings() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.Settings) }

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot { RootForgeTheme { content() } }
    }
}
