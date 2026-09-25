package dev.alfieprojects.mirroredlotus.core.render

/**
 * GLSL ES 3.00 sources, kept as `:core` resources so a JVM test can run them
 * through glslang before a phone ever sees them, and so the uniform names the
 * renderer binds are checked against the source.
 */
object ShaderSources {
    enum class Program(val vertex: String, val fragment: String, val uniforms: List<String>) {
        CAMERA("shaders/camera.vert", "shaders/camera.frag", listOf("uTexMatrix", "uCrop", "uTex", "uBrightness")),
        MESH("shaders/mesh.vert", "shaders/mesh.frag", listOf("uMvp", "uTex", "uTextured", "uColor", "uOpacity", "uBackFaces")),
        LINES("shaders/lines.vert", "shaders/lines.frag", listOf("uViewProj")),
    }

    fun load(path: String): String {
        val stream = ShaderSources::class.java.classLoader.getResourceAsStream(path)
            ?: error("Shader resource missing: $path")
        return stream.bufferedReader().use { it.readText() }
    }
}
