import { getDistanceKm, getBearing } from './geo.js';
import {
    SITES, nearestSite, getActiveSite,
    getCamera, startExperience,
    setNativeOrientationActive, setUserDistance,
    isExperienceStarted, clearWatchPosition,
    getAlignmentData, getPerfStats,
} from './scene.js';
import { initRecorder, recordGPS, recordOrientation } from './session-recorder.js';

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

const CHECKPOINTS = [
    { name: 'PHIVOLCS',  lat: 14.652088, lng: 121.058387, expectedM: 1571 },
    { name: '600m-N',    lat: 14.664278, lng: 121.071173, expectedM:  600 },
    { name: '600m-E',    lat: 14.658888, lng: 121.076744, expectedM:  600 },
    { name: '600m-S',    lat: 14.653498, lng: 121.071173, expectedM:  600 },
    { name: '600m-W',    lat: 14.658888, lng: 121.065602, expectedM:  600 },
];

function _nearestCheckpoint(lat, lng) {
    let best = CHECKPOINTS[0], bestDist = Infinity;
    for (const cp of CHECKPOINTS) {
        const d = Math.hypot(lat - cp.lat, lng - cp.lng);
        if (d < bestDist) { bestDist = d; best = cp; }
    }
    return best;
}

function _stageLabel(distM) {
    if (distM > 1800)  return 'approaching';
    if (distM > 1500)  return 'beacon_fading_in';
    if (distM > 800)   return 'beacon_full';
    if (distM > 650)   return 'beacon_fading_out';
    if (distM > 400)   return 'fireworks_only';
    if (distM > 150)   return 'marquee_fading_in';
    if (distM > 100)   return 'marquee_full';
    if (distM > 40)    return 'stats_fading_in';
    return 'full_experience';
}

// Distance (m) + bearing (deg) from a position to a specific site.
function _geoTo(lat, lng, site) {
    const metres     = getDistanceKm(lat, lng, site.lat, site.lng) * 1000;
    const bearingDeg = ((getBearing(lat, lng, site.lat, site.lng) * 180 / Math.PI) + 360) % 360;
    return { metres, bearingDeg };
}

function _buildHUD(lat, lng, acc, src, distM, bearingDeg, site) {
    const cp   = _nearestCheckpoint(lat, lng);
    const diff = Math.round(distM - cp.expectedM);
    const sign = diff >= 0 ? '+' : '';
    const ali  = getAlignmentData();
    const camStr = ali                 ? `${ali.camDeg}&deg;`   : '&mdash;';
    const arStr  = ali && ali.arDeg    ? `${ali.arDeg}&deg;`    : '&mdash;';
    const dStr   = ali && ali.deltaDeg ? `${ali.deltaDeg}&deg;` : '&mdash;';
    const p = getPerfStats();
    const perfStr = p.busyFrames
        ? `frame ${p.frameMsAvg}/${p.frameMsMax} &middot; part ${p.partMsAvg}/${p.partMsMax} &middot; rend ${p.renderMsAvg}/${p.renderMsMax}ms &middot; n${p.partCountMax}`
        : 'no fireworks yet';
    return [
        `[${src}] &plusmn;${acc}m`,
        `${lat.toFixed(4)} / ${lng.toFixed(4)}`,
        `dist: ${Math.round(distM)}m &nbsp; brg: ${bearingDeg.toFixed(1)}&deg;`,
        `site: ${site ? site.id : '&mdash;'} &nbsp; &asymp; ${cp.name} &nbsp; &Delta;${sign}${diff}m`,
        `stage: ${_stageLabel(distM)}`,
        `cam: ${camStr} &nbsp; AR: ${arStr} &nbsp; &Delta;: ${dStr}`,
        `perf(avg/max): ${perfStr}`,
        `<span data-action="export" style="pointer-events:auto;cursor:pointer;text-decoration:underline;opacity:0.8">&#x2B07; Export Session</span>`,
    ].join('<br>');
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
                const lat  = pos.coords.latitude;
                const lng  = pos.coords.longitude;
                const acc  = pos.coords.accuracy != null ? Math.round(pos.coords.accuracy) : '?';
                const near = nearestSite(lat, lng);
                document.getElementById('debug-hud').innerHTML = _buildHUD(lat, lng, acc, 'web', near.distM, near.bearingDeg, near.site);
                recordGPS(lat, lng, acc, 'web', near.distM, near.bearingDeg, getAlignmentData(), near.site.id);
                if (near.distM <= 1800) {
                    clearInterval(_webGeoIntervalId);
                    _webGeoIntervalId = null;
                    startExperience(near.site, lat, lng, false);
                } else {
                    document.getElementById('status-message').innerText = formatDistanceHint(near.distM);
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
    initRecorder(SITES, getPerfStats);
    if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.SensorBridge) {
        const SensorBridge = window.Capacitor.Plugins.SensorBridge;

        SensorBridge.addListener('onNativeOrientationUpdate', (data) => {
            setNativeOrientationActive();
            getCamera().quaternion.set(data.x, data.y, data.z, data.w);
            recordOrientation(data.x, data.y, data.z, data.w);
        });

        SensorBridge.addListener('onNativeLocationUpdate', (data) => {
            _lastNativeLocationAt = Date.now();
            window.__nativeLocationActive = true;
            clearWatchPosition(); // cancel any watchPosition started by startExperience()

            const lat = data.latitude;
            const lng = data.longitude;
            const acc = data.accuracy != null ? Math.round(data.accuracy) : '?';

            if (isExperienceStarted()) {
                // Locked to the triggering site for the rest of the session.
                const site = getActiveSite();
                const { metres, bearingDeg } = _geoTo(lat, lng, site);
                document.getElementById('debug-hud').innerHTML = _buildHUD(lat, lng, acc, 'native', metres, bearingDeg, site);
                recordGPS(lat, lng, acc, 'native', metres, bearingDeg, getAlignmentData(), site.id);
                if (!_isTestMode) setUserDistance(metres);
            } else {
                const near = nearestSite(lat, lng);
                document.getElementById('debug-hud').innerHTML = _buildHUD(lat, lng, acc, 'native', near.distM, near.bearingDeg, near.site);
                recordGPS(lat, lng, acc, 'native', near.distM, near.bearingDeg, getAlignmentData(), near.site.id);
                if (near.distM <= 1800) {
                    if (_webGeoIntervalId !== null) { clearInterval(_webGeoIntervalId); _webGeoIntervalId = null; }
                    startExperience(near.site, lat, lng, false);
                } else {
                    document.getElementById('status-message').innerText = formatDistanceHint(near.distM);
                }
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
