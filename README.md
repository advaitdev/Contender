# Contender

Contender runs duels and round-robin tournaments on Paper 26.2 with Java 25 or newer. FastAsyncWorldEdit is required. LibsDisguises provides spectator disguises, and Simple Voice Chat provides voice controls when installed.

## Roles and nametags

Run `/role` to choose a player and assign a role, or use `/role Alice director`, `/role Alice spectator`, or `/role Alice contestant`. The command requires `contender.master` and can also update an offline player whose name the server has cached. Roles are saved by UUID in `roles.yml`; players without an assignment are contestants.

Only contestants can enter duels or round-robin tournaments. This applies to manual selections, free-for-all enrollment, saved tournament resumption, and automatic match scheduling. Deceased players remain ineligible. Finish a player's duel, or finish or cancel their tournament, before assigning them a nonplaying role. A role does not grant admin permissions or change the player's game mode.

`/settings` opens a Minecraft Dialog. Admins can edit the director and spectator prefixes and choose their prefix/name color from the Minecraft colors. Changes apply to everyone with that role and are saved in `config.yml`. The default tags are gold `[Director]` and gray `[Spectator]`. Existing disguise, chat, and PvP settings are available from the same menu.

Contestants' nametags show their strongest MCTiers rating across all modes. Strength is ordered `HT1`, `LT1`, `HT2`, `LT2`, and so on through `LT5`. Retired ratings use their peak tier when available and compete equally with active ratings; a retired HT1 beats an active HT2. An exact tie prefers the active rating. Retired tags have a light gray `(R)` immediately before the tier, while the tier keeps Tierer's original color. Directors and spectators display their configured role tag instead. Spectator disguises use the same formatted names.

Profiles are fetched off the server thread, cached for five minutes, and refreshed while players are online. A failed refresh keeps the last known tag and waits a minute before retrying; a missing profile produces no tier prefix. `/tier [player]` lists a player's ratings. Contender handles nametags itself and does not require Tierer or PlaceholderAPI. Nametags use teams on each viewer's scoreboard without replacing its objectives; another plugin that continuously manages those same player teams may conflict.

The original plugin's decompiled source and embedded Maven project are preserved in [recovered/Tierer](recovered/Tierer/README.md). The maintained integration is in `src/main/java/me/advait/contender/tier`.

## Create a map

1. Build the original map in a world separate from the arena pool.
2. Select a cuboid with the WorldEdit wand (`//wand`). Include the floor, playable space, and enough air above the map for players and spectators. Spawns must be inside the selection.
3. Run `/arena`, choose **Create map**, and enter an ID, display name, and copy count. The default is 20 copies per map. The selection's blocks, block entity data, biomes, and non-player entities are saved to a schematic.
4. Stand at the first team's spawn, open the map in `/arena`, and choose **Set team 1 spawn**. Repeat for team 2. Set a spectator spawn if needed; otherwise team 1's spawn is used.
5. Choose **Prepare arenas** and wait for the completion message.

Each map has its own pool. Display names appear in map selection; IDs identify saved templates. To change the template's blocks or entities, edit the original map and choose **Save current blocks**, then **Prepare arenas**. Name, spawn, and copy-count changes also require preparation. Editing is unavailable while that map has a match or preparation in progress.

The arena world is created or loaded during plugin startup. Saved maps are prepared before their copies can be reserved. Starting a duel never creates a world or copies the source map. When a round ends, its arena is restored from the saved schematic, including air and entities. Capture and reset load the selected chunks and their entity data before working, and keep them loaded until finished. The next round waits for that reset. An arena only becomes available for another duel after cleanup finishes.

Players, block placements, fluids, pistons, and explosion damage are confined to the selected region. Include all intended playing space in the selection. Restoring a template removes non-player entities in that arena's slot, then pastes the saved entities again.

Settings in `config.yml`:

```yaml
arenas:
  world: contender_arenas
  copies-per-map: 20
  spacing: 1024
  max-template-blocks: 5000000
```

A map can have 1–100 copies. Each map reserves its own range of slots, so increasing its count does not overlap another pool. Leave spacing unchanged for an existing arena world; use a new world name if you need different spacing. Map regions must fit inside a slot with a 32-block margin on each side and within the world's build height.

Existing `maps.yml` entries with both spawns and a rollback region can be imported during preparation. Their source worlds must already be loaded for the first capture. Later starts use the saved schematic. Invalid or incomplete maps remain unavailable until corrected.

## Run a tournament

Run `/tournament` and choose **New tournament**. Set the name, map, kit, and default best-of count. Entries can be individual players or named teams. For teams, enter one roster per line:

```text
Red: Alice, Bob
Blue: Charlie, Dana
```

Players can be offline when entering the roster, including players who have never joined the server. Enter their Minecraft usernames. Contender uses cached identities where available and asks Paper to look up unfamiliar names in the background. If a lookup fails, the roster stays editable and no tournament is created. Names are saved with player UUIDs, so the bracket survives restarts. On servers without account authentication, use the exact spelling and capitalization the player will join with.

A player can only appear once, and only contestants can enter. The schedule supports 2–64 entries and gives every pair one match, with byes where needed. A tournament is one round-robin stage with a fixed roster, kit, and standings. For an event with sword, axe, UHC, and other gamemodes, finish or cancel the current stage, then create the next bracket with its kit and roster. Losing a match does not eliminate an entry or change the remaining pairings.

Choose either scheduling mode:

- **Start available matches** starts another match whenever its players and an arena are free.
- **Wait for each round** waits until every match in the current tournament round has finished.

Choose the inventory-sorting delay and maximum simultaneous matches, then create the tournament. It is saved while paused, so you can prepare the bracket in advance. Review it and choose **Start / resume** when the arenas are ready. Every player on both sides must be online before a match starts; other available pairings can run according to the selected scheduling mode. The selected map's pool limits actual concurrency. Waiting players stay where they are; they are not automatically assigned as spectators.

Under **Matches**, you can change a waiting match's best-of count, award a forfeit, or spectate a match. Match lengths are odd best-of values from 1 to 15. A contestant disconnecting during play forfeits that match. Offline players' future matches wait for them to return; an admin can award those matches if they will not return.

**Pause new matches** lets current matches finish. **Cancel tournament** ends its active duels and stops its schedule. Starting a community vote pauses the tournament and cancels active duels; cancelled matches remain queued for a later resume.

Results are saved in `tournament.yml`. After a restart, completed results and match settings remain, unfinished matches return to the queue, and the tournament is paused. Arena copies are restored at startup before reuse.

## Show the bracket

Stand in the lobby and choose **Place board here** in `/tournament`. A text display appears in front of you. It shows standings, live scores, and pages of scheduled matches. Pages rotate every ten seconds. The board faces its viewer and is restored at its saved location after a restart.

Wins earn 3 points and draws earn 1. Ties are ordered by combat rounds won minus rounds lost; entries still tied share a rank. Alphabetical display order does not break a tie, and the plugin does not eliminate anyone based on standings. If a tie crosses your elimination cutoff, you can settle it with a separate duel before choosing the next roster. The board includes forfeits in the results.

## Other commands

- `/duel`: set up an individual duel through Dialogs. The kit editor retains inventory slots for arranging items.
- `/spectate`: choose a running match or return to the lobby. Tournament players who are watching another match are moved into their own match when it starts.
- `/endduel`: end your current match using its current score.
- `/contender reload`: reload settings and prepare map pools again. Wait for active duels and arena work to finish first. The tournament stays paused until resumed.

Arena and tournament management require `contender.master` (operators by default). Anyone can open `/spectate`.

## Build

Run `mvn verify`. The JAR is `target/Contender-v1.1.0.jar`. The tests cover role persistence and eligibility, offline roster lookup, tier ordering and retirement, lookup caching, nametag updates, state cleanup, spectator visibility, round-robin scheduling, roster validation, saved results, arena reservations, and concurrent duel ownership. Live Paper testing is still needed for profile lookup against the account service, WorldEdit entity copying, Dialog layout, and the appearance of nametags and the tournament board.
