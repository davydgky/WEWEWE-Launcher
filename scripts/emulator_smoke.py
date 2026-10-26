#!/usr/bin/env python3
"""Drives the launcher on an emulator like a person would and checks the main flows.

1. Installs the APK, makes it the default home app and grants the search permissions.
2. Swipes down on the home screen: the search must open with the keyboard.
3. Types an app name: the app must appear in the results.
4. Puts a picture with text on the device, simulates charging and runs the text recognition job:
   the picture must then be found by a word in it.
5. Checks for crashes and prints the latency measurements (not meaningful on a software-rendered
   emulator, but they show that the measurement works).
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.github.davydgky.launcher"
SHOTS = "screenshots"
failures = []


def adb(*args, check=True):
    result = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        print(result.stdout, result.stderr)
        raise RuntimeError(f"adb {' '.join(args)} failed")
    return result.stdout


def shell(command, check=True):
    return adb("shell", command, check=check)


def screen_size():
    match = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return int(match.group(1)), int(match.group(2))


def ui_texts():
    shell("uiautomator dump /sdcard/ui.xml", check=False)
    xml = shell("cat /sdcard/ui.xml", check=False)
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):])
    except (ValueError, ET.ParseError):
        return []
    return [node.get("text") for node in root.iter("node") if node.get("text")]


def wait_for_text(fragment, timeout=10):
    deadline = time.time() + timeout
    texts = []
    while time.time() < deadline:
        texts = ui_texts()
        if any(fragment.lower() in t.lower() for t in texts):
            return True
        time.sleep(0.5)
    print(f"  visible texts: {texts[:40]}")
    return False


def screenshot(name):
    """Saves a screenshot for the preview artifact."""
    os.makedirs(SHOTS, exist_ok=True)
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    with open(os.path.join(SHOTS, f"{name}.png"), "wb") as f:
        f.write(png)


def retry(command, attempts=6, wait=10):
    """Some system services are still busy right after boot."""
    for attempt in range(attempts):
        result = subprocess.run(["adb", "shell", command], capture_output=True, text=True)
        if result.returncode == 0 and "Exception" not in result.stdout + result.stderr:
            return result.stdout
        print(f"  retrying '{command}': {(result.stdout + result.stderr).strip()[:200]}")
        time.sleep(wait)
    raise RuntimeError(f"'{command}' kept failing")


def keyboard_shown():
    return "mInputShown=true" in shell("dumpsys input_method", check=False)


def check(condition, message):
    print(("PASS " if condition else "FAIL ") + message)
    if not condition:
        failures.append(message)


def go_home():
    shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    time.sleep(1.5)


def open_search(width, height):
    shell(f"input swipe {width // 2} {int(height * 0.25)} {width // 2} {int(height * 0.6)} 250")
    time.sleep(1.5)


def make_picture(path, lines):
    from PIL import Image, ImageDraw, ImageFont
    image = Image.new("RGB", (1200, 800), "white")
    draw = ImageDraw.Draw(image)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 72)
    except OSError:
        font = ImageFont.load_default(size=72)
    for i, line in enumerate(lines):
        draw.text((60, 120 + i * 120), line, fill="black", font=font)
    image.save(path, "JPEG", quality=92)


def main(apk):
    adb("wait-for-device")
    shell("settings put global window_animation_scale 1")
    adb("install", "-r", "-g", apk)
    time.sleep(20)  # let the system settle after boot
    retry(f"cmd role add-role-holder --user 0 android.app.role.HOME {PACKAGE}")
    for permission in ["READ_CONTACTS", "READ_CALENDAR", "READ_MEDIA_IMAGES"]:
        shell(f"pm grant {PACKAGE} android.permission.{permission}", check=False)
    shell(f"appops set {PACKAGE} GET_USAGE_STATS allow", check=False)
    shell(f"appops set {PACKAGE} MANAGE_EXTERNAL_STORAGE allow", check=False)
    shell("logcat -c")
    go_home()
    width, height = screen_size()

    holder = shell("cmd role get-role-holders --user 0 android.app.role.HOME")
    check(PACKAGE in holder, "launcher is the default home app")
    time.sleep(3)
    screenshot("1-home")

    # 1. Gesture opens the search with the keyboard; halfway through, both are half open.
    x, y = width // 2, int(height * 0.25)
    shell(f"input motionevent DOWN {x} {y}")
    for step in range(1, 9):
        shell(f"input motionevent MOVE {x} {y + step * 22}")
        time.sleep(0.05)
    time.sleep(0.8)
    screenshot("2-gesture-halfway")
    shell(f"input motionevent MOVE {x} {y + 400}")
    shell(f"input motionevent UP {x} {y + 400}")
    time.sleep(2)
    check(keyboard_shown(), "swipe down opens the keyboard")
    check(wait_for_text("Abbrechen") or wait_for_text("Cancel"), "search panel is visible")
    screenshot("3-suggestions")

    # 2. An app is found by its name.
    shell("input text Sett")
    check(wait_for_text("Settings") or wait_for_text("Einstellungen"), "typing 'Sett' finds the Settings app")
    screenshot("4-results-apps")
    shell("input keyevent KEYCODE_BACK")  # hides the keyboard
    shell("input keyevent KEYCODE_BACK")  # closes the search
    time.sleep(1)
    go_home()

    # 3. Text in a picture is recognised while charging and becomes searchable.
    make_picture("/tmp/receipt.jpg", ["Garantieschein", "Waschmaschine", "Kaufdatum 12.03."])
    make_picture("/tmp/wifi.jpg", ["Gast-WLAN", "Passwort: Sonne123", "Garantie bis 2027"])
    adb("push", "/tmp/receipt.jpg", "/sdcard/Pictures/receipt.jpg")
    adb("push", "/tmp/wifi.jpg", "/sdcard/Pictures/wifi.jpg")
    shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/receipt.jpg", check=False)
    shell("content call --uri content://media/external/file --method scan_volume --arg external_primary", check=False)
    time.sleep(5)
    shell("dumpsys battery set ac 1")
    shell("dumpsys battery set status 2")
    go_home()
    time.sleep(3)
    shell(f"cmd jobscheduler run -f {PACKAGE} 1", check=False)
    time.sleep(25)
    open_search(width, height)
    shell("input text garantie")
    check(wait_for_text("Garantieschein", timeout=20), "picture is found by recognised text")
    shell("input keyevent KEYCODE_BACK")  # keyboard down, to show all results
    time.sleep(1.5)
    screenshot("5-results-photos")
    shell("dumpsys battery reset")
    shell("input keyevent KEYCODE_BACK")
    shell("input keyevent KEYCODE_BACK")

    # 4. Settings page.
    shell(f"am start -n {PACKAGE}/.settings.SettingsActivity")
    time.sleep(3)
    screenshot("6-settings")
    shell("input keyevent KEYCODE_BACK")

    # 5. Crashes and measurements.
    crashes = adb("logcat", "-d", "-b", "crash", check=False)
    check(PACKAGE not in crashes, "no crashes")
    if PACKAGE in crashes:
        print(crashes[-4000:])
    perf = [line for line in adb("logcat", "-d", "-s", "LauncherPerf:I", check=False).splitlines() if "ms" in line]
    print("Latency measurements (software emulator, for information only):")
    print("\n".join(perf[-20:]) or "  none")
    check(any("Keyboard" in line for line in perf), "keyboard latency was measured")
    check(any("Results" in line for line in perf), "result latency was measured")
    print(adb("logcat", "-d", "-s", "SearchRepository:I", check=False)[-2000:])

    if failures:
        print(f"{len(failures)} check(s) failed: {failures}")
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1])
