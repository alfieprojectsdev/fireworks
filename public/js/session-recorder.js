const MAX_GPS = 600;   // 10 min at 1 Hz native
const MAX_ORI = 120;   // one sample every 5 s

const _gpsEvents  = [];
const _oriSamples = [];
const _actEvents  = [];
let   _lastOriAt  = 0;
let   _targetLat  = null;
let   _targetLng  = null;

const _ACT_EXPECTED_M = {
    beacon_on:    1800,
    fireworks_on:  800,
    marquee_on:    400,
    stats_on:      100,
};

export function initRecorder(targetLat, targetLng) {
    _targetLat = targetLat;
    _targetLng = targetLng;
}

export function recordGPS(lat, lng, acc, src, distM, bearingDeg, alignment = null) {
    if (_gpsEvents.length >= MAX_GPS) _gpsEvents.shift();
    const entry = {
        ts: Date.now(), lat, lng, acc, src,
        distM: Math.round(distM),
        bearingDeg: +bearingDeg.toFixed(1),
    };
    if (alignment) {
        entry.align = {
            cam:   alignment.camDeg   != null ? +alignment.camDeg   : null,
            ar:    alignment.arDeg    != null ? +alignment.arDeg    : null,
            delta: alignment.deltaDeg != null ? +alignment.deltaDeg : null,
        };
    }
    _gpsEvents.push(entry);
}

export function recordOrientation(x, y, z, w) {
    const now = Date.now();
    if (now - _lastOriAt < 5000) return;
    _lastOriAt = now;
    if (_oriSamples.length >= MAX_ORI) _oriSamples.shift();
    _oriSamples.push({
        ts: now,
        x: +x.toFixed(4), y: +y.toFixed(4),
        z: +z.toFixed(4), w: +w.toFixed(4),
    });
}

export function recordActEvent(name, distM) {
    const expectedM = _ACT_EXPECTED_M[name] ?? null;
    _actEvents.push({
        ts: Date.now(),
        name,
        distM:     Math.round(distM),
        expectedM,
        deltaM:    expectedM != null ? Math.round(distM) - expectedM : null,
    });
}

function _summarize() {
    if (_gpsEvents.length === 0) return null;

    const numericAcc = _gpsEvents.map(e => e.acc).filter(a => typeof a === 'number');
    const avgAccuracyM = numericAcc.length
        ? +(numericAcc.reduce((s, v) => s + v, 0) / numericAcc.length).toFixed(1)
        : null;
    const maxAccuracyM = numericAcc.length ? Math.max(...numericAcc) : null;

    const absDeltas = _gpsEvents
        .filter(e => e.align && e.align.delta != null)
        .map(e => Math.abs(+e.align.delta));
    const maxAlignmentDeltaDeg = absDeltas.length ? Math.max(...absDeltas) : null;

    const durationSeconds = _gpsEvents.length >= 2
        ? Math.round((_gpsEvents[_gpsEvents.length - 1].ts - _gpsEvents[0].ts) / 1000)
        : 0;

    return {
        durationSeconds,
        gpsCount: _gpsEvents.length,
        avgAccuracyM,
        maxAccuracyM,
        actTriggers:         _actEvents,
        maxAlignmentDeltaDeg,
    };
}

export function exportSession() {
    if (_gpsEvents.length === 0) {
        alert('No GPS events recorded yet.');
        return;
    }

    const payload = JSON.stringify({
        exportedAt: new Date().toISOString(),
        targetLat:  _targetLat,
        targetLng:  _targetLng,
        summary:    _summarize(),
        actEvents:  _actEvents,
        gpsEvents:  _gpsEvents,
        oriSamples: _oriSamples,
    }, null, 2);

    const fname = `session_${Date.now()}.json`;

    if (navigator.canShare && navigator.canShare({ text: payload })) {
        navigator.share({ title: fname, text: payload })
            .catch(() => _blobDownload(payload, fname));
    } else {
        _blobDownload(payload, fname);
    }
}

function _blobDownload(text, fname) {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    const a = Object.assign(document.createElement('a'), { href: url, download: fname });
    // Must be in the DOM for Android WebView to honour the download attribute
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 5000);
}
