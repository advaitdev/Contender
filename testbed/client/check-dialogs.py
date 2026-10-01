#!/usr/bin/env python3
"""Opens Contender's menus in the render client and flags layout problems.

For each menu it reports buttons that sit below the visible area (you'd have to scroll to reach them),
button labels wider than their button, and sprites the client couldn't find. It walks the probe's
dialog list plus every New Event form. Run with the client connected and Render_1 as an op director.

  client/check-dialogs.py            all menus
  client/check-dialogs.py hacks tools
"""
import os, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rc

RCON = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools", "rcon.py")
PROBE_DIALOGS = ["tournament", "formats", "tools", "minigames", "duel", "arena", "kits", "spectate", "startvote",
                 "hackers", "sabotagesettings", "options", "theme", "bracket", "roles", "racesetup", "manhuntsetup"]
EVENTS = ["Round Robin", "Mace Race", "Combo", "Manhunt", "Last Man Standing", "King of the Hill", "Winner Stays On", "Juggernaut"]


def console(command):
    subprocess.run([sys.executable, RCON, command], stdout=subprocess.DEVNULL)


def problems(screen):
    found = []
    widgets = screen.get("widgets", [])
    containers = [w for w in widgets if w["label"] == "" and w["height"] > 40]
    bottom = max((c["y"] + c["height"] for c in containers), default=None)
    footer_y = max((w["y"] for w in widgets if w["height"] == 20), default=0)
    for w in widgets:
        # Skip the footer and the vanilla "custom screen" warning icon (a 20px button with a tooltip label).
        if w["height"] != 20 or w["y"] == footer_y or w["width"] <= 20:
            continue
        label = w["label"]
        if bottom is not None and w["y"] + w["height"] > bottom:
            found.append(f"below the fold: {label!r} (y={w['y']}, visible to {bottom})")
        if w["labelWidth"] + 8 > w["width"]:
            found.append(f"label wider than button: {label!r} ({w['labelWidth']}px in {w['width']}px)")
        if any(sprite.get("missing") for sprite in w.get("sprites", [])):
            found.append(f"missing sprite: {label!r}")
    return found


def report(name, screen):
    if not screen.get("open"):
        print(f"{name:22} (didn't open)")
        return
    issues = problems(screen)
    print(f"{name:22} {screen['title'][:30]:32} {'OK' if not issues else ''}")
    for issue in issues:
        print(f"{'':24}{issue}")


def main(names):
    for name in names or PROBE_DIALOGS:
        console(f"probe dialog Render_1 {name}")
        time.sleep(1.2)
        report(name, rc.screen())
    if names:
        return
    for event in EVENTS:
        rc.call("POST", "/v1/actors/Render_1/actions", {"type": "command", "command": "tournament"})
        time.sleep(1.2)
        rc.call("POST", "/v1/actors/Render_1/click", {"widgetId": rc.find_widget(rc.screen(), "New Event")})
        time.sleep(1)
        rc.call("POST", "/v1/actors/Render_1/click", {"widgetId": rc.find_widget(rc.screen(), event)})
        time.sleep(1)
        report(f"new: {event}", rc.screen())
    rc.call("POST", "/v1/actors/Render_1/actions", {"type": "close"})


if __name__ == "__main__":
    main(sys.argv[1:])
