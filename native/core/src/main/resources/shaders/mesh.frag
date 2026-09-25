#version 300 es
precision mediump float;

// Additive, like every web material: blended ONE, ONE with premultiplied output.
uniform sampler2D uTex;
uniform bool uTextured;
uniform vec3 uColor;
uniform float uOpacity;

in vec2 vUv;
out vec4 fragColor;

void main() {
    if (uTextured) {
        // Texels arrive premultiplied (Android Bitmaps are). WebGL clamped
        // alpha * opacity to 1 before blending, so an act opacity above 1
        // brightened the soft glow but not the glyph cores; reproduce that.
        vec4 t = texture(uTex, vUv);
        vec3 straight = t.a > 0.0 ? t.rgb / t.a : vec3(0.0);
        float a = min(1.0, t.a * uOpacity);
        fragColor = vec4(straight * a, a);
    } else {
        float a = min(1.0, uOpacity);
        fragColor = vec4(uColor * a, a);
    }
}
