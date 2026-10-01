#!/usr/bin/env python3
"""Make Android QEMU initialize gfxstream when entered from the APK."""

from pathlib import Path
import sys


OLD = '''    struct stream_renderer_param params[] = {
        {STREAM_RENDERER_PARAM_USER_DATA, (uintptr_t)(g)},
        {STREAM_RENDERER_PARAM_RENDERER_FLAGS, 0},
        {STREAM_RENDERER_PARAM_FENCE_CALLBACK, (uintptr_t)(&stream_renderer_write_fence)},
        {STREAM_RENDERER_SKIP_OPENGLES_INIT, 1},
    };
    ret = stream_renderer_init(&params[0], 4);'''

NEW = '''    struct stream_renderer_param params[] = {
        {STREAM_RENDERER_PARAM_USER_DATA, (uintptr_t)(g)},
        {STREAM_RENDERER_PARAM_RENDERER_FLAGS, 0},
        {STREAM_RENDERER_PARAM_FENCE_CALLBACK, (uintptr_t)(&stream_renderer_write_fence)},
        {STREAM_RENDERER_PARAM_WIN0_WIDTH, 1080},
        {STREAM_RENDERER_PARAM_WIN0_HEIGHT, 1920},
    };
    ret = stream_renderer_init(&params[0], 5);'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} virtio-gpu-3d.c")
    path = Path(sys.argv[1])
    text = path.read_text()
    if OLD not in text:
        raise SystemExit("direct gfxstream init block was not found")
    path.write_text(text.replace(OLD, NEW, 1))


if __name__ == "__main__":
    main()
