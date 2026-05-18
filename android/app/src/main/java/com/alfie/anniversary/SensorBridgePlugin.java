package com.alfie.anniversary;

import android.Manifest;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.annotation.PluginMethod;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Capacitor plugin that bridges two hardware data streams into JS event channels:
 * <ul>
 *   <li><b>onNativeOrientationUpdate</b> — absolute-frame quaternion from
 *       {@code TYPE_ROTATION_VECTOR} (accelerometer + gyroscope + magnetometer fusion).
 *       Uses {@code TYPE_ROTATION_VECTOR} rather than {@code TYPE_GAME_ROTATION_VECTOR}
 *       so the yaw component is anchored to true compass north, keeping the AR marquee
 *       aligned to the church across sessions. (ref: DL-011)</li>
 *   <li><b>onNativeLocationUpdate</b> — GPS fix from {@code FusedLocationProviderClient}
 *       at 1-second intervals, PRIORITY_HIGH_ACCURACY. (ref: DL-007)</li>
 * </ul>
 *
 * <p>Combining orientation and location in one plugin avoids double permission flow,
 * double JS init, and double fallback gating in the single-file consumer
 * {@code public/index.html}. (ref: DL-001)</p>
 *
 * <p>Auto-discovered by Capacitor v5+ classpath scanning via {@code @CapacitorPlugin}
 * annotation; {@code MainActivity.java} requires no {@code registerPlugin()} override.
 * (ref: DL-002)</p>
 *
 * <p>Thread safety: {@link #onSensorChanged} is dispatched on a SensorManager background
 * HandlerThread. {@code notifyListeners} posts through Capacitor's bridge to the WebView
 * thread internally. (ref: DL-014)</p>
 */
@CapacitorPlugin(
    name = "SensorBridge",
    permissions = {
        @Permission(alias = "location", strings = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        })
    }
)
public class SensorBridgePlugin extends Plugin implements SensorEventListener {

    private static final String TAG = "SensorBridge";

    /**
     * L-infinity norm threshold per quaternion component before a
     * {@code notifyListeners} call is issued. Suppresses redundant IPC events
     * at ~50 Hz (SENSOR_DELAY_GAME) when the device is stationary.
     * Estimated ~10x above the typical stationary noise floor (~1e-4/component)
     * and ~17x below the smallest perceptible head motion (~0.017 per component).
     * Tune post-deployment via field measurement on Galaxy A56 5G. (ref: DL-005)
     */
    private static final float DELTA_THRESHOLD = 0.001f;

    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    /** Tracks the last emitted quaternion components [x, y, z, w] for delta gating. */
    private float[] lastQuat = new float[]{0f, 0f, 0f, 1f};

    /**
     * Acquires system services at plugin load time. Logs confirmation so Logcat
     * can verify auto-discovery succeeded at app start. (ref: DL-002)
     */
    @Override
    public void load() {
        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        fusedClient = LocationServices.getFusedLocationProviderClient(getContext());
        Log.d(TAG, "SensorBridgePlugin loaded");
    }

    /**
     * Requests ACCESS_FINE_LOCATION at call time and starts both sensor streams
     * on grant. Resolves with {@code status=fallback} on denial so the JS consumer
     * falls back to {@code navigator.geolocation} without disruption. (ref: DL-004)
     */
    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            doStart(call);
        } else {
            requestPermissionForAlias("location", call, "locationPermissionCallback");
        }
    }

    /** Resolves the pending {@code start()} call after the permission dialog settles. */
    @PermissionCallback
    private void locationPermissionCallback(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            doStart(call);
        } else {
            JSObject result = new JSObject();
            result.put("status", "fallback");
            call.resolve(result);
        }
    }

    /**
     * Registers the rotation-vector sensor at SENSOR_DELAY_GAME (~20 ms / 50 Hz)
     * and starts FusedLocationProviderClient at 1-second intervals on the main looper.
     *
     * <p>SENSOR_DELAY_GAME is chosen over SENSOR_DELAY_FASTEST to avoid saturating
     * the JNI-to-JS bridge; the delta threshold provides additional suppression.
     * (ref: DL-006)</p>
     *
     * <p>FusedLocationProviderClient.requestLocationUpdates is called on
     * {@code Looper.getMainLooper()} per Android requirements and Capacitor thread
     * expectations. (ref: DL-007)</p>
     *
     * <p>SecurityException from location updates is caught and logged rather than
     * propagated; the JS 2s watchdog engages the web fallback if no location events
     * arrive. </p>
     *
     * <p>Resolves with {@code status=native_ok} when both streams start successfully.
     * Resolves with {@code status=fallback} only when location permission is denied
     * (handled in {@link #locationPermissionCallback}); a missing rotation-vector
     * sensor is silent on the Java side — the JS 2s watchdog detects absence of
     * {@code onNativeOrientationUpdate} events and keeps DeviceOrientationControls
     * active. (ref: DL-008, DL-009)</p>
     */
    private void doStart(PluginCall call) {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                android.location.Location loc = result.getLastLocation();
                if (loc == null) return;
                JSObject data = new JSObject();
                data.put("latitude", loc.getLatitude());
                data.put("longitude", loc.getLongitude());
                data.put("accuracy", loc.getAccuracy());
                notifyListeners("onNativeLocationUpdate", data, true);
            }
        };

        try {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.d(TAG, "SecurityException requesting location updates: " + e.getMessage());
        }

        JSObject result = new JSObject();
        result.put("status", "native_ok");
        call.resolve(result);
    }

    /**
     * Applies L-infinity delta gating before forwarding the quaternion to JS.
     *
     * <p>{@code SensorManager.getQuaternionFromVector} output layout: index 0 = w,
     * indices 1-3 = x, y, z. The component is re-ordered to the Three.js
     * {@code Quaternion.set(x, y, z, w)} convention before emission.</p>
     *
     * <p>Called on the SensorManager background HandlerThread; {@code notifyListeners}
     * marshals to the WebView thread internally. (ref: DL-014)</p>
     *
     * <p>Each component is compared against {@link #DELTA_THRESHOLD}; the event is
     * suppressed when all four components fall within the threshold. (ref: DL-005)</p>
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;

        float[] qRaw = new float[5];
        SensorManager.getQuaternionFromVector(qRaw, event.values);
        // getQuaternionFromVector output: [w, x, y, z] in indices [0,1,2,3]
        float x = qRaw[1];
        float y = qRaw[2];
        float z = qRaw[3];
        float w = qRaw[0];

        if (Math.abs(x - lastQuat[0]) > DELTA_THRESHOLD ||
            Math.abs(y - lastQuat[1]) > DELTA_THRESHOLD ||
            Math.abs(z - lastQuat[2]) > DELTA_THRESHOLD ||
            Math.abs(w - lastQuat[3]) > DELTA_THRESHOLD) {

            lastQuat[0] = x; lastQuat[1] = y; lastQuat[2] = z; lastQuat[3] = w;

            JSObject data = new JSObject();
            data.put("x", x);
            data.put("y", y);
            data.put("z", z);
            data.put("w", w);
            notifyListeners("onNativeOrientationUpdate", data, true);
        }
    }

    /** Required by {@code SensorEventListener}; no accuracy-change behavior needed. */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Unregisters the rotation-vector sensor and stops location updates when
     * the app enters the background, conserving battery. Re-registration happens
     * in {@link #handleOnResume}.
     */
    @Override
    public void handleOnPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
    }

    /**
     * Re-registers both sensor streams after the app returns to the foreground.
     * Location re-registration is guarded by the same SecurityException catch
     * as {@link #doStart} to handle mid-session permission revocation.
     */
    @Override
    public void handleOnResume() {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (locationCallback != null) {
            LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .build();
            try {
                fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            } catch (SecurityException e) {
                Log.d(TAG, "SecurityException re-registering location on resume: " + e.getMessage());
            }
        }
    }
}
