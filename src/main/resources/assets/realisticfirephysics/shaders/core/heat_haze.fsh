#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform vec2 SceneSize;
uniform float HeatTime;
uniform float HeatStrength;
in vec2 heatUv;
in float strength;
out vec4 fragColor;
void main() {
    vec2 screen = gl_FragCoord.xy / SceneSize;
    float depth = texture(SceneDepth, screen).r;
    if (depth + 0.000001 < gl_FragCoord.z) discard;
    vec2 p = heatUv;
    float shape = exp(-pow((p.x - .5) * 3.6, 2.0));
    shape *= smoothstep(0.0, .18, p.y) * (1.0 - smoothstep(.45, 1.0, p.y));
    float a = p.y * 18.0 - HeatTime * 4.1;
    float b = p.x * 12.0 + HeatTime * 1.9;
    vec2 curl = vec2(sin(a + sin(b)), cos(b + .7 * sin(a * .73)));
    vec2 offset = curl * shape * strength * HeatStrength * 1.8 * (SceneSize.y / 1080.0) / SceneSize;
    vec2 sampleUv = clamp(screen + offset, .5 / SceneSize, 1.0 - .5 / SceneSize);
    // Sampling across a foreground silhouette must never pull that object into the hot air.
    if (texture(SceneDepth, sampleUv).r + .000001 < gl_FragCoord.z) sampleUv = screen;
    fragColor = vec4(texture(SceneColor, sampleUv).rgb, shape * .8);
}
