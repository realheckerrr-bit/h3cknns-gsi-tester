#!/usr/bin/env python3
"""Patch Android QEMU's gfxstream loader for the bundled app frontend."""

from pathlib import Path
import sys


DECLARATION = '''
extern "C" const GoldfishPipeServiceOps* gsi_android_pipe_init(void* backend);
'''

OLD = '''    ret = 0;
    s_render.stream_renderer_set_service_ops(goldfish_pipe_get_service_ops());'''
NEW = '''    pipe_ops = gsi_android_pipe_init(rendererSo);
    if (pipe_ops == NULL) {
        fprintf(stderr, "Could not initialize AndroidPipe service bridge\\n");
        goto BAD_EXIT;
    }
    goldfish_pipe_set_service_ops(pipe_ops);
    s_render.stream_renderer_set_service_ops(pipe_ops);'''

WINDOW_FUNCTIONS = '''
static decltype(gfxstream_backend_setup_window)* s_gfxstream_setup_window = nullptr;
static void* s_gsi_native_window = nullptr;
static int32_t s_gsi_window_x = 0;
static int32_t s_gsi_window_y = 0;
static int32_t s_gsi_window_width = 0;
static int32_t s_gsi_window_height = 0;
static int32_t s_gsi_fb_width = 0;
static int32_t s_gsi_fb_height = 0;

static void gsi_apply_gfxstream_window() {
    if (s_gfxstream_setup_window != nullptr && s_gsi_native_window != nullptr) {
        s_gfxstream_setup_window(s_gsi_native_window, s_gsi_window_x, s_gsi_window_y,
                                 s_gsi_window_width, s_gsi_window_height,
                                 s_gsi_fb_width, s_gsi_fb_height);
    }
}

extern "C" void gsi_gfxstream_setup_window(void* native_window,
                                             int32_t window_x, int32_t window_y,
                                             int32_t window_width, int32_t window_height,
                                             int32_t fb_width, int32_t fb_height) {
    s_gsi_native_window = native_window;
    s_gsi_window_x = window_x;
    s_gsi_window_y = window_y;
    s_gsi_window_width = window_width;
    s_gsi_window_height = window_height;
    s_gsi_fb_width = fb_width;
    s_gsi_fb_height = fb_height;
    gsi_apply_gfxstream_window();
}
'''

INIT_MARKER = '''VG_EXPORT int stream_renderer_init(struct stream_renderer_param* stream_renderer_params,
                                   uint64_t num_params) {
    return s_render.stream_renderer_init(stream_renderer_params, num_params);
}'''

INIT_REPLACEMENT = '''VG_EXPORT int stream_renderer_init(struct stream_renderer_param* stream_renderer_params,
                                   uint64_t num_params) {
    int ret = s_render.stream_renderer_init(stream_renderer_params, num_params);
    if (ret == 0) {
        gsi_apply_gfxstream_window();
    }
    return ret;
}'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} QEMU_GFXSTREAM_WRAPPER_CPP")

    path = Path(sys.argv[1])
    text = path.read_text()
    if "gsi_android_pipe_init" not in text:
        marker = '#define RENDERER_LIB_NAME "libgfxstream_backend"\n'
        if marker not in text:
            raise SystemExit("gfxstream wrapper insertion point was not found")
        text = text.replace(marker, marker + DECLARATION, 1)
    if "gsi_gfxstream_setup_window" not in text:
        marker = 'static struct stream_renderer_funcs s_render;\n'
        if marker not in text:
            raise SystemExit("gfxstream function table was not found")
        text = text.replace(marker, marker + WINDOW_FUNCTIONS, 1)
    if OLD not in text:
        raise SystemExit("gfxstream service-op call was not found")
    declaration = "    const GoldfishPipeServiceOps* pipe_ops = NULL;\n"
    init_marker = "    int ret = -1;\n"
    if declaration not in text:
        if init_marker not in text:
            raise SystemExit("gfxstream loader declarations were not found")
        text = text.replace(init_marker, init_marker + declaration, 1)
    updated = text.replace(OLD, NEW, 1)
    setup_marker = '    s_render.stream_renderer_set_service_ops(pipe_ops);\n'
    setup_code = '''    s_render.stream_renderer_set_service_ops(pipe_ops);
    symbol = dlsym(rendererSo, "gfxstream_backend_setup_window");
    if (symbol == NULL) {
        fprintf(stderr, "Could not find gfxstream_backend_setup_window\\n");
        goto BAD_EXIT;
    }
    s_gfxstream_setup_window = reinterpret_cast<decltype(gfxstream_backend_setup_window)*>(symbol);
    ret = 0;
'''
    if '"gfxstream_backend_setup_window"' not in updated:
        if setup_marker not in updated:
            raise SystemExit("gfxstream service-op setup was not found")
        updated = updated.replace(setup_marker, setup_code, 1)
    if INIT_MARKER in updated:
        updated = updated.replace(INIT_MARKER, INIT_REPLACEMENT, 1)
    path.write_text(updated)


if __name__ == "__main__":
    main()
