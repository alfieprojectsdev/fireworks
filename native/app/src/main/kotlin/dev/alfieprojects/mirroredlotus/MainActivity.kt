package dev.alfieprojects.mirroredlotus

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.alfieprojects.mirroredlotus.audio.AudioEngine
import dev.alfieprojects.mirroredlotus.audio.EarphoneDetector
import dev.alfieprojects.mirroredlotus.camera.CameraFeed
import dev.alfieprojects.mirroredlotus.core.audio.ListenerPose
import dev.alfieprojects.mirroredlotus.core.audio.SpatialMixer
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldSample
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestLog
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTests
import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.orientation.Frames
import dev.alfieprojects.mirroredlotus.core.orientation.OrientationHistory
import dev.alfieprojects.mirroredlotus.core.perf.RenderPace
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy
import dev.alfieprojects.mirroredlotus.core.record.SessionRecorder
import dev.alfieprojects.mirroredlotus.core.sim.ApproachRoute
import dev.alfieprojects.mirroredlotus.core.site.Site
import dev.alfieprojects.mirroredlotus.core.site.Sites
import dev.alfieprojects.mirroredlotus.core.site.TriggerDecision
import dev.alfieprojects.mirroredlotus.core.site.TriggerPolicy
import dev.alfieprojects.mirroredlotus.core.story.ActEvent
import dev.alfieprojects.mirroredlotus.core.story.AnniversaryStats
import dev.alfieprojects.mirroredlotus.core.story.ExperienceDirector
import dev.alfieprojects.mirroredlotus.core.track.PositionTracker
import dev.alfieprojects.mirroredlotus.core.track.WalkHeading
import dev.alfieprojects.mirroredlotus.core.ui.Alignment
import dev.alfieprojects.mirroredlotus.core.ui.CompassBanner
import dev.alfieprojects.mirroredlotus.core.ui.DistanceHint
import dev.alfieprojects.mirroredlotus.core.ui.HudFormatter
import dev.alfieprojects.mirroredlotus.core.ui.MultiTapDetector
import dev.alfieprojects.mirroredlotus.core.ui.PerfSnapshot
import dev.alfieprojects.mirroredlotus.core.ui.PoseGate
import dev.alfieprojects.mirroredlotus.core.ui.ThermalSnapshot
import dev.alfieprojects.mirroredlotus.core.ui.TrackSnapshot
import dev.alfieprojects.mirroredlotus.export.SessionExporter
import dev.alfieprojects.mirroredlotus.fieldtest.FieldTestController
import dev.alfieprojects.mirroredlotus.gl.ArRenderer
import dev.alfieprojects.mirroredlotus.gl.MsaaConfigChooser
import dev.alfieprojects.mirroredlotus.gl.SceneTextures
import dev.alfieprojects.mirroredlotus.gl.TextTextures
import dev.alfieprojects.mirroredlotus.location.DeviceLocationFeed
import dev.alfieprojects.mirroredlotus.location.Fix
import dev.alfieprojects.mirroredlotus.location.LocationFeed
import dev.alfieprojects.mirroredlotus.location.LocationListenerSink
import dev.alfieprojects.mirroredlotus.location.LocationProblem
import dev.alfieprojects.mirroredlotus.location.SimulatedLocationFeed
import dev.alfieprojects.mirroredlotus.perf.ThermalMonitor
import dev.alfieprojects.mirroredlotus.sensors.OrientationSource
import dev.alfieprojects.mirroredlotus.ui.Haptics
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The one screen. Owns the pipeline's threads and wires them together:
 *
 * - **orientation** thread: rotation vectors -> heading fusion -> [history];
 * - **camera** thread: Camera2 -> the renderer's SurfaceTexture;
 * - **render** thread: [ArRenderer] + [ExperienceDirector] (story and fireworks);
 * - **audio** thread: [SpatialMixer] -> AudioTrack;
 * - **main** thread (here): GPS, the trigger decision, splash/HUD/taps.
 *
 * Everything the web build decided in `sensors.js`/`index.html` is decided in
 * `:core` (TriggerPolicy, DistanceHint, CompassBanner, MultiTapDetector,
 * HudFormatter); this class only routes events to it and renders the answers.
 */
class MainActivity : Activity(), LocationListenerSink, ArRenderer.Listener {

    private lateinit var options: DebugOptions
    private val ui = Handler(Looper.getMainLooper())
    private val history = OrientationHistory()
    private val recorder = SessionRecorder(Sites.ALL)
    private val banner = CompassBanner()
    private val secretTaps = MultiTapDetector()
    private val hudTaps = MultiTapDetector()
    private val testModeGate = PoseGate()
    /** GPS fused with footsteps: what the monument is anchored from after the trigger. */
    private val tracker = PositionTracker()
    private val thermalPolicy = ThermalPolicy()
    private lateinit var thermal: ThermalMonitor
    /** How the render loop is driven now; read from the camera callback's thread. */
    @Volatile private var pace = RenderPace.FULL
    private var appliedRenderScale = 1.0

    private lateinit var orientation: OrientationSource
    private lateinit var locationFeed: LocationFeed
    private lateinit var cameraFeed: CameraFeed
    private lateinit var mixer: SpatialMixer
    private lateinit var audio: AudioEngine
    private lateinit var earphones: EarphoneDetector
    private lateinit var director: ExperienceDirector
    private lateinit var renderer: ArRenderer
    private lateinit var haptics: Haptics

    private lateinit var glView: GLSurfaceView
    private lateinit var splash: View
    private lateinit var statusMessage: TextView
    private lateinit var compassView: TextView
    private lateinit var audioButton: TextView
    private lateinit var hud: View
    private lateinit var hudLines: TextView
    private var arrowFloat: ObjectAnimator? = null

    private var experienceStarted = false
    private var testMode = false
    private var activeSite: Site? = null
    private var cameraFailed = false
    private var cameraSurface: SurfaceTexture? = null
    private var resumed = false
    private var locationRunning = false

    private var lastFix: Fix? = null
    private var shownBanner: CompassBanner.State? = null
    private var lastHudAt = 0L
    private val enuScratch = DoubleArray(4)
    private val facingScratch = DoubleArray(2)
    private val sceneScratch = DoubleArray(4)
    private val forwardScratch = DoubleArray(3)

    /** Null in release: no field tests, so R8 drops them and their log writer. */
    private var fieldTests: FieldTestController? = null
    /** A field test asked for at launch (`--es fieldTest`), started on the first UI tick. */
    private var pendingFieldTest: String? = null

    // ---- lifecycle ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        options = DebugOptions.from(intent)
        // A continuous hold-the-phone-up session: the display must never sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
        setContentView(R.layout.activity_main)
        bindViews()

        haptics = Haptics(this)
        if (BuildConfig.DEV_TOOLS) fieldTests = FieldTestController(this, haptics, fieldTestHost)
        pendingFieldTest = options.fieldTest
        orientation = OrientationSource(this, history, options.headingTauS)
        orientation.onStep = { east, north -> ui.post { onStep(east, north) } }
        cameraFeed = CameraFeed(this)

        val listenerScratch = DoubleArray(4)
        mixer = SpatialMixer(AudioEngine.outputSampleRate(this), ListenerPose { out ->
            if (history.latest(listenerScratch) == Long.MIN_VALUE) {
                false
            } else {
                Frames.enuToScene(listenerScratch, out)
                true
            }
        })
        audio = AudioEngine(this, mixer)
        earphones = EarphoneDetector(this) { present -> setEarphones(present) }

        director = ExperienceDirector(
            rng = Rng(),
            explosionListener = { x, y, z, shell -> mixer.queue.offer(x, y, z, shell) },
            actListener = { event, distance -> ui.post { onAct(event, distance) } },
        )
        renderer = ArRenderer(history, director, cameraFeed, resources.displayMetrics.density, this)
        renderer.syncToCamera = options.syncCamera

        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(MsaaConfigChooser())
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.holder.addCallback(frameRateHint)
        // Camera-paced rendering draws each frame the moment it lands.
        renderer.onCameraFrame = { if (pace == RenderPace.CAMERA) glView.requestRender() }
        thermal = ThermalMonitor(this) { applyQuality() }
        applyQuality() // idle until the trigger

        locationFeed = options.simulate?.let { site ->
            SimulatedLocationFeed(ApproachRoute(site, options.simStartM, options.simFromBearingDeg, options.simSpeedMps))
        } ?: DeviceLocationFeed(this)

        if (options.hud) hud.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        DeviceProbe.log(this, windowManager.defaultDisplay)
        earphones.register()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        glView.onResume()
        thermal.start()
        applyQuality()
        orientation.start()
        audio.start()
        startLocationIfPermitted()
        if (experienceStarted) openCameraIfReady()
        ui.post(uiTick)
        fieldTests?.onResume()
        goImmersive()
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(uiTick)
        fieldTests?.onPause()
        cameraFeed.close()
        renderer.setCameraConfig(null)
        locationFeed.stop()
        locationRunning = false
        audio.stop()
        orientation.stop()
        thermal.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fieldTests?.close()
        cameraFeed.release()
        earphones.unregister()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // ---- permissions --------------------------------------------------------

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        // Camera up front too: a permission dialog mid-experience, with the
        // phone held up at the sky, is the worst possible moment for one.
        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA)
            .filterNot(::has)
        if (missing.isEmpty()) {
            onPermissionsSettled()
        } else {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) onPermissionsSettled()
    }

    private fun onPermissionsSettled() {
        startLocationIfPermitted()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION) && options.simulate == null) {
            setStatus(getString(R.string.status_permission_denied))
        }
        if (options.testMode) startTestMode()
    }

    private fun startLocationIfPermitted() {
        if (!resumed || locationRunning) return
        val permitted = options.simulate != null ||
            has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!permitted) return
        locationFeed.start(this)
        locationRunning = true
    }

    // ---- location -----------------------------------------------------------

    override fun onFix(fix: Fix) {
        lastFix = fix
        val (lat, lng, accuracyM, source) = fix
        val fused = tracker.onFix(
            lat, lng, accuracyM, System.currentTimeMillis(),
            fix.speedMps, fix.bearingDeg, fix.speedAccuracyMps,
        )
        if (fused == PositionTracker.FixResult.REJECTED_JUMP || fused == PositionTracker.FixResult.RESET) {
            Log.i(TAG, "tracker: $fused (fix ±${accuracyM?.let { Math.round(it) } ?: "?"} m)")
        }
        fieldTests?.onFix(SystemClock.elapsedRealtime(), fix, fused.name, trackForLog())
        val alignment = alignment()
        if (!experienceStarted) {
            val decision = TriggerPolicy.evaluate(lat, lng)
            val near = decision.nearest
            recorder.recordGps(System.currentTimeMillis(), lat, lng, accuracyM, source, near.distanceM, near.bearingDeg, alignment, near.site.id)
            when (decision) {
                is TriggerDecision.Trigger -> startExperience(near.site, lat, lng, test = false)
                is TriggerDecision.Approach -> setStatus(distanceHint(near.distanceM))
            }
        } else {
            val site = activeSite ?: return
            val distance = Geo.distanceM(lat, lng, site.lat, site.lng)
            val bearing = Geo.bearingDeg(lat, lng, site.lat, site.lng)
            recorder.recordGps(System.currentTimeMillis(), lat, lng, accuracyM, source, distance, bearing, alignment, site.id)
            // Test mode freezes staging at "arrived"; real GPS still logs. The
            // monument follows the fused estimate, not the raw fix, so indoor
            // GPS wander can't drag it around.
            anchorToTracker()
        }
        refreshHud(force = true)
    }

    /** A footstep (main thread): dead-reckon, and move the monument with the wearer. */
    private fun onStep(east: Double, north: Double) {
        tracker.onStep(east, north, System.currentTimeMillis())
        if (experienceStarted) anchorToTracker()
        fieldTests?.onStep(SystemClock.elapsedRealtime(), east, north, orientation.detectedSteps, trackForLog())
    }

    private fun anchorToTracker() {
        if (testMode || !tracker.initialised) return
        val lat = tracker.lat
        val lng = tracker.lng
        glView.queueEvent { director.onLocation(lat, lng) }
    }

    override fun onProblem(problem: LocationProblem) {
        if (experienceStarted) return
        setStatus(
            getString(
                when (problem) {
                    LocationProblem.SERVICES_OFF -> R.string.status_location_off
                    LocationProblem.UNAVAILABLE -> R.string.status_position_unavailable
                    LocationProblem.SLOW -> R.string.status_slow_fix
                },
            ),
        )
        if (lastFix == null) hudLines.text = getString(R.string.hud_gps_problem, problem.name.lowercase())
    }

    private fun distanceHint(metres: Double): String = when (val h = DistanceHint.of(metres)) {
        is DistanceHint.Metres -> getString(R.string.status_distance_m, h.metres.toString())
        is DistanceHint.Kilometres -> getString(R.string.status_distance_km, h.text)
    }

    // ---- the experience -----------------------------------------------------

    /**
     * Test mode places the monument along the camera's heading, so it waits
     * (up to 2 s, see [PoseGate]) for a compass-corrected orientation. From a
     * launch intent this runs in onCreate, before the sensors have even started.
     */
    private fun startTestMode() {
        if (experienceStarted) return
        testModeGate.request(System.currentTimeMillis())
        maybeStartTestMode()
    }

    private fun maybeStartTestMode() {
        if (experienceStarted || !testModeGate.poll(System.currentTimeMillis(), orientation.headingReady)) return
        val site = Sites.ALL.first() // test mode previews the primary site, as before
        startExperience(site, site.lat, site.lng, test = true)
    }

    /** Camera forward (scene axes, x and z) from the newest orientation; north when there is none. */
    private fun currentForward(): Pair<Double, Double> {
        // WalkHeading, not the raw camera axis: test mode is usually launched
        // with the phone face-up on a desk, where the camera's horizontal
        // component is a couple of degrees of tilt pointing anywhere.
        if (history.latest(enuScratch) == Long.MIN_VALUE || !WalkHeading.of(enuScratch, facingScratch)) return 0.0 to -1.0
        return facingScratch[0] to -facingScratch[1] // scene: x = east, z = south
    }

    private fun startExperience(site: Site, lat: Double, lng: Double, test: Boolean) {
        if (experienceStarted) return
        experienceStarted = true
        testMode = test
        applyQuality() // the render loop wakes
        activeSite = site
        Log.i(TAG, "experience start site=${site.id} test=$test")

        splash.animate().alpha(0f).setDuration(1000).withEndAction {
            if (!cameraFailed) {
                splash.visibility = View.GONE
                // An infinite animator on a hidden view still wakes the UI thread every frame.
                arrowFloat?.cancel()
            }
        }.start()
        banner.start(System.currentTimeMillis())

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val stats = AnniversaryStats.compute(site.anchor, now, zone)
        val maxTex = renderer.maxTextureSize
        val textures = SceneTextures(
            marquee = TextTextures.marquee(site.marquee, maxTex),
            pylons = stats.pylonTexts().map { TextTextures.pylon(it, maxTex) },
            message = TextTextures.message(AnniversaryStats.messageLine(site.anchor, now)),
        )
        val (fwdX, fwdZ) = currentForward()
        glView.queueEvent {
            renderer.setSceneTextures(textures)
            director.start(site, lat, lng, test, fwdX, fwdZ)
        }
        openCameraIfReady()
    }

    private fun openCameraIfReady() {
        if (!experienceStarted || !resumed || cameraFeed.isOpen) return
        if (!has(Manifest.permission.CAMERA)) {
            showCameraFailure("permission denied")
            return
        }
        val surface = cameraSurface ?: return // onCameraSurface will call back
        val shortSide = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        cameraFeed.open(surface, shortSide, object : CameraFeed.Callback {
            override fun onConfigured(config: CameraFeed.Config) {
                Log.i(TAG, "camera ${config.description}")
                renderer.setCameraConfig(config)
                ui.post { onCameraRecovered() }
            }

            override fun onError(message: String) {
                ui.post { showCameraFailure(message) }
            }
        })
    }

    private fun showCameraFailure(reason: String) {
        Log.w(TAG, "camera unavailable: $reason")
        cameraFailed = true
        statusMessage.text = getString(R.string.status_camera_unavailable)
        splash.animate().cancel()
        splash.visibility = View.VISIBLE
        splash.alpha = 1f
    }

    /** A later open succeeded: take down the failure splash so it can't cover a working feed. */
    private fun onCameraRecovered() {
        if (!cameraFailed) return
        cameraFailed = false
        splash.animate().cancel()
        splash.visibility = View.GONE
        arrowFloat?.cancel()
    }

    private fun onAct(event: ActEvent, distanceM: Double) {
        Log.i(TAG, "act ${event.id} at ${Math.round(distanceM)} m (designed ${event.expectedM} m)")
        recorder.recordAct(System.currentTimeMillis(), event, distanceM)
    }

    // ---- renderer callbacks -------------------------------------------------

    override fun onCameraSurface(texture: SurfaceTexture) {
        ui.post {
            val changed = cameraSurface !== texture
            cameraSurface = texture
            if (changed && cameraFeed.isOpen) {
                cameraFeed.close()
                renderer.setCameraConfig(null)
            }
            openCameraIfReady()
        }
    }

    override fun onRenderError(message: String) {
        ui.post { Log.e(TAG, "render error: $message"); setStatus(message) }
    }

    // ---- controls -----------------------------------------------------------

    private fun bindViews() {
        glView = findViewById(R.id.ar_surface)
        splash = findViewById(R.id.splash)
        statusMessage = findViewById(R.id.status_message)
        compassView = findViewById(R.id.compass_indicator)
        audioButton = findViewById(R.id.audio_mode)
        hud = findViewById(R.id.debug_hud)
        hudLines = findViewById(R.id.hud_lines)

        val buildLabel = findViewById<TextView>(R.id.build_version)
        val hudToggle = findViewById<View>(R.id.hud_toggle)
        if (BuildConfig.DEV_TOOLS) {
            buildLabel.text = getString(R.string.build_version, BuildConfig.VERSION_CODE, BuildConfig.BUILD_STAMP)
            // The field-test menu: human taps work on the A07, only adb's are blocked.
            buildLabel.setOnLongClickListener { showFieldTestMenu(); true }
            hudLines.setOnLongClickListener { showFieldTestMenu(); true }
            hudToggle.setOnClickListener {
                if (hudTaps.onTap(System.currentTimeMillis()) == MultiTapDetector.Result.TRIGGERED) {
                    hud.visibility = if (hud.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    refreshHud(force = true)
                }
            }
            findViewById<View>(R.id.hud_export).setOnClickListener { exportSession() }
        } else {
            // Release: no build label, no HUD, no field-test menu, no export.
            buildLabel.visibility = View.GONE
            hudToggle.visibility = View.GONE
        }

        arrowFloat = ObjectAnimator.ofFloat(
            findViewById(R.id.splash_arrow), View.TRANSLATION_Y, 0f,
            -resources.getDimension(R.dimen.splash_arrow_float),
        ).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        findViewById<View>(R.id.secret_override).setOnClickListener { onSecretTap() }
        audioButton.setOnClickListener { setEarphones(!mixer.earphones) }
    }

    private fun onSecretTap() {
        // Release has no test mode: the corner only skips a failed camera, and
        // stays silent otherwise so nothing hints that it's there.
        if (!BuildConfig.DEV_TOOLS && !cameraFailed) return
        when (secretTaps.onTap(System.currentTimeMillis())) {
            MultiTapDetector.Result.TICK -> haptics.tick()
            MultiTapDetector.Result.TRIGGERED -> {
                haptics.confirm()
                if (!experienceStarted) {
                    startTestMode()
                } else if (cameraFailed) {
                    // "Tap three times to skip": carry on over black.
                    splash.visibility = View.GONE
                    arrowFloat?.cancel()
                }
            }
        }
    }

    private fun setEarphones(on: Boolean) {
        mixer.earphones = on
        audioButton.text = getString(if (on) R.string.audio_mode_earphones else R.string.audio_mode_speaker)
        // 0.55 is the floor for a white glyph over a sunlit feed; +0.05 marks the state.
        audioButton.alpha = if (on) 0.6f else 0.55f
    }

    private fun exportSession() {
        val json = recorder.exportJson(Instant.now().toString(), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (json == null) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_LONG).show()
            return
        }
        val chooser = SessionExporter.share(this, json, getString(R.string.export_chooser))
        if (chooser == null) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        } else {
            startActivity(chooser)
        }
    }

    private fun setStatus(text: String) {
        statusMessage.text = text
    }

    // ---- 10 Hz UI tick: compass banner, HUD, periodic log samples -----------

    private val uiTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (testModeGate.pending) maybeStartTestMode()
            if (experienceStarted) {
                if (orientation.lastAbsoluteAtMs > 0) {
                    banner.onOrientation(now, absolute = true, good = orientation.headingGood())
                } else if (orientation.lastAnyAtMs > 0) {
                    banner.onOrientation(now, absolute = false, good = true)
                }
                showBanner(banner.update(now))
            }
            if (history.latest(enuScratch) != Long.MIN_VALUE) {
                recorder.recordOrientation(now, enuScratch[1], enuScratch[2], enuScratch[3], enuScratch[0])
            }
            if (thermal.pollHeadroom(now)) applyQuality()
            // Camera-paced, a stalled camera would freeze the story: keep it at 10 Hz or better.
            if (pace == RenderPace.CAMERA && System.nanoTime() - renderer.telemetry.lastDrawNanos > STALL_NANOS) {
                glView.requestRender()
            }
            val p = perf()
            recorder.recordPerf(
                now, p.renderFps, p.cameraFps, p.cameraLatencyMs, p.sensorHz, p.poseLagMs,
                thermal.status, thermal.headroom, thermalPolicy.level,
            )
            pendingFieldTest?.let { id ->
                pendingFieldTest = null
                startFieldTest(id, p)
            }
            fieldTests?.let { if (it.running) it.onTick(fieldSample(p), p, thermalPolicy.level) }
            refreshHud(force = false)
            ui.postDelayed(this, 100)
        }
    }

    private fun showBanner(state: CompassBanner.State) {
        if (state == shownBanner) return
        shownBanner = state
        val text = when (state) {
            CompassBanner.State.CALIBRATING -> R.string.compass_calibrating
            CompassBanner.State.LOCKED -> R.string.compass_locked
            CompassBanner.State.ACTIVE -> R.string.compass_active
            CompassBanner.State.NEEDS_CALIBRATION -> R.string.compass_needs_calibration
            CompassBanner.State.HIDDEN -> null
        }
        if (text != null) compassView.text = getString(text)
        compassView.animate().alpha(if (text != null) 1f else 0f).setDuration(500).start()
    }

    /** Before the trigger the render loop idles, so fps reads 0 there. */
    private fun perf(): PerfSnapshot {
        val t = renderer.telemetry
        val now = System.nanoTime()
        return PerfSnapshot(
            renderFps = t.renderRate.rateHz(now),
            cameraFps = t.cameraRate.rateHz(now),
            cameraLatencyMs = t.cameraLatencyMs.value.takeUnless { it.isNaN() } ?: 0.0,
            sensorHz = orientation.rate.rateHz(android.os.SystemClock.elapsedRealtimeNanos()),
            poseSyncedToCamera = t.poseSynced,
            poseLagMs = t.poseLagMs.value.takeUnless { it.isNaN() } ?: 0.0,
            headingSource = "${orientation.describe}  ${t.viewDescription}",
            thermal = ThermalSnapshot(thermal.status, thermal.headroom, thermalPolicy.quality),
        )
    }

    // ---- thermal ladder -----------------------------------------------------

    /**
     * Re-evaluates the thermal rung and applies it with the render pace (idle
     * until the trigger). Main thread; cheap when nothing changed.
     */
    private fun applyQuality() {
        if (thermalPolicy.update(thermal.status, thermal.headroom)) {
            val note = "quality ${thermalPolicy.quality.describe} (thermal ${ThermalPolicy.statusName(thermal.status)}, " +
                "headroom ${thermal.headroom})"
            Log.i(TAG, note)
            fieldTests?.note(SystemClock.elapsedRealtime(), note)
        }
        val q = thermalPolicy.quality
        val next = thermalPolicy.pace(experienceStarted)
        if (next != pace) {
            pace = next
            glView.renderMode = if (next == RenderPace.FULL) GLSurfaceView.RENDERMODE_CONTINUOUSLY else GLSurfaceView.RENDERMODE_WHEN_DIRTY
            if (next != RenderPace.IDLE) glView.requestRender()
            applyFrameRateHint(glView.holder)
        }
        applyRenderScale(if (experienceStarted) q.renderScale else 1.0)
        val cap = q.maxLiveFireworks
        glView.queueEvent { director.maxLiveFireworks = cap }
    }

    /** Shrinks the GL surface; the compositor scales it back up to the view. */
    private fun applyRenderScale(scale: Double) {
        if (scale == appliedRenderScale) return
        val w = glView.width
        val h = glView.height
        if (w == 0 || h == 0) return // not laid out yet; the next applyQuality catches it
        appliedRenderScale = scale
        renderer.renderScale = scale.toFloat()
        if (scale >= 1.0) {
            glView.holder.setSizeFromLayout()
        } else {
            glView.holder.setFixedSize(maxOf(1, Math.round(w * scale).toInt()), maxOf(1, Math.round(h * scale).toInt()))
        }
    }

    /**
     * Camera heading from the newest orientation (the render loop idles before
     * the trigger, so its telemetry can be stale), monument from the renderer.
     */
    private fun alignment(): Alignment? {
        if (history.latest(enuScratch) == Long.MIN_VALUE) return null
        Frames.enuToScene(enuScratch, sceneScratch)
        Frames.forward(sceneScratch, forwardScratch)
        val fx = forwardScratch[0]
        val fz = forwardScratch[2]
        val t = renderer.telemetry
        return if (t.groupPlaced) Alignment.of(fx, fz, t.groupX, t.groupZ) else Alignment.of(fx, fz, null, null)
    }

    // ---- field tests --------------------------------------------------------

    private val fieldTestHost = object : FieldTestController.Host {
        /**
         * The whole show, every act lit. A session already triggered by GPS
         * (the origin site triggers at home) is switched to test staging, or
         * its fireworks would stay dark beyond 800 m.
         */
        override fun startShow() {
            if (!experienceStarted) startTestMode() else if (!testMode) placeAhead()
        }

        override fun placeAhead() {
            if (!experienceStarted) {
                startTestMode()
                return
            }
            val site = activeSite ?: return
            testMode = true // GPS no longer re-anchors it
            val (fx, fz) = currentForward()
            glView.queueEvent { director.placeAhead(site, fx, fz) }
        }

        override fun setSyncCamera(on: Boolean) {
            renderer.syncToCamera = on
        }

        override fun restoreSyncCamera() {
            renderer.syncToCamera = options.syncCamera
        }
    }

    private fun showFieldTestMenu() {
        val tests = fieldTests ?: return
        if (tests.running) {
            Toast.makeText(this, R.string.field_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val scripts = FieldTests.all(SystemClock.elapsedRealtime())
        val items = scripts.map { "${it.id.uppercase(Locale.US)}  ${it.title}\n${it.summary}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.field_menu_title)
            .setItems(items) { _, which -> startFieldTest(scripts[which].id, perf()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startFieldTest(id: String, p: PerfSnapshot) {
        val tests = fieldTests ?: return
        if (tests.running) return
        val seed = SystemClock.elapsedRealtime()
        val script = FieldTests.byId(id, seed) ?: run {
            Toast.makeText(this, getString(R.string.field_unknown, id), Toast.LENGTH_LONG).show()
            return
        }
        val extras = mapOf(
            "seed" to seed.toString(),
            "buildStamp" to BuildConfig.BUILD_STAMP,
            "syncCamera" to options.syncCamera.toString(),
            "headingTau" to options.headingTauS.toString(),
            "experienceStarted" to experienceStarted.toString(),
            "testMode" to testMode.toString(),
            "site" to (activeSite?.id ?: "none"),
            "simulate" to (options.simulate?.id ?: "none"),
        )
        tests.start(script, fieldSample(p), extras)
    }

    private fun fieldSample(p: PerfSnapshot): FieldSample {
        val q = if (history.latest(enuScratch) != Long.MIN_VALUE) enuScratch.copyOf() else null
        val a = alignment()
        val tracked = tracker.initialised
        return FieldSample(
            tMs = SystemClock.elapsedRealtime(),
            quat = q,
            steps = orientation.detectedSteps,
            fusedLat = if (tracked) tracker.lat else null,
            fusedLng = if (tracked) tracker.lng else null,
            sigmaM = if (tracked) tracker.sigmaM else null,
            deltaDeg = a?.deltaDeg,
            renderFps = p.renderFps,
            cameraFps = p.cameraFps,
            photonMs = p.cameraLatencyMs,
            thermalStatus = thermal.status,
            headroom = thermal.headroom,
            compassHeadingDeg = orientation.compassHeadingDeg.takeUnless { it.isNaN() },
            yawCorrectionDeg = orientation.yawCorrectionDeg.takeUnless { it.isNaN() },
        )
    }

    private fun trackForLog(): FieldTestLog.Track? {
        if (!tracker.initialised) return null
        return FieldTestLog.Track(
            tracker.lat, tracker.lng, tracker.sigmaM, tracker.stepCount, tracker.rejectedFixes,
            tracker.speedMps(System.currentTimeMillis()),
        )
    }

    private fun refreshHud(force: Boolean) {
        if (hud.visibility != View.VISIBLE) return
        val now = System.currentTimeMillis()
        if (!force && now - lastHudAt < 500) return
        lastHudAt = now
        val fix = lastFix ?: return
        val site = activeSite ?: TriggerPolicy.evaluate(fix.lat, fix.lng).nearest.site
        val distance = Geo.distanceM(fix.lat, fix.lng, site.lat, site.lng)
        val bearing = Geo.bearingDeg(fix.lat, fix.lng, site.lat, site.lng)
        val track = if (tracker.initialised) {
            TrackSnapshot(
                sigmaM = tracker.sigmaM,
                steps = tracker.stepCount,
                rejectedFixes = tracker.rejectedFixes,
                fusedDistanceM = activeSite?.let { Geo.distanceM(tracker.lat, tracker.lng, it.lat, it.lng) },
                speedMps = tracker.speedMps(now),
            )
        } else null
        hudLines.text = HudFormatter.lines(
            fix.source, fix.accuracyM, fix.lat, fix.lng, if (testMode) 0.0 else distance, bearing,
            site.id, alignment(), perf(), track,
        ).joinToString("\n")
    }

    // ---- window -------------------------------------------------------------

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 60 Hz is the story's tick rate; asking the A56's 120 Hz panel for 60
     * halves GPU and battery cost. Camera-paced, 30 matches what is drawn.
     */
    private fun applyFrameRateHint(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !holder.surface.isValid) return
        val hz = if (pace == RenderPace.CAMERA) 30f else 60f
        runCatching { holder.surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    private val frameRateHint = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = applyFrameRateHint(holder)

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!BuildConfig.DEV_TOOLS) return
        // singleTask: a second `am start` without -S reaches us here. Only the
        // switches that can change at runtime are honoured; simulate and
        // headingTau need a fresh start (`am start -S`, as the deploy scripts do).
        if (intent.getBooleanExtra("testMode", false)) startTestMode()
        if (intent.getBooleanExtra("hud", false)) hud.visibility = View.VISIBLE
        if (intent.hasExtra("syncCamera")) renderer.syncToCamera = intent.getBooleanExtra("syncCamera", true)
        intent.getStringExtra("fieldTest")?.let { pendingFieldTest = it }
    }

    private companion object {
        const val TAG = "Lotus"
        const val PERMISSION_REQUEST = 1
        const val STALL_NANOS = 100_000_000L
    }
}
