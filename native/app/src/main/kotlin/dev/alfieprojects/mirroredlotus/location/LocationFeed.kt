package dev.alfieprojects.mirroredlotus.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.alfieprojects.mirroredlotus.core.sim.ApproachRoute

/** Where fixes and location trouble are delivered - always on the main thread. */
interface LocationListenerSink {
    fun onFix(lat: Double, lng: Double, accuracyM: Double?, source: String)
    fun onProblem(problem: LocationProblem)
}

enum class LocationProblem { SERVICES_OFF, UNAVAILABLE, SLOW }

interface LocationFeed {
    fun start(sink: LocationListenerSink)
    fun stop()
}

/**
 * 1 Hz high-accuracy fixes from the fused provider (what SensorBridge used),
 * falling back to the platform GPS provider on phones without Play services.
 * FLP also carries mock locations, so synth-loc can drive this for testing.
 */
class DeviceLocationFeed(private val context: Context) : LocationFeed {
    private val main = Handler(Looper.getMainLooper())
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var sink: LocationListenerSink? = null
    private var fusedCallback: LocationCallback? = null
    private var platformListener: LocationListener? = null
    private var lastFixAt = 0L

    // The web build's getCurrentPosition timeout: past 8 s without a fix, say so.
    private val slowCheck = object : Runnable {
        override fun run() {
            val s = sink ?: return
            if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                s.onProblem(LocationProblem.SERVICES_OFF)
            } else if (SystemClock.elapsedRealtime() - lastFixAt > SLOW_MS) {
                s.onProblem(LocationProblem.SLOW)
            }
            main.postDelayed(this, SLOW_MS)
        }
    }

    @SuppressLint("MissingPermission") // callers check the runtime permission first
    override fun start(sink: LocationListenerSink) {
        if (this.sink != null) return
        this.sink = sink
        lastFixAt = SystemClock.elapsedRealtime()
        main.postDelayed(slowCheck, SLOW_MS)
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) sink.onProblem(LocationProblem.SERVICES_OFF)

        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices == ConnectionResult.SUCCESS) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .setWaitForAccurateLocation(false)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { deliver(it, "fused") }
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!availability.isLocationAvailable) this@DeviceLocationFeed.sink?.onProblem(
                        if (LocationManagerCompat.isLocationEnabled(locationManager)) LocationProblem.UNAVAILABLE else LocationProblem.SERVICES_OFF,
                    )
                }
            }
            fusedCallback = cb
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .requestLocationUpdates(request, cb, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "Fused location refused: ${e.message}")
            }
        } else {
            Log.i(TAG, "Play services unavailable ($playServices); using the platform GPS provider")
            val listener = LocationListener { deliver(it, "gps") }
            platformListener = listener
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "GPS provider refused: ${e.message}")
                sink.onProblem(LocationProblem.UNAVAILABLE)
            }
        }
    }

    private fun deliver(loc: Location, source: String) {
        lastFixAt = SystemClock.elapsedRealtime()
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
        sink?.onFix(loc.latitude, loc.longitude, acc, if (mock) "$source-mock" else source)
    }

    override fun stop() {
        fusedCallback?.let { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(it) }
        fusedCallback = null
        platformListener?.let { locationManager.removeUpdates(it) }
        platformListener = null
        main.removeCallbacks(slowCheck)
        sink = null
    }

    private companion object {
        const val TAG = "LotusLocation"
        const val SLOW_MS = 8_000L
    }
}

/**
 * Replaces GPS with a scripted walk-in (`--es simulate church`), so every act
 * can be watched at a desk. Resumes where it left off across pause/resume.
 */
class SimulatedLocationFeed(private val route: ApproachRoute) : LocationFeed {
    private val main = Handler(Looper.getMainLooper())
    private var sink: LocationListenerSink? = null
    private var elapsedS = 0.0

    private val step = object : Runnable {
        override fun run() {
            val s = sink ?: return
            val p = route.positionAt(elapsedS)
            s.onFix(p.lat, p.lng, 4.0, "sim")
            elapsedS += 1.0
            main.postDelayed(this, 1000L)
        }
    }

    override fun start(sink: LocationListenerSink) {
        if (this.sink != null) return
        this.sink = sink
        main.post(step)
    }

    override fun stop() {
        main.removeCallbacks(step)
        sink = null
    }
}
