import { playExplosion } from './audio.js';

const windDrift = new THREE.Vector3(0.015, 0, -0.005);

// Owned here because only Firework.explode() writes it and scene.js reads/ticks it.
// Exported as getter/ticker to avoid a circular import (scene→fireworks→scene).
let _textIllumination = 0;

export function getTextIllumination() { return _textIllumination; }
export function tickIllumination() { _textIllumination = Math.max(0, _textIllumination - 0.02); }

export class Firework {
    constructor(scene, arGroup) {
        this.scene = scene;
        this.arGroup = arGroup;
        this.isRocket = true;
        this.isDead = false;
        this.particles = null;

        this.hue = Math.random();
        this.color = new THREE.Color().setHSL(this.hue, 1, 0.6);

        const dist = 30 + Math.random() * 30;
        const angle = (Math.random() - 0.5) * 1.5;
        this.pos = new THREE.Vector3(Math.sin(angle) * dist, -40, -Math.cos(angle) * dist);
        this.vel = new THREE.Vector3((Math.random() - 0.5) * 2, 4 + Math.random() * 3, (Math.random() - 0.5) * 2);

        if (this.arGroup) {
            this.pos.x += this.arGroup.position.x;
            this.pos.z += this.arGroup.position.z;
        }

        this.life = 0;
        this.maxLife = 120 + Math.random() * 60;
        this._createRocket();
    }

    _createRocket() {
        this.geometry = new THREE.BufferGeometry();
        const pos = new Float32Array([
            this.pos.x, this.pos.y, this.pos.z,
            this.pos.x, this.pos.y - 4, this.pos.z
        ]);
        this.geometry.setAttribute('position', new THREE.BufferAttribute(pos, 3));
        this.particles = new THREE.LineSegments(this.geometry, new THREE.LineBasicMaterial({
            color: new THREE.Color(0xffeebb),
            transparent: true,
            blending: THREE.AdditiveBlending
        }));
        this.scene.add(this.particles);
    }

    explode() {
        this.scene.remove(this.particles);
        this.isRocket = false;

        // 0=Peony 1=Willow 2=Chrysanthemum 3=Heart 4=Lemniscate(∞) 5=Lissajous(3:2)
        const _r = Math.random();
        this.type = _r < 0.10 ? 3 : _r < 0.18 ? 4 : _r < 0.26 ? 5 : Math.floor(Math.random() * 3);
        if (this.type === 3) this.color = new THREE.Color().setHSL(0.93, 1.0, 0.75);
        if (this.type === 4) this.color = new THREE.Color().setHSL(0.12, 1.0, 0.65);
        if (this.type === 5) this.color = new THREE.Color().setHSL(0.55, 1.0, 0.70);

        this.particleCount = this.type === 2 ? 300 : this.type === 3 ? 120
                           : this.type === 4 ? 160 : this.type === 5 ? 180 : 220;
        this.positions = new Float32Array(this.particleCount * 6);
        this.velocities = [];
        this.lifespans = [];

        for (let i = 0; i < this.particleCount; i++) {
            let v;
            // Shaped shells: pre-position particles along the parametric curve so the
            // shape appears instantly; velocity is slow outward drift from the offset.
            let ox = 0, oy = 0, oz = 0;

            if (this.type === 3) {
                // Heart
                const t = (i / this.particleCount) * Math.PI * 2;
                const hx = 16 * Math.pow(Math.sin(t), 3);
                const hy = (Math.random() - 0.5) * 2;
                const hz = -(13 * Math.cos(t) - 5 * Math.cos(2*t) - 2 * Math.cos(3*t) - Math.cos(4*t));
                const sc = 12 / 21;
                ox = hx * sc; oy = hy * sc; oz = hz * sc;
                v = new THREE.Vector3(hx, hy, hz).normalize().multiplyScalar(0.8 + Math.random() * 0.4);
            } else if (this.type === 4) {
                // Lemniscate of Bernoulli (∞)
                const t = (i / this.particleCount) * Math.PI * 2;
                const d = 1 + Math.sin(t) * Math.sin(t);
                const lx = Math.cos(t) / d;
                const ly = (Math.random() - 0.5) * 0.12;
                const lz = Math.sin(t) * Math.cos(t) / d;
                ox = lx * 13; oy = ly * 13; oz = lz * 13;
                v = new THREE.Vector3(lx, ly, lz).normalize().multiplyScalar(0.8 + Math.random() * 0.4);
            } else if (this.type === 5) {
                // Lissajous 3:2
                const t = (i / this.particleCount) * Math.PI * 2;
                const lx = Math.sin(3 * t + Math.PI / 2);
                const ly = (Math.random() - 0.5) * 0.12;
                const lz = Math.sin(2 * t);
                ox = lx * 11; oy = ly * 11; oz = lz * 11;
                v = new THREE.Vector3(lx, ly, lz).normalize().multiplyScalar(0.8 + Math.random() * 0.4);
            } else {
                v = new THREE.Vector3(
                    (Math.random() - 0.5), (Math.random() - 0.5), (Math.random() - 0.5)
                ).normalize();
                if (this.type === 0)      v.multiplyScalar(Math.random() * 3 + 4);
                else if (this.type === 1) v.multiplyScalar(Math.random() * 2 + 2);
                else                      v.multiplyScalar(Math.random() * 4 + 5);
            }

            this.velocities.push(v);

            const sx = this.pos.x + ox;
            const sy = this.pos.y + oy;
            const sz = this.pos.z + oz;
            this.positions[i * 6]     = sx;
            this.positions[i * 6 + 1] = sy;
            this.positions[i * 6 + 2] = sz;
            this.positions[i * 6 + 3] = sx;
            this.positions[i * 6 + 4] = sy;
            this.positions[i * 6 + 5] = sz;

            // Shaped shells: tight lifespan so the outline fades coherently
            this.lifespans.push(this.type >= 3 ? 0.88 + Math.random() * 0.18 : 0.7 + Math.random() * 0.6);
        }

        this.geometry = new THREE.BufferGeometry();
        this.geometry.setAttribute('position', new THREE.BufferAttribute(this.positions, 3));
        this.particles = new THREE.LineSegments(this.geometry, new THREE.LineBasicMaterial({
            color: this.color,
            transparent: true,
            blending: THREE.AdditiveBlending
        }));
        this.scene.add(this.particles);

        if (this.arGroup) {
            const distToText = this.pos.distanceTo(this.arGroup.position);
            if (distToText < 150) {
                _textIllumination = Math.max(_textIllumination, 1.0 - (distToText / 150));
            }
        }

        playExplosion(this.pos, this.type);
    }

    update() {
        if (this.isRocket) {
            this.pos.add(this.vel);
            this.vel.y -= 0.2;
            this.vel.add(windDrift);
            const posArr = this.geometry.attributes.position.array;

            posArr[0] = this.pos.x; posArr[1] = this.pos.y; posArr[2] = this.pos.z;
            posArr[3] = this.pos.x - this.vel.x * 2;
            posArr[4] = this.pos.y - this.vel.y * 2;
            posArr[5] = this.pos.z - this.vel.z * 2;

            this.geometry.attributes.position.needsUpdate = true;
            this.particles.material.opacity = 0.5 + Math.random() * 0.5;

            if (this.vel.y <= 1) this.explode();
        } else {
            this.life++;
            const posArr = this.geometry.attributes.position.array;
            let allDead = true;
            const fadeRate = this.life / this.maxLife;

            for (let i = 0; i < this.particleCount; i++) {
                if (fadeRate * this.lifespans[i] > 1) {
                    posArr[i * 6]     = posArr[i * 6 + 3];
                    posArr[i * 6 + 1] = posArr[i * 6 + 4];
                    posArr[i * 6 + 2] = posArr[i * 6 + 5];
                    continue;
                }
                allDead = false;

                const v = this.velocities[i];
                posArr[i * 6]     += v.x;
                posArr[i * 6 + 1] += v.y;
                posArr[i * 6 + 2] += v.z;

                // Short streaks on shaped shells so the outline stays crisp
                const streakLength = this.type === 2 ? 3 : (this.type >= 3) ? 0.8 : 1.5;
                posArr[i * 6 + 3] = posArr[i * 6]     - v.x * streakLength;
                posArr[i * 6 + 4] = posArr[i * 6 + 1] - v.y * streakLength;
                posArr[i * 6 + 5] = posArr[i * 6 + 2] - v.z * streakLength;

                // Minimal gravity on shaped shells so the outline stays readable
                v.y -= this.type === 1 ? 0.05 : (this.type >= 3) ? 0.004 : 0.03;
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
        this.scene.remove(this.particles);
        this.geometry.dispose();
        this.particles.material.dispose();
    }
}
