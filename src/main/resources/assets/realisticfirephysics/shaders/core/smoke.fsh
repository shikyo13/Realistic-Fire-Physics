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
    vec2 p=uv*2.0-1.0;
    float radial=1.0-dot(p,p);
    float density=fbm(uv*5.0+vec2(time*.07,-time*.12));
    float mask=smoothstep(0.0,.55,radial)*smoothstep(.22,.72,density);
    fragColor=vec4(tint.rgb+(density-.5)*.07,mask*tint.a);
    if(fragColor.a<.002) discard;
}
