#!/usr/bin/env python3
"""Connect QEMU's Android SDL window to the bundled gfxstream backend."""

from pathlib import Path
import sys


INCLUDE_MARKER = '#include "sysemu/sysemu.h"\n'
FUNCTION_MARKER = 'static void sdl_update_caption(struct sdl2_console *scon);\n'
CREATE_MARKER = '''    if (scon->opengl) {
        scon->winctx = SDL_GL_GetCurrentContext();
    }
    sdl_update_caption(scon);
'''
CREATE_REPLACEMENT = '''    if (scon->opengl) {
        scon->winctx = SDL_GL_GetCurrentContext();
    }
    sdl_update_caption(scon);
    sdl2_setup_gfxstream_window(scon);
'''
RESIZE_MARKER = '''    SDL_SetWindowSize(scon->real_window,
                      surface_width(scon->surface),
                      surface_height(scon->surface));
'''
RESIZE_REPLACEMENT = RESIZE_MARKER + '    sdl2_setup_gfxstream_window(scon);\n'

DECLARATION = '''
#ifdef CONFIG_STREAM_RENDERER
extern void gsi_gfxstream_setup_window(void* native_window,
                                       int32_t window_x, int32_t window_y,
                                       int32_t window_width, int32_t window_height,
                                       int32_t fb_width, int32_t fb_height);
#endif
'''

HELPER = '''
static void sdl2_setup_gfxstream_window(struct sdl2_console *scon)
{
#ifdef CONFIG_STREAM_RENDERER
    SDL_SysWMinfo wm_info;

    if (!scon->real_window) {
        return;
    }
    SDL_VERSION(&wm_info.version);
    if (SDL_GetWindowWMInfo(scon->real_window, &wm_info) &&
        wm_info.subsystem == SDL_SYSWM_ANDROID &&
        wm_info.info.android.window != NULL) {
        int width = surface_width(scon->surface);
        int height = surface_height(scon->surface);
        gsi_gfxstream_setup_window(wm_info.info.android.window,
                                    0, 0, width, height, width, height);
    }
#else
    (void)scon;
#endif
}
'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} QEMU_SDL2_C")

    path = Path(sys.argv[1])
    text = path.read_text()
    if "sdl2_setup_gfxstream_window" not in text:
        if INCLUDE_MARKER not in text or FUNCTION_MARKER not in text:
            raise SystemExit("QEMU SDL2 insertion points were not found")
        text = text.replace(INCLUDE_MARKER, INCLUDE_MARKER + DECLARATION, 1)
        text = text.replace(FUNCTION_MARKER, FUNCTION_MARKER + HELPER, 1)
    if CREATE_MARKER not in text:
        raise SystemExit("QEMU SDL2 window creation block was not found")
    if "sdl2_setup_gfxstream_window(scon);" not in text:
        text = text.replace(CREATE_MARKER, CREATE_REPLACEMENT, 1)
        if RESIZE_MARKER not in text:
            raise SystemExit("QEMU SDL2 resize block was not found")
        text = text.replace(RESIZE_MARKER, RESIZE_REPLACEMENT, 1)
    path.write_text(text)


if __name__ == "__main__":
    main()
