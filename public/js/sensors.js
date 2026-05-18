import { getDistanceKm } from './geo.js';
import {
    TARGET_LAT, TARGET_LNG,
    getCamera, startExperience,
    setNativeOrientationActive, setUserDistance,
    isExperienceStarted, clearWatchPosition,
} from './scene.js';

let _webGeoIntervalId      = null;
let _webGeoIntervalStarted = false;
let _isTestMode            = false;
let _lastNativeLocationAt  = 0;

// Called by index.html secret-override handler before calling startExperience().
export function setTestMode(val) { _isTestMode = val; }

function formatDistanceHint(metres) {
    if (metres < 1000) return `Approx ${Math.floor(metres / 10) * 10}m to go`;
    return `Approx ${Math.floor(metres / 100) / 10}km to go`;
}

/**
 * startWebGeoInterval — Fallback pre-trigger GPS polling via navigator.geolocation.
 * Starts a 3-second setInterval that calls getCurrentPosition and updates the debug HUD,
 * triggers startExperience when within 1800m, or shows a distance hint.
 * Guard flag prevents multiple intervals if both the start() fallback and the 2s watchdog fire.
 */
export function startWebGeoInterval() {
    if (_webGeoIntervalStarted) return;
    _webGeoIntervalStarted = true;
    _webGeoIntervalId = setInterval(() => {
        if (navigator.geolocation && !isExperienceStarted()) {
            navigator.geolocation.getCurrentPosition(pos => {
                const metres = getDistanceKm(pos.coords.latitude, pos.coords.longitude, TARGET_LAT, TARGET_LNG) * 1000;
                const acc    = pos.coords.accuracy != null ? Math.round(pos.coords.accuracy) : '?';
                document.getElementById('debug-hud').innerHTML =
                    `dist: ${Math.round(metres)}m<br>trigger: ≤1800m<br>GPS acc: ±${acc}m`;
                if (metres <= 1800) {
                    clearInterval(_webGeoIntervalId);
                    _webGeoIntervalId = null;
                    startExperience(pos.coords.latitude, pos.coords.longitude, false);
                } else {
                    document.getElementById('status-message').innerText = formatDistanceHint(metres);
                }
            }, (err) => {
                // A missing error callback leaves permission-denied unrecoverable at the venue.
                // Map known error codes to readable copy so the wearer can act.
                let msg;
                if      (err.code === err.PERMISSION_DENIED)   msg = 'Location permission denied. Enable it in Android settings.';
                else if (err.code === err.POSITION_UNAVAILABLE) msg = 'Location unavailable. Step outside for clearer sky.';
                else                                            msg = 'Location is taking longer than usual...';
                document.getElementById('status-message').innerText = msg;
                document.getElementById('debug-hud').innerHTML = `GPS error ${err.code}`;
            }, { enableHighAccuracy: true });
        }
    }, 3000);
}

/**
 * bootstrapSensors — Entry point for native sensor bridge initialization.
 * Called once from index.html after initScene().
 *
 * Strategy:
 * 1. If Capacitor.Plugins.SensorBridge is present, register listeners for both
 *    onNativeOrientationUpdate and onNativeLocationUpdate, then call start().
 * 2. start() resolves with status=fallback (permission denied) → startWebGeoInterval().
 * 3. A 2000ms watchdog fires startWebGeoInterval() if no location event arrives,
 *    covering GPS cold-start latency without blocking the splash transition.
 * 4. If SensorBridge is absent (non-Capacitor env), startWebGeoInterval() runs immediately.
 */
export function bootstrapSensors() {
    if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.SensorBridge) {
        const SensorBridge = window.Capacitor.Plugins.SensorBridge;

        SensorBridge.addListener('onNativeOrientationUpdate', (data) => {
            setNativeOrientationActive();
            getCamera().quaternion.set(data.x, data.y, data.z, data.w);
        });

        SensorBridge.addListener('onNativeLocationUpdate', (data) => {
            _lastNativeLocationAt = Date.now();
            window.__nativeLocationActive = true;
            clearWatchPosition(); // cancel any watchPosition started by startExperience()

            const metres = getDistanceKm(data.latitude, data.longitude, TARGET_LAT, TARGET_LNG) * 1000;
            const acc    = data.accuracy != null ? Math.round(data.accuracy) : '?';
            document.getElementById('debug-hud').innerHTML =
                `dist: ${Math.round(metres)}m<br>trigger: ≤1800m<br>GPS acc: ±${acc}m`;

            if (!isExperienceStarted() && metres <= 1800) {
                if (_webGeoIntervalId !== null) { clearInterval(_webGeoIntervalId); _webGeoIntervalId = null; }
                startExperience(data.latitude, data.longitude, false);
            } else if (isExperienceStarted() && !_isTestMode) {
                setUserDistance(metres);
            } else if (!isExperienceStarted()) {
                document.getElementById('status-message').innerText = formatDistanceHint(metres);
            }
        });

        SensorBridge.start().then((result) => {
            if (result && result.status === 'fallback') startWebGeoInterval();
        }).catch(() => {
            startWebGeoInterval();
        });

        // 2s watchdog: covers GPS cold-start latency without blocking the splash screen
        setTimeout(() => {
            if (_lastNativeLocationAt === 0) startWebGeoInterval();
        }, 2000);
    } else {
        startWebGeoInterval();
    }
}
