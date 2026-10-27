#version 150
#moj_import <surfacefire:fire_color.glsl>
uniform float GameTime;
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
    float wave=sin(time*18.4+seed-y*7.0)*.08+sin(time*27.1+seed*.63-y*11.0)*.025;
    float sway=wave*y+(noise(vec2(y*3.6-time*4.6,seed))-.5)*y*.28;
    float x=(uv.x-.5+sway)*2.0;
    vec2 flow=vec2(x*3.2+seed,y*4.8-time*9.6);
    float n=fbm(flow+vec2(fbm(flow*.7+4.0)*1.4,0));
    float envelope=1.10-pow(abs(x),1.25)*(1.22+y*.55)-y*.94;
    float density=envelope+(n-.5)*mix(.32,1.10,y);
    float mask=smoothstep(-.08,.23,density)*smoothstep(1.0,.76,y)*smoothstep(0.0,.015,y);
    float core=smoothstep(.32,.88,density)*(1.0-y*.8);
    vec3 color=fireRadiance(core);
    float halo=exp(-x*x*5.0-y*7.0)*.15;
    fragColor=vec4(color*(mask+halo)*tint.a,(mask+halo)*tint.a);
    if(mask+halo<.004) discard;
}
