#!/usr/bin/env python3
"""Drive the real APK flow on an Android emulator through adb/uiautomator."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.realheckerrr.gsilab"
DISPLAY_ACTIVITY = "org.libsdl.app.GsiSDLActivity"
UI_DUMP_PATH = "/data/local/tmp/gsi-tester-window.xml"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str, check: bool = True, timeout: int = 30) -> str:
    result = subprocess.run(
        ["adb", *args],
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"adb {' '.join(args)} failed ({result.returncode}): "
            f"{result.stdout.strip()} {result.stderr.strip()}"
        )
    return result.stdout


def dump_ui() -> ET.Element:
    # Prefer stdout so the smoke test does not depend on the emulator's
    # emulated /sdcard or on uiautomator's file-namespace behavior.
    output = adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=45)
    xml_start = output.find("<?xml")
    if xml_start >= 0:
        xml_end = output.find("</hierarchy>", xml_start)
        if xml_end >= 0:
            xml_end += len("</hierarchy>")
            return ET.fromstring(output[xml_start:xml_end])
    # Keep a shell-writable fallback for emulator images whose uiautomator
    # build refuses /dev/tty.
    adb("shell", "uiautomator", "dump", UI_DUMP_PATH, timeout=45)
    return ET.fromstring(adb("shell", "cat", UI_DUMP_PATH))


def visible_nodes(root: ET.Element):
    for node in root.iter("node"):
        bounds = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        if node.attrib.get("visible-to-user", "true") != "true":
            continue
        x1, y1, x2, y2 = (int(value) for value in bounds.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        yield node, ((x1 + x2) // 2, (y1 + y2) // 2)


def find_text(candidates: list[str], enabled: bool = False):
    root = dump_ui()
    lowered = [candidate.lower() for candidate in candidates]
    for node, center in visible_nodes(root):
        values = [node.attrib.get("text", ""), node.attrib.get("content-desc", "")]
        value = next((item for item in values if item), "")
        if not any(candidate in value.lower() for candidate in lowered):
            continue
        if enabled and node.attrib.get("enabled", "true") != "true":
            continue
        return node, center
    return None


def click(candidates: list[str], timeout: int = 60, enabled: bool = False) -> None:
    deadline = time.time() + timeout
    last = ", ".join(candidates)
    while time.time() < deadline:
        match = find_text(candidates, enabled=enabled)
        if match is not None:
            _, (x, y) = match
            adb("shell", "input", "tap", str(x), str(y))
            return
        # MainActivity is a long Material page. Bring lower controls into
        # view while preserving the same text-driven selector.
        adb("shell", "input", "swipe", "700", "1500", "700", "500", "350")
        time.sleep(1)
    raise RuntimeError(f"Could not find visible UI control containing: {last}")


def click_picker_file(name: str, timeout: int = 90) -> None:
    # ACTION_OPEN_DOCUMENT commonly starts in Recent. The pushed test assets
    # live in Download, so enter that provider directory first if present.
    deadline = time.time() + timeout
    entered_downloads = False
    while time.time() < deadline:
        match = find_text([name])
        if match is not None:
            _, (x, y) = match
            adb("shell", "input", "tap", str(x), str(y))
            return
        if not entered_downloads:
            match = find_text(["Downloads", "Download"])
            if match is not None:
                _, (x, y) = match
                adb("shell", "input", "tap", str(x), str(y))
                entered_downloads = True
                time.sleep(2)
                continue
        adb("shell", "input", "swipe", "700", "1500", "700", "500", "350")
        time.sleep(1)
    raise RuntimeError(f"Could not select {name} from the Android document picker")


def wait_for_console_marker(timeout: int) -> str:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        result = adb(
            "shell",
            "run-as",
            PACKAGE,
            "sh",
            "-c",
            "cat files/vm-session/console.log 2>/dev/null || true",
            check=False,
        )
        last = result
        lower = result.lower()
        if (
            "sys.boot_completed" in lower
            or "boot animation stopped" in lower
            or "starting service .zygote" in lower
            or "android runtime started" in lower
            or ("class_start main" in lower and "succeeded" in lower)
        ):
            return result
        time.sleep(2)
    raise RuntimeError("Android boot marker was not observed in the APK console log.\n" + last[-6000:])


def foreground_activity() -> str:
    return adb("shell", "dumpsys", "activity", "activities", check=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gsi", required=True)
    parser.add_argument("--guest", required=True)
    parser.add_argument(
        "--private-staged",
        action="store_true",
        help="Use debug-app private CI staging instead of the public DocumentsUI picker",
    )
    parser.add_argument("--boot-timeout", type=int, default=600)
    parser.add_argument("--screenshot", default="guest-screen.png")
    args = parser.parse_args()

    gsi_name = os.path.basename(args.gsi)
    guest_name = os.path.basename(args.guest)
    if args.private_staged:
        adb(
            "shell",
            "am",
            "start",
            "-n",
            f"{PACKAGE}/.MainActivity",
            "--ez",
            "ci_private_stage",
            "true",
            "--es",
            "ci_gsi_name",
            gsi_name,
            "--es",
            "ci_guest_name",
            guest_name,
        )
    else:
        adb("shell", "monkey", "-p", PACKAGE, "1")
        click(["Select GSI image or ZIP"])
        click_picker_file(gsi_name)
    click(["Analyze image"], timeout=180, enabled=True)
    if not args.private_staged:
        click(["Select guest bundle ZIP"], timeout=180)
        click_picker_file(guest_name)
    click(["Start VM"], timeout=300, enabled=True)

    console = wait_for_console_marker(args.boot_timeout)
    activity = foreground_activity()
    if DISPLAY_ACTIVITY not in activity:
        raise RuntimeError("Android serial booted, but the guest display Activity is not foreground")

    with open(args.screenshot, "wb") as output:
        result = subprocess.run(["adb", "exec-out", "screencap", "-p"], check=False, stdout=output)
    if result.returncode != 0 or os.path.getsize(args.screenshot) == 0:
        raise RuntimeError("Guest display screenshot was empty")

    print("Guest display Activity is foreground and Android boot marker was observed.")
    print(console[-6000:])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        print(f"UI smoke failed: {error}", file=sys.stderr)
        raise SystemExit(1)
