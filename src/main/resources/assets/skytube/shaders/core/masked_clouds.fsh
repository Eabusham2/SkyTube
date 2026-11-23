#version 330

#moj_import <minecraft:fog.glsl>

in float vertexDistance;
in vec4 vertexColor;
out vec4 fragColor;

layout(std140) uniform SkyTubeMask {
    // xy converts framebuffer pixels to NDC, z is cloud opacity, w is polygon edge count.
    vec4 maskInfo;
    vec4 maskEdges[10];
};

void main() {
    vec3 ndc = vec3(gl_FragCoord.xy * maskInfo.xy - vec2(1.0), 1.0);
    bool overVideo = true;
    for (int i = 0; i < int(maskInfo.w); ++i) {
        if (dot(ndc, maskEdges[i].xyz) < 0.0) {
            overVideo = false;
            break;
        }
    }
    if (overVideo && maskInfo.z == 0.0) discard;
    float cloudFade = 1.0 - linear_fog_value(vertexDistance, 0.0, FogCloudsEnd);
    float opacity = overVideo ? maskInfo.z : 1.0;
    fragColor = vec4(vertexColor.rgb, vertexColor.a * cloudFade * opacity);
}
