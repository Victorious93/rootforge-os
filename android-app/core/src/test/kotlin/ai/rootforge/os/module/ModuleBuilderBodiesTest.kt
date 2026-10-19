package ai.rootforge.os.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class ModuleBuilderBodiesTest {
    private fun spec() = ModuleSpec(id = "my_mod", name = "My Module", author = "T", description = "d")

    private fun entry(zip: ByteArray, name: String): String? =
        ZipInputStream(zip.inputStream()).use { z ->
            generateSequence { z.nextEntry }.firstOrNull { it.name == name }?.let { z.readBytes().decodeToString() }
        }

    @Test
    fun customTextReplacesTheStubAndIsNormalised() {
        val s = spec().copy(bodies = mapOf("service.sh" to "while true; do\r\n  sleep 60\r\ndone"))
        val text = entry(ModuleBuilder.build(s).zip, "service.sh")!!
        assertEquals("#!/system/bin/sh\nwhile true; do\n  sleep 60\ndone\n", text)
        assertFalse(text.contains('\r'))
    }

    @Test
    fun anExistingShebangIsKeptAndBlankBodyKeepsTheStub() {
        val s = spec().copy(bodies = mapOf("service.sh" to "#!/system/bin/sh\necho hi", "customize.sh" to "   \n"))
        val built = ModuleBuilder.build(s)
        assertEquals("#!/system/bin/sh\necho hi\n", entry(built.zip, "service.sh"))
        assertTrue(entry(built.zip, "customize.sh")!!.contains("ui_print"))
    }

    @Test
    fun customizeShIsSourcedSoItGetsNoShebang() {
        val s = spec().copy(bodies = mapOf("customize.sh" to "ui_print 'hello'"))
        assertEquals("ui_print 'hello'\n", entry(ModuleBuilder.build(s).zip, "customize.sh"))
    }

    @Test
    fun newLifecycleFilesAreGeneratedOnlyWhenEnabled() {
        val off = ModuleBuilder.build(spec())
        assertEquals(null, entry(off.zip, "action.sh"))
        assertEquals(null, entry(off.zip, "boot-completed.sh"))
        val on = ModuleBuilder.build(spec().copy(actionSh = true, bootCompletedSh = true))
        assertTrue(entry(on.zip, "action.sh")!!.startsWith("#!/system/bin/sh"))
        assertTrue(entry(on.zip, "boot-completed.sh")!!.startsWith("#!/system/bin/sh"))
    }

    @Test
    fun textForADisabledOrUnknownFileIsAnErrorNotSilentlyDropped() {
        val a = ModuleBuilder.validate(spec().copy(bodies = mapOf("uninstall.sh" to "echo x")))
        assertTrue(a.any { it.contains("not enabled") })
        val b = ModuleBuilder.validate(spec().copy(bodies = mapOf("../evil" to "x")))
        assertTrue(b.any { it.contains("cannot have custom contents") })
        val c = ModuleBuilder.validate(spec().copy(bodies = mapOf("module.prop" to "id=x")))
        assertTrue(c.any { it.contains("cannot have custom contents") })
    }

    @Test
    fun oversizedAndNulBodiesAreRejected() {
        val big = "a".repeat(ModuleBuilder.MAX_BODY_BYTES + 1)
        assertTrue(ModuleBuilder.validate(spec().copy(bodies = mapOf("service.sh" to big))).any { it.contains("larger than") })
        assertTrue(ModuleBuilder.validate(spec().copy(bodies = mapOf("service.sh" to "a\u0000b"))).any { it.contains("NUL") })
        assertTrue(ModuleBuilder.validate(spec().copy(bodies = mapOf("service.sh" to "a".repeat(ModuleBuilder.MAX_BODY_BYTES)))).isEmpty())
    }

    @Test
    fun customContentCannotReachModulePropOrTheInstaller() {
        val s = spec().copy(bodies = mapOf("service.sh" to "x\nid=injected"))
        val built = ModuleBuilder.build(s)
        assertFalse(entry(built.zip, "module.prop")!!.contains("injected"))
        assertTrue(entry(built.zip, "META-INF/com/google/android/update-binary")!!.contains("install_module"))
    }

    @Test
    fun theSameSpecWithBodiesIsReproducible() {
        val s = spec().copy(bodies = mapOf("service.sh" to "echo a"), actionSh = true)
        assertTrue(ModuleBuilder.build(s).zip.contentEquals(ModuleBuilder.build(s).zip))
    }

    @Test
    fun compatibilityNotesDependOnTheChosenManager() {
        assertTrue(ModuleBuilder.compatibilityNotes(spec().copy(webroot = true, target = ModuleTarget.MAGISK)).any { it.contains("webroot") })
        assertTrue(ModuleBuilder.compatibilityNotes(spec().copy(webroot = true, target = ModuleTarget.KERNELSU)).none { it.contains("webroot") })
        assertTrue(ModuleBuilder.compatibilityNotes(spec().copy(postFsDataSh = true)).any { it.contains("bootloop") })
        assertTrue(ModuleBuilder.compatibilityNotes(spec()).isEmpty())
    }
}
