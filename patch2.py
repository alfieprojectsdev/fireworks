import sys

with open("public/index.html", "r") as f:
    content = f.read()

# I will replace the script content.
script_start = content.find("<script>")
script_end = content.find("</script>", script_start)

new_script = """<script>
        const canvas = document.getElementById('ar-canvas');
        const scene = new THREE.Scene();
        const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 1000);
        const renderer = new THREE.WebGLRenderer({ canvas: canvas, alpha: true, antialias: true });
        renderer.setSize(window.innerWidth, window.innerHeight);
        renderer.setPixelRatio(window.devicePixelRatio);

        let controls;
        let showFireworks = false;

        // --- Setup Bloom (Disabled for AR Background Transparency) ---
        let composer = null;

        // --- Orchestration Variables ---
        const targetLat = 14.658888478751235;
        const targetLng = 121.071173166497;
        let experienceStarted = false;

        function getDistanceKm(lat1, lon1, lat2, lon2) {
            const R = 6371;
            const dLat = (lat2 - lat1) * (Math.PI / 180);
            const dLon = (lon2 - lon1) * (Math.PI / 180);
            const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1 * (Math.PI / 180)) * Math.cos(lat2 * (Math.PI / 180)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }

        function getBearing(lat1, lon1, lat2, lon2) {
            const toRad = Math.PI / 180;
            const dLon = (lon2 - lon1) * toRad;
            const y = Math.sin(dLon) * Math.cos(lat2 * toRad);
            const x = Math.cos(lat1 * toRad) * Math.sin(lat2 * toRad) -
                      Math.sin(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.cos(dLon);
            return Math.atan2(y, x);
        }

        // --- 3D Spatial Structures ---
        let arGroup;
        let marqueeCylinder;
        let textIllumination = 0;
        let textBaseOpacity = 0;

        function createARGroup(userLat, userLng) {
            const group = new THREE.Group();
            
            // 1. Calculate GPS offset
            const dist = getDistanceKm(userLat, userLng, targetLat, targetLng) * 1000; // meters
            const bearing = getBearing(userLat, userLng, targetLat, targetLng);
            
            // -Z is North, +X is East in standard Three.js orientation
            const xOffset = dist * Math.sin(bearing);
            const zOffset = -dist * Math.cos(bearing);
            
            // Set group exactly over the church, 40 meters high
            group.position.set(xOffset, 40, zOffset);
            
            // 2. Cylindrical Marquee
            const mCanvas = document.createElement('canvas');
            const mCtx = mCanvas.getContext('2d');
            mCanvas.width = 4096; mCanvas.height = 256;
            mCtx.font = "italic 100px Georgia";
            mCtx.textAlign = "center";
            mCtx.textBaseline = "middle";
            mCtx.shadowColor = "rgba(255, 200, 100, 0.8)";
            mCtx.shadowBlur = 20;
            mCtx.fillStyle = "white";
            
            // Repeat text around cylinder
            for(let i=0; i<4; i++) {
                mCtx.fillText("Happy Anniversary, Bhaze!", 512 + (i * 1024), 128);
            }
            
            const mTex = new THREE.CanvasTexture(mCanvas);
            mTex.wrapS = THREE.RepeatWrapping;
            
            const mMat = new THREE.MeshBasicMaterial({
                map: mTex, transparent: true, opacity: 0, 
                blending: THREE.AdditiveBlending, depthWrite: false,
                side: THREE.DoubleSide
            });
            // Large radius so user can walk inside
            const mGeo = new THREE.CylinderGeometry(20, 20, 4, 64, 1, true);
            marqueeCylinder = new THREE.Mesh(mGeo, mMat);
            group.add(marqueeCylinder);

            // 3. Time Stats
            const start = new Date('2008-05-15T00:00:00');
            const now = new Date();
            const diffMs = now - start;
            
            const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
            const weeks = Math.floor(days / 7);
            
            let years = now.getFullYear() - start.getFullYear();
            let m = now.getMonth() - start.getMonth();
            if (m < 0 || (m === 0 && now.getDate() < start.getDate())) { years--; }
            const months = (years * 12) + (now.getMonth() - start.getMonth()) + (now.getDate() < start.getDate() ? -1 : 0);

            const stats = [
                { text: `${years} Years`, rotY: 0 },
                { text: `${months} Months`, rotY: Math.PI / 2 },
                { text: `${weeks} Weeks`, rotY: Math.PI },
                { text: `${days} Days`, rotY: -Math.PI / 2 }
            ];

            stats.forEach(stat => {
                const sCanvas = document.createElement('canvas');
                sCanvas.width = 512; sCanvas.height = 256;
                const sCtx = sCanvas.getContext('2d');
                sCtx.font = "italic 70px Georgia";
                sCtx.textAlign = "center";
                sCtx.textBaseline = "middle";
                sCtx.fillStyle = "rgba(255, 255, 255, 0.5)"; // 50% transparent base
                sCtx.fillText(stat.text, 256, 128);
                
                const sMat = new THREE.MeshBasicMaterial({
                    map: new THREE.CanvasTexture(sCanvas),
                    transparent: true, opacity: 0,
                    blending: THREE.AdditiveBlending, depthWrite: false,
                    side: THREE.DoubleSide
                });
                const sGeo = new THREE.PlaneGeometry(20, 10);
                const sMesh = new THREE.Mesh(sGeo, sMat);
                
                sMesh.position.set(0, -10, 0); // below marquee
                sMesh.rotation.y = stat.rotY;
                sMesh.translateZ(20); // push outwards to form a square ring matching cylinder radius
                
                sMesh.userData.isStat = true;
                group.add(sMesh);
            });

            return group;
        }

        // --- High Quality Firework Shell ---
        const windDrift = new THREE.Vector3(0.015, 0, -0.005);

        class Firework {
            constructor() {
                this.isRocket = true;
                this.isDead = false;
                this.particles = null;

                this.hue = Math.random();
                this.color = new THREE.Color().setHSL(this.hue, 1, 0.6);

                const dist = 30 + Math.random() * 30;
                const angle = (Math.random() - 0.5) * 1.5; 
                this.pos = new THREE.Vector3(Math.sin(angle) * dist, -30, -Math.cos(angle) * dist);
                this.vel = new THREE.Vector3((Math.random() - 0.5) * 3, 10 + Math.random() * 5, (Math.random() - 0.5) * 3);
                
                // Add group offset if arGroup exists so fireworks spawn around the church
                if (arGroup) {
                    this.pos.x += arGroup.position.x;
                    this.pos.z += arGroup.position.z;
                }
                
                this.life = 0;
                this.maxLife = 120 + Math.random() * 60; 

                this.createRocket();
            }

            createRocket() {
                this.geometry = new THREE.BufferGeometry();
                const pos = new Float32Array([this.pos.x, this.pos.y, this.pos.z, this.pos.x, this.pos.y - 4, this.pos.z]);
                this.geometry.setAttribute('position', new THREE.BufferAttribute(pos, 3));
                const material = new THREE.LineBasicMaterial({
                    color: new THREE.Color(0xffeebb),
                    transparent: true,
                    blending: THREE.AdditiveBlending
                });
                this.particles = new THREE.LineSegments(this.geometry, material);
                scene.add(this.particles);
            }

            explode() {
                scene.remove(this.particles);
                this.isRocket = false;
                
                // 0 = Peony, 1 = Willow, 2 = Chrysanthemum
                this.type = Math.floor(Math.random() * 3);
                
                this.particleCount = (this.type === 2) ? 400 : 250;
                this.positions = new Float32Array(this.particleCount * 6);
                this.velocities = [];
                this.lifespans = [];

                for (let i = 0; i < this.particleCount; i++) {
                    let v = new THREE.Vector3(
                        (Math.random() - 0.5), (Math.random() - 0.5), (Math.random() - 0.5)
                    ).normalize();
                    
                    if (this.type === 0) { // Peony
                        v.multiplyScalar(Math.random() * 3 + 4);
                    } else if (this.type === 1) { // Willow
                        v.multiplyScalar(Math.random() * 2 + 2);
                    } else { // Chrysanthemum
                        v.multiplyScalar(Math.random() * 4 + 5);
                    }
                    
                    this.velocities.push(v);
                    
                    this.positions[i * 6] = this.pos.x;
                    this.positions[i * 6 + 1] = this.pos.y;
                    this.positions[i * 6 + 2] = this.pos.z;
                    this.positions[i * 6 + 3] = this.pos.x;
                    this.positions[i * 6 + 4] = this.pos.y;
                    this.positions[i * 6 + 5] = this.pos.z;
                    
                    this.lifespans.push(0.7 + Math.random() * 0.6); 
                }

                this.geometry = new THREE.BufferGeometry();
                this.geometry.setAttribute('position', new THREE.BufferAttribute(this.positions, 3));
                const material = new THREE.LineBasicMaterial({
                    color: this.color,
                    transparent: true,
                    blending: THREE.AdditiveBlending
                });
                this.particles = new THREE.LineSegments(this.geometry, material);
                scene.add(this.particles);
                
                // Environmental Illumination
                if (arGroup) {
                    const distToText = this.pos.distanceTo(arGroup.position);
                    if (distToText < 150) {
                        textIllumination = Math.max(textIllumination, 1.0 - (distToText / 150));
                    }
                }
            }

            update() {
                if (this.isRocket) {
                    this.pos.add(this.vel);
                    this.vel.y -= 0.2; // Gravity
                    this.vel.add(windDrift);
                    const posArr = this.geometry.attributes.position.array;
                    
                    // Update LineSegment (streak from current pos to previous pos offset)
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
                            posArr[i * 6] = posArr[i * 6 + 3]; // collapse streak
                            posArr[i * 6 + 1] = posArr[i * 6 + 4];
                            posArr[i * 6 + 2] = posArr[i * 6 + 5];
                            continue;
                        }
                        allDead = false;
                        
                        const v = this.velocities[i];
                        const px = posArr[i * 6];
                        const py = posArr[i * 6 + 1];
                        const pz = posArr[i * 6 + 2];
                        
                        // New head position
                        posArr[i * 6] += v.x;
                        posArr[i * 6 + 1] += v.y;
                        posArr[i * 6 + 2] += v.z;
                        
                        // Tail position trails behind
                        const streakLength = this.type === 2 ? 3 : 1.5;
                        posArr[i * 6 + 3] = posArr[i * 6] - v.x * streakLength;
                        posArr[i * 6 + 4] = posArr[i * 6 + 1] - v.y * streakLength;
                        posArr[i * 6 + 5] = posArr[i * 6 + 2] - v.z * streakLength;
                        
                        // Apply physics
                        v.y -= (this.type === 1) ? 0.05 : 0.03; // Higher gravity for Willow
                        v.multiplyScalar(0.96); 
                        v.add(windDrift);
                    }
                    this.geometry.attributes.position.needsUpdate = true;
                    
                    const baseOpacity = 1 - Math.pow(fadeRate, 2);
                    this.particles.material.opacity = baseOpacity * (0.6 + Math.random() * 0.4);
                    
                    if (allDead || this.life >= this.maxLife) this.isDead = true;
                }
            }

            destroy() {
                scene.remove(this.particles);
                this.geometry.dispose();
                this.particles.material.dispose();
            }
        }

        let fireworks = [];
        
        function animate() {
            requestAnimationFrame(animate);
            
            if (controls) controls.update();
            
            // Interaction: Fade illumination
            if (textIllumination > 0) {
                textIllumination -= 0.02;
                if (textIllumination < 0) textIllumination = 0;
            }
            if (arGroup) {
                arGroup.children.forEach(child => {
                    if (child === marqueeCylinder) {
                        child.material.opacity = textBaseOpacity + (textIllumination * 0.6);
                    } else if (child.userData.isStat) {
                        // Max opacity for stats is 0.5, plus flash
                        child.material.opacity = (textBaseOpacity * 0.5) + (textIllumination * 0.4);
                    }
                });
                marqueeCylinder.rotation.y += 0.002;
            }

            if (showFireworks) {
                if (Math.random() < 0.1) fireworks.push(new Firework());
                if (Math.random() < 0.02) {
                    fireworks.push(new Firework());
                    fireworks.push(new Firework());
                }
            }

            for (let i = fireworks.length - 1; i >= 0; i--) {
                fireworks[i].update();
                if (fireworks[i].isDead) {
                    fireworks[i].destroy();
                    fireworks.splice(i, 1);
                }
            }
            
            if (composer) {
                composer.render();
            } else {
                renderer.render(scene, camera);
            }
        }
        animate();

        window.addEventListener('resize', () => {
            camera.aspect = window.innerWidth / window.innerHeight;
            camera.updateProjectionMatrix();
            renderer.setSize(window.innerWidth, window.innerHeight);
            if (composer) composer.setSize(window.innerWidth, window.innerHeight);
        });

        function startExperience(uLat = targetLat, uLng = targetLng) {
            if (experienceStarted) return;
            experienceStarted = true;
            try { document.documentElement.requestFullscreen().catch(err => console.log("Fullscreen API failed", err)); } catch (e) { }
            document.getElementById('status-text').style.opacity = '0';
            
            navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } })
                .then(stream => { document.getElementById('camera-bg').srcObject = stream; })
                .catch(err => console.error("Camera error:", err));
                
            controls = new THREE.DeviceOrientationControls(camera);
            arGroup = createARGroup(uLat, uLng);
            scene.add(arGroup);

            setTimeout(() => {
                document.getElementById('status-text').style.display = 'none';
                document.getElementById('camera-bg').style.opacity = '1';
                showFireworks = true;
                
                // Fade in the spatial text
                setTimeout(() => {
                    const fade = setInterval(() => {
                        textBaseOpacity += 0.02;
                        if (textBaseOpacity >= 0.8) clearInterval(fade);
                    }, 50);
                }, 2000);
            }, 1000);
        }

        const geoInterval = setInterval(() => {
            if (navigator.geolocation && !experienceStarted) {
                navigator.geolocation.getCurrentPosition(pos => {
                    if (getDistanceKm(pos.coords.latitude, pos.coords.longitude, targetLat, targetLng) <= 0.1) {
                        clearInterval(geoInterval);
                        startExperience(pos.coords.latitude, pos.coords.longitude);
                    } else {
                        document.getElementById('status-message').innerText = "We aren't in the right spot yet...";
                    }
                }, null, { enableHighAccuracy: true });
            }
        }, 3000);

        let tapCount = 0;
        document.getElementById('secret-override').addEventListener('click', () => {
            tapCount++;
            if (tapCount >= 3) {
                clearInterval(geoInterval);
                // When tapped manually, assume user is at target location to spawn it around them
                startExperience(targetLat, targetLng);
            }
        });
</script>"""

new_content = content[:script_start] + new_script + content[script_end+9:]

with open("public/index.html", "w") as f:
    f.write(new_content)

