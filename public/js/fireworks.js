import { playExplosion } from './audio.js';

const windDrift = new THREE.Vector3(0.015, 0, -0.005);

// Owned here so Firework.explode() can write it directly.
// scene.js reads it via the exported getter/ticker without creating a circular import.
let _textIllumination = 0;

export function getTextIllumination() { return _textIllumination; }
export function tickIllumination()    { _textIllumination = Math.max(0, _textIllumination - 0.02); }

export class Firework {
    // scene and arGroup passed at construction so fireworks.js stays decoupled from scene.js.
    constructor(scene, arGroup) {
        this._scene   = scene;
        this._arGroup = arGroup;
        this.isRocket = true;
        this.isDead   = false;
        this.particles = null;

        this.hue   = Math.random();
        this.color = new THREE.Color().setHSL(this.hue, 1, 0.6);

        const dist  = 30 + Math.random() * 30;
        const angle = (Math.random() - 0.5) * 1.5;
        this.pos = new THREE.Vector3(Math.sin(angle) * dist, -40, -Math.cos(angle) * dist);
        this.vel = new THREE.Vector3((Math.random() - 0.5) * 2, 4 + Math.random() * 3, (Math.random() - 0.5) * 2);

        if (arGroup) {
            this.pos.x += arGroup.position.x;
            this.pos.z += arGroup.position.z;
        }

        this.life    = 0;
        this.maxLife = 120 + Math.random() * 60;
        this._createRocket();
    }

    _createRocket() {
        this.geometry = new THREE.BufferGeometry();
        const pos = new Float32Array([
            this.pos.x, this.pos.y, this.pos.z,
            this.pos.x, this.pos.y - 4, this.pos.z,
        ]);
        this.geometry.setAttribute('position', new THREE.BufferAttribute(pos, 3));
        this.particles = new THREE.LineSegments(this.geometry, new THREE.LineBasicMaterial({
            color: new THREE.Color(0xffeebb), transparent: true, blending: THREE.AdditiveBlending,
        }));
        this._scene.add(this.particles);
    }

    explode() {
        this._scene.remove(this.particles);
        this.isRocket = false;

        // 0=Peony 1=Willow 2=Chrysanthemum 3=Heart 4=Lemniscate(∞) 5=Lissajous(3:2)
        const _r = Math.random();
        this.type = _r < 0.10 ? 3 : _r < 0.18 ? 4 : _r < 0.26 ? 5 : Math.floor(Math.random() * 3);
        if (this.type === 3) this.color = new THREE.Color().setHSL(0.93, 1.0, 0.75); // hot pink
        if (this.type === 4) this.color = new THREE.Color().setHSL(0.12, 1.0, 0.65); // amber gold
        if (this.type === 5) this.color = new THREE.Color().setHSL(0.55, 1.0, 0.70); // electric cyan

        this.particleCount = this.type === 2 ? 300 : this.type === 3 ? 120
                           : this.type === 4 ? 160 : this.type === 5 ? 180 : 220;
        this.positions = new Float32Array(this.particleCount * 6);
        this.velocities = [];
        this.lifespans  = [];

        for (let i = 0; i < this.particleCount; i++) {
            let v;
            // Shaped shells: pre-position particles along the parametric curve at explosion
            // so the shape appears instantly rather than emerging slowly from a point.
            // Velocity is slow outward drift; the curve offset carries the shape information.
            let ox = 0, oy = 0, oz = 0;

            if (this.type === 3) {
                const t = (i / this.particleCount) * Math.PI * 2;
                const hx = 16 * Math.pow(Math.sin(t), 3);
                const hy = (Math.random() - 0.5) * 2;
                const hz = -(13 * Math.cos(t) - 5 * Math.cos(2*t) - 2 * Math.cos(3*t) - Math.cos(4*t));
                const sc = 12 / 21;
                ox = hx * sc; oy = hy * sc; oz = hz * sc;
                v = new THREE.Vector3(hx, hy, hz).normalize().multiplyScalar(0.8 + Math.random() * 0.4);
            } else if (this.type === 4) {
                const t = (i / this.particleCount) * Math.PI * 2;
                const d = 1 + Math.sin(t) ** 2;
                const lx = Math.cos(t) / d, lz = Math.sin(t) * Math.cos(t) / d;
                const ly = (Math.random() - 0.5) * 0.12;
                ox = lx * 13; oy = ly * 13; oz = lz * 13;
                v = new THREE.Vector3(lx, ly, lz).normalize().multiplyScalar(0.8 + Math.random() * 0.4);
            } else if (this.type === 5) {
                const t = (i / this.particleCount) * Math.PI * 2;
                const lx = Math.sin(3 * t + Math.PI / 2);
                const lz = Math.sin(2 * t);
                const ly = (Math.random() - 0.5) * 0.12;
                ox = lx * 11; oy = ly * 11; oz = lz * 11;
                v = new THREE.Vector3(lx, ly, lz).normalize().multiplyScalar(0.8 + Math.random() * 0.4);
            } else {
                v = new THREE.Vector3(
                    Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5
                ).normalize();
                if      (this.type === 0) v.multiplyScalar(Math.random() * 3 + 4);
                else if (this.type === 1) v.multiplyScalar(Math.random() * 2 + 2);
                else                      v.multiplyScalar(Math.random() * 4 + 5);
            }

            this.velocities.push(v);
            const sx = this.pos.x + ox, sy = this.pos.y + oy, sz = this.pos.z + oz;
            this.positions[i*6]=sx; this.positions[i*6+1]=sy; this.positions[i*6+2]=sz;
            this.positions[i*6+3]=sx; this.positions[i*6+4]=sy; this.positions[i*6+5]=sz;
            this.lifespans.push(this.type >= 3 ? 0.88 + Math.random() * 0.18 : 0.7 + Math.random() * 0.6);
        }

        this.geometry = new THREE.BufferGeometry();
        this.geometry.setAttribute('position', new THREE.BufferAttribute(this.positions, 3));
        this.particles = new THREE.LineSegments(this.geometry, new THREE.LineBasicMaterial({
            color: this.color, transparent: true, blending: THREE.AdditiveBlending,
        }));
        this._scene.add(this.particles);

        if (this._arGroup) {
            const distToText = this.pos.distanceTo(this._arGroup.position);
            if (distToText < 150)
                _textIllumination = Math.max(_textIllumination, 1.0 - (distToText / 150));
        }

        playExplosion(this.pos, this.type);
    }

    update() {
        if (this.isRocket) {
            this.pos.add(this.vel);
            this.vel.y -= 0.2;
            this.vel.add(windDrift);
            const p = this.geometry.attributes.position.array;
            p[0]=this.pos.x; p[1]=this.pos.y; p[2]=this.pos.z;
            p[3]=this.pos.x-this.vel.x*2; p[4]=this.pos.y-this.vel.y*2; p[5]=this.pos.z-this.vel.z*2;
            this.geometry.attributes.position.needsUpdate = true;
            this.particles.material.opacity = 0.5 + Math.random() * 0.5;
            if (this.vel.y <= 1) this.explode();
        } else {
            this.life++;
            const p = this.geometry.attributes.position.array;
            let allDead = true;
            const fadeRate = this.life / this.maxLife;

            for (let i = 0; i < this.particleCount; i++) {
                if (fadeRate * this.lifespans[i] > 1) {
                    p[i*6]=p[i*6+3]; p[i*6+1]=p[i*6+4]; p[i*6+2]=p[i*6+5];
                    continue;
                }
                allDead = false;
                const v = this.velocities[i];

                // New head position (vs tail at i*6+3..5 below)
                p[i*6]   += v.x; p[i*6+1] += v.y; p[i*6+2] += v.z;

                // Short streaks on shaped shells so the outline stays crisp
                const sl = this.type === 2 ? 3 : this.type >= 3 ? 0.8 : 1.5;
                p[i*6+3] = p[i*6]   - v.x * sl;
                p[i*6+4] = p[i*6+1] - v.y * sl;
                p[i*6+5] = p[i*6+2] - v.z * sl;

                // Minimal gravity on shaped shells so the outline stays readable
                v.y -= this.type === 1 ? 0.05 : this.type >= 3 ? 0.004 : 0.03;
                v.multiplyScalar(this.type >= 3 ? 0.985 : 0.96);
                v.add(windDrift);
            }
            this.geometry.attributes.position.needsUpdate = true;
            const baseOpacity = 1 - Math.pow(fadeRate, 2);
            this.particles.material.opacity = baseOpacity * (0.6 + Math.random() * 0.4);
            if (allDead || this.life >= this.maxLife) this.isDead = true;
        }
    }

    destroy() {
        this._scene.remove(this.particles);
        this.geometry.dispose();
        this.particles.material.dispose();
    }
}
