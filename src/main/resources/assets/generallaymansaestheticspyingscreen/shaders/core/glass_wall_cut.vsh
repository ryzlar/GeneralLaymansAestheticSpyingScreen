#version 150

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec4 texProj0;
out vec3 boxMin;
out vec3 boxMax;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texProj0 = projection_from_position(gl_Position);
    // The cloak field's hidden box, relative to the camera: x in UV0, y and z in UV1 and UV2 (in 1/32 blocks).
    boxMin = vec3(UV0.x, float(UV1.x) / 32.0, float(UV2.x) / 32.0);
    boxMax = vec3(UV0.y, float(UV1.y) / 32.0, float(UV2.y) / 32.0);
}
