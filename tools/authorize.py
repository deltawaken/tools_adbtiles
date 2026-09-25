#!/usr/bin/env python3
"""Autorise la clé adb d'ADB Tiles sur un téléphone, en relisant l'état à chaque étape.

⚠️ Aucune coordonnée codée en dur : les bornes sont relues dans le dump juste avant l'appui.
uiautomator et `input tap` « réussissent » silencieusement sur une zone vide — la seule défense
est de relire après chaque geste. Voir la mémoire du banc.
"""
import re, subprocess, sys, time

ADB = "/home/lazuli/Android/Sdk/platform-tools/adb"
PKG = "com.deltawaken.adbtiles"


def sh(dev, *args, t=25):
    try:
        return subprocess.run([ADB, "-s", dev, "shell", *args], capture_output=True,
                              text=True, timeout=t, stdin=subprocess.DEVNULL).stdout
    except subprocess.TimeoutExpired:
        return ""


def nodes(dev):
    sh(dev, "uiautomator", "dump", "/sdcard/ui.xml")
    raw = sh(dev, "cat", "/sdcard/ui.xml")
    out = []
    for n in re.findall(r"<node[^>]*>", raw):
        g = lambda k: (re.search(k + r'="([^"]*)"', n).group(1)
                       if re.search(k + r'="([^"]*)"', n) else "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", g("bounds"))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"text": g("text"), "cls": g("class"), "checked": g("checked"),
                    "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    return out


def find(ns, pattern, cls=None):
    for n in ns:
        if re.search(pattern, n["text"], re.I) and (cls is None or cls in n["cls"]):
            return n
    return None


def tap(dev, n, why):
    print(f"    appui {why} -> ({n['cx']},{n['cy']}) sur {n['text']!r}")
    sh(dev, "input", "tap", str(n["cx"]), str(n["cy"]))
    time.sleep(3)


def unlock(dev):
    sh(dev, "input", "keyevent", "KEYCODE_WAKEUP"); time.sleep(1)
    sh(dev, "wm", "dismiss-keyguard"); time.sleep(1)
    # glissement calculé sur la taille réelle de l'écran, pas sur une valeur devinée
    size = sh(dev, "wm", "size")
    m = re.search(r"(\d+)x(\d+)", size)
    if m:
        w, h = int(m.group(1)), int(m.group(2))
        sh(dev, "input", "swipe", str(w // 2), str(int(h * 0.8)), str(w // 2), str(int(h * 0.2)), "200")
    time.sleep(2)


def status(ns):
    """Rend 'oui', 'non' ou '?' — lu sur la ligne d'état de l'app, dans les deux langues."""
    for n in ns:
        t = n["text"]
        if re.search(r"(autoris\w+ pour adb|authorized for adb)", t, re.I):
            return "non" if re.search(r"(pas encore|not .* yet)", t, re.I) else "oui"
    return "?"


def main(dev):
    print(f"### {dev} — {sh(dev, 'getprop', 'ro.product.model').strip()}")
    unlock(dev)
    subprocess.run([ADB, "-s", dev, "shell", "am", "start", "-n", f"{PKG}/.MainActivity"],
                   capture_output=True, timeout=25, stdin=subprocess.DEVNULL)
    time.sleep(5)
    ns = nodes(dev)
    st = status(ns)
    print(f"  état AVANT, lu à l'écran : autorisée = {st}")
    if st == "?":
        texts = [n["text"] for n in ns if n["text"].strip()][:8]
        print("  ⛔ écran de l'app introuvable (verrou ?). Ce qui est affiché :")
        for t in texts:
            print(f"      {t[:70]!r}")
        return 2
    if st == "oui":
        print("  ✅ déjà autorisée, rien à faire")
        return 0

    btn = find(ns, r"autoris|authoriz", cls="Button")
    if not btn:
        print("  ⛔ bouton d'autorisation introuvable")
        return 3
    tap(dev, btn, "sur le bouton d'autorisation")

    ns = nodes(dev)
    box = find(ns, r".*", cls="CheckBox")
    if box:
        if box["checked"] != "true":
            tap(dev, box, "sur « toujours autoriser »")
            ns = nodes(dev)
            box2 = find(ns, r".*", cls="CheckBox")
            print(f"    relu : checked={box2['checked'] if box2 else '?'}")
            if not box2 or box2["checked"] != "true":
                print("  ⛔ la case n'est pas cochée — l'autorisation ne survivrait pas")
                return 4
        else:
            print("    case « toujours autoriser » déjà cochée")
    else:
        print("  ⚠️ pas de case « toujours autoriser » sur cette ROM")

    ns = nodes(dev)
    ok = find(ns, r"^(allow|autoriser|ok)$", cls="Button")
    if not ok:
        print("  ⛔ bouton de validation introuvable dans la boîte système")
        return 5
    tap(dev, ok, "sur la validation")

    time.sleep(3)
    subprocess.run([ADB, "-s", dev, "shell", "am", "start", "-n", f"{PKG}/.MainActivity"],
                   capture_output=True, timeout=25, stdin=subprocess.DEVNULL)
    time.sleep(4)
    st = status(nodes(dev))
    print(f"  état APRÈS, relu à l'écran : autorisée = {st}")
    return 0 if st == "oui" else 6


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
