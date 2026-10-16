#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

uniform samplerExternalOES uTex;
uniform float uBrightness; // the 2 s fade-in after the trigger

in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(uTex, vUv).rgb * uBrightness, 1.0);
}
