#version 300 es
// Full-screen camera background. The quad is in NDC; its texture coordinates
// are cropped (cover-fit) in upright image space, then mapped into the
// SurfaceTexture buffer by the transform Camera2 attached to the frame (which
// includes the sensor's 90 degree rotation).
layout(location = 0) in vec2 aPos;

uniform mat4 uTexMatrix;
uniform vec4 uCrop; // scale.xy, offset.zw

out vec2 vUv;

void main() {
    vec2 uv = (aPos * 0.5 + 0.5) * uCrop.xy + uCrop.zw;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
