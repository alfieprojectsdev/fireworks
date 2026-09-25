package dev.alfieprojects.mirroredlotus.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import dev.alfieprojects.mirroredlotus.camera.CameraFeed
import dev.alfieprojects.mirroredlotus.core.camera.CameraIntrinsics
import dev.alfieprojects.mirroredlotus.core.camera.CameraProjection
import dev.alfieprojects.mirroredlotus.core.camera.CameraTiming
import dev.alfieprojects.mirroredlotus.core.camera.ViewFit
import dev.alfieprojects.mirroredlotus.core.fx.FireworkSystem
import dev.alfieprojects.mirroredlotus.core.math.Mat4
import dev.alfieprojects.mirroredlotus.core.orientation.Frames
import dev.alfieprojects.mirroredlotus.core.orientation.OrientationHistory
import dev.alfieprojects.mirroredlotus.core.orientation.PoseSampler
import dev.alfieprojects.mirroredlotus.core.render.BackFaces
import dev.alfieprojects.mirroredlotus.core.render.Meshes
import dev.alfieprojects.mirroredlotus.core.render.MonumentPlacement
import dev.alfieprojects.mirroredlotus.core.render.SceneLayout
import dev.alfieprojects.mirroredlotus.core.render.ShaderSources
import dev.alfieprojects.mirroredlotus.core.story.ExperienceDirector
import dev.alfieprojects.mirroredlotus.core.telemetry.Ema
import dev.alfieprojects.mirroredlotus.core.telemetry.RateMeter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Monument text, rasterised on the main thread and handed to the render thread once. */
class SceneTextures(val marquee: Bitmap, val pylons: List<TextTextures.Pylon>, val message: Bitmap)

/** Numbers the render thread publishes for the HUD and the session log. */
class RenderTelemetry {
    val renderRate = RateMeter()
    val cameraRate = RateMeter()
    /** Age of the drawn camera frame (mid-exposure to draw), ms. */
    val cameraLatencyMs = Ema(0.05)
    /** How far the synced pose trails the newest sensor sample, ms. */
    val poseLagMs = Ema(0.05)
    @Volatile var poseSynced = false
    @Volatile var fwdX = 0.0
    @Volatile var fwdZ = -1.0
    @Volatile var hasPose = false
    @Volatile var groupX = 0.0
    @Volatile var groupZ = 0.0
    @Volatile var groupPlaced = false
    @Volatile var viewDescription = ""
    /** System.nanoTime of the newest draw, for the camera-paced watchdog. */
    @Volatile var lastDrawNanos = 0L
}

/**
 * Draws a frame the way the web build's `_animate()` + compositor did, in one
 * GL pass on one thread:
 *
 * 1. latch the newest camera frame into the OES texture and note when it was exposed;
 * 2. take the phone's orientation *at that instant* from the sensor history;
 * 3. advance the story in fixed 60 Hz ticks (rate-independent on 60/120 Hz panels);
 * 4. draw the camera, then the monument and every firework additively on top.
 *
 * Because the camera picture and the overlay are composed together and posed
 * for the same instant, the monument cannot drift against the church while the
 * phone moves - the "swim" of the WebView build is gone by construction.
 * Nothing is allocated per frame.
 */
class ArRenderer(
    history: OrientationHistory,
    private val director: ExperienceDirector,
    private val cameraFeed: CameraFeed,
    private val density: Float,
    private val listener: Listener,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    interface Listener {
        /** A new camera target exists (first surface, or after the GL context was lost). Render thread. */
        fun onCameraSurface(texture: SurfaceTexture)
        fun onRenderError(message: String)
    }

    val telemetry = RenderTelemetry()

    /** Pose follows the camera frame's exposure time; false = newest sample (A/B switch). */
    @Volatile var syncToCamera = true

    /**
     * Called (on whatever thread the SurfaceTexture reports on) when a camera
     * frame lands, so a camera-paced loop can draw it at once.
     */
    @Volatile var onCameraFrame: (() -> Unit)? = null

    /**
     * Fraction of the view's size the GL surface has been shrunk to by the
     * thermal ladder. Lines are thinned to match, or the compositor's upscale
     * would thicken the fireworks.
     */
    @Volatile var renderScale = 1f

    /** GL_MAX_TEXTURE_SIZE capped at 4096, for sizing text bitmaps. */
    @Volatile var maxTextureSize = 4096
        private set

    @Volatile private var cameraConfig: CameraFeed.Config? = null
    private var fitFor: CameraFeed.Config? = null
    private var fit: ViewFit? = null
    /** Set on resize: the fit depends on the viewport as well as the camera config. */
    private var fitDirty = true

    private val sampler = PoseSampler(history)
    /**
     * Frames the camera has queued since the last latch. A boolean flag here
     * would be a latency bug: two frames landing between draws would set it
     * once, one would be latched, and the texture would trail the camera by a
     * frame from then on. Latching once per queued frame always ends on the newest.
     */
    private val framesPending = AtomicInteger(0)

    private var cameraProgram: GlProgram? = null
    private var meshProgram: GlProgram? = null
    private var linesProgram: GlProgram? = null
    private var crown: GlMesh? = null
    private var beacon: GlMesh? = null
    private var ground: GlMesh? = null
    private var plane: GlMesh? = null
    private var messageMesh: GlMesh? = null
    private var quadVao = 0
    private var linesVao = 0
    private var linesVbo = 0

    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var lastFrameTimestamp = 0L

    private var textures: SceneTextures? = null
    private var marqueeTex = 0
    private val pylonTex = IntArray(4)
    private val pylonWidth = FloatArray(4)
    private var messageTex = 0
    private var anisotropy = 0f

    private var viewportW = 1
    private var viewportH = 1
    private var lineWidth = 1f

    private val st = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val fwd = DoubleArray(3)

    private val lineData = FloatArray(FireworkSystem.MAX_VERTICES * FireworkSystem.FLOATS_PER_VERTEX)
    private val lineBuffer = ByteBuffer.allocateDirect(lineData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var lastFrameNanos = 0L
    private var tickAccumulator = 0L

    // ---- inputs from other threads (via queueEvent or volatile) ------------

    fun setCameraConfig(config: CameraFeed.Config?) {
        cameraConfig = config
    }

    /** Render thread (queueEvent). */
    fun setSceneTextures(t: SceneTextures) {
        textures = t
        uploadTextures()
    }

    override fun onFrameAvailable(texture: SurfaceTexture) {
        framesPending.incrementAndGet()
        onCameraFrame?.invoke()
    }

    // ---- GLSurfaceView.Renderer --------------------------------------------

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            cameraProgram = GlProgram.build(ShaderSources.Program.CAMERA)
            meshProgram = GlProgram.build(ShaderSources.Program.MESH)
            linesProgram = GlProgram.build(ShaderSources.Program.LINES)
        } catch (e: Exception) {
            Log.e(TAG, "shader build failed", e)
            listener.onRenderError(e.message ?: "shader build failed")
            return
        }
        crown = GlMesh(Meshes.cylinder(SceneLayout.CROWN_RADIUS, SceneLayout.CROWN_HEIGHT, SceneLayout.CROWN_SEGMENTS))
        beacon = GlMesh(Meshes.cylinder(SceneLayout.BEACON_RADIUS, SceneLayout.BEACON_HEIGHT, SceneLayout.BEACON_SEGMENTS))
        ground = GlMesh(Meshes.ring(SceneLayout.GROUND_INNER, SceneLayout.GROUND_OUTER, SceneLayout.GROUND_SEGMENTS))
        plane = GlMesh(Meshes.plane(1f, 1f))
        messageMesh = GlMesh(Meshes.cylinder(SceneLayout.MESSAGE_RADIUS, SceneLayout.MESSAGE_HEIGHT, SceneLayout.MESSAGE_SEGMENTS))
        buildQuad()
        buildLines()

        val limits = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limits, 0)
        maxTextureSize = minOf(4096, limits[0].coerceAtLeast(2048))
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        anisotropy = if ("GL_EXT_texture_filter_anisotropic" in ext) {
            val max = FloatArray(1)
            GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, max, 0)
            minOf(4f, max[0])
        } else 0f
        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        // The web canvas rendered at min(dpr, 2), so a 1 px line was dpr/2 physical px.
        lineWidth = (density / 2f).coerceIn(1f, maxOf(1f, range[1]))

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture?.release()
        val surface = SurfaceTexture(oesTexture).also { it.setOnFrameAvailableListener(this) }
        surfaceTexture = surface
        lastFrameTimestamp = 0L

        // A new context means every texture name is gone; re-upload what we have.
        uploadTextures()
        listener.onCameraSurface(surface)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewportW = maxOf(1, width)
        viewportH = maxOf(1, height)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        fitDirty = true // recompute against the new viewport, camera or not
    }

    override fun onDrawFrame(unused: GL10?) {
        val program = meshProgram ?: run {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val nowNanos = System.nanoTime()
        advanceStory(nowNanos)
        val frameCentre = latchCamera()
        updateView(frameCentre)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val fade = director.cameraFade().toFloat()
        if (fade > 0f && lastFrameTimestamp != 0L && cameraConfig != null) drawCamera(fade)

        if (director.started) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            drawMonument(program)
            drawFireworks()
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        telemetry.renderRate.record(nowNanos)
        telemetry.lastDrawNanos = nowNanos
    }

    // ---- frame stages -------------------------------------------------------

    /** Fixed 60 Hz steps; a long stall (pause, GC) is skipped rather than fast-forwarded. */
    private fun advanceStory(now: Long) {
        if (lastFrameNanos == 0L) lastFrameNanos = now
        var dt = now - lastFrameNanos
        lastFrameNanos = now
        if (dt > MAX_CATCH_UP_NANOS) dt = TICK_NANOS
        tickAccumulator += dt
        var steps = 0
        while (tickAccumulator >= TICK_NANOS && steps < MAX_STEPS_PER_FRAME) {
            director.tick()
            tickAccumulator -= TICK_NANOS
            steps++
        }
        if (steps == MAX_STEPS_PER_FRAME) tickAccumulator = 0
        telemetry.groupX = director.groupX
        telemetry.groupZ = director.groupZ
        telemetry.groupPlaced = director.started
    }

    /** Latches the newest camera frame; returns its mid-exposure time on the sensor clock. */
    private fun latchCamera(): Long {
        val surface = surfaceTexture ?: return CameraTiming.NO_TIME
        val config = cameraConfig ?: return CameraTiming.NO_TIME
        val pending = framesPending.getAndSet(0)
        if (pending > 0) {
            try {
                repeat(pending) { surface.updateTexImage() }
                surface.getTransformMatrix(st)
                lastFrameTimestamp = surface.timestamp
                telemetry.cameraRate.record(System.nanoTime())
            } catch (e: RuntimeException) {
                // The camera was torn down between the callback and the latch.
                return CameraTiming.NO_TIME
            }
        }
        if (lastFrameTimestamp == 0L) return CameraTiming.NO_TIME
        val realtime = SystemClock.elapsedRealtimeNanos()
        val centre = CameraTiming.frameCentreRealtime(
            lastFrameTimestamp, config.timestampBase,
            cameraFeed.exposureNanos, cameraFeed.rollingSkewNanos,
            realtime, System.nanoTime(),
        )
        if (centre != CameraTiming.NO_TIME) telemetry.cameraLatencyMs.add((realtime - centre) / 1e6)
        return centre
    }

    private fun updateView(frameCentre: Long) {
        sampler.sample(frameCentre, syncToCamera)
        telemetry.poseSynced = sampler.synced
        if (sampler.synced) telemetry.poseLagMs.add(sampler.lagNanos / 1e6)
        if (sampler.hasPose) {
            Mat4.viewFromCameraQuat(view, sampler.scene)
            Frames.forward(sampler.scene, fwd)
            telemetry.fwdX = fwd[0]
            telemetry.fwdZ = fwd[2]
            telemetry.hasPose = true
        } else {
            Mat4.identity(view)
        }
        val config = cameraConfig
        if (fitDirty || fit == null || fitFor !== config) {
            val intrinsics = config?.intrinsics ?: CameraIntrinsics.fallback()
            val sw = config?.streamWidth ?: 1920
            val sh = config?.streamHeight ?: 1080
            fit = CameraProjection.fit(intrinsics, sw, sh, viewportW, viewportH).also {
                telemetry.viewDescription = String.format(Locale.US, "vfov %.1f° hfov %.1f°", it.verticalFovDeg, it.horizontalFovDeg)
            }
            fitFor = config
            fitDirty = false
        }
        val f = fit!!
        Mat4.perspective(proj, f.fxNdc, f.fyNdc, NEAR, FAR)
        Mat4.multiply(viewProj, proj, view)
    }

    private fun drawCamera(brightness: Float) {
        val p = cameraProgram ?: return
        val f = fit ?: return
        GLES30.glUseProgram(p.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(p.u("uTex"), 0)
        GLES30.glUniformMatrix4fv(p.u("uTexMatrix"), 1, false, st, 0)
        GLES30.glUniform4f(p.u("uCrop"), f.uvScaleX, f.uvScaleY, f.uvOffsetX, f.uvOffsetY)
        GLES30.glUniform1f(p.u("uBrightness"), brightness)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawMonument(p: GlProgram) {
        GLES30.glUseProgram(p.id)
        GLES30.glUniform1i(p.u("uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val gx = director.groupX.toFloat()
        val gy = director.groupY.toFloat()
        val gz = director.groupZ.toFloat()

        // Act 0: the beacon pillar.
        val beaconOpacity = director.beaconOpacity.toFloat()
        if (beaconOpacity > MIN_OPACITY) {
            MonumentPlacement.beacon(model, gx, gy, gz)
            drawSolid(p, beacon, SceneLayout.BEACON_COLOUR, beaconOpacity)
        }

        // Ground glow, laid flat under the church.
        MonumentPlacement.ground(model, gx, gy, gz)
        drawSolid(p, ground, SceneLayout.GROUND_COLOUR, director.groundOpacity().toFloat())

        // Act 3: the four stat pylons.
        val statsOpacity = director.statsOpacity.toFloat()
        if (statsOpacity > MIN_OPACITY && pylonTex[0] != 0) {
            for (i in 0 until 4) {
                MonumentPlacement.pylon(model, i, gx, gy, gz, pylonWidth[i])
                drawTextured(p, plane, pylonTex[i], statsOpacity, BackFaces.FOR_PANEL_TEXT)
            }
        }

        // Act 2: the crown marquee.
        val marqueeOpacity = director.marqueeOpacity.toFloat()
        if (marqueeOpacity > MIN_OPACITY && marqueeTex != 0) {
            MonumentPlacement.crown(model, gx, gy, gz, director.marqueeRotation.toFloat())
            val facing = BackFaces.forCylinderText(director.groupX, director.groupZ, SceneLayout.CROWN_RADIUS)
            drawTextured(p, crown, marqueeTex, marqueeOpacity, facing)
        }

        // The personal message, a minute after arriving.
        if (director.messageVisible && messageTex != 0) {
            MonumentPlacement.message(model, gx, gy, gz, director.messageRotation.toFloat())
            val facing = BackFaces.forCylinderText(director.groupX, director.groupZ, SceneLayout.MESSAGE_RADIUS)
            drawTextured(p, messageMesh, messageTex, director.messageOpacity.toFloat(), facing)
        }
    }

    private fun drawSolid(p: GlProgram, mesh: GlMesh?, colour: FloatArray, opacity: Float) {
        mesh ?: return
        Mat4.multiply(mvp, viewProj, model)
        GLES30.glUniformMatrix4fv(p.u("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1i(p.u("uTextured"), 0)
        GLES30.glUniform1i(p.u("uBackFaces"), BackFaces.SHOW) // pillar and ring glow from both sides
        GLES30.glUniform3f(p.u("uColor"), colour[0], colour[1], colour[2])
        GLES30.glUniform1f(p.u("uOpacity"), opacity)
        mesh.draw()
    }

    private fun drawTextured(p: GlProgram, mesh: GlMesh?, texture: Int, opacity: Float, backFaces: Int) {
        mesh ?: return
        Mat4.multiply(mvp, viewProj, model)
        GLES30.glUniformMatrix4fv(p.u("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1i(p.u("uTextured"), 1)
        GLES30.glUniform1i(p.u("uBackFaces"), backFaces)
        GLES30.glUniform1f(p.u("uOpacity"), opacity)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        mesh.draw()
    }

    private fun drawFireworks() {
        val p = linesProgram ?: return
        val vertices = director.fireworks.writeVertices(lineData)
        if (vertices == 0) return
        val floats = vertices * FireworkSystem.FLOATS_PER_VERTEX
        lineBuffer.clear()
        lineBuffer.put(lineData, 0, floats)
        lineBuffer.flip()
        GLES30.glUseProgram(p.id)
        GLES30.glUniformMatrix4fv(p.u("uViewProj"), 1, false, viewProj, 0)
        GLES30.glBindVertexArray(linesVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linesVbo)
        // Orphan, then fill: the driver hands back fresh storage instead of
        // stalling on the buffer the GPU is still reading from last frame.
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineData.size * 4, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, floats * 4, lineBuffer)
        GLES30.glLineWidth((lineWidth * renderScale).coerceAtLeast(1f))
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertices)
    }

    // ---- resources ----------------------------------------------------------

    private fun buildQuad() {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        quadVao = ids[0]
        GLES30.glBindVertexArray(quadVao)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, floats(quad), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun buildLines() {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        linesVao = ids[0]
        GLES30.glBindVertexArray(linesVao)
        GLES30.glGenBuffers(1, ids, 0)
        linesVbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linesVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineData.size * 4, null, GLES30.GL_STREAM_DRAW)
        val stride = FireworkSystem.FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glBindVertexArray(0)
    }

    private fun uploadTextures() {
        val t = textures ?: return
        if (meshProgram == null) return // no context yet; onSurfaceCreated will call back
        marqueeTex = upload(t.marquee, repeatS = true)
        t.pylons.forEachIndexed { i, pylon ->
            if (i < 4) {
                pylonTex[i] = upload(pylon.bitmap, repeatS = false)
                pylonWidth[i] = SceneLayout.pylonWidth(pylon.charCount)
            }
        }
        messageTex = upload(t.message, repeatS = true)
    }

    private fun upload(bitmap: Bitmap, repeatS: Boolean): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
            if (repeatS) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (anisotropy > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy)
        // Bitmaps are premultiplied and upload as such; mesh.frag expects that.
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    private companion object {
        const val TAG = "LotusRenderer"
        const val TICK_NANOS = 1_000_000_000L / ExperienceDirector.TICK_HZ
        const val MAX_STEPS_PER_FRAME = 4
        const val MAX_CATCH_UP_NANOS = 250_000_000L
        const val NEAR = 0.1f
        // Covers the whole approach: the beacon apex 2 km up is ~2.7 km slant range from the trigger ring.
        const val FAR = 4000f
        const val MIN_OPACITY = 0.002f
        const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
    }
}
