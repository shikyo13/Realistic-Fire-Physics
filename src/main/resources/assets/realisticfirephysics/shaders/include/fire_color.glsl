const vec3 FIRE_EDGE_COLOR = vec3(1.0, 0.48, 0.08);
const vec3 FIRE_BODY_COLOR = vec3(1.0, 0.88, 0.50);
const vec3 FIRE_CORE_COLOR = vec3(1.0, 0.98, 0.92);
const float FIRE_CORE_RADIANCE = 2.8;

vec3 fireRadiance(float core) {
    vec3 color = mix(FIRE_EDGE_COLOR, FIRE_BODY_COLOR, smoothstep(0.0, 0.32, core));
    color = mix(color, FIRE_CORE_COLOR, smoothstep(0.10, 0.52, core));
    return color * mix(1.0, FIRE_CORE_RADIANCE, smoothstep(0.12, 0.72, core));
}
