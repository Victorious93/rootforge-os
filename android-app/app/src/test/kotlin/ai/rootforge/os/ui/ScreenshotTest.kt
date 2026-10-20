package ai.rootforge.os.ui

import ai.rootforge.os.core.LinkState
import ai.rootforge.os.ui.screens.BuildCatalogCard
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
    fun flashTools() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.FlashTools) }

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
    fun buildCatalog() = snap {
        val state = previewState()
        ScreenColumn {
            BuildCatalogCard(state.buildCatalog, state.info, busy = false, actions = NoopActions)
            BuildCatalogCard(null, state.info, busy = false, actions = NoopActions)
        }
    }

    @Test
    fun modules() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.Modules) }

    @Test
    fun customRecovery() = snap { RootForgeUi(previewState(), NoopActions, startAt = Destination.CustomRecovery) }

    @Test
    fun settings() = snap {
        RootForgeUi(
            previewState().copy(
                bridgeEnabled = true,
                pending = listOf(
                    ai.rootforge.os.bridge.CallerIdentity("ai.droidcommand.app.debug", "aa".repeat(32)),
                ),
                paired = listOf(
                    ai.rootforge.os.bridge.PairedController("ai.example.oldcontroller", "bb".repeat(32), 0L),
                ),
            ),
            NoopActions,
            startAt = Destination.Settings,
        )
    }

    private fun snap(content: @androidx.compose.runtime.Composable () -> Unit) {
        paparazzi.snapshot { RootForgeTheme { content() } }
    }
}
