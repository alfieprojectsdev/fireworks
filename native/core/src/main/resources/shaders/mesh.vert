#version 300 es
// Monument geometry: crown, pylons, beacon, ground ring, message.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;

uniform mat4 uMvp;

out vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
