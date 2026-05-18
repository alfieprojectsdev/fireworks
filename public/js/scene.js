import { getDistanceKm, getBearing } from './geo.js';
import { setupAudioUnlock, ensureAudioContext, detectEarphones, setEarphonesMode, syncAudioListener } from './audio.js';
import { Firework, getTextIllumination, tickIllumination } from './fireworks.js';

export const TARGET_LAT = 14.658888478751235;
export const TARGET_LNG = 121.071173166497;

let scene, camera, renderer, controls;
let showFireworks = false;
let arGroup = null;
let marqueeCylinder = null;
let marqueeBaseOpacity = 0;
let statsBaseOpacity = 0;
let userDistanceToChurch = Infinity;
const fireworks = [];

export function initScene() {
    const canvas = document.getElementById('ar-canvas');
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 1000);
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

    setupAudioUnlock();

    window.addEventListener('resize', () => {
        camera.aspect = window.innerWidth / window.innerHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(window.innerWidth, window.innerHeight);
    });

    _animate();
}

export function checkProximity(lat, lng) {
    const metres = getDistanceKm(lat, lng, TARGET_LAT, TARGET_LNG) * 1000;
    return { metres, isArrived: metres <= 650 };
}

function _createARGroup(userLat, userLng, isTestMode) {
    const group = new THREE.Group();

    if (isTestMode) {
        group.position.set(0, 15, -40);
    } else {
        const dist = getDistanceKm(userLat, userLng, TARGET_LAT, TARGET_LNG) * 1000;
        const bearing = getBearing(userLat, userLng, TARGET_LAT, TARGET_LNG);
        group.position.set(dist * Math.sin(bearing), 15, -dist * Math.cos(bearing));
    }

    // Cylindrical marquee
    const mCanvas = document.createElement('canvas');
    const mCtx = mCanvas.getContext('2d');
    mCanvas.width = 4096; mCanvas.height = 256;
    mCtx.font = "72px sans-serif";
    mCtx.textAlign = "center";
    mCtx.textBaseline = "middle";
    mCtx.shadowColor = "rgba(255, 220, 160, 0.4)";
    mCtx.shadowBlur = 8;
    mCtx.fillStyle = "white";
    for (let i = 0; i < 3; i++) {
        mCtx.fillText("Happy Anniversary, Bhaze!", Math.round((i + 0.5) * 4096 / 3), 128);
    }
    const mTex = new THREE.CanvasTexture(mCanvas);
    mTex.wrapS = THREE.RepeatWrapping;
    const mMat = new THREE.MeshBasicMaterial({
        map: mTex, transparent: true, opacity: 0,
        blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide
    });
    // 30m radius = 60m diameter, clearing the 55m chapel dome
    const mGeo = new THREE.CylinderGeometry(30, 30, 4, 64, 1, true);
    marqueeCylinder = new THREE.Mesh(mGeo, mMat);
    group.add(marqueeCylinder);

    // Time stats panels
    const start = new Date('2008-05-15T00:00:00');
    const now = new Date();
    const diffMs = now - start;
    const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    const weeks = Math.floor(days / 7);
    let years = now.getFullYear() - start.getFullYear();
    if (now.getMonth() < start.getMonth() || (now.getMonth() === start.getMonth() && now.getDate() < start.getDate())) years--;
    // monthsRaw counts elapsed months on its own calendar boundary, independent of the
    // anniversary-day boundary used for completed years. (ref: DL-001)
    let monthsRaw = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth());
    if (now.getDate() < start.getDate()) monthsRaw--;

    const stats = [
        { text: `${years} Years`,      rotY: 0 },
        { text: `${monthsRaw} Months`, rotY: Math.PI / 2 },
        { text: `${weeks} Weeks`,      rotY: Math.PI },
        { text: `${days} Days`,        rotY: -Math.PI / 2 }
    ];

    stats.forEach(stat => {
        const sCanvas = document.createElement('canvas');
        sCanvas.width = 512; sCanvas.height = 256;
        const sCtx = sCanvas.getContext('2d');
        sCtx.font = "56px sans-serif";
        sCtx.textAlign = "center";
        sCtx.textBaseline = "middle";
        sCtx.fillStyle = "rgba(255, 255, 255, 0.5)";
        sCtx.fillText(stat.text, 256, 128);
        const sMat = new THREE.MeshBasicMaterial({
            map: new THREE.CanvasTexture(sCanvas),
            transparent: true, opacity: 0,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide
        });
        const sMesh = new THREE.Mesh(new THREE.PlaneGeometry(20, 10), sMat);
        sMesh.position.set(0, -10, 0);
        sMesh.rotation.y = stat.rotY;
        sMesh.translateZ(30); // push outwards to match the 30m cylinder radius
        sMesh.userData.isStat = true;
        group.add(sMesh);
    });

    // Ground glow: faint additive ring at Y=0 under the church
    const groundRing = new THREE.Mesh(
        new THREE.RingGeometry(12, 24, 64),
        new THREE.MeshBasicMaterial({
            color: new THREE.Color(0.85, 0.55, 1.0),
            transparent: true, opacity: 0.025,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide
        })
    );
    groundRing.rotation.x = -Math.PI / 2;
    groundRing.position.y = -15;
    groundRing.userData.isGround = true;
    group.add(groundRing);

    return group;
}

export function startExperience(uLat = TARGET_LAT, uLng = TARGET_LNG, isTestMode = false) {
    try { document.documentElement.requestFullscreen().catch(() => {}); } catch (e) {}
    document.getElementById('status-text').style.opacity = '0';

    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        .then(stream => {
            const vid = document.getElementById('camera-bg');
            vid.srcObject = stream;
            vid.play().catch(() => {});
        })
        .catch(() => {
            document.getElementById('status-message').innerText = 'Camera unavailable. Tap the upper-right corner three times to skip.';
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
    let bannerDismissed = false;
    let bannerHeadingReceived = false;
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
        const msgStart = new Date('2008-05-15T00:00:00');
        const msgNow = new Date();
        let yr = msgNow.getFullYear() - msgStart.getFullYear();
        if (msgNow.getMonth() < msgStart.getMonth() ||
            (msgNow.getMonth() === msgStart.getMonth() && msgNow.getDate() < msgStart.getDate())) yr--;

        const msgCanvas = document.createElement('canvas');
        msgCanvas.width = 2048; msgCanvas.height = 192;
        const msgCtx = msgCanvas.getContext('2d');
        msgCtx.font = "56px sans-serif";
        msgCtx.textAlign = "center";
        msgCtx.textBaseline = "middle";
        msgCtx.fillStyle = "rgba(255, 210, 235, 0.9)";
        const line = `${yr} years. Still you. Always you.`;
        for (let i = 0; i < 2; i++) msgCtx.fillText(line, 512 + i * 1024, 96);

        const msgMat = new THREE.MeshBasicMaterial({
            map: new THREE.CanvasTexture(msgCanvas),
            transparent: true, opacity: 0,
            blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide
        });
        const msgMesh = new THREE.Mesh(new THREE.CylinderGeometry(12, 12, 3, 64, 1, true), msgMat);
        msgMesh.userData.isMessageCylinder = true;
        arGroup.add(msgMesh);

        const msgFade = setInterval(() => {
            msgMat.opacity = Math.min(msgMat.opacity + 0.008, 0.65);
            if (msgMat.opacity >= 0.65) clearInterval(msgFade);
        }, 50);
    }, 60000);

    // ACT 1 begins immediately — fireworks fire as the beacon the moment experience starts.
    showFireworks = true;

    if (isTestMode) {
        userDistanceToChurch = 0;
    } else {
        // Seed from the triggering fix so the opacity lerp has a real distance before
        // the first watchPosition callback arrives.
        userDistanceToChurch = getDistanceKm(uLat, uLng, TARGET_LAT, TARGET_LNG) * 1000;
        navigator.geolocation.watchPosition(pos => {
            userDistanceToChurch = getDistanceKm(
                pos.coords.latitude, pos.coords.longitude, TARGET_LAT, TARGET_LNG
            ) * 1000;
        }, null, { enableHighAccuracy: true });
    }

    setTimeout(() => {
        document.getElementById('status-text').style.display = 'none';
        document.getElementById('camera-bg').style.opacity = '1';
    }, 1000);
}

function _animate() {
    requestAnimationFrame(_animate);
    if (controls) controls.update();

    syncAudioListener(camera);

    if (arGroup) {
        tickIllumination();
        const textIllumination = getTextIllumination();

        // ACT 2: Marquee materialises from 400m, fully opaque by 150m
        const marqueeTarget = userDistanceToChurch <= 400
            ? Math.max(0, 1 - ((userDistanceToChurch - 150) / 250)) : 0;

        // ACT 3: Stat panels emerge from 100m, fully opaque by 40m
        const statsTarget = userDistanceToChurch <= 100
            ? Math.max(0, 1 - ((userDistanceToChurch - 40) / 60)) : 0;

        marqueeBaseOpacity += (marqueeTarget * 0.8 - marqueeBaseOpacity) * 0.02;
        statsBaseOpacity   += (statsTarget   * 0.8 - statsBaseOpacity)   * 0.02;

        arGroup.children.forEach(child => {
            if (child === marqueeCylinder) {
                child.material.opacity = marqueeBaseOpacity + (textIllumination * 0.6);
            } else if (child.userData.isStat) {
                child.material.opacity = (statsBaseOpacity * 0.5) + (textIllumination * 0.4);
            } else if (child.userData.isGround) {
                child.material.opacity = 0.025 + 0.015 * Math.sin(performance.now() * 0.0006);
            } else if (child.userData.isMessageCylinder) {
                child.rotation.y += 0.003;
            }
        });
        marqueeCylinder.rotation.y -= 0.002;
    }

    if (showFireworks && fireworks.length < 15) {
        if (Math.random() < 0.1) fireworks.push(new Firework(scene, arGroup));
        if (Math.random() < 0.02 && fireworks.length < 13) {
            fireworks.push(new Firework(scene, arGroup));
            fireworks.push(new Firework(scene, arGroup));
        }
    }

    for (let i = fireworks.length - 1; i >= 0; i--) {
        fireworks[i].update();
        if (fireworks[i].isDead) {
            fireworks[i].destroy();
            fireworks.splice(i, 1);
        }
    }

    renderer.render(scene, camera);
}
