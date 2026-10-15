package ai.rootforge.os.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInspectorTest {
    @Test
    fun bootSparseApkAndManifestAreRecognised() {
        val boot = FileInspector.kindOf("boot.img", "ANDROID!".toByteArray() + ByteArray(8))
        val vendor = FileInspector.kindOf("vendor_boot.img", "VNDRBOOT".toByteArray())
        val sparse = FileInspector.kindOf("system.img", byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte()))
        val apk = FileInspector.kindOf("app.apk", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val zip = FileInspector.kindOf("update.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val manifest = FileInspector.kindOf("boot.img.manifest.json", """{"product":"oriole"}""".toByteArray())
        assertEquals(FileKind.BOOT_IMAGE, boot)
        assertEquals(FileKind.VENDOR_BOOT, vendor)
        assertEquals(FileKind.SPARSE_IMAGE, sparse)
        assertEquals(FileKind.APK, apk)
        assertEquals(FileKind.ZIP, zip)
        assertEquals(FileKind.MANIFEST, manifest)
    }

    @Test
    fun inspectSurfacesTheSidecarClaimAndHash() {
        val head = "ANDROID!".toByteArray()
        val sha = FileDigest.sha256Hex(head)
        val json = """{"product":"oriole","codename":"oriole","slot":"*"}"""
        val inspection = FileInspector.inspect("boot.img", 8, head, sha, json)
        assertEquals(FileKind.BOOT_IMAGE, inspection.kind)
        assertEquals(sha, inspection.sha256)
        assertEquals("oriole", inspection.claim?.product)
        assertTrue(inspection.notes.any { it.contains("SHA-256") })
        assertTrue(inspection.notes.any { it.contains("product=oriole") })
    }

    @Test
    fun aFileWithoutASidecarWarnsThatFlashWillBeRefused() {
        val head = byteArrayOf(1, 2, 3, 4)
        val inspection = FileInspector.inspect("mystery.bin", 4, head, FileDigest.sha256Hex(head), null)
        assertEquals(FileKind.UNKNOWN, inspection.kind)
        assertNull(inspection.claim)
        assertTrue(inspection.notes.any { it.contains("will be refused") })
    }
}
