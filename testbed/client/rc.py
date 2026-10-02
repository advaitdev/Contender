#!/usr/bin/env python3
"""Drive the render client started by run-client.sh.

  rc.py state                     connection and open screen
  rc.py screen                    widgets on the open screen (id, label, size, label width)
  rc.py shot [name]               screenshot (and dialog crop) copied to shots/<name>.png
  rc.py click <id|label>          click a widget by id (widget-3) or exact/partial label
  rc.py slide <id|label> <0-1>    click a slider at that point along its track
  rc.py type <id|label> <text>    replace a text field's contents
  rc.py cmd "<command>"           run a command as Render_1
  rc.py chat "<message>"          send chat
  rc.py wait <title> [seconds]    wait until a screen with this title (substring) is open
  rc.py waitclosed [seconds]      wait until no screen is open
  rc.py use <slot>                select a hotbar slot (0-8) and right-click with it
  rc.py attack                    left-click whatever the crosshair is on
  rc.py swing                     swing the arm (a left click at nothing, used by in-world boards)
  rc.py hud on|off                show or hide the HUD (like F1)
  rc.py close                     press Escape on the open screen (via /dialog clear fallback)
"""
import json, os, shutil, sys, time, urllib.request, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.environ.get("CONTENDER_TEST_WORK", os.path.join(os.path.dirname(HERE), ".work"))
RUN = os.path.join(WORK, "client")
SHOTS = os.path.join(WORK, "shots")
PORT = 25999


def call(method, path, body=None, timeout=15):
    token = open(os.path.join(RUN, "token")).read().strip()
    run_id = open(os.path.join(RUN, "run-id")).read().strip()
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("X-UHCR-Testbed-Run", run_id)
    req.add_header("X-Idempotency-Key", uuid.uuid4().hex)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        return json.loads(error.read() or b"{}")


def screen(screenshot=False):
    return call("GET", "/v1/actors/Render_1/screen" + ("?screenshot=true" if screenshot else ""))


def show_screen(s):
    if not s.get("open"):
        print("(no screen open)")
        return
    print(f"title: {s['title']}   [{s['dialogType']}]   window {s['window']['width']}x{s['window']['height']} scale {s['window']['guiScale']}")
    for w in s["widgets"]:
        clip = " CLIPPED" if w["height"] == 20 and w["labelWidth"] + 8 > w["width"] else ""
        missing = " MISSING-SPRITE" if any(sp.get("missing") for sp in w.get("sprites", [])) else ""
        print(f"  {w['id']:>9}  {w['x']:>4},{w['y']:<4} {w['width']:>3}x{w['height']:<3} label={w['labelWidth']:<3} {'' if w['enabled'] else '(off) '}{w['label']!r}{clip}{missing}")
    if s.get("missingResources"):
        print("  missing resources:", s["missingResources"][:5])


def find_widget(s, needle):
    """Finds a button by id, exact label or partial label. Sprite markers like [item/x] are ignored."""
    import re
    if needle.startswith("widget-"):
        return needle
    def plain(label):
        return re.sub(r"\[[^\]]*\]\s*", "", label).strip()
    widgets = s.get("widgets", [])
    buttons = [w for w in widgets if w["height"] == 20 and w["enabled"]]
    for pool in (buttons, widgets):
        exact = [w for w in pool if plain(w["label"]).lower() == needle.lower()]
        if exact:
            return exact[0]["id"]
    for pool in (buttons, widgets):
        partial = [w for w in pool if needle.lower() in plain(w["label"]).lower()]
        if partial:
            return partial[0]["id"]
    raise SystemExit(f"No widget matching {needle!r}")


def shot(name=None):
    # With a menu open, park the cursor in a corner so no tooltip covers it. Without one, moving
    # the cursor would turn the camera, so leave it alone.
    if screen().get("open"):
        call("POST", "/v1/actors/Render_1/actions", {"type": "mouse", "x": 2, "y": 2})
        time.sleep(0.3)
    s = screen(screenshot=True)
    files = s.get("screenshot") or {}
    time.sleep(1.0)  # screenshots are written on the render thread
    os.makedirs(SHOTS, exist_ok=True)
    out = []
    for kind, path in files.items():
        if path and os.path.exists(path):
            target = os.path.join(SHOTS, f"{name or 'shot'}{'' if kind == 'full' else '-' + kind}.png")
            shutil.copy(path, target)
            out.append(target)
    print("\n".join(out) if out else "(no screenshot written)")
    return s


def main(argv):
    if not argv:
        print(__doc__)
        return
    op = argv[0]
    if op == "state":
        print(json.dumps(call("GET", "/v1/state"), indent=1))
    elif op == "screen":
        show_screen(screen())
    elif op == "shot":
        show_screen(shot(argv[1] if len(argv) > 1 else None))
    elif op == "click":
        s = screen()
        widget = find_widget(s, " ".join(argv[1:]))
        print(json.dumps(call("POST", "/v1/actors/Render_1/click", {"widgetId": widget})))
    elif op == "slide":
        # slide <id|label> <position 0-1>: click a slider at that point along its track
        print(json.dumps(call("POST", "/v1/actors/Render_1/click", {"widgetId": find_widget(screen(), argv[1]), "position": float(argv[2])})))
    elif op == "type":
        # type <id|label> <text>: replace a text field's contents
        print(json.dumps(call("POST", "/v1/actors/Render_1/click", {"widgetId": find_widget(screen(), argv[1]), "text": " ".join(argv[2:])})))
    elif op in ("cmd", "chat"):
        body = {"type": "command", "command": argv[1]} if op == "cmd" else {"type": "chat", "message": argv[1]}
        print(json.dumps(call("POST", "/v1/actors/Render_1/actions", body)))
    elif op == "use":
        print(json.dumps(call("POST", "/v1/actors/Render_1/actions", {"type": "useHotbar", "slot": int(argv[1])})))
    elif op == "swing":
        print(json.dumps(call("POST", "/v1/actors/Render_1/actions", {"type": "swing"})))
    elif op == "attack":
        print(json.dumps(call("POST", "/v1/actors/Render_1/actions", {"type": "attack"})))
    elif op == "close":
        print(json.dumps(call("POST", "/v1/actors/Render_1/actions", {"type": "close"})))
    elif op == "hud":
        print(json.dumps(call("POST", "/v1/actors/Render_1/actions", {"type": "hud", "hidden": argv[1] == "off"})))
    elif op == "wait":
        title, limit = argv[1], float(argv[2]) if len(argv) > 2 else 10
        deadline = time.time() + limit
        while time.time() < deadline:
            s = screen()
            if s.get("open") and title.lower() in s.get("title", "").lower():
                show_screen(s)
                return
            time.sleep(0.3)
        raise SystemExit(f"Timed out waiting for a screen titled {title!r}")
    elif op == "waitclosed":
        deadline = time.time() + (float(argv[1]) if len(argv) > 1 else 10)
        while time.time() < deadline:
            if not screen().get("open"):
                return
            time.sleep(0.3)
        raise SystemExit("A screen is still open")
    elif op == "ready":
        deadline = time.time() + (float(argv[1]) if len(argv) > 1 else 240)
        while time.time() < deadline:
            try:
                state = call("GET", "/v1/state", timeout=5)
                if state.get("connected"):
                    print(json.dumps(state))
                    return
            except Exception:
                pass
            time.sleep(2)
        raise SystemExit("Client did not connect in time")
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
