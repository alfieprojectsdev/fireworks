import sys

with open("public/index.html", "r") as f:
    content = f.read()

# Chunk 1: Head and HTML Body UI
chunk1_old = """    <script src="https://unpkg.com/three@0.128.0/build/three.min.js"></script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/controls/DeviceOrientationControls.js"></script>
</head>

<body>

    <video id="camera-bg" class="layer" autoplay playsinline muted loop webkit-playsinline></video>
    <canvas id="ar-canvas" class="layer"></canvas>

    <div id="ui-layer" class="layer">
        <div id="status-text">
            <div id="status-inner">
                <div class="spinner"></div>
                <span id="status-message">Locating...</span>
            </div>
        </div>
        <div id="message" style="display:none;">Happy Anniversary,<br>Bhaze!</div>
    </div>"""

chunk1_new = """    <script src="https://unpkg.com/three@0.128.0/build/three.min.js"></script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/controls/DeviceOrientationControls.js"></script>
    
    <!-- Bloom dependencies with offline fallback -->
    <script src="https://unpkg.com/three@0.128.0/examples/js/postprocessing/EffectComposer.js"></script>
    <script>if (!THREE.EffectComposer) document.write('<script src="./lib/three-post/EffectComposer.js"><\\/script>');</script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/postprocessing/RenderPass.js"></script>
    <script>if (!THREE.RenderPass) document.write('<script src="./lib/three-post/RenderPass.js"><\\/script>');</script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/postprocessing/ShaderPass.js"></script>
    <script>if (!THREE.ShaderPass) document.write('<script src="./lib/three-post/ShaderPass.js"><\\/script>');</script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/shaders/CopyShader.js"></script>
    <script>if (!THREE.CopyShader) document.write('<script src="./lib/three-post/CopyShader.js"><\\/script>');</script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/shaders/LuminosityHighPassShader.js"></script>
    <script>if (!THREE.LuminosityHighPassShader) document.write('<script src="./lib/three-post/LuminosityHighPassShader.js"><\\/script>');</script>
    <script src="https://unpkg.com/three@0.128.0/examples/js/postprocessing/UnrealBloomPass.js"></script>
    <script>if (!THREE.UnrealBloomPass) document.write('<script src="./lib/three-post/UnrealBloomPass.js"><\\/script>');</script>
</head>

<body>

    <video id="camera-bg" class="layer" autoplay playsinline muted loop webkit-playsinline></video>
    <canvas id="ar-canvas" class="layer"></canvas>

    <div id="ui-layer" class="layer">
        <div id="status-text">
            <div id="status-inner">
                <div class="spinner"></div>
                <span id="status-message">Locating...</span>
            </div>
        </div>
    </div>"""

# Chunk 2: Three.js logic replacement
import re
# Find where "let controls;" starts
start_idx = content.find("        let controls;\n")
# Find where "animate();" ends
end_idx = content.find("        animate();\n") + len("        animate();\n")

if start_idx == -1 or end_idx == -1:
    print("Could not find script bounds")
    sys.exit(1)

js_new = """        let controls;
        let showFireworks = false;
        
        // --- Setup Bloom ---
        const renderScene = new THREE.RenderPass(scene, camera);
        const bloomPass = new THREE.UnrealBloomPass(new THREE.Vector2(window.innerWidth, window.innerHeight), 1.5, 0.4, 0.85);
        bloomPass.threshold = 0;
        bloomPass.strength = 1.2;
        bloomPass.radius = 0;

        const composer = new THREE.EffectComposer(renderer);
        composer.addPass(renderScene);
        composer.addPass(bloomPass);

        // --- 3D Spatial Text ---
        let textPlane;
        let textIllumination = 0;
        let textBaseOpacity = 0;

        function createTextPlane() {
            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            canvas.width = 1024; canvas.height = 256;
            
            ctx.font = "italic 80px Georgia";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.shadowColor = "rgba(255, 200, 100, 0.8)";
            ctx.shadowBlur = 20;
            ctx.fillStyle = "white";
            ctx.fillText("Happy Anniversary,", 512, 80);
            ctx.fillText("Bhazel", 512, 180);
            
            const texture = new THREE.CanvasTexture(canvas);
            const material = new THREE.MeshBasicMaterial({ 
                map: texture, 
                transparent: true, 
                opacity: 0, 
                blending: THREE.AdditiveBlending,
                depthWrite: false
            });
            const geometry = new THREE.PlaneGeometry(60, 15);
            const plane = new THREE.Mesh(geometry, material);
            
            plane.position.set(0, 40, -100);
            return plane;
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

                const dist = 70 + Math.random() * 50;
                const angle = (Math.random() - 0.5) * 1.5; 
                this.pos = new THREE.Vector3(Math.sin(angle) * dist, -60, -Math.cos(angle) * dist);
                this.vel = new THREE.Vector3((Math.random() - 0.5) * 4, 18 + Math.random() * 8, (Math.random() - 0.5) * 4);
                
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
                const distToText = this.pos.distanceTo(new THREE.Vector3(0, 40, -100));
                if (distToText < 150) {
                    textIllumination = Math.max(textIllumination, 1.0 - (distToText / 150));
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
            if (textPlane) {
                textPlane.material.opacity = textBaseOpacity + (textIllumination * 0.6);
            }

            if (showFireworks && Math.random() < 0.04) fireworks.push(new Firework());

            for (let i = fireworks.length - 1; i >= 0; i--) {
                fireworks[i].update();
                if (fireworks[i].isDead) {
                    fireworks[i].destroy();
                    fireworks.splice(i, 1);
                }
            }
            composer.render();
        }\n"""

content = content.replace(chunk1_old, chunk1_new)
content = content[:start_idx] + js_new + content[end_idx:]

# Chunk 3: Resize logic
chunk3_old = """        window.addEventListener('resize', () => {
            camera.aspect = window.innerWidth / window.innerHeight;
            camera.updateProjectionMatrix();
            renderer.setSize(window.innerWidth, window.innerHeight);
        });"""
chunk3_new = """        window.addEventListener('resize', () => {
            camera.aspect = window.innerWidth / window.innerHeight;
            camera.updateProjectionMatrix();
            renderer.setSize(window.innerWidth, window.innerHeight);
            if (composer) composer.setSize(window.innerWidth, window.innerHeight);
        });"""
content = content.replace(chunk3_old, chunk3_new)

# Chunk 4: Orchestration logic
chunk4_old = """        function startExperience() {
            if (experienceStarted) return;
            experienceStarted = true;
            document.getElementById('status-text').style.opacity = '0';
            navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } })
                .then(stream => { document.getElementById('camera-bg').srcObject = stream; })
                .catch(err => console.error("Camera error:", err));
            controls = new THREE.DeviceOrientationControls(camera);
            setTimeout(() => {
                document.getElementById('status-text').style.display = 'none';
                document.getElementById('camera-bg').style.opacity = '1';
                showFireworks = true;
                setTimeout(() => {
                    document.getElementById('message').style.display = 'block';
                    void document.getElementById('message').offsetWidth;
                    document.getElementById('message').style.opacity = '1';
                }, 2000);
            }, 1000);
        }"""
chunk4_new = """        function startExperience() {
            if (experienceStarted) return;
            experienceStarted = true;
            document.getElementById('status-text').style.opacity = '0';
            
            navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } })
                .then(stream => { document.getElementById('camera-bg').srcObject = stream; })
                .catch(err => console.error("Camera error:", err));
                
            controls = new THREE.DeviceOrientationControls(camera);
            textPlane = createTextPlane();
            scene.add(textPlane);

            setTimeout(() => {
                document.getElementById('status-text').style.display = 'none';
                document.getElementById('camera-bg').style.opacity = '1';
                showFireworks = true;
                
                // Fade in the spatial text
                setTimeout(() => {
                    const fade = setInterval(() => {
                        textBaseOpacity += 0.02;
                        if(textBaseOpacity >= 0.8) clearInterval(fade);
                    }, 50);
                }, 2000);
            }, 1000);
        }"""
content = content.replace(chunk4_old, chunk4_new)

with open("public/index.html", "w") as f:
    f.write(content)

