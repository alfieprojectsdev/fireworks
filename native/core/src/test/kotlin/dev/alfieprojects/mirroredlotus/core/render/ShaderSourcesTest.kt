package dev.alfieprojects.mirroredlotus.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ShaderSourcesTest {

    private val all = ShaderSources.Program.entries.flatMap { listOf(it.vertex, it.fragment) }

    @Test
    fun `every shader is GLSL ES 3_00`() {
        for (path in all) {
            assertEquals(path, "#version 300 es", ShaderSources.load(path).lineSequence().first().trim())
        }
    }

    @Test
    fun `every uniform the renderer binds is declared`() {
        for (p in ShaderSources.Program.entries) {
            val src = ShaderSources.load(p.vertex) + ShaderSources.load(p.fragment)
            for (u in p.uniforms) {
                assertTrue("${p.name} lacks $u", Regex("""uniform\s+\w+\s+$u\s*;""").containsMatchIn(src))
            }
        }
    }

    @Test
    fun `glslang accepts every shader`() {
        val validator = findValidator()
        assumeTrue("glslangValidator not installed; skipping compile check", validator != null)
        for (path in all) {
            val stage = if (path.endsWith(".vert")) "vert" else "frag"
            val tmp = File.createTempFile("shader", ".$stage")
            try {
                tmp.writeText(ShaderSources.load(path))
                val proc = ProcessBuilder(validator!!, "-S", stage, tmp.absolutePath).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
                assertEquals("$path:\n$output", 0, proc.exitValue())
            } finally {
                tmp.delete()
            }
        }
    }

    private fun findValidator(): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "glslangValidator") }
            .firstOrNull { it.canExecute() }?.absolutePath
}
