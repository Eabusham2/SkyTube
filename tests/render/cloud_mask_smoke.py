"""Render the cloud fragment shader on a 32x32 EGL pbuffer and inspect color and depth.

Requires Linux, Python 3, libEGL, libGL, and a desktop OpenGL 3.3 driver.
Run with --client-jar pointing to your own Minecraft 1.21.11 client JAR.
"""
import argparse
import ctypes as ct
import os
from pathlib import Path
import re
import struct
import zipfile

INT = ct.c_int
UINT = ct.c_uint
FLOAT = ct.c_float
PTR = ct.c_void_p
STRING = ct.c_char_p
SIZE = 32


def bind(library, name, result_type, *argument_types):
    function = getattr(library, name)
    function.restype = result_type
    function.argtypes = argument_types
    return function


def attributes(*values):
    return (INT * (len(values) + 1))(*values, 0x3038)  # EGL_NONE


class Graphics:
    def __init__(self):
        os.environ.setdefault("EGL_PLATFORM", "surfaceless")
        os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
        egl = ct.CDLL("libEGL.so.1")
        self.gl = ct.CDLL("libGL.so.1")

        def call(name, result, types, *args):
            value = bind(egl, name, result, *types)(*args)
            if not value:
                error = bind(egl, "eglGetError", INT)()
                raise RuntimeError(f"{name} failed with EGL error 0x{error:04x}")
            return value

        display = call("eglGetDisplay", PTR, [PTR], None)
        major, minor = INT(), INT()
        call("eglInitialize", UINT, [PTR, PTR, PTR], display, ct.byref(major), ct.byref(minor))
        call("eglBindAPI", UINT, [UINT], 0x30A2)  # EGL_OPENGL_API
        # Request an RGBA8 pbuffer with a depth attachment.
        config, count = PTR(), INT()
        wanted = attributes(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 24)
        call("eglChooseConfig", UINT, [PTR, PTR, PTR, INT, PTR],
             display, wanted, ct.byref(config), 1, ct.byref(count))
        if count.value == 0:
            raise RuntimeError("EGL did not provide the requested RGBA/depth pbuffer configuration")
        surface = call("eglCreatePbufferSurface", PTR, [PTR, PTR, PTR],
                       display, config, attributes(0x3057, SIZE, 0x3056, SIZE))
        context = call("eglCreateContext", PTR, [PTR, PTR, PTR, PTR],
                       display, config, None, attributes(0x3098, 3, 0x30FB, 3))
        call("eglMakeCurrent", UINT, [PTR, PTR, PTR, PTR], display, surface, surface, context)
        driver = self.function("glGetString", STRING, UINT)
        print("Renderer:", driver(0x1F01).decode(), driver(0x1F02).decode())

    def function(self, name, result, *types):
        return bind(self.gl, name, result, *types)

    def check_shader(self, handle, *, program):
        target = "Program" if program else "Shader"
        status = INT()
        flag = 0x8B82 if program else 0x8B81  # GL_LINK_STATUS / GL_COMPILE_STATUS
        self.function(f"glGet{target}iv", None, UINT, UINT, PTR)(handle, flag, ct.byref(status))
        if status.value == 0:
            log = ct.create_string_buffer(8192)
            self.function(f"glGet{target}InfoLog", None, UINT, INT, PTR, PTR)(handle, len(log), None, log)
            raise RuntimeError(log.value.decode(errors="replace"))

    def compile(self, kind, source):
        handle = self.function("glCreateShader", UINT, UINT)(kind)
        encoded = STRING(source.encode())
        self.function("glShaderSource", None, UINT, INT, PTR, PTR)(handle, 1, ct.byref(encoded), None)
        self.function("glCompileShader", None, UINT)(handle)
        self.check_shader(handle, program=False)
        return handle

    def link(self, vertex, fragment):
        program = self.function("glCreateProgram", UINT)()
        for kind, source in [(0x8B31, vertex), (0x8B30, fragment)]:
            shader = self.compile(kind, source)
            self.function("glAttachShader", None, UINT, UINT)(program, shader)
            self.function("glDeleteShader", None, UINT)(shader)
        self.function("glLinkProgram", None, UINT)(program)
        self.check_shader(program, program=True)
        return program

    def uniform_block(self, program, name, binding, values):
        index = self.function("glGetUniformBlockIndex", UINT, UINT, STRING)(program, name.encode())
        if index == 0xFFFFFFFF:
            raise AssertionError(f"Uniform block {name} missing")
        self.function("glUniformBlockBinding", None, UINT, UINT, UINT)(program, index, binding)
        buffer = UINT()
        self.function("glGenBuffers", None, INT, PTR)(1, ct.byref(buffer))
        target = 0x8A11  # GL_UNIFORM_BUFFER
        self.function("glBindBuffer", None, UINT, UINT)(target, buffer)
        data = struct.pack(f"={len(values)}f", *values)
        self.function("glBufferData", None, UINT, ct.c_ssize_t, PTR, UINT)(target, len(data), data, 0x88E4)
        self.function("glBindBufferBase", None, UINT, UINT, UINT)(target, binding, buffer)

    def pixel(self, x, y):
        rgba = (ct.c_ubyte * 4)()
        depth = FLOAT()
        read = self.function("glReadPixels", None, INT, INT, INT, INT, UINT, UINT, PTR)
        read(x, y, 1, 1, 0x1908, 0x1401, rgba)  # GL_RGBA, GL_UNSIGNED_BYTE
        read(x, y, 1, 1, 0x1902, 0x1406, ct.byref(depth))  # GL_DEPTH_COMPONENT, GL_FLOAT
        return tuple(rgba[:3]), round(depth.value, 3)


def shader_source(source, client_jar):
    """Expand Mojang includes and select the highest included GLSL version."""
    versions = []

    def without_version(match):
        versions.append(int(match.group(1)))
        return ""

    def expand(text):
        text = re.sub(r"^#version (\d+)[^\n]*", without_version, text, flags=re.MULTILINE)

        def include(match):
            relative = "assets/minecraft/shaders/include/" + match.group(1)
            return expand(client_jar.read(relative).decode())

        return re.sub(r"^#moj_import <minecraft:([^>]+)>", include, text, flags=re.MULTILINE)

    body = expand(source)
    return f"#version {max(versions)}\n" + body


def check_pixels(gl, fragment):
    # Supply a fixed red cloud over a blue background. The fragment shader under test stays intact.
    vertex = """#version 330
out float vertexDistance;
out vec4 vertexColor;
void main() {
    const vec2 corners[3] = vec2[3](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
    gl_Position = vec4(corners[gl_VertexID], 0, 1);
    vertexColor = vec4(1, 0, 0, 1);
    vertexDistance = 0;
}
"""
    program = gl.link(vertex, fragment)
    gl.function("glUseProgram", None, UINT)(program)
    gl.uniform_block(program, "Fog", 0, [0] * 9 + [300, 0, 0])
    vao = UINT()
    gl.function("glGenVertexArrays", None, INT, PTR)(1, ct.byref(vao))
    gl.function("glBindVertexArray", None, UINT)(vao)
    gl.function("glViewport", None, INT, INT, INT, INT)(0, 0, SIZE, SIZE)
    enable = gl.function("glEnable", None, UINT)
    enable(0x0B71)  # GL_DEPTH_TEST
    enable(0x0BE2)  # GL_BLEND
    gl.function("glBlendFunc", None, UINT, UINT)(0x0302, 0x0303)
    gl.function("glClearColor", None, FLOAT, FLOAT, FLOAT, FLOAT)(0, 0, 1, 1)
    edges = [1, 0, .5, 0, -1, 0, .5, 0, 0, 1, .5, 0, 0, -1, .5, 0] + [0] * 24
    for opacity in (0, .5, 1):
        gl.uniform_block(program, "SkyTubeMask", 1, [2 / SIZE, 2 / SIZE, opacity, 4] + edges)
        gl.function("glClear", None, UINT)(0x4000 | 0x0100)
        gl.function("glDrawArrays", None, UINT, INT, INT)(4, 0, 3)  # GL_TRIANGLES
        inside = gl.pixel(SIZE // 2, SIZE // 2)
        outside = gl.pixel(2, 2)
        print(f"Opacity {opacity:g}: center {inside}, outside {outside}")
        expected = ((255 * opacity, 0, 255 * (1 - opacity)), .5 if opacity else 1)
        if any(abs(a - b) > 1 for a, b in zip(inside[0], expected[0])) or inside[1] != expected[1]:
            raise AssertionError(f"Unexpected center pixel {inside} at opacity {opacity}")
        if outside != ((255, 0, 0), .5):
            raise AssertionError(f"Unexpected outside pixel {outside} at opacity {opacity}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-jar", required=True, type=Path)
    args = parser.parse_args()
    shader_dir = Path(__file__).resolve().parents[2] / "src/main/resources/assets/skytube/shaders/core"
    gl = Graphics()
    with zipfile.ZipFile(args.client_jar) as client_jar:
        fragment = shader_source((shader_dir / "masked_clouds.fsh").read_text(), client_jar)
        vanilla_vertex = client_jar.read("assets/minecraft/shaders/core/rendertype_clouds.vsh").decode()
        gl.link(shader_source(vanilla_vertex, client_jar), fragment)
        print("Linked the vanilla cloud vertex shader and SkyTube cloud fragment shader.")
        sky_vertex = shader_source((shader_dir / "sky_video.vsh").read_text(), client_jar)
        sky_fragment = client_jar.read("assets/minecraft/shaders/core/position_tex_color.fsh").decode()
        gl.link(sky_vertex, shader_source(sky_fragment, client_jar))
        print("Linked the SkyTube video vertex shader and vanilla texture/color fragment shader.")
    check_pixels(gl, fragment)


if __name__ == "__main__":
    main()
