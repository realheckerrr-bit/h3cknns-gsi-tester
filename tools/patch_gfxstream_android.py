#!/usr/bin/env python3
"""Apply the small Android-only CMake fixes needed by standalone Gfxstream."""

from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} GFXSTREAM_ROOT")

    root = Path(sys.argv[1])
    replace_once(
        root / "CMakeLists.txt",
        "if(UNIX AND NOT APPLE AND NOT QNX)",
        "if(UNIX AND NOT APPLE AND NOT QNX AND NOT ANDROID)",
    )
    replace_once(
        root / "CMakeLists.txt",
        "add_compile_definitions(VK_USE_PLATFORM_METAL_EXT)\nelseif(QNX)",
        "add_compile_definitions(VK_USE_PLATFORM_METAL_EXT)\n"
        "elseif(ANDROID)\n"
        "    add_compile_definitions(VK_USE_PLATFORM_ANDROID_KHR)\n"
        "elseif(QNX)",
    )
    replace_once(
        root / "host" / "CMakeLists.txt",
        "elseif (QNX)\n"
        "    set(stream-server-core-platform-sources NativeSubWindow_qnx.cpp)\n"
        "else()",
        "elseif (QNX)\n"
        "    set(stream-server-core-platform-sources NativeSubWindow_qnx.cpp)\n"
        "elseif (ANDROID)\n"
        "    set(stream-server-core-platform-sources NativeSubWindow_android.cpp)\n"
        "else()",
    )
    egl = root / "host" / "gl" / "glestranslator" / "EGL" / "CMakeLists.txt"
    replace_once(
        egl,
        "elseif (QNX)\n"
        "    add_library(\n"
        "        EGL_translator_static\n"
        "        ${egl-translator-common-sources}\n"
        "        ${egl-translator-qnx-sources})",
        "elseif (ANDROID)\n"
        "    add_library(\n"
        "        EGL_translator_static\n"
        "        ${egl-translator-common-sources}\n"
        "        ${egl-translator-qnx-sources})\n"
        "elseif (QNX)\n"
        "    add_library(\n"
        "        EGL_translator_static\n"
        "        ${egl-translator-common-sources}\n"
        "        ${egl-translator-qnx-sources})",
    )
    replace_once(
        egl,
        "elseif (QNX)\n"
        "    target_link_libraries(EGL_translator_static PUBLIC \"-lscreen -lregex -lEGL -lGLESv2\")\n"
        "else()",
        "elseif (ANDROID)\n"
        "    target_link_libraries(EGL_translator_static PUBLIC \"-ldl -llog\")\n"
        "elseif (QNX)\n"
        "    target_link_libraries(EGL_translator_static PUBLIC \"-lscreen -lregex -lEGL -lGLESv2\")\n"
        "else()",
    )
    replace_once(
        root / "host" / "vulkan" / "VkDecoderInternalStructs.h",
        "        mAddr = aligned_alloc(alignment, size);",
        "        mAddr = nullptr;\n"
        "        if (posix_memalign(&mAddr, alignment, size) != 0) mAddr = nullptr;",
    )
    host = root / "host" / "CMakeLists.txt"
    host_text = host.read_text()
    if "target_link_libraries(gfxstream_backend PUBLIC android)" not in host_text:
        host.write_text(host_text + "\nif(ANDROID)\n"
                        "    target_link_libraries(gfxstream_backend PUBLIC android)\n"
                        "endif()\n")


if __name__ == "__main__":
    main()
