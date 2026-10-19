package ai.rootforge.os.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStateMapperTest {
    @Test
    fun nothingInstalledAndBinderDeadIsNotInstalled() {
        assertEquals(
            ShizukuStatus.NotInstalled,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = false, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun leftoverGrantFlagDoesNotCountAsInstalledWhenBinderIsDead() {
        assertEquals(
            ShizukuStatus.NotInstalled,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = false, binderAlive = false, permissionGranted = true)),
        )
    }

    @Test
    fun shizukuAppInstalledButNotStartedIsNotRunning() {
        assertEquals(
            ShizukuStatus.NotRunning,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = true, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun suiPresentButBinderDeadIsNotRunning() {
        // Sui counts as the same provider as Shizuku.
        assertEquals(
            ShizukuStatus.NotRunning,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = true, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun binderAliveWithoutPermissionIsDenied() {
        assertEquals(
            ShizukuStatus.PermissionDenied,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = true, binderAlive = true, permissionGranted = false)),
        )
    }

    @Test
    fun binderAliveAndGrantedIsGranted() {
        assertEquals(
            ShizukuStatus.Granted,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = true, binderAlive = true, permissionGranted = true)),
        )
    }

    @Test
    fun suiWithoutDetectablePackageStillCountsAsAvailableWhenBinderIsAlive() {
        assertEquals(
            ShizukuStatus.Granted,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = false, binderAlive = true, permissionGranted = true)),
        )
        assertEquals(
            ShizukuStatus.PermissionDenied,
            ShizukuStateMapper.map(ShizukuProbe(providerPresent = false, binderAlive = true, permissionGranted = false)),
        )
    }

    @Test
    fun preV11IsNotRunningEvenIfPermissionLooksGranted() {
        assertEquals(
            ShizukuStatus.NotRunning,
            ShizukuStateMapper.map(
                ShizukuProbe(providerPresent = true, binderAlive = true, permissionGranted = true, preV11 = true),
            ),
        )
    }

    @Test
    fun fourStatesAreDistinctAndHaveCopy() {
        val all = ShizukuStatus.entries
        assertEquals(4, all.size)
        assertEquals(4, all.map { it.label() }.toSet().size)
        all.forEach { status ->
            assertTrue("${status.name} needs an explanation", status.explanation().isNotBlank())
        }
        assertTrue(ShizukuStatus.NotInstalled.explanation().contains("Sui"))
        assertTrue(ShizukuStatus.NotRunning.explanation().contains("Sui"))
        assertTrue(ShizukuStatus.Granted.explanation().contains("confirmation"))
    }

    @Test
    fun everyCombinationMapsToExactlyOneState() {
        val seen = mutableSetOf<ShizukuStatus>()
        for (provider in listOf(false, true)) {
            for (alive in listOf(false, true)) {
                for (granted in listOf(false, true)) {
                    for (preV11 in listOf(false, true)) {
                        val status = ShizukuStateMapper.map(
                            ShizukuProbe(providerPresent = provider, binderAlive = alive, permissionGranted = granted, preV11 = preV11),
                        )
                        seen += status
                        when {
                            !alive && !provider -> assertEquals(ShizukuStatus.NotInstalled, status)
                            !alive && provider -> assertEquals(ShizukuStatus.NotRunning, status)
                            alive && preV11 -> assertEquals(ShizukuStatus.NotRunning, status)
                            alive && !granted -> assertEquals(ShizukuStatus.PermissionDenied, status)
                            else -> assertEquals(ShizukuStatus.Granted, status)
                        }
                    }
                }
            }
        }
        assertEquals(ShizukuStatus.entries.toSet(), seen)
    }
}
