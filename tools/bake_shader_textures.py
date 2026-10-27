"""Bake the original GLSL into shader-pack-compatible atlases (macOS, Python + Pillow).

Run from the project root. Baking uses a separate offscreen OpenGL context, never
Minecraft's active context. Assets are checked in; building the mod needs no Python.
"""
from pathlib import Path
import ctypes as C
import re
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / 'src/main/resources/assets/surfacefire/shaders/core'
INCLUDES = SHADERS.parent / 'include'
OUTPUT = ROOT / 'src/main/resources/assets/surfacefire/textures/effect'
FRAMES, COLUMNS, RATE, BLEND = 128, 8, 60, 16
GL = C.CDLL('/System/Library/Frameworks/OpenGL.framework/OpenGL')

def function(name, result, *args):
    fn = getattr(GL, name); fn.restype = result; fn.argtypes = args; return fn

I, U, F, P = C.c_int, C.c_uint, C.c_float, C.c_void_p
choose = function('CGLChoosePixelFormat', I, C.POINTER(I), C.POINTER(P), C.POINTER(I))
create = function('CGLCreateContext', I, P, P, C.POINTER(P))
current = function('CGLSetCurrentContext', I, P)
fmt, ctx, count = P(), P(), I()
attributes = (I * 4)(99, 0x3200, 73, 0)
assert choose(attributes, C.byref(fmt), C.byref(count)) == 0
assert create(fmt, None, C.byref(ctx)) == 0
assert current(ctx) == 0
shader_create = function('glCreateShader', U, U)
shader_source = function('glShaderSource', None, U, I, C.POINTER(C.c_char_p), C.POINTER(I))
compile_shader = function('glCompileShader', None, U)
shader_status = function('glGetShaderiv', None, U, U, C.POINTER(I))
shader_log = function('glGetShaderInfoLog', None, U, I, C.POINTER(I), C.c_char_p)
program_create = function('glCreateProgram', U)
attach = function('glAttachShader', None, U, U)
link = function('glLinkProgram', None, U)
program_status = function('glGetProgramiv', None, U, U, C.POINTER(I))
use = function('glUseProgram', None, U)
uniform = function('glGetUniformLocation', I, U, C.c_char_p)
f1 = function('glUniform1f', None, I, F)
f4 = function('glUniform4f', None, I, F, F, F, F)
vao = U(); function('glGenVertexArrays', None, I, C.POINTER(U))(1, C.byref(vao))
function('glBindVertexArray', None, U)(vao)
fbo, texture = U(), U()
function('glGenFramebuffers', None, I, C.POINTER(U))(1, C.byref(fbo))
function('glBindFramebuffer', None, U, U)(0x8D40, fbo)
function('glGenTextures', None, I, C.POINTER(U))(1, C.byref(texture))
function('glBindTexture', None, U, U)(0x0DE1, texture)
tex_image = function('glTexImage2D', None, U, I, I, I, I, I, U, U, P)
function('glFramebufferTexture2D', None, U, U, U, U, I)(0x8D40, 0x8CE0, 0x0DE1, texture, 0)
viewport = function('glViewport', None, I, I, I, I)
clear_color = function('glClearColor', None, F, F, F, F)
clear = function('glClear', None, U)
draw = function('glDrawArrays', None, U, I, I)
read = function('glReadPixels', None, I, I, I, I, U, U, P)
VERTEX = '''#version 150
out vec4 tint; out vec2 uv; uniform vec4 BakeTint; uniform float BakeScale;
void main() { vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); uv=p*BakeScale;
tint=BakeTint; gl_Position=vec4(p*2.0-1.0,0,1); }
'''

def shader(kind, source):
    obj = shader_create(kind); data = C.c_char_p(source.encode()); shader_source(obj, 1, C.byref(data), None); compile_shader(obj)
    ok = I(); shader_status(obj, 0x8B81, C.byref(ok))
    if not ok.value:
        message = C.create_string_buffer(8192); shader_log(obj, len(message), None, message); raise RuntimeError(message.value.decode())
    return obj

def program(fragment):
    obj = program_create(); attach(obj, shader(0x8B31, VERTEX)); attach(obj, shader(0x8B30, fragment)); link(obj)
    ok = I(); program_status(obj, 0x8B82, C.byref(ok)); assert ok.value
    return obj

def render(obj, size, seconds, tint, scale=1, root_softness=.025):
    tex_image(0x0DE1, 0, 0x8058, size, size, 0, 0x1908, 0x1401, None)
    viewport(0, 0, size, size); clear_color(0, 0, 0, 0); clear(0x4000); use(obj)
    f1(uniform(obj,b'GameTime'), seconds/1200); f1(uniform(obj,b'BakeScale'), scale); f4(uniform(obj,b'BakeTint'), *tint)
    f1(uniform(obj,b'RootSoftness'),root_softness)
    draw(4,0,3); pixels=(C.c_ubyte*(size*size*4))(); read(0,0,size,size,0x1908,0x1401,pixels)
    return Image.frombytes('RGBA',(size,size),bytes(pixels))

def atlas(name, size):
    source_name='flame' if name=='surface_flame' else name
    source=(SHADERS/(source_name+'.fsh')).read_text()
    source=re.sub(r'#moj_import <surfacefire:([a-z_]+\.glsl)>',
                  lambda match: (INCLUDES / match.group(1)).read_text(), source)
    if name != 'smoke':
        # Entity blending multiplies alpha itself, unlike the additive native pass.
        source=source.replace('color*(mask+halo)*tint.a,(mask+halo)*tint.a','color,(mask+halo)*tint.a')
    obj=program(source); tint=(.37,.35,.32,1) if name=='smoke' else (1,.31,.31,1)
    frames=[render(obj,size,i/RATE,tint,root_softness=.22 if name=='surface_flame' else .025) for i in range(FRAMES+BLEND)]
    sheet=Image.new('RGBA',(size*COLUMNS,size*(FRAMES//COLUMNS)))
    for i in range(FRAMES):
        frame=frames[i+BLEND]
        if i>=FRAMES-BLEND:
            j=i-(FRAMES-BLEND);frame=Image.blend(frame,frames[j],(j+1)/BLEND)
        sheet.paste(frame,((i%COLUMNS)*size,(i//COLUMNS)*size))
    sheet.save(OUTPUT/(name+'.png'))
    print(name, sheet.size, flush=True)

OUTPUT.mkdir(parents=True,exist_ok=True)
selected=set(sys.argv[1:])
for name,size in [('torch_flame',256),('flame',128),('surface_flame',128),('smoke',128)]:
    if not selected or name in selected:atlas(name,size)
source=(SHADERS/'ground.fsh').read_text()
for name,tint in [('char',(1,0,0,0)),('dry',(0,0,0,1))]:
    if not selected or name in selected:render(program(source.replace('fragColor=vec4(glow,coverage);', 'fragColor=vec4(coal,coverage);' if name=='char' else 'fragColor=vec4(mix(vec3(.28,.18,.045),vec3(.56,.40,.12),ash),tint.a*.77);')),1024,0,tint,16).save(OUTPUT/(name+'.png'))
for name,alpha in [('ember','fleck'),('heat','noise(uv*38.0)*.6+.4')]:
    if selected and name not in selected:continue
    fragment=source.replace('fragColor=vec4(glow,coverage);','fragColor=vec4(1.0,.48,.08,'+alpha+');')
    render(program(fragment),1024,0,(1,0,0,0),16).save(OUTPUT/(name+'.png'))
for path in OUTPUT.glob('*.png'):
    (OUTPUT/(path.name+'.mcmeta')).write_text('{"texture":{"blur":true,"clamp":false}}\n')
function('CGLDestroyContext',I,P)(ctx);function('CGLDestroyPixelFormat',I,P)(fmt)
