#!/usr/bin/env python3
"""Autorise la clé adb d'ADB Tiles sur un téléphone, en relisant l'état à chaque étape.

⚠️ Aucune coordonnée codée en dur : les bornes sont relues dans le dump juste avant l'appui.
uiautomator et `input tap` « réussissent » silencieusement sur une zone vide — la seule défense
est de relire après chaque geste. Voir la mémoire du banc.

2026-09-26 : sur OPPO CPH2135 (ColorOS 12), `uiautomator dump` rend rc=0 sans rien écrire
(« ERROR: could not get idle state »), et le `cat` qui suivait relisait alors un ui.xml
PÉRIMÉ laissé par un appel précédent — le script « lisait » un écran qui n'était plus affiché
et concluait à tort « écran de l'app introuvable ». Correctif : chaque dump utilise un nom de
fichier unique (donc jamais de résidu d'un appel précédent), est supprimé avant l'appel et
effacé après lecture ; un échec du dump est signalé explicitement, jamais interprété via un
fichier ancien ; en repli, on tente `dumpsys activity top` pour lire les `text=` visibles
(sans coordonnées, donc jamais utilisé pour taper).
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


def sh_full(dev, *args, t=25):
    """Comme sh(), mais renvoie (stdout, stderr) — utilisé pour diagnostiquer un dump raté."""
    try:
        r = subprocess.run([ADB, "-s", dev, "shell", *args], capture_output=True,
                           text=True, timeout=t, stdin=subprocess.DEVNULL)
        return r.stdout, r.stderr
    except subprocess.TimeoutExpired:
        return "", "(timeout adb)"


def nodes(dev):
    """Dump l'arbre UI courant et le parse. Renvoie (liste_de_noeuds, erreur) : erreur est
    None si le dump a réussi, sinon un texte diagnostique et la liste est vide.

    Fichier UNIQUE par appel (jamais /sdcard/ui.xml en dur) : supprimé avant le dump,
    vérifié après (son existence même suffit à garantir qu'il vient de CET appel, vu le nom
    unique), puis effacé après lecture. On ne retombe donc jamais sur un dump laissé par un
    appel précédent, même quand uiautomator rend rc=0 sans avoir rien écrit."""
    tag = f"/sdcard/adbtiles-auth-{int(time.time() * 1000)}.xml"
    sh(dev, "rm", "-f", tag)
    out_dump, err_dump = sh_full(dev, "uiautomator", "dump", tag)
    listing = sh(dev, "ls", "-la", tag)
    if tag not in listing or re.search(r"no such file", listing, re.I):
        diag = (out_dump + " " + err_dump).strip() or "(aucune sortie, aucun fichier écrit)"
        return [], diag
    raw = sh(dev, "cat", tag)
    sh(dev, "rm", "-f", tag)
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
    return out, None


def activity_top_texts(dev):
    """Repli quand uiautomator est indisponible : les `text=` visibles dans
    `dumpsys activity top`. Pas de bounds ici — jamais utilisé pour piloter un appui,
    seulement pour lire un mot d'état affiché."""
    raw = sh(dev, "dumpsys", "activity", "top")
    out = []
    for mo in re.finditer(r'\btext=(?:"([^"]*)"|(\S+))', raw):
        t = mo.group(1) if mo.group(1) is not None else mo.group(2)
        if t and t not in ("null", "{"):
            out.append(t)
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


def status_from_texts(texts):
    """Même lecture que status(), mais sur une simple liste de chaînes (repli dumpsys)."""
    for t in texts:
        if re.search(r"(autoris\w+ pour adb|authorized for adb)", t, re.I):
            return "non" if re.search(r"(pas encore|not .* yet)", t, re.I) else "oui"
    return "?"


def handle_dump_failure(dev, err, when):
    """uiautomator a échoué : le dire en clair, ne jamais raisonner sur un fichier ancien.
    Tente le repli dumpsys (texte seul) uniquement pour lire un état déjà-autorisé ; sans
    bounds, ce repli ne permet jamais de piloter un appui."""
    print(f"  ⛔ uiautomator dump a échoué ({when}) : {err}")
    print("     sur ColorOS c'est connu (« could not get idle state ») — repli sur "
          "`dumpsys activity top`, jamais sur un fichier laissé par un appel précédent")
    texts = activity_top_texts(dev)
    st = status_from_texts(texts)
    if st == "oui":
        print("  ✅ état lu via le repli dumpsys : déjà autorisée, rien à faire")
        return 0
    if st == "non":
        print("  ⛔ état lu via le repli dumpsys : PAS autorisée, mais uiautomator est "
              "indisponible — pas de coordonnées fiables pour appuyer. Demander un appui du "
              "porteur, ou réparer uiautomator puis rejouer.")
        return 7
    print("  ⛔ état introuvable, même par le repli. Ce que `dumpsys activity top` montre :")
    for t in texts[:8]:
        print(f"      {t[:70]!r}")
    return 8


def main(dev):
    print(f"### {dev} — {sh(dev, 'getprop', 'ro.product.model').strip()}")
    unlock(dev)
    subprocess.run([ADB, "-s", dev, "shell", "am", "start", "-n", f"{PKG}/.MainActivity"],
                   capture_output=True, timeout=25, stdin=subprocess.DEVNULL)
    time.sleep(5)
    ns, err = nodes(dev)
    if err:
        return handle_dump_failure(dev, err, "état AVANT")
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

    ns, err = nodes(dev)
    if err:
        return handle_dump_failure(dev, err, "après l'appui sur le bouton d'autorisation")
    box = find(ns, r".*", cls="CheckBox")
    if box:
        if box["checked"] != "true":
            tap(dev, box, "sur « toujours autoriser »")
            ns2, err2 = nodes(dev)
            if err2:
                return handle_dump_failure(dev, err2, "après l'appui sur « toujours autoriser »")
            box2 = find(ns2, r".*", cls="CheckBox")
            print(f"    relu : checked={box2['checked'] if box2 else '?'}")
            if not box2 or box2["checked"] != "true":
                print("  ⛔ la case n'est pas cochée — l'autorisation ne survivrait pas")
                return 4
        else:
            print("    case « toujours autoriser » déjà cochée")
    else:
        print("  ⚠️ pas de case « toujours autoriser » sur cette ROM")

    ns, err = nodes(dev)
    if err:
        return handle_dump_failure(dev, err, "avant de chercher le bouton de validation")
    ok = find(ns, r"^(allow|autoriser|ok)$", cls="Button")
    if not ok:
        print("  ⛔ bouton de validation introuvable dans la boîte système")
        return 5
    tap(dev, ok, "sur la validation")

    time.sleep(3)
    subprocess.run([ADB, "-s", dev, "shell", "am", "start", "-n", f"{PKG}/.MainActivity"],
                   capture_output=True, timeout=25, stdin=subprocess.DEVNULL)
    time.sleep(4)
    ns, err = nodes(dev)
    if err:
        return handle_dump_failure(dev, err, "état APRÈS")
    st = status(ns)
    print(f"  état APRÈS, relu à l'écran : autorisée = {st}")
    return 0 if st == "oui" else 6


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
