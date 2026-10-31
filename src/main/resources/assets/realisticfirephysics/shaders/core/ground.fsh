#version 150
uniform float GameTime;
in vec4 tint;
in vec2 uv;
out vec4 fragColor;
float hash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
float noise(vec2 p) { vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f); return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y); }
float fbm(vec2 p) { return noise(p)*0.57+noise(p*2.03+7.2)*0.28+noise(p*4.11+1.3)*0.15; }
void main() {
    float time=GameTime*1200.0;
    vec2 pixel=floor(uv*16.0);
    float ash=hash(pixel);
    float grit=fbm(uv*24.0);
    float coverage=smoothstep(0.12,0.78,tint.r+(grit-0.5)*0.18);
    float hot=tint.g;
    float fleck=smoothstep(.955,.98,hash(pixel+71.0));
    float pulse=.60+.40*sin(time*2.1+hash(pixel+2.0)*40.0);
    float ember=fleck*tint.b*pulse;
    vec3 coal=mix(vec3(.048,.047,.045),vec3(.19,.175,.155),pow(ash,3.0));
    float rim=hot*(.4+.6*noise(uv*38.0+time*.16));
    vec3 glow=vec3(1.0,.48,.08)*(rim*.30+ember*1.3);
    fragColor=vec4(glow,coverage);
    if (fragColor.a<.005) discard;
}
