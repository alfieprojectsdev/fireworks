let audioCtx = null;
let earphonesMode = false;
let reverbNode = null;
let reverbGain = null;
let _noisePool = null;
let _masterComp = null;

const _audioFwd = new THREE.Vector3();
const _audioUp = new THREE.Vector3();

// Soft-clip waveshaper: generates harmonics from the thump oscillator so low
// frequencies are audible on small phone speakers that can't reproduce true bass.
const _shaperCurve = (() => {
    const c = new Float32Array(256);
    for (let i = 0; i < 256; i++) {
        const x = (i * 2) / 256 - 1;
        c[i] = (Math.PI + 200) * x / (Math.PI + 200 * Math.abs(x));
    }
    return c;
})();

function _buildGraph(ctx) {
    const makeNoise = (dur) => {
        const buf = ctx.createBuffer(1, Math.ceil(ctx.sampleRate * dur), ctx.sampleRate);
        const d = buf.getChannelData(0);
        for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
        return buf;
    };
    _noisePool = {
        body:    makeNoise(0.20),  // longer than max bodyDur (0.14s); gain envelope cuts it
        sizzle:  makeNoise(1.50),  // longer than max sizzleDur (1.1s)
        crackle: makeNoise(0.06)
    };

    reverbNode = ctx.createConvolver();
    const len = Math.ceil(ctx.sampleRate * 2.0);
    const buf = ctx.createBuffer(2, len, ctx.sampleRate);
    for (let ch = 0; ch < 2; ch++) {
        const d = buf.getChannelData(ch);
        for (let i = 0; i < len; i++)
            d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3.0);
    }
    reverbNode.buffer = buf;
    reverbGain = ctx.createGain();
    reverbGain.gain.value = 0;
    reverbNode.connect(reverbGain);
    reverbGain.connect(ctx.destination);

    _masterComp = ctx.createDynamicsCompressor();
    _masterComp.threshold.value = -6;
    _masterComp.knee.value = 3;
    _masterComp.ratio.value = 4;
    _masterComp.attack.value = 0.001;
    _masterComp.release.value = 0.1;
    _masterComp.connect(ctx.destination);
}

// Pre-create and unlock AudioContext on first touch so GPS-triggered startExperience
// (which runs outside a user gesture) finds the context already running.
export function setupAudioUnlock() {
    const handler = () => {
        if (audioCtx) return;
        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        audioCtx.resume().then(() => _buildGraph(audioCtx));
    };
    document.addEventListener('touchstart', handler, { once: true });
    document.addEventListener('click', handler, { once: true });
}

// Called by startExperience — creates context if the touch unlock hasn't fired yet,
// or builds the graph if the context was unlocked but the graph wasn't set up.
export function ensureAudioContext() {
    if (!audioCtx) {
        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        _buildGraph(audioCtx);
    } else if (!_noisePool) {
        _buildGraph(audioCtx);
    }
    audioCtx.resume().catch(() => {});
}

export async function detectEarphones() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return false;
    try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        return devices.some(d =>
            /bluetooth|headset|headphone|earphone|airpod|a2dp|wireless/i.test(d.label)
        );
    } catch (e) { return false; }
}

export function setEarphonesMode(active) {
    earphonesMode = active;
    if (reverbGain && audioCtx)
        reverbGain.gain.setTargetAtTime(active ? 0.28 : 0, audioCtx.currentTime, 0.5);
    const btn = document.getElementById('audio-mode-btn');
    btn.textContent = active ? '🎧' : '♪';
    // 0.6 active vs 0.55 inactive: perceptible state change; 0.55 is outdoor legibility floor (ref: DL-006)
    btn.style.opacity = active ? '0.6' : '0.55';
}

export function toggleEarphonesMode() {
    setEarphonesMode(!earphonesMode);
}

// Called every animation frame — keeps spatial panning locked to phone orientation.
export function syncAudioListener(camera) {
    if (!audioCtx) return;
    camera.getWorldDirection(_audioFwd);
    _audioUp.set(0, 1, 0).applyQuaternion(camera.quaternion);
    const L = audioCtx.listener;
    if (L.positionX !== undefined) {
        L.positionX.value = 0; L.positionY.value = 0; L.positionZ.value = 0;
        L.forwardX.value = _audioFwd.x; L.forwardY.value = _audioFwd.y; L.forwardZ.value = _audioFwd.z;
        L.upX.value = _audioUp.x; L.upY.value = _audioUp.y; L.upZ.value = _audioUp.z;
    }
}

export function playExplosion(pos, shellType) {
    if (!audioCtx || !_noisePool || !_masterComp) return;
    if (audioCtx.state === 'suspended') { audioCtx.resume(); return; }
    const ctx = audioCtx;
    const now = ctx.currentTime;

    const panner = ctx.createPanner();
    panner.panningModel = 'HRTF';
    panner.distanceModel = 'inverse';
    panner.refDistance = 30;
    panner.rolloffFactor = 1;
    panner.setPosition(pos.x, pos.y, pos.z);
    panner.connect(_masterComp);
    if (earphonesMode && reverbNode) panner.connect(reverbNode);

    // Earphones can reproduce true bass; speakers need higher freq with harmonics
    const thumpFreq = earphonesMode
        ? (shellType === 1 ? 80  : shellType === 3 ? 120 : shellType === 4 ? 95  : shellType === 5 ? 110 : 100)
        : (shellType === 1 ? 120 : shellType === 3 ? 180 : shellType === 4 ? 135 : shellType === 5 ? 160 : 150);
    const thumpOsc = ctx.createOscillator();
    thumpOsc.type = 'triangle';
    thumpOsc.frequency.setValueAtTime(thumpFreq, now);
    thumpOsc.frequency.exponentialRampToValueAtTime(thumpFreq * 0.4, now + 0.3);
    const shaper = ctx.createWaveShaper();
    shaper.curve = _shaperCurve;
    const thumpGain = ctx.createGain();
    thumpGain.gain.setValueAtTime(shellType === 3 ? 0.4 : shellType >= 4 ? 0.55 : 0.75, now);
    thumpGain.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
    thumpOsc.connect(shaper);
    shaper.connect(thumpGain);
    thumpGain.connect(panner);
    thumpOsc.start(now);
    thumpOsc.stop(now + 0.35);

    // Body: bandpass noise centred where phone speakers have actual output (300–600 Hz)
    const bodyDur = shellType === 3 ? 0.08 : shellType >= 4 ? 0.10 : 0.14;
    const bodySrc = ctx.createBufferSource();
    bodySrc.buffer = _noisePool.body;
    const bodyFilter = ctx.createBiquadFilter();
    bodyFilter.type = 'bandpass';
    bodyFilter.frequency.value = shellType === 1 ? 300 : shellType === 3 ? 600
                               : shellType === 4 ? 380 : shellType === 5 ? 560 : 420;
    bodyFilter.Q.value = 0.7;
    const bodyGain = ctx.createGain();
    bodyGain.gain.setValueAtTime(1.0, now);
    bodyGain.gain.exponentialRampToValueAtTime(0.001, now + bodyDur);
    bodySrc.connect(bodyFilter);
    bodyFilter.connect(bodyGain);
    bodyGain.connect(panner);
    bodySrc.start(now);

    // Sizzle tail: slow-decaying high-frequency noise (burning sparks)
    const sizzleDur = 0.6 + Math.random() * 0.5;
    const sizzleSrc = ctx.createBufferSource();
    sizzleSrc.buffer = _noisePool.sizzle;
    const sizzleFilter = ctx.createBiquadFilter();
    sizzleFilter.type = 'highpass';
    sizzleFilter.frequency.value = 3000;
    const sizzleGain = ctx.createGain();
    sizzleGain.gain.setValueAtTime(0.12, now);
    sizzleGain.gain.exponentialRampToValueAtTime(0.001, now + sizzleDur);
    sizzleSrc.connect(sizzleFilter);
    sizzleFilter.connect(sizzleGain);
    sizzleGain.connect(panner);
    sizzleSrc.start(now);

    // Crackle: Chrysanthemum gets extra pops spread over time
    if (shellType === 2) {
        for (let k = 0; k < 6; k++) {
            const delay = 0.06 + Math.random() * 0.5;
            const crackleSrc = ctx.createBufferSource();
            crackleSrc.buffer = _noisePool.crackle;
            const crackleGain = ctx.createGain();
            crackleGain.gain.setValueAtTime(0.25 + Math.random() * 0.3, now + delay);
            crackleGain.gain.exponentialRampToValueAtTime(0.001, now + delay + 0.04);
            crackleSrc.connect(crackleGain);
            crackleGain.connect(panner);
            crackleSrc.start(now + delay);
        }
    }

    const cleanupMs = (sizzleDur + 0.25) * 1000;
    setTimeout(() => { try { panner.disconnect(); } catch (_) {} }, cleanupMs);
}
