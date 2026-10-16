package dev.alfieprojects.mirroredlotus.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import dev.alfieprojects.mirroredlotus.core.render.MeshData
import dev.alfieprojects.mirroredlotus.core.render.ShaderSources
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** A linked program plus the locations of the uniforms the renderer sets. */
class GlProgram(val id: Int, private val uniforms: Map<String, Int>) {
    fun u(name: String): Int = uniforms[name] ?: error("uniform $name not declared")

    companion object {
        fun build(program: ShaderSources.Program): GlProgram {
            val vs = compile(GLES30.GL_VERTEX_SHADER, ShaderSources.load(program.vertex), program.vertex)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, ShaderSources.load(program.fragment), program.fragment)
            val id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, vs)
            GLES30.glAttachShader(id, fs)
            GLES30.glLinkProgram(id)
            val status = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES30.GL_TRUE) {
                val log = GLES30.glGetProgramInfoLog(id)
                GLES30.glDeleteProgram(id)
                error("${program.name} link failed: $log")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            // An unused uniform is optimised away (-1); setting -1 is a harmless no-op.
            return GlProgram(id, program.uniforms.associateWith { GLES30.glGetUniformLocation(id, it) })
        }

        private fun compile(type: Int, source: String, name: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES30.GL_TRUE) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error("$name failed to compile: $log")
            }
            return shader
        }
    }
}

/** A static indexed mesh in a VAO: attribute 0 = position, 1 = uv. */
class GlMesh(mesh: MeshData) {
    private val vao: Int
    private val buffers = IntArray(3)
    private val indexCount = mesh.indices.size

    init {
        val v = IntArray(1)
        GLES30.glGenVertexArrays(1, v, 0)
        vao = v[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glGenBuffers(3, buffers, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.positions.size * 4, floats(mesh.positions), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.uvs.size * 4, floats(mesh.uvs), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, shorts(mesh.indices), GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }
}

fun floats(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.flip() }

fun shorts(a: ShortArray): ShortBuffer =
    ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(a).also { it.flip() }

/**
 * RGB888 with 4x MSAA when the driver offers it (WebGL's `antialias: true`;
 * nearly free on the Mali tilers), no depth buffer (every material was additive
 * with depthWrite off), falling back to plain RGB888.
 */
class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
        fun attempt(samples: Int): EGLConfig? {
            val attribs = mutableListOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, es3,
            )
            if (samples > 0) attribs += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            attribs += EGL10.EGL_NONE
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            return if (egl.eglChooseConfig(display, attribs.toIntArray(), configs, 1, count) && count[0] > 0) configs[0] else null
        }
        return attempt(4) ?: attempt(0) ?: error("No GLES 3 RGB888 EGL config")
    }
}
