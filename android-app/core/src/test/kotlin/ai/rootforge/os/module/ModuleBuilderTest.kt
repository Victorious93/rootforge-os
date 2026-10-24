package ai.rootforge.os.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class ModuleBuilderTest {
    private fun spec(id: String = "my_mod", name: String = "My Module") = ModuleSpec(
        id = id, name = name, author = "Tester", description = "A test module",
    )

    @Test
    fun aValidSpecBuildsAZipWithTheInstallerShim() {
        val built = ModuleBuilder.build(spec())
        assertEquals("my_mod-v1.0.zip", built.fileName)
        assertEquals(64, built.sha256.length)
        val names = zipNames(built.zip)
        assertTrue(names.contains("module.prop"))
        assertTrue(names.contains("META-INF/com/google/android/update-binary"))
        assertTrue(names.contains("customize.sh"))
        assertTrue(names.contains("service.sh"))
        val prop = zipEntry(built.zip, "module.prop")
        assertTrue(prop.startsWith("id=my_mod\n"))
        assertTrue(!prop.contains("id=my_mod\nid=injected"))
        val shim = zipEntry(built.zip, "META-INF/com/google/android/update-binary")
        assertTrue(shim.contains(". /data/adb/magisk/util_functions.sh"))
        assertTrue(shim.contains("install_module"))
        assertTrue(!shim.contains("grep_prop"))
    }

    /**
     * The built zip is stored through `Library.fileFor`, which sanitizes the name, and found again by
     * exact name (Modules screen, `module.build` result, flash `image`). `fileName` must therefore
     * already be a library name, whatever the free-text version contains.
     */
    @Test
    fun theFileNameIsAlreadyALibraryName() {
        val versions = listOf("v1.0", "1.0 beta", "1.0+5", "2.0/rc1", "..", "1.0 (ünï)", "x".repeat(200))
        for (version in versions) {
            val built = ModuleBuilder.build(spec().copy(version = version))
            val name = built.fileName
            assertEquals("'$version' gave '$name'", ai.rootforge.os.core.Library.sanitize(name), name)
            assertTrue("'$name'", name.endsWith(".zip") && name.length <= 96 && name.startsWith("my_mod-"))
        }
        assertEquals("my_mod-1.0_beta.zip", ModuleBuilder.build(spec().copy(version = "1.0 beta")).fileName)
        assertEquals("my_mod-v1.0.zip", ModuleBuilder.build(spec()).fileName)
    }

    @Test
    fun aNewlineInTheNameIsRejected() {
        val problems = ModuleBuilder.validate(spec(name = "Evil\nid=injected"))
        assertTrue(problems.any { it.contains("control character") })
    }

    @Test
    fun aNameWithShellMetacharactersIsQuotedInCustomizeSh() {
        val built = ModuleBuilder.build(spec(name = """O'Brien $(touch pwned)"; touch also"""))
        val script = zipEntry(built.zip, "customize.sh")
        assertTrue(script.contains("""ui_print '- Installing O'\''Brien $(touch pwned)"; touch also'"""))
        assertTrue(!script.contains("""ui_print "- Installing"""))
    }

    @Test
    fun aLeadingDigitIdIsRejected() {
        assertTrue(ModuleBuilder.validate(spec(id = "9bad")).isNotEmpty())
    }

    @Test
    fun aPathTraversalExtraFileIsRejected() {
        val problems = ModuleBuilder.validate(spec().copy(files = mapOf("../escape" to byteArrayOf(1))))
        assertTrue(problems.any { it.contains("safe relative path") })
    }

    @Test
    fun theSameSpecProducesTheSameDigest() {
        val a = ModuleBuilder.build(spec())
        val b = ModuleBuilder.build(spec())
        assertEquals(a.sha256, b.sha256)
        assertTrue(a.zip.contentEquals(b.zip))
    }

    private fun zipNames(bytes: ByteArray): List<String> =
        ZipInputStream(bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.map { it.name }.toList()
        }

    private fun zipEntry(bytes: ByteArray, name: String): String =
        ZipInputStream(bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == name }
            z.readBytes().decodeToString()
        }
}
