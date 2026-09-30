#!/usr/bin/env python3
"""Patch Android QEMU's gfxstream loader for the bundled pipe bridge."""

from pathlib import Path
import sys


DECLARATION = '''
extern "C" const GoldfishPipeServiceOps* gsi_android_pipe_init(void* backend);
'''

OLD = '    s_render.stream_renderer_set_service_ops(goldfish_pipe_get_service_ops());'
NEW = '''    const GoldfishPipeServiceOps* pipe_ops = gsi_android_pipe_init(rendererSo);
    if (pipe_ops == NULL) {
        fprintf(stderr, "Could not initialize AndroidPipe service bridge\\n");
        return -1;
    }
    goldfish_pipe_set_service_ops(pipe_ops);
    s_render.stream_renderer_set_service_ops(pipe_ops);'''


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
    if OLD not in text:
        raise SystemExit("gfxstream service-op call was not found")
    path.write_text(text.replace(OLD, NEW, 1))


if __name__ == "__main__":
    main()
