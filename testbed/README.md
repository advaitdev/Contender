# Testbed

Tools for testing Contender on a local Paper server without real players:

- **Fake players** (`fakeplayers/`): server-side bots that join, walk, fight, use items, chat, run commands and reconnect.
- **Probe** (`probe/`): a test plugin that drives Contender's API directly (start events, vote, trigger sabotages, open dialogs, inspect state).
- **Render client** (`client/`): a real Minecraft 1.21.11 Fabric client (the driver comes from the UHCR testbed) that joins through ViaVersion. It takes screenshots, reports dialog widgets, clicks buttons and inventory slots, and runs commands. It runs on a hidden Xvfb display.

Nothing here is part of the plugin build.

## Setup

1. Put Paper 26.2's `paper.jar` in `testbed/server/`. Put FastAsyncWorldEdit, ViaVersion and ViaBackwards (and optionally Simple Voice Chat) in `testbed/server/plugins/`.
2. Run `tools/setup-server.sh`. It writes an offline-mode, flat-world `server.properties` with RCON on 25575, and builds the fake players plugin.
3. Run `tools/deploy.sh`. It builds Contender and the probe, installs both and starts the server.
4. For the render client (optional): run `client/xvfb/setup-xvfb.sh` once. It unpacks Xvfb locally and needs no sudo. Then build the driver with `cd client/driver && ./gradlew build downloadAssets configureClientLaunch`.

To use a server folder elsewhere, set `CONTENDER_TEST_SERVER`. Logs, screenshots and build output go to `testbed/.work` (or `CONTENDER_TEST_WORK`).

## Everyday use

```sh
tools/deploy.sh                      # rebuild and restart with the current code
tools/rcon.py "bots spawn Alice Bob" "probe duel arena1 sword 2 Alice Bob" "sleep:15" "probe registry"
tools/errors.sh                      # warnings and stack traces from the server log
tools/stress.sh                      # 6-player round robin with hacks, sabotages, a spectator and a disconnect
```

`rcon.py` runs each argument as a console command. `sleep:N` waits N seconds between commands.

Useful commands:

- `bots spawn <names...>` or `bots spawn <count> [radius]`, `bots remove [all|name]`, `bots list`
- Movement: `bots wander on|off [names]`, `bots fight on|off [names]`, `bots tphere`, `bots gather <radius> [x z]`, `bots walk|look|vel|tp`
- Actions: `bots cmd|chat <name|all> ...`, `bots attack|use|swing|slot|info <name> ...`
- Diagnostics: `bots chatlog off|chat|all` (what bots see, in `plugins/FakePlayers/chat.log`), `bots verify on|off` (encode every packet like a real connection), `bots sys`. Slow ticks go to `plugins/FakePlayers/slow-ticks.log`.

The fake players plugin is UHCR's FakePlayers tool (`tools/fakeplayers` in the UHCR repos) without its MultiPaper parts. Bots confirm teleports and respawn like a vanilla client. `fakeplayers/build.sh` builds it against the test server, and the built jar is also kept in `dist/FakePlayers.jar`.
- `probe game <type> <map|-> <kit|-> <k=v,...|-> <players...>` starts a minigame (`mace_race`, `combo`, `manhunt`, `last_stand`, `king_of_the_hill`, `winner_stays_on`, `juggernaut`)
- `probe tournament <map> <kit> <bestOf> <players...>` and `probe trounds` show a round robin and its standings
- `probe vote <seconds>`, `probe votefor <voter> <number>`
- `probe hackers <names>`, `probe hackmode self|director`, `probe plan <hack> <value>`
- `probe sabotage <id> [hacker]`, `probe sabotageconfig <on> <seconds> <uses>`
- `probe dialog <player> <name>` opens any menu (`tournament`, `tools`, `formats`, `hacks`, `hackers`, `sabotage`, `startvote`, `racesetup`, `manhuntsetup`, …)
- `probe registry` shows what every player is doing, and `probe gamestatus` shows the current minigame

## Render client

```sh
client/restart-client.sh             # start (or restart) the client and wait until it joins as Render_1
client/rc.py screen                  # widgets on the open screen, with clipped labels and missing sprites flagged
client/rc.py shot name               # screenshot to .work/shots/name.png (and a crop of the dialog)
client/rc.py click "Start a Vote"    # click a widget by label or id
client/rc.py cmd "tournament"        # run a command as Render_1
client/rc.py attack                  # hit whatever the crosshair is on
client/rc.py hud off                 # hide the HUD for clean shots of holograms (also hides entity name tags)
client/shoot-dialogs.sh tournament tools hacks   # screenshot several menus in one go
client/check-dialogs.py              # open every menu and flag buttons below the fold, clipped labels, missing sprites
```

Give the client permissions with `tools/rcon.py "op Render_1" "role Render_1 director"`. Move its camera with server teleports, for example `tp Render_1 x y z yaw pitch` or `execute as Render_1 at @s run tp @s ~ ~ ~ facing entity Alice eyes`.

Most players see a smaller GUI than the default 640x360. Minecraft's Auto GUI scale is 4 at 1080p (480x270) and gives 426x240 at 1440p and 4K. Check menus used during the show at those sizes with `GUI_SCALE=3 client/restart-client.sh` (426x240) and `WIDTH=1920 HEIGHT=1080 GUI_SCALE=4 client/restart-client.sh` (480x270). Long setup forms may scroll. `client/run-client.sh --show` opens a visible window instead of using Xvfb.

To check voice chat, put the Simple Voice Chat Fabric mod for 1.21.11 in `.work/client/game/mods/` and start the client with `UHCR_TESTBED_VOICE_MODERATION=1 client/restart-client.sh`. The driver keeps voice traffic on loopback. `tools/rcon.py "contender voice Render_1"` should then report "Voice connection: connected". The hidden client has no microphone, so voice shows as turned off.

ViaVersion logs "Could not find UserConnection" warnings for fake players, because they have no network connection. They're expected and filtered out of `errors.sh`.
