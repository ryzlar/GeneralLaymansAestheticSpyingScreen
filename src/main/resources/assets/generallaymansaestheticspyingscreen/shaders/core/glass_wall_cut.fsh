#version 150

#moj_import <minecraft:dynamictransforms.glsl>

// The picture before players and mobs were drawn, and the depth after they were.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec4 texProj0;
in vec3 boxMin;
in vec3 boxMax;

out vec4 fragColor;

// Puts the picture from before players and mobs were drawn back wherever what is seen lies inside the cloak field's
// hidden box: the part of someone walking into the field that is already in it is gone, the rest of them stays.
void main() {
    vec2 uv = texProj0.xy / texProj0.w;
    float depth = texture(Sampler1, uv).r;
    // TextureMat: from the screen back to the world, relative to the camera.
    vec4 world = TextureMat * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 point = world.xyz / world.w;
    if (any(lessThan(point, boxMin)) || any(greaterThan(point, boxMax))) {
        discard;
    }
    fragColor = vec4(texture(Sampler0, uv).rgb, 1.0);
}
