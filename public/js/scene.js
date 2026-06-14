import { getDistanceKm, getBearing } from './geo.js';
import { ensureAudioContext, detectEarphones, setEarphonesMode, syncAudioListener } from './audio.js';
import { Firework, getTextIllumination, tickIllumination } from './fireworks.js';
import { recordActEvent } from './session-recorder.js';

// Each site triggers the experience independently; the nearest in-range site
// wins (sites are ~2 km apart so trigger ranges don't overlap). `anchor` is the
// ISO datetime the stat panels + message count from; `marquee` is the cylinder text.
export const SITES = [
    {
        id:      'church',
        lat:     14.658888478751235,
        lng:     121.071173166497,
        anchor:  '2008-05-15T10:00:00',   // wedding, 10am ceremony (Hours stat anchors here)
        marquee: 'Happy Anniversary, Bhaze!',
    },
    {
        id:      'origin',
        lat:     14.651726103123695,
        lng:     121.05472805488795,
        anchor:  '2005-10-15T00:00:00',
        marquee: 'Happy Monthsary, Bhaze!',
    },
];

// Backward-compatible aliases for the primary site (test-mode default, etc.).
export const TARGET_LAT = SITES[0].lat;
export const TARGET_LNG = SITES[0].lng;

// Returns the nearest site to a position plus its distance (m) and bearing (deg).
export function nearestSite(lat, lng) {
    let best = SITES[0], bestD = Infinity;
    for (const s of SITES) {
        const d = getDistanceKm(lat, lng, s.lat, s.lng) * 1000;
        if (d < bestD) { bestD = d; best = s; }
    }
    const bearingDeg = ((getBearing(lat, lng, best.lat, best.lng) * 180 / Math.PI) + 360) % 360;
    return { site: best, distM: bestD, bearingDeg };
}

// The site the experience locked onto at trigger time. Defaults to the primary
// site so accessors are safe before startExperience() runs.
let _activeSite = SITES[0];
export function getActiveSite() { return _activeSite; }

let scene, camera, renderer, controls;

// True once the first onNativeOrientationUpdate event arrives.
// When true, animate() skips controls.update() so the native quaternion written directly
// to camera.quaternion is not overwritten each frame. DeviceOrientationControls is still
// constructed and its internal listeners remain attached for fallback.
let _nativeOrientationActive = false;

let _experienceStarted = false;
let _watchId = null;

let arGroup = null;
let marqueeCylinder = null;
let showFireworks = false;
let beaconBaseOpacity  = 0;
let marqueeBaseOpacity = 0;
let statsBaseOpacity   = 0;
let userDistanceToSite = Infinity;

const fireworks = [];

let _actBeaconFired = false, _actFwFired = false,
    _actMqFired    = false,  _actStFired = false;

// --- Public accessors used by sensors.js ---

export function getCamera()                { return camera; }
export function isExperienceStarted()      { return _experienceStarted; }
export function setNativeOrientationActive() { _nativeOrientationActive = true; }

export function setUserDistance(metres) {
    userDistanceToSite = metres;
}

const _fwdScratch = new THREE.Vector3();

export function getAlignmentData() {
    if (!camera) return null;
    const fwd = _fwdScratch.set(0, 0, -1).applyQuaternion(camera.quaternion);
    const camDeg = ((Math.atan2(fwd.x, -fwd.z) * 180 / Math.PI) + 360) % 360;
    if (!arGroup) return { camDeg: camDeg.toFixed(1), arDeg: null, deltaDeg: null };
    const arDeg = ((Math.atan2(arGroup.position.x, -arGroup.position.z) * 180 / Math.PI) + 360) % 360;
    let delta = camDeg - arDeg;
    if (delta >  180) delta -= 360;
    if (delta < -180) delta += 360;
    return { camDeg: camDeg.toFixed(1), arDeg: arDeg.toFixed(1), deltaDeg: delta.toFixed(1) };
}

export function clearWatchPosition() {
    if (_watchId !== null) {
        navigator.geolocation.clearWatch(_watchId);
        _watchId = null;
    }
}

// ---

export function initScene() {
    const canvas = document.getElementById('ar-canvas');
    scene    = new THREE.Scene();
    camera   = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 1000);
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

    window.addEventListener('resize', () => {
        camera.aspect = window.innerWidth / window.innerHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(window.innerWidth, window.innerHeight);
    });

    _animate();
}

function _createARGroup(userLat, userLng, isTestMode) {
    const group = new THREE.Group();

    if (isTestMode) {
        group.position.set(0, 15, -40);
    } else {
        const dist    = getDistanceKm(userLat, userLng, _activeSite.lat, _activeSite.lng) * 1000;
        const bearing = getBearing(userLat, userLng, _activeSite.lat, _activeSite.lng);
        group.position.set(dist * Math.sin(bearing), 15, -dist * Math.cos(bearing));
    }

    // Cylindrical marquee — radius sized to clear the chapel dome (~55m wide)
    const mCanvas = document.createElement('canvas');
    const mCtx    = mCanvas.getContext('2d');
    mCanvas.width = 4096; mCanvas.height = 256;
    mCtx.font          = '72px sans-serif';
    mCtx.textAlign     = 'center';
    mCtx.textBaseline  = 'middle';
    mCtx.shadowColor   = 'rgba(255, 220, 160, 0.4)';
    mCtx.shadowBlur    = 8;
    mCtx.fillStyle     = 'white';
    for (let i = 0; i < 3; i++)
        mCtx.fillText(_activeSite.marquee, Math.round((i + 0.5) * 4096 / 3), 128);

    const mTex = new THREE.CanvasTexture(mCanvas);
    mTex.wrapS = THREE.RepeatWrapping;
    marqueeCylinder = new THREE.Mesh(
        new THREE.CylinderGeometry(30, 30, 4, 64, 1, true),
        new THREE.MeshBasicMaterial({
            map: mTex, transparent: true, opacity: 0,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
        })
    );
    group.add(marqueeCylinder);

    // ACT 0: Orbital Beacon — 2 km pillar visible from PHIVOLCS rooftop (~1.5 km away)
    const beaconMesh = new THREE.Mesh(
        new THREE.CylinderGeometry(8, 8, 2000, 32, 1, true),
        new THREE.MeshBasicMaterial({
            color: new THREE.Color(0.85, 0.95, 1.0), // icy cyan-white
            transparent: true, opacity: 0,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
        })
    );
    beaconMesh.position.y = 1000; // base at church ground level, apex at 2000 m
    beaconMesh.userData.isBeacon = true;
    group.add(beaconMesh);

    // Time stats panels — anchored to the active site's date
    const start  = new Date(_activeSite.anchor);
    const now    = new Date();
    const diffMs = now - start;
    const days   = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    const hours  = Math.floor(diffMs / (1000 * 60 * 60));

    // Years counts completed anniversaries — a partial year does not count as a full one.
    let years = now.getFullYear() - start.getFullYear();
    if (now.getMonth() < start.getMonth() ||
        (now.getMonth() === start.getMonth() && now.getDate() < start.getDate())) years--;

    // monthsRaw derives from raw year/month fields so it measures elapsed months on its own
    // calendar boundary (day-of-month), independent of the anniversary-day boundary used
    // to define a completed year.
    let monthsRaw = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth());
    if (now.getDate() < start.getDate()) monthsRaw--;

    [
        { text: `${years} Years`,      rotY: 0 },
        { text: `${monthsRaw} Months`, rotY: Math.PI / 2 },
        { text: `${hours} Hours`,       rotY: Math.PI },
        { text: `${days} Days`,        rotY: -Math.PI / 2 },
    ].forEach(stat => {
        const sCanvas = document.createElement('canvas');
        sCanvas.width = 512; sCanvas.height = 256;
        const sCtx = sCanvas.getContext('2d');
        sCtx.font = '56px sans-serif';
        sCtx.textAlign = 'center';
        sCtx.textBaseline = 'middle';
        sCtx.fillStyle = 'rgba(255, 255, 255, 0.5)';
        sCtx.fillText(stat.text, 256, 128);
        const sMesh = new THREE.Mesh(
            new THREE.PlaneGeometry(20, 10),
            new THREE.MeshBasicMaterial({
                map: new THREE.CanvasTexture(sCanvas),
                transparent: true, opacity: 0,
                blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
            })
        );
        sMesh.position.set(0, -10, 0);
        sMesh.rotation.y = stat.rotY;
        sMesh.translateZ(30); // push outward to match the 30m cylinder radius
        sMesh.userData.isStat = true;
        group.add(sMesh);
    });

    // Ground glow: faint additive ring at Y=0 under the church
    const groundRing = new THREE.Mesh(
        new THREE.RingGeometry(12, 24, 64),
        new THREE.MeshBasicMaterial({
            color: new THREE.Color(0.85, 0.55, 1.0),
            transparent: true, opacity: 0.025,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
        })
    );
    groundRing.rotation.x = -Math.PI / 2;
    groundRing.position.y = -15;
    groundRing.userData.isGround = true;
    group.add(groundRing);

    return group;
}

export function startExperience(site = SITES[0], uLat = site.lat, uLng = site.lng, isTestMode = false) {
    if (_experienceStarted) return;
    _experienceStarted = true;
    _activeSite = site;   // lock the experience to this site for its lifetime

    // _webGeoIntervalId is owned by sensors.js; both trigger paths (web-geo success callback
    // and onNativeLocationUpdate) clear it via inline clearInterval() before calling here.

    try { document.documentElement.requestFullscreen().catch(() => {}); } catch (e) {}
    document.getElementById('status-text').style.opacity = '0';

    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        .then(stream => {
            const vid = document.getElementById('camera-bg');
            vid.srcObject = stream;
            vid.play().catch(() => {});
        })
        .catch(() => {
            document.getElementById('status-message').innerText =
                'Camera unavailable. Tap the upper-right corner three times to skip.';
            document.getElementById('status-text').style.opacity = '1';
        });

    ensureAudioContext();
    detectEarphones().then(found => { if (found) setEarphonesMode(true); });
    navigator.mediaDevices.addEventListener('devicechange', async () => {
        setEarphonesMode(await detectEarphones());
    });

    controls = new THREE.DeviceOrientationControls(camera);

    // Compass calibration feedback
    const compassEl = document.getElementById('compass-indicator');
    compassEl.textContent = '◎ calibrating heading…';
    compassEl.style.opacity = '1';
    let bannerHeadingReceived = false;
    let bannerDismissed = false;
    // onBannerOrientation tracks whether any orientation event has fired during the 8s window.
    // Some Android stacks never emit deviceorientationabsolute even when heading is functional,
    // so the banner uses this flag to distinguish "heading working but not absolute" from
    // "no sensor at all". The named reference is removed via removeEventListener; it does NOT
    // touch DeviceOrientationControls' own internal listeners.
    const onBannerOrientation = () => { bannerHeadingReceived = true; };
    window.addEventListener('deviceorientation', onBannerOrientation);
    const onHeadingLocked = (e) => {
        if (e.absolute || e.type === 'deviceorientationabsolute') {
            compassEl.textContent = '◉ heading locked';
            setTimeout(() => { compassEl.style.opacity = '0'; }, 2500);
            window.removeEventListener('deviceorientationabsolute', onHeadingLocked);
            window.removeEventListener('deviceorientation', onHeadingLocked);
            window.removeEventListener('deviceorientation', onBannerOrientation);
            bannerDismissed = true;
        }
    };
    window.addEventListener('deviceorientationabsolute', onHeadingLocked);
    window.addEventListener('deviceorientation', onHeadingLocked);
    // 8s timeout: typical mid-range Android magnetometer lock is 3-6s; doubling the upper
    // bound covers cold-start near reinforced concrete without exceeding the ~10s
    // wearer-tolerance threshold for unexplained UI elements.
    setTimeout(() => {
        if (bannerDismissed) return;
        window.removeEventListener('deviceorientation', onBannerOrientation);
        window.removeEventListener('deviceorientationabsolute', onHeadingLocked);
        window.removeEventListener('deviceorientation', onHeadingLocked);
        if (bannerHeadingReceived) {
            compassEl.textContent = '◉ heading active';
            setTimeout(() => { compassEl.style.opacity = '0'; }, 2500);
        } else {
            compassEl.style.opacity = '0';
        }
        bannerDismissed = true;
    }, 8000);

    arGroup = _createARGroup(uLat, uLng, isTestMode);
    scene.add(arGroup);

    // 60s after trigger: fade in a personal inner cylinder
    setTimeout(() => {
        if (!arGroup) return;
        const msgStart = new Date(_activeSite.anchor);
        const msgNow   = new Date();
        let yr = msgNow.getFullYear() - msgStart.getFullYear();
        if (msgNow.getMonth() < msgStart.getMonth() ||
            (msgNow.getMonth() === msgStart.getMonth() && msgNow.getDate() < msgStart.getDate())) yr--;

        const msgCanvas = document.createElement('canvas');
        msgCanvas.width = 2048; msgCanvas.height = 192;
        const msgCtx = msgCanvas.getContext('2d');
        msgCtx.font = '56px sans-serif';
        msgCtx.textAlign = 'center';
        msgCtx.textBaseline = 'middle';
        msgCtx.fillStyle = 'rgba(255, 210, 235, 0.9)';
        const line = `${yr} years. Still you. Always you.`;
        for (let i = 0; i < 2; i++) msgCtx.fillText(line, 512 + i * 1024, 96);

        const msgMat = new THREE.MeshBasicMaterial({
            map: new THREE.CanvasTexture(msgCanvas),
            transparent: true, opacity: 0,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide,
        });
        const msgMesh = new THREE.Mesh(new THREE.CylinderGeometry(12, 12, 3, 64, 1, true), msgMat);
        msgMesh.userData.isMessageCylinder = true;
        arGroup.add(msgMesh);

        const msgFade = setInterval(() => {
            msgMat.opacity = Math.min(msgMat.opacity + 0.008, 0.65);
            if (msgMat.opacity >= 0.65) clearInterval(msgFade);
        }, 50);
    }, 60000);

    if (isTestMode) {
        // Test mode: simulate standing at the church — reveal everything immediately
        userDistanceToSite = 0;
    } else if (!window.__nativeLocationActive) {
        // Seed from the triggering fix so the opacity lerp starts with a real distance
        // before the first watchPosition callback arrives.
        userDistanceToSite = getDistanceKm(uLat, uLng, _activeSite.lat, _activeSite.lng) * 1000;
        _watchId = navigator.geolocation.watchPosition(pos => {
            userDistanceToSite = getDistanceKm(
                pos.coords.latitude, pos.coords.longitude, _activeSite.lat, _activeSite.lng
            ) * 1000;
        }, null, { enableHighAccuracy: true });
    }

    setTimeout(() => {
        document.getElementById('status-text').style.display = 'none';
        document.getElementById('camera-bg').style.opacity = '1';
        // showFireworks is distance-driven in _animate() — no longer set here
    }, 1000);
}

// Profiling probe (Phase 1c decision gate). Accumulates only on frames where
// fireworks particles are live, so idle approach frames don't dilute the
// fireworks-active cost. getPerfStats() reports averages + peaks; the question
// it answers: is the JS particle update a meaningful slice of the frame budget,
// or is the cost in renderer.render() (GPU) where an NDK/WASM port wouldn't help?
const _perf = {
    busyFrames: 0,
    partSum: 0,  partMax: 0,   // particle spawn + update, ms
    rendSum: 0,  rendMax: 0,   // renderer.render(), ms
    frameSum: 0, frameMax: 0,  // whole _animate body, ms
    partCountMax: 0,           // peak live line-segment vertices being updated
};

export function getPerfStats() {
    const n = _perf.busyFrames || 1;
    return {
        busyFrames:   _perf.busyFrames,
        partMsAvg:    +(_perf.partSum  / n).toFixed(3),
        partMsMax:    +_perf.partMax.toFixed(3),
        renderMsAvg:  +(_perf.rendSum  / n).toFixed(3),
        renderMsMax:  +_perf.rendMax.toFixed(3),
        frameMsAvg:   +(_perf.frameSum / n).toFixed(3),
        frameMsMax:   +_perf.frameMax.toFixed(3),
        partCountMax: _perf.partCountMax,
    };
}

function _animate() {
    requestAnimationFrame(_animate);
    const _t0 = performance.now();

    // Skip controls.update() when native path is active to avoid overwriting
    // camera.quaternion set by the onNativeOrientationUpdate handler.
    if (controls && !_nativeOrientationActive) controls.update();

    syncAudioListener(camera);

    tickIllumination();
    const textIllumination = getTextIllumination();

    if (arGroup) {
        // ACT 0: Beacon fades in 1800→1500m, holds solid 1500→800m, fades out 800→650m
        let beaconTarget = 0;
        if (userDistanceToSite <= 1800 && userDistanceToSite > 650) {
            if      (userDistanceToSite > 1500) beaconTarget = Math.max(0, 1 - ((userDistanceToSite - 1500) / 300));
            else if (userDistanceToSite < 800)  beaconTarget = Math.max(0, (userDistanceToSite - 650) / 150);
            else                                  beaconTarget = 1.0;
        }
        // ACT 1: Fireworks ≤800m; suppressed above to save GPU during the approach
        showFireworks = userDistanceToSite <= 800;
        // ACT 2: Marquee materialises 400→150m
        const marqueeTarget = userDistanceToSite <= 400
            ? Math.max(0, 1 - ((userDistanceToSite - 150) / 250)) : 0;
        // ACT 3: Stat panels emerge 100→40m
        const statsTarget = userDistanceToSite <= 100
            ? Math.max(0, 1 - ((userDistanceToSite - 40) / 60)) : 0;

        beaconBaseOpacity  += (beaconTarget  * 0.7 - beaconBaseOpacity)  * 0.02;
        marqueeBaseOpacity += (marqueeTarget * 0.8 - marqueeBaseOpacity) * 0.02;
        statsBaseOpacity   += (statsTarget   * 0.8 - statsBaseOpacity)   * 0.02;

        if (!_actBeaconFired && beaconBaseOpacity > 0.01) {
            _actBeaconFired = true;
            recordActEvent('beacon_on', userDistanceToSite);
        }
        if (!_actFwFired && showFireworks) {
            _actFwFired = true;
            recordActEvent('fireworks_on', userDistanceToSite);
        }
        if (!_actMqFired && marqueeBaseOpacity > 0.01) {
            _actMqFired = true;
            recordActEvent('marquee_on', userDistanceToSite);
        }
        if (!_actStFired && statsBaseOpacity > 0.01) {
            _actStFired = true;
            recordActEvent('stats_on', userDistanceToSite);
        }

        arGroup.children.forEach(child => {
            if      (child === marqueeCylinder)          child.material.opacity = marqueeBaseOpacity + (textIllumination * 0.6);
            else if (child.userData.isStat)              child.material.opacity = (statsBaseOpacity * 0.5) + (textIllumination * 0.4);
            else if (child.userData.isBeacon)            child.material.opacity = beaconBaseOpacity;
            else if (child.userData.isGround)            child.material.opacity = 0.025 + 0.015 * Math.sin(performance.now() * 0.0006);
            else if (child.userData.isMessageCylinder)   child.rotation.y += 0.003;
        });
        marqueeCylinder.rotation.y -= 0.002;
    }

    const _tA = performance.now();

    if (showFireworks && fireworks.length < 15) {
        if (Math.random() < 0.1)  fireworks.push(new Firework(scene, arGroup));
        if (Math.random() < 0.02 && fireworks.length < 13) {
            fireworks.push(new Firework(scene, arGroup));
            fireworks.push(new Firework(scene, arGroup));
        }
    }

    let _partCount = 0;
    for (let i = fireworks.length - 1; i >= 0; i--) {
        const fw = fireworks[i];
        _partCount += fw.isRocket ? 2 : (fw.particleCount || 0);
        fw.update();
        if (fw.isDead) { fw.destroy(); fireworks.splice(i, 1); }
    }

    const _tB = performance.now();
    renderer.render(scene, camera);
    const _tC = performance.now();

    if (_partCount > 0) {
        _perf.busyFrames++;
        const partMs = _tB - _tA, rendMs = _tC - _tB, frameMs = _tC - _t0;
        _perf.partSum  += partMs;  if (partMs  > _perf.partMax)  _perf.partMax  = partMs;
        _perf.rendSum  += rendMs;  if (rendMs  > _perf.rendMax)  _perf.rendMax  = rendMs;
        _perf.frameSum += frameMs; if (frameMs > _perf.frameMax) _perf.frameMax = frameMs;
        if (_partCount > _perf.partCountMax) _perf.partCountMax = _partCount;
    }
}
