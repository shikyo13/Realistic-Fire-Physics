#version 150
#moj_import <surfacefire:fire_color.glsl>
uniform float GameTime;
uniform float RootSoftness;
in vec4 tint;
in vec2 uv;
out vec4 fragColor;
float hash(vec2 p) { return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453); }
float noise(vec2 p) { vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f); return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y); }
float fbm(vec2 p) { return noise(p)*0.57+noise(p*2.03+7.2)*0.28+noise(p*4.11+1.3)*0.15; }
void main() {
    float time=GameTime*1200.0;
    float seed=tint.b*63.0;
    float y=uv.y;
    float sway=(noise(vec2(y*3.6-time*2.9,seed))-.5)*(.15+y*.65);
    float x=(uv.x-.5+sway)*2.0;
    vec2 flow=vec2(x*4.7+seed,y*5.7-time*6.3);
    float n=fbm(flow+vec2(fbm(flow*.75+4.0)*2.1,0));
    float envelope=1.0-pow(abs(x),.8)*(1.25+y*1.2)-y*.88;
    float density=envelope+(n-.5)*1.45;
    float mask=smoothstep(-.02,.35,density)*smoothstep(1.0,.72,y)*smoothstep(0.0,RootSoftness,y);
    float core=smoothstep(.32,.88,density)*(1.0-y*.8);
    vec3 color=fireRadiance(core);
    float halo=exp(-x*x*5.0-y*7.0)*.15*smoothstep(0.0,RootSoftness,y);
    fragColor=vec4(color*(mask+halo)*tint.a,(mask+halo)*tint.a);
    if(mask+halo<.004) discard;
}
