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


def ui_nodes():
    shell("uiautomator dump /sdcard/ui.xml", check=False)
    xml = shell("cat /sdcard/ui.xml", check=False)
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):])
    except (ValueError, ET.ParseError):
        return []
    return [node for node in root.iter("node") if node.get("text")]


def ui_texts():
    nodes = ui_nodes()
    # The emulator's previous launcher sometimes shows "isn't responding"; close such dialogs.
    if any("isn't responding" in node.get("text") for node in nodes):
        for node in nodes:
            if node.get("text") in ("Close app", "Wait"):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                time.sleep(1)
                return ui_texts()
    return [node.get("text") for node in nodes]


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
    """Saves a downscaled screenshot for the preview."""
    import io
    from PIL import Image
    os.makedirs(SHOTS, exist_ok=True)
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    try:
        image = Image.open(io.BytesIO(png)).convert("RGB")
    except OSError:
        print(f"  no screenshot for {name}")
        return
    image.thumbnail((540, 1200))
    image.save(os.path.join(SHOTS, f"{name}.jpg"), "JPEG", quality=85)


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
    state = shell("dumpsys input_method", check=False)
    return any(flag in state for flag in ["mInputShown=true", "mIsInputViewShown=true", "isInputViewShown=true"])


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
    # The emulator has a hardware keyboard; show the on-screen keyboard anyway, as on a phone.
    shell("settings put secure show_ime_with_hard_keyboard 1")
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

    # The emulator's own launcher is not needed any more and must not get in the way.
    shell("pm disable-user --user 0 com.google.android.apps.nexuslauncher", check=False)
    holder = shell("cmd role get-role-holders --user 0 android.app.role.HOME")
    check(PACKAGE in holder, "launcher is the default home app")
    go_home()
    time.sleep(3)
    ui_texts()  # closes a leftover dialog, if any
    screenshot("1-home")

    # 1. Gesture opens the search with the keyboard. A slow swipe is captured halfway through.
    x, y = width // 2, int(height * 0.25)
    slow = subprocess.Popen(["adb", "shell", f"input swipe {x} {y} {x} {y + 500} 3000"])
    time.sleep(1.6)
    screenshot("2-gesture-halfway")
    slow.wait()
    time.sleep(2)
    if not wait_for_text("Cancel", timeout=3):
        open_search(width, height)
    check(keyboard_shown(), "swipe down opens the keyboard")
    check(wait_for_text("Cancel"), "search panel is visible")
    check(wait_for_text("Suggestions"), "suggestions are shown without input")
    screenshot("3-suggestions")

    # 2. An app is found by its name.
    shell("input text Sett")
    check(wait_for_text("Top hit") and wait_for_text("Settings"), "typing 'Sett' finds the Settings app as top hit")
    screenshot("4-results-apps")
    go_home()  # pressing home closes the search
    time.sleep(2)
    check(not wait_for_text("Cancel", timeout=2), "home button closes the search")

    # 3. Text in a picture is recognised while charging and becomes searchable.
    make_picture("/tmp/receipt.jpg", ["Garantieschein", "Waschmaschine", "Kaufdatum 12.03."])
    make_picture("/tmp/wifi.jpg", ["Gast-WLAN", "Passwort: Sonne123", "Garantie bis 2027"])
    adb("push", "/tmp/receipt.jpg", "/sdcard/Pictures/receipt.jpg")
    adb("push", "/tmp/wifi.jpg", "/sdcard/Pictures/wifi.jpg")
    shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/receipt.jpg", check=False)
    shell("content call --uri content://media/external/file --method scan_volume --arg external_primary", check=False)
    shell("dumpsys battery set ac 1")
    shell("dumpsys battery set status 2")
    # Wait until MediaStore lists the pictures and the launcher has planned its charging job.
    deadline = time.time() + 60
    while time.time() < deadline and "waiting" not in shell(f"cmd jobscheduler get-job-state {PACKAGE} 1", check=False) \
            and "ready" not in shell(f"cmd jobscheduler get-job-state {PACKAGE} 1", check=False):
        go_home()
        time.sleep(3)
    print("  job state: " + shell(f"cmd jobscheduler get-job-state {PACKAGE} 1", check=False).strip())
    shell(f"cmd jobscheduler run -f {PACKAGE} 1", check=False)
    deadline = time.time() + 90
    while time.time() < deadline and "Text recognition finished" not in adb("logcat", "-d", "-s", "ContentIndexer:I", check=False):
        time.sleep(3)
    print(adb("logcat", "-d", "-s", "ContentIndexer:*", "images:*", "ContentIndexJob:*", "TextExtraction:*", check=False)[-3000:])
    time.sleep(3)
    open_search(width, height)
    check(not wait_for_text("Sett", timeout=1), "search starts empty")
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
    time.sleep(8)
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
    print(adb("logcat", "-d", "-s", "SearchRepository:I", "LauncherIme:D", check=False)[-2500:])

    if failures:
        print(f"{len(failures)} check(s) failed: {failures}")
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1])
