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
# Crashes and "isn't responding" of the launcher itself; these stop a release.
critical = []
# ANRs while the main thread only waited for the emulator's software renderer (reported, not critical).
slow_frames = []


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
    # Close "isn't responding" dialogs. Ours is a critical failure: keep the traces and wait for it.
    dialog = next((node.get("text") for node in nodes if "isn't responding" in node.get("text")), None)
    if dialog:
        ours = dialog.startswith("WEWEWE")
        if ours:
            record_anr(dialog)
        for node in nodes:
            if node.get("text") == ("Wait" if ours else "Close app"):
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


def record_anr(dialog):
    if dialog in critical or dialog in slow_frames:
        return
    print(f"ANR: {dialog}")
    stuck_in_our_code = True
    # Why the system considered us stuck: the lines after "ANR in" in the system log.
    log = adb("logcat", "-d", "-b", "system", check=False).splitlines()
    for i, line in enumerate(log):
        if f"ANR in {PACKAGE}" in line:
            print("\n".join(log[i:i + 12]))
            break
    # The newest trace that belongs to the launcher (other apps' ANRs land in the same folder).
    for name in adb("shell", "ls -t /data/anr/", check=False).split():
        text = adb("shell", f"cat /data/anr/{name}", check=False)
        ours = text.find(f"Cmd line: {PACKAGE}")
        if ours < 0:
            continue
        print(f"  trace {name}")
        for line in text.splitlines()[:8]:
            if line.startswith("Subject:"):
                print("  " + line)
        section = text[ours:]
        start = section.find('"main" prio')
        main = section[start:start + 5000] if start >= 0 else section[:5000]
        print(main)
        # The main thread handed a frame to the render thread and waits for it: without a GPU the
        # emulator renders the glass blur in software, which says nothing about a phone.
        stack = main.split("\n\n")[0]
        stuck_in_our_code = "nSyncAndDrawFrame" not in stack or "io.github.davydgky" in stack
        break
    if stuck_in_our_code:
        critical.append(dialog)
        print("CRITICAL: the launcher's main thread was busy")
    else:
        slow_frames.append(dialog)
        print("NOT CRITICAL: the main thread waited for the software renderer of the emulator")


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


def wait_until_idle(limit=90):
    """Right after boot the software-rendered emulator is busy for minutes; starting the launcher then
    measures the emulator, not the launcher."""
    deadline = time.time() + limit
    time.sleep(20)
    while time.time() < deadline:
        try:
            load = float(shell("cat /proc/loadavg", check=False).split()[0])
        except (IndexError, ValueError):
            load = 99.0
        if load < 2.5:
            break
        time.sleep(10)
    print(f"  load before start: {shell('cat /proc/loadavg', check=False).strip()}")


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
    # Root lets the test read ANR traces; available on the google_apis emulator images.
    adb("root", check=False)
    time.sleep(3)
    adb("wait-for-device")
    shell("settings put global window_animation_scale 1")
    # The emulator has a hardware keyboard; show the on-screen keyboard anyway, as on a phone.
    shell("settings put secure show_ime_with_hard_keyboard 1")
    adb("install", "-r", "-g", apk)
    wait_until_idle()
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

    # 1. Gesture opens the search with the keyboard. A slow swipe is captured halfway through. It starts
    # on free space: on a slow emulator a slow swipe over an icon can turn into a long press.
    x, y = width // 2, int(height * 0.58)
    slow = subprocess.Popen(["adb", "shell", f"input swipe {x} {y} {x} {y + 500} 3000"])
    time.sleep(1.6)
    screenshot("2-gesture-halfway")
    slow.wait()
    time.sleep(2)
    if not wait_for_text("Cancel", timeout=10):
        # The slow swipe did not open it (emulator hiccup): open with the search button instead.
        shell("input keyevent KEYCODE_HOME")
        time.sleep(2)
        for node in ui_nodes():
            if node.get("text") == "Search":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                time.sleep(2)
                break
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
    check(wait_for_text("Cancel", timeout=5) and not wait_for_text("Top hit", timeout=1), "search starts empty")
    shell("input text garantie")
    check(wait_for_text("Garantieschein", timeout=20), "picture is found by recognised text")
    shell("input keyevent KEYCODE_BACK")  # keyboard down, to show all results
    time.sleep(1.5)
    screenshot("5-results-photos")
    shell("dumpsys battery reset")
    shell("input keyevent KEYCODE_BACK")
    shell("input keyevent KEYCODE_BACK")

    # 4. Home screen editing: a folder by dragging one app onto another.
    go_home()
    time.sleep(2)
    labels = [n for n in ui_nodes() if n.get("text") not in ("Search", "Suchen")]
    def center(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        return (x1 + x2) // 2, (y1 + y2) // 2
    page_labels = sorted((n for n in labels if center(n)[1] < height * 0.7), key=lambda n: (center(n)[1] // 50, center(n)[0]))
    if len(page_labels) >= 2:
        (ax, ay), (bx, by) = center(page_labels[0]), center(page_labels[1])
        icon_offset = int(40 * width / 1080)
        shell(f"input draganddrop {bx} {by - icon_offset} {ax} {ay - icon_offset} 1500")
        time.sleep(2.5)
        texts = ui_texts()
        folder_made = page_labels[1].get("text") not in texts or any(t in texts for t in ["Folder", "Ordner", "Utilities", "Dienstprogramme", "Productivity", "Produktivität"])
        print(adb("logcat", "-d", "-s", "HomeController:*", "WorkspaceRepository:*", check=False)[-1500:])
        check(folder_made, "dragging an app onto another makes a folder")
        screenshot("7-folder")
        go_home()  # dragging started edit mode; home ends it
        time.sleep(1.5)
    else:
        check(False, "home screen shows apps to arrange")

    # 5. App Library to the right of the last page.
    for _ in range(4):
        shell(f"input swipe {int(width * 0.85)} {height // 2} {int(width * 0.15)} {height // 2} 200")
        time.sleep(0.8)
    check(wait_for_text("App Library", timeout=5), "App Library is right of the last page")
    screenshot("8-app-library")
    go_home()

    # 6. A long press on empty background starts edit mode; its bar adds a widget (binding allowed
    # beforehand, as a user would allow it once).
    shell(f"appwidget grantbind --package {PACKAGE} --user 0", check=False)
    pill = next((n for n in ui_nodes() if n.get("text") in ("Search", "Suchen")), None)
    py = center(pill)[1] if pill is not None else int(height * 0.8)
    shell(f"input swipe {int(width * 0.1)} {py} {int(width * 0.1)} {py} 1200")
    time.sleep(1.5)
    check(wait_for_text("Done", timeout=5), "a long press on the background starts edit mode")
    screenshot("9-edit-mode")
    added = False
    for node in ui_nodes():
        if node.get("text") in ("Add widget", "Widget hinzufügen"):
            x, y = center(node)
            shell(f"input tap {x} {y}")
            time.sleep(4)
            # Each widget shows its size ("2 × 2"); tap the first one.
            candidates = [n for n in ui_nodes() if re.fullmatch(r"\d+ × \d+", n.get("text"))]
            if candidates:
                x, y = center(candidates[0])
                shell(f"input tap {x} {y}")
                time.sleep(4)
                added = not wait_for_text("Widgets", timeout=2)
            break
    check(added, "a widget can be added from the picker")
    screenshot("10-widget")
    go_home()

    # 7. Settings page.
    shell(f"am start -n {PACKAGE}/.settings.SettingsActivity")
    time.sleep(8)
    screenshot("6-settings")
    shell("input keyevent KEYCODE_BACK")

    # 5. Crashes and measurements.
    anr = [line for line in adb("logcat", "-d", "-b", "system", check=False).splitlines() if f"ANR in {PACKAGE}" in line]
    if anr:
        record_anr(anr[0])
    check(not anr, "the launcher never stopped responding")
    crashes = adb("logcat", "-d", "-b", "crash", check=False)
    check(PACKAGE not in crashes, "no crashes")
    if PACKAGE in crashes:
        critical.append("crash")
        print(crashes[-4000:])
    print(adb("logcat", "-d", "-s", "HomeController:I", "WorkspaceRepository:*", "WidgetAdder:*", check=False)[-2000:])
    perf = [line for line in adb("logcat", "-d", "-s", "LauncherPerf:I", check=False).splitlines() if "ms" in line]
    print("Latency measurements (software emulator, for information only):")
    print("\n".join(perf[-20:]) or "  none")
    check(any("Keyboard" in line for line in perf), "keyboard latency was measured")
    check(any("Results" in line for line in perf), "result latency was measured")
    print(adb("logcat", "-d", "-s", "SearchRepository:I", "LauncherIme:D", check=False)[-2500:])

    if failures:
        print(f"{len(failures)} check(s) failed: {failures}")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception as error:  # an adb failure is not a launcher failure
        print(f"test aborted: {error}")
        failures.append(str(error))
    finally:
        with open("smoke-result.txt", "a") as result:
            result.write(f"critical={'true' if critical else 'false'}\n")
    if failures:
        sys.exit(1)
