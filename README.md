# Contender

Contender runs duels, round-robin tournaments, and Mace Race on Paper 26.2 with Java 25 or newer. FastAsyncWorldEdit is required. Spectator allays are built in. Simple Voice Chat runs independently when installed.

Text uses Minecraft's default font. Dialog icons use built-in sprites and player heads. No custom resource pack is required.

## Roles and nametags

Run `/role` to choose a player and assign a role, or use `/role Alice director`, `/role Alice spectator`, or `/role Alice contestant`. The command requires `contender.master` and can also update an offline player whose name the server has cached. Roles are saved by UUID in `roles.yml`; players without an assignment are contestants.

Only contestants can enter duels, tournaments, or community votes. Eliminate a player with `/role Alice spectator`; this applies the spectator nametag and puts them in Minecraft's Spectator mode. If they are entered in an unfinished stage, it pauses the stage; their active duel is cancelled without awarding a result. The roster and completed scores remain available for review. Cancel that stage and create the next bracket when ready. `/role Alice contestant` makes them eligible again and returns them to Survival. Roles do not grant admin permissions.

The separate deceased status and `/deceased` command have been removed. Existing entries in `spectators.yml` are imported into spectator roles once, while director assignments are preserved. The old file remains as a backup. Role assignments survive restarts and are applied when players join or return to the lobby.

`/options` opens a Minecraft Dialog. Admins can edit the director and spectator prefixes and choose their prefix/name color from the Minecraft colors. Changes apply to everyone with that role and are saved in `config.yml`. The default tags are gold `[Director]` and gray `[Spectator]`. **Chat & Voice** and **PvP Settings** open dialogs from the same menu and require `contender.admin`. Chat controls are grouped into Lobby, Contestants, Spectators, and Admin Bypass. Each form has a Back / Save row; Back discards unsaved edits. Saved Contender voice controls are inactive; Simple Voice Chat uses its own settings. Settings continue to use `chat_settings.yml` and `pvp_settings.yml`.

Contestants' nametags show their strongest MCTiers rating across all modes. Strength is ordered `HT1`, `LT1`, `HT2`, `LT2`, and so on through `LT5`. Retired ratings use their peak tier when available and compete equally with active ratings; a retired HT1 beats an active HT2. An exact tie prefers the active rating. Retired tags have a light gray `(R)` immediately before the tier, while the tier keeps Tierer's original color. Directors and spectators display their configured role tag instead. Spectator allays use the same formatted names.

Profiles are fetched off the server thread, cached for five minutes, and refreshed while players are online. A failed refresh keeps the last known tag and waits a minute before retrying; a missing profile produces no tier prefix. `/tier [player]` lists a player's ratings. Contender handles nametags itself and does not require Tierer or PlaceholderAPI. Nametags use teams on each viewer's scoreboard without replacing its objectives; another plugin that continuously manages those same player teams may conflict.

The original plugin's decompiled source and embedded Maven project are preserved in [recovered/Tierer](recovered/Tierer/README.md). The maintained integration is in `src/main/java/me/advait/contender/tier`.

## Lobby

Stand where players should arrive and run `/setlobby`. It saves the world name and key, position, and facing. Players join at this location by default. `/lobby` returns there after spectating. Contender loads the saved lobby and source map worlds at startup, using Builder's saved world types and Paper 26.2's world folders. It also tries loading the lobby before returning a player there if another plugin unloaded it. If loading fails, Contender logs the reason and uses a non-arena world's spawn.

World types and custom generator details are remembered in `managed-worlds.yml`. A migrated world without Builder metadata or a remembered type needs to be loaded once with Builder using its correct type; subsequent loads are automatic. Missing world files must be restored before that world can load.

In `/options` → **Lobby Settings**, admins can change joining at the lobby, block breaking, block placing, and the admin build bypass. Save applies the settings immediately. The protection covers the entire lobby world. Breaking also controls collecting fluids; placing controls emptying buckets. Editing pots, cakes, and display items requires both permissions. Fire spread, flowing fluids, explosions, and mob griefing remain blocked.

```yaml
lobby:
  teleport-on-join: true
  allow-block-break: false
  allow-block-place: false
  admin-build-bypass: true
```

These options belong alongside the saved location in `config.yml`. Use `/contender reload` after editing the file. The admin bypass is enabled by default, including for existing configs, and uses `contender.admin` (granted to operators by default). It does not override Minecraft's Spectator mode or another plugin's protection. Existing configs without `allow-block-place` keep using `allow-block-break` for placement until a separate value is saved.

## Create a map

1. Build the original map in a world separate from the arena pool.
2. Select a cuboid with the WorldEdit wand (`//wand`). Include the floor, playable space, and enough air above the map for players and spectators. Spawns must be inside the selection.
3. Run `/arena`, choose **Create map**, and enter an ID, display name, and copy count. The default is 20 copies per map. The selection's blocks, block entity data, biomes, and non-player entities are saved to a schematic.
4. Stand at the first team's spawn, open the map in `/arena`, and choose **Set team 1 spawn**. Repeat for team 2. Set a spectator spawn if needed; otherwise team 1's spawn is used.
5. Copies start preparing automatically once both spawns are saved. **Check Arena Copies** shows their progress.

Each map has its own pool. Display names appear in map selection; IDs identify saved templates. To change the template's blocks or entities, edit the original map and choose **Save Current Blocks**. Copies update automatically after saving blocks, spawns, names, or copy counts. Editing is unavailable while that map has a match or preparation in progress; checking its progress remains available.

The arena world is created or loaded during plugin startup, and complete maps begin preparing automatically. Each copy becomes available as soon as it finishes; it does not wait for the whole pool. Starting a duel never creates a world or copies the source map. Between duel rounds, contestants and spectators stay inside their arena while it is restored from the saved schematic, including air and entities. They keep their kits and are protected from damage and falling during the reset. Once it finishes, they return to their arena spawns for the next countdown. Duel participants return to the lobby when the duel ends. Visitors who arrive through `/tp` are also protected during round resets and final cleanup, then moved to the spectator spawn without changing their role, game mode, or inventory. Their presence does not cancel the match. Capture and reset load the selected chunks and their entity data before working, and keep them loaded until finished. The next round waits for that reset. An arena only becomes available for another duel after cleanup finishes.

Static arena copies can be reused after a clean server shutdown. Contender flushes the arena world and records empty, clean copies in `arena-ready.yml`. On the next start it checks their world, layout, schematic revision, and saved chunks, then loads the reset template before making them available. Templates with entities, block entities, liquids, power sources, or randomly ticking blocks still rebuild because they can change while idle. Template checks run once per revision off the server thread. Interrupted matches, missing chunks, changed templates, and crashes also require rebuilding. The first startup after installing this version may still prepare every copy; the cache is written when this version shuts down cleanly.

Cancelled or failed copies reset automatically once they are empty and any earlier work has finished. Failed preparation retries with a delay of 5–60 seconds; the map dialog shows the reason. Chunk loading has a timeout so one stalled load cannot hold up every map. Active pastes finish before another paste can begin in that copy.

Map saves use a separate queue from arena pastes, so creating a map does not wait for startup copies to finish. Each map prepares one copy at a time and stops an attempt at the first failure before retrying. The dialog shows the current stage, copy number, and elapsed time; saving a selection acknowledges the request immediately and reports its status after ten seconds if still pending. Long operations also log their stage and worker stack once a minute. FAWE undo history is disabled for these operations because the saved schematic provides the reset data. Block, entity, and biome copying remain enabled.

Players, block placements, fluids, pistons, and explosion damage are confined to the selected region. Include all intended playing space in the selection. Restoring a template removes non-player entities in that arena's slot, then pastes the saved entities again.

Settings in `config.yml`:

```yaml
arenas:
  world: contender_arenas
  copies-per-map: 20
  spacing: 1024
  max-template-blocks: 100000000
```

The template limit counts the whole selection, including air. Existing config files keep their saved value; set `arenas.max-template-blocks` to `100000000` to use the new default.

A map can have 1–100 copies. Each map reserves its own range of slots, so increasing its count does not overlap another pool. Leave spacing unchanged for an existing arena world; use a new world name if you need different spacing. Map regions must fit inside a slot with a 32-block margin on each side and within the world's build height.

Existing `maps.yml` entries with both spawns and a rollback region can be imported during preparation. Contender loads their source worlds for the first capture. Later starts use the saved schematic. Invalid or incomplete maps remain unavailable until corrected.

## Create a kit

1. Run `/tournament` → **Setup Tools** → **Maps & Kits** → **Edit Kits**, or choose **Setup Tools** → **Edit Kits** in `/duel`.
2. Choose **Create New Kit**, enter a name such as `Axe`, then choose **Next: Items**.
3. Place the kit's items in the first four rows. The first row becomes the hotbar. Use the marked slots for armor and the offhand item.
4. Set the block placement, block breaking, and natural regeneration options. Leave **No Clear** off to give every player the saved items.
5. Click **Save Kit**, then reopen `/tournament` to select it.

Kits are saved in `plugins/Contender/kits.yml`. Choose an existing kit in the dialog to edit it. Kit lists use the icon saved in the item editor and have page navigation at the bottom. Tournament and duel setup show separate map and kit statuses, so a missing kit does not suggest that your prepared map is missing.

The item editor has **PvP Hurt** and **PvE Hurt** toggles. Enabling PvP Hurt keeps hit reactions and knockback from player attacks but prevents health and absorption loss. This also covers projectiles and other damage sources attributed to a player. PvE Hurt does the same for other causes, except the void. With either toggle enabled, hunger stays full. Both default to off, so existing kits keep normal damage. The settings are saved as `pvp-hurt` and `pve-hurt`.

For sumo, enable both Hurt settings. Players can be knocked past the selected platform's edges into the empty space around their arena. Falling below the bottom of the selection counts as a loss with either Hurt setting enabled. Ordinary kits can fall through to the world's void and take normal void damage. Block edits stay inside the saved selection.

Keep the selected bottom below the platform. Teleports stay within the selection and never trigger a fall loss. Armor and shields keep their normal hit behavior.

## Run a tournament

Run `/tournament` and choose **New Tournament**. Choose the name, map and kit, enter the roster, then choose how many bracket rounds to play and how many combat round wins each match needs. Entries can be individual players or named teams. For teams, enter one roster per line:

```text
Red: Alice, Bob
Blue: Charlie, Dana
```

Players can be offline when entering the roster, including players who have never joined the server. Enter their Minecraft usernames. Contender uses cached identities where available and asks Paper to look up unfamiliar names in the background. If a lookup fails, the roster stays editable and no tournament is created. Names are saved with player UUIDs, so the bracket survives restarts. On servers without account authentication, use the exact spelling and capitalization the player will join with.

A player can only appear once, and only contestants can enter. The schedule supports 2–64 entries. Under **Rules → Bracket Rounds**, choose how much of the round robin to play. The default is a full schedule, where every pair meets once. A shorter schedule uses the first chosen bracket rounds, with no repeat opponents. The Review screen shows the selected round count and total matches before creation.

One bracket round gives each entry at most one match. For example, 10 players over 3 bracket rounds produces 15 matches, with 3 matches per player. A full schedule has `n(n−1)/2` matches spread across `n−1` bracket rounds for an even roster, or `n` bracket rounds for an odd roster. Odd rosters have one bye per bracket round, so a shortened schedule can leave some entries with one fewer match. **Round Wins Needed** controls the combat rounds within each match separately.

A tournament is one stage with a fixed roster, kit, and standings. For an event with sword, axe, UHC, and other gamemodes, finish or cancel the current stage, then create the next bracket with its kit and roster. The stage ends after its selected schedule finishes. Losing a match does not eliminate an entry or change the remaining pairings.

Choose either scheduling mode:

- **As arenas become free** starts another match whenever its players and an arena are free.
- **Finish each bracket round** waits until every match in the current tournament round has finished.

Setup follows **Details → Players → Rules → Review**. The final screen shows the title preview, map, kit, roster, and match rules before you create anything. Every step has Back on the left and the next action on the right; Back keeps your edits. The main menu puts its primary action first and groups maps, kits, board placement, and lobby controls under **Setup Tools**. Dialogs use regular text, vanilla sprites, neutral labels, and gold primary actions. The bracket is saved while paused, so you can prepare it in advance. Choose **Start Tournament** when ready to play. If copies are still preparing, the schedule waits and starts matches as they become available. Every player on both sides must be online before a match starts; other available pairings can run according to the selected scheduling mode. The selected map's pool limits actual concurrency. Waiting players stay where they are; they are not automatically assigned as spectators.

Under **Matches**, you can change how many round wins a waiting match needs, award a forfeit, or spectate a match. Matches can require 1–8 round wins (up to 1–15 rounds played). A contestant disconnecting during play forfeits that match. Offline players' future matches wait for them to return; an admin can award those matches if they will not return.

**Pause Tournament** lets current matches finish. If a match stops unexpectedly, the tournament pauses automatically and shows the reason in `/tournament`. The pairing stays waiting until you resume or award its result; it does not restart on its own. An arena cleanup failure after a decided win or forfeit preserves that result. **Cancel Tournament** ends its active duels and stops its schedule. Tab immediately returns to the normal player list while the arenas finish resetting. Starting a community vote pauses the tournament and cancels active duels; cancelled matches remain queued for a later resume.

Results and the bracket round count are saved in `tournament.yml`. After a restart, completed results and match settings remain, unfinished matches return to the queue, and the tournament is paused. Older saves without a round count keep their full schedules. Arena copies from interrupted matches are restored before reuse; clean static copies can load from the saved world.

## Show the bracket

On UHCR Paper, tab shows the round-robin matches with each score to the right of its entry, dashed separators, and a player column. Scores update while duels run. The initial view follows the earliest unfinished tournament round. A team uses its saved label even when it has only one member.

Run `/bracket` to choose a round, browse pages, or switch to standings. Anyone can use it, and each player controls their own view. Matches stay together within a column. Large rounds and rosters use pages to fit Minecraft's 80-entry tab limit. The tab footer shows only the round or standings heading and tournament status; the board footer shows only the heading. Page counts and instructions stay out of both footers. The player column includes every contestant, including byes and offline entries, plus other players seen during this stage. Directors and spectators keep their role tags and do not enter the bracket. An offline player's ping becomes an X; a team shows an X when any member is offline. Reconnecting restores their ping and skin. Saved rosters remain visible after a server restart; extra visitors are remembered until a restart or the next stage.

In `/options`, choose **Tab title and bracket** to change the title and color or turn the tab display off. A blank title uses the current tournament's name. The title and nametag editors show the current appearance; **Show preview** renders unsaved edits and keeps the fields filled in. **Save** applies them. Tournament setup also previews its tab title. `/bracket` has a preview of each column. Dialog buttons use vanilla sprites and player heads without a resource pack.

The tab renderer uses UHCR Paper's `UhcrTabEntries` API. On standard Paper, the normal list stays available and `/bracket` can preview the bracket in a dialog. Contender must be the only plugin controlling fake tab rows on the server; turn off any other tab layout renderer, including UHCR Chat's, if installed alongside it. Contender restores the prior header, footer, and list membership when its tab display is disabled. World visibility and nametag scoreboards are unchanged by the tab renderer.


Stand in the lobby and choose `/tournament` → **Setup Tools** → **Board & Lobby** → **Place Board Here**. The board shares tab's bracket renderer: player heads, tier prefixes, scores on the right, and colored separators. Teams show every member's head and tag. It faces the position you placed it from and remembers that orientation after a restart. Point at a live match row to enlarge it slightly for yourself, then left-click to spectate. The controls below the board switch rounds, standings, and pages; **Follow current round** resumes automatic round selection. Board navigation is shared, while `/bracket` changes only your tab view. The board background is transparent by default. In `/options` → **Board appearance**, set the opacity from 0% (transparent) to 100% (solid); 90% gives it a dark, nearly opaque background. This also updates a board that is already placed.

Wins earn 3 points and draws earn 1. Ties are ordered by combat rounds won minus rounds lost; entries still tied share a rank. Alphabetical display order does not break a tie, and the plugin does not eliminate anyone based on standings. If a tie crosses your elimination cutoff, you can settle it with a separate duel before choosing the next roster. The board includes forfeits in the results.

## Manual duels

The action bar shows `Starts in 10`, then counts down until combat begins. Later rounds show a three-second countdown after the arena resets. The countdown clears when combat starts or the duel is cancelled. Duels have no boss bars or persistent score/action-bar messages. Duel setup errors appear in chat. Arena worlds disable the locator bar and advancement chat announcements, and each duel reapplies these rules when it starts.

Run `/duel`, choose the map, kit, mode, round wins needed, and sorting time, then choose **Next: Players**. Team 1 is the left column and Team 2 is the right. Click a player head to select it; click again to remove it. A selected player is unavailable on the other side. Only online contestants who are free to play and not reserved for a tournament appear. Back keeps your selections. **Next: Review** shows both teams and a centered **Start Duel** button. Free for All enrolls available contestants when started.

Duel and tournament kit choices use the material saved as the kit's inventory icon. Flat items use their vanilla texture; blocks use a recognizable face. Items without a suitable inline sprite use a sword fallback. The catalog contains sprite names only; no texture pack is needed. Regenerate it for a new client version with `python3 tools/generate_item_sprites.py /path/to/minecraft-client.jar`.

## Spectating

While watching a duel, spectators use the flying allay system instead of native Spectator mode. They appear as allays hovering above their position, and can see their own allay in third person. The avatars exist only on clients and cannot collide with players, pick up items, or block attacks. Contestants see them only from at least 10 blocks away; the invisible-spectators kit option hides them at any distance. The owner and other spectators can still see them. There is no disguise plugin or separate packet plugin to install.

Directors receive a compass in the lobby. Players in native Spectator mode can open `/spectate` directly. Anyone watching a duel, including a contestant waiting for the next combat round after dying, receives one too. Right-click it to open a paginated match list or return to the lobby. Each match is one button, such as **Alice vs Bob 3-0**, with both heads and a gold score. Click it to spectate, or hover to see all players and their tier tags. Long team labels are shortened to fit. Lobby controls and page navigation have their own rows. A contestant still assigned to a match cannot switch away or return early. `/spectate` opens the same menu. Avatars, flight flags and player visibility are cleaned up when spectating ends, a player disconnects, or Contender shuts down.

## Voice chat

Simple Voice Chat is optional and handles audio normally through its own proximity, group, and client settings. Contender's voice integration is disabled: it does not filter or forward voice packets, mute or deafen players, or create a private spectator voice channel. Spectators use normal Simple Voice Chat behavior too.

Configure audio through Simple Voice Chat. Voice options under `/options` → **Chat & Voice** remain saved in `chat_settings.yml`, including **Hear the Match**, but have no effect. The dialogs show this notice. Game chat controls still apply to the lobby, contestants, and spectators.

## Community votes

`/startvote <seconds>` starts a community vote and `/endvote` finishes it early. Both require `contender.master`. `/vote` opens a dialog with contestant heads and vote counts. Click a player to vote, or click the same player again to undo your choice. Use Refresh to update counts and time remaining. Directors and spectators can view the vote but cannot vote or be candidates. Directors with `contender.master` can use **Manage Candidates** to remove someone from that vote. Voting does not change roles or eliminate anyone.

## Other commands

- `/duel`: set up an individual duel through Dialogs. The kit editor retains inventory slots for arranging items.
- `/spectate`: choose a running match or return to the lobby. Tournament players who are watching another match are moved into their own match when it starts.
- `/setlobby`: save your position and facing as the lobby return point (admin). You can also use `/tournament` → **Setup Tools** → **Board & Lobby** → **Set Lobby Here**.
- `/lobby`: leave spectating and return to the saved lobby. Players taking part in a match must wait until it finishes.
- `/endduel`: end the match you are in or watching and record its current score.
- `/cancelduel Alice`: immediately cancel the match Alice is in or watching without recording a result. Omit the name to cancel your own current match. Requires `contender.master`; console can use it with a player name. For tournament matches, new matches are paused and the cancelled pairing returns to the queue after cleanup. Resume through `/tournament` when you want it replayed.
- `/contender reload`: reload settings and prepare map pools again. Wait for active duels and arena work to finish first. The tournament stays paused until resumed.
- `/contender voice [player]`: reports that Contender's voice controls are disabled and shows the player's Simple Voice Chat permissions. Defaults to yourself; console requires a name. Contender does not collect voice packet counts while its integration is disabled. The command does not change settings.

Arena and tournament management require `contender.master` (operators by default). Anyone can open `/spectate`.

## Force cancellation

Run `/cancelall` to stop every round robin, minigame, duel, and vote, including interrupted events stuck during cleanup. It works in game and from the server console and requires `contender.master`. The same control is available under `/tournament` → **Setup Tools** → **Force Cancel All**.

Cancellation returns participants to the lobby and resets tab and the board. Saved minigame inventories remain available for players who are offline or whose lobby teleport fails; their returns are retried. Unfinished arena copies are taken out of use, so a late reset cannot interfere with another event. Other ready copies remain available. Affected copies reset automatically after everyone has left and any earlier preparation has finished.

## Build

The prebuilt plugin is [`dist/Contender-v1.8.20.jar`](dist/Contender-v1.8.20.jar). Run `mvn verify` to build it from source; the output is `target/Contender-v1.8.20.jar`. The tests cover role migration, game modes, eligibility, offline roster lookup, tier ordering and retirement, lookup caching, nametag updates, state cleanup, spectator visibility (including self-view), full and shortened round-robin schedules, roster validation, saved results, arena reservations, and concurrent duel ownership. Lobby tests cover building permissions, saved spawn selection, missing worlds, cancelled teleports, and joining while a duel starts. Dialog tests cover button placement, navigation, preserving form edits and round limits, title previews, regular font styling, saved lobby/chat/PvP controls, and stale vote callbacks. Tab tests also cover pagination through all rounds, disconnected profiles, shared ranks, score alignment, and restoring the normal list. Tests also cover countdown cleanup, hurt-kit persistence and damage handling, sumo falls, spectator voice routing, and legacy mute settings. Live Paper testing is still needed for voice audio, knockback behavior, login behavior alongside other plugins, profile lookup against the account service, WorldEdit entity copying, Dialog layout, and the appearance of nametags and the tournament board.

## Hacker controls

Use `/pickhacker Alice Bob` to select the hackers. Each use replaces the selection. Names can be online players or players who have joined this server before. Directors and spectators must be changed to contestants first. Selected players receive the red title; each hacker sees the other hackers as their teammates. Everyone else online receives a green "You are not the hacker." title. Selected offline players receive their red title on their next join. Use `/pickhacker --clear` to remove the selection and its abilities. This command requires `contender.master`.

Selected hackers can use `/hacks` or **Quick Actions → Contender → Hacks**. The Quick Actions launcher is shared client data; only selected hackers can open the settings, including operators. Old buttons stop working after another selection or a newer settings session.

The editor shows all eight settings as sliders on one screen. Close or Escape applies and saves the current values automatically, with a private confirmation. Vanilla dialogs submit inputs when an action runs, so dragging a slider does not change gameplay until the menu closes. Each slider shows a warning threshold in brackets; higher values may look blatant. There is no separate Save or Review screen.

| Setting | Range | Normal value | Warns above |
| --- | --- | --- | --- |
| Entity reach | 3–64 blocks | 3 | 3.5 |
| Attack speed | 1–10x | 1x | 1.5x |
| Melee attack damage | 1–10x | 1x | 1.5x |
| Damage resistance | 0–100% | 0% | 30% |
| Anti-knockback | 0–100% | 0% | 40% |
| Movement speed | 1–5x | 1x | 1.2x |
| Jump strength | 1–5x | 1x | 1.2x |
| Step height | 0.6–3 blocks | 0.6 | 0.9 |

Attack speed and melee damage multiply the held weapon's values. Anti-knockback covers ordinary and explosion knockback and adds to armor's resistance. Resistance reduces incoming damage before absorption; it does not cancel hits. Void damage and sumo fall losses still count.

Selected hackers' abilities stay active everywhere, including the lobby, countdowns, between rounds, and while watching matches. You can test them without starting a game. Settings return automatically after respawning or reconnecting and are saved with the selected players in `plugins/Contender/hackers.yml`. Changing a hacker's role to director or spectator suspends their abilities and menu access; returning to contestant restores them. `/pickhacker --clear` removes the selection and its abilities. Attribute changes use temporary modifiers so they do not overwrite kit or other plugin attributes. Spectator allays hide from contestants within 10 blocks; spectators can still see themselves and each other.

Contender uses Paper's bootstrap and command lifecycle APIs to register Quick Actions. Restart the server after replacing the JAR. FAWE remains required and Simple Voice Chat remains optional.


## Mace Race

Mace Race shares one prebuilt arena copy among all racers. It uses finish times instead of duel scores. It does not eliminate players or change their roles.

1. Save a map selection with `/arena`. Include the course, all checkpoint mobs, and the start platform inside the selection.
2. Open `/tournament` → **Setup Tools** → **Minigames** → **Race Courses**, then choose the map. **Set Start Here** saves your position as the race start; it also supplies a second spawn if the arena pool does not have one yet.
3. Place living mobs with the exact names `#1`, `#2`, `#3`, and so on. **Number New Mobs** automatically names mobs spawned inside this selection with spawn eggs or commands. Numbered mobs are frozen and protected from damage immediately. Toggle it off in the same menu, or disconnect, to stop numbering.
4. Choose **Mark Finish Mob**, close the dialog, and right-click the finish mob. Its default name is `Finish`. Existing manually named mobs work too.
5. Choose **Save Course & Rebuild Copies**. This checks for missing or duplicate numbers and exactly one finish mob, saves the blocks and entities with FAWE, and prepares the map's arena copies. Save again after editing the course.
6. Open `/tournament` → **New Tournament** → **Mace Race**. Choose a course and kit, enter contestant names, set the checkpoint jump and optional time limit, then review and create the race. Offline players can be entered beforehand. All remaining racers must be online and alive when you press **Start Race**. One player is allowed for testing.

Checkpoint and finish mobs are protected in the original map and during the race. Opening a map in **Race Courses** also protects existing manually named checkpoints inside its selection. They cannot move, take knockback, burn, transform, explode, or be collected in buckets. Protection returns when their chunks load, including after a restart. Unrelated mobs outside the course are unaffected. Valid race hits still trigger native Wind Burst. Minecraft 26.2 removes hostile mobs in Peaceful, so placing a hostile checkpoint changes a Peaceful source world to Normal; the race arena also uses Normal if it was Peaceful.

To remove a misplaced mob, open its **Race Courses** menu and choose **Get Removal Tool**. Hold the supplied shears and left-click or right-click the checkpoint or finish mob. The tool only edits its original map and requires tournament management permission. It preserves other inventory items and cannot break blocks or remove players. Remaining numbered checkpoints close the gap automatically: removing `#3` changes `#4` to `#3`, `#5` to `#4`, and so on. The finish mob keeps its finish name. Marking an existing numbered mob as the finish also closes its old gap.

The same reordering happens when `/kill` or another editor removes an original course mob. Chunk unloading does not count as removal. Reordering includes unloaded portions of the course, and unfinished work resumes after a restart. Race copies keep their damage and death protection. Use **Save Course & Rebuild Copies** after editing to apply the changes to future races; saving waits for pending reordering.

The default maximum jump is **15 checkpoints**: `#1 → #16` works; `#1 → #17` does not. Progress starts at 0. The finish mob counts as the checkpoint after the last number and follows the same jump limit. Hits on earlier checkpoints do not move your saved return point backward. Only direct hits with a mace or any spear advance progress; wind charges, pearls, and sweep attacks do not count. Out-of-range hits are blocked.

Each racer receives three unbreakable maces named `Wind Burst 1 Mace`, `Wind Burst 2 Mace`, and `Wind Burst 3 Mace`, with the corresponding enchantment levels; an unbreakable netherite spear with Lunge III; 64 wind charges; and 16 ender pearls. Right-click the bed to return above the last valid checkpoint, or above the start before the first checkpoint. Return height defaults to 6 blocks for both and can be changed in **Course Rules**. Courses saved with the old 3-block default upgrade to 6; other saved heights stay unchanged. You can rearrange your hotbar and inventory during the countdown and race, including moving the bed or using it from your offhand. Checkpoint mobs remain stationary and can take repeated hits without dying, allowing native Wind Burst to work. Active racers can hit each other with melee attacks and projectiles, with normal hit reactions and knockback. They keep full hunger and lose no health or absorption. Countdown players, finishers, and spectators cannot hit or be hit by racers. Players cannot edit the map or drop race equipment.

**Return on Ground** is enabled by default in **Course Rules**. After leaving the ground, landing returns a racer to their most recent checkpoint, or the start if they have not reached one. Standing on the starting platform does not trigger repeated returns. Turn this setting off for courses where players should be able to land. Falling below the course still returns racers to their checkpoint. The setting is saved per course as `return-on-ground` in `mace-race.yml`; existing courses default to enabled.

There is a ten-second action-bar countdown. During the race, chat announces finishers with their place and time. Each finisher launches three colorful fireworks that cannot damage players or checkpoint mobs. Tab and the board show the same leaderboard with tier tags, player heads, and finish times. Unfinished racers sort by checkpoint progress; disconnected racers remain visible with an X ping. Their timer keeps running, and reconnecting returns them to their checkpoint without replenishing their items. Finishers can fly around the course in spectator mode while the others finish. Exact time ties retain the server's order of finish hits.

The race ends after everyone finishes or withdraws, when the optional time limit expires, or when a director chooses **End Race**. That keeps recorded times and marks unfinished racers DNF. **Cancel Race** also returns players but clears the race from tab. **Manage Racers** lets a director withdraw someone; changing a racer to director or spectator also withdraws them. Duels and votes cannot take over an active racer's inventory. A new round robin requires the current race to be finished or cancelled.

The arena is reset before it is released for reuse. Inventory snapshots in `race-returns.yml` restore equipment, game mode, food, health, effects, and XP at the lobby, including for players who reconnect later. A server restart interrupts an active race and preserves its completed times; it does not restart the clock. Course rules, the roster, and last results are stored in `mace-race.yml`.

## Vote timer display

Run `/votetimer` to place the timer in front of you, or use `/tournament` → **Setup Tools** → **Board & Lobby** → **Place Vote Timer Here**. It appears during `/startvote <seconds>` and disappears when the vote ends, including an early `/endvote`. The location is saved for the next vote. `/votetimer remove` removes that location. The timer is larger than normal text; tournament board text is also slightly larger in this version.


## Minigame kits and event safety

Mace Race can use any saved kit that clears the previous inventory. Choose **Default Race Kit** to keep the original loadout, or pick a saved kit in the creation dialog. **Setup Tools → Minigames → Race Kits** opens an editable copy of the default loadout. The return bed starts in hotbar slot 9 and can be moved during the race; an item saved there moves to another empty storage slot. Leave at least one storage slot free. Race damage and hunger protection remain enabled regardless of the kit's flags.

Only one unfinished tournament stage can be selected at a time. A new mode requires the previous stage to finish or be cancelled. Event participants cannot be pulled into duels, votes, or another event while their inventory is owned by a minigame or waiting to be restored. Directors and spectators remain ineligible. Results, selected mode, and inventory recovery survive restarts; an interrupted game must be created again. No minigame changes player roles automatically.

Minigame results use the same tab and board as round robin, including player heads, tier tags, and X ping for offline players. `/bracket` also shows the selected event's standings. Cancelling returns the tab list to normal. Simple Voice Chat handles audio for minigame players and spectators normally; Contender does not separate their voices.

## Manhunt

1. Open `/tournament` → **Setup Tools → Minigames → Manhunt World** and choose **Prepare End World**. Preparation loads the island, finds its obsidian pillars, and freezes the dragon before the game starts.
2. Save the desired kit, then choose **New Tournament → Manhunt**. Enter the roster and either split teams randomly or enter separate runner and hunter lists. Both teams need at least one contestant. Offline entries work, but all remaining players must be online to start.
3. Review the teams and countdown, create the game, then choose **Start Manhunt**. Players spawn across the pillars, alternating teams. Pillars are shared when there are more players than pillars.

The countdown freezes movement, damage, entities, block changes, and item use. Once it ends, the dragon resumes flying and both teams have one life. Dead or disconnected players are out for the rest of that game; reconnecting lets them spectate. Any dragon death awards the runners a win. The result is announced and saved immediately, then everyone stays in the End for ten seconds to watch the death animation before returning to the lobby. Damage cannot change the result during that time, and `/cancelall` can still end the wait immediately. Losing every runner awards the hunters a win. Hunters dying does not end the game by itself. Saved kit rules control building, healing, and PvP/PvE Hurt behavior; void damage still applies.

Each game uses a fresh End world named `contender_manhunt_<unique id>`. Preparation generates and loads 289 chunks around the island before finding the pillar spawns. An unused prepared End survives restarts: Contender reloads its chunks and restores the dragon's AI after the countdown. Prepare another after finishing or cancelling a started game, since that End's dragon, crystals, and terrain may have changed. Previous worlds are unloaded when empty and retained on disk; remove unused ones while the server is stopped if you want to reclaim space. The End has no locator bar or advancement announcements. Results are in `manhunt.yml`, world preparation in `manhunt-world.yml`, and inventory recovery in `manhunt-returns.yml`.

## Combo

1. Save an arena with `/arena`. Include its platform and empty space above it. **Setup Tools → Minigames → Combo Arenas** lets you set the **Player Spawn** (Team 1) and **Bot Spawn** (Team 2), then rebuild copies.
2. Save a sword kit. Choose **New Tournament → Combo**, select the arena and player kit, enter the roster, and choose the bot difficulty and retry grace. The default grace is 5 hits.
3. Choose **Start Combo**. Online contestants automatically join the arena as spectators and take turns in roster order. Each player switches to survival with the chosen kit for their turn, then returns to spectating the remaining turns. The bot stays idle until the first accepted sword hit.

Late arrivals join as spectators once any pending inventory recovery finishes; offline contestants keep their place in the roster. Spectators can use `/lobby` to leave without losing their turn. They stay in the lobby until their turn starts or they choose to spectate again. Finishing or cancelling the event returns everyone to the lobby and restores their original inventory and game mode. Spectating during Combo does not change anyone's assigned role.

The mannequin uses the built-in Steve skin and player hurt sounds. It turns gradually, builds up movement input, and steers less while airborne so knockback can carry it away. Its aim must stay on the player's hitbox briefly before it can swing, with recovery time after each hit. Easy, Normal, and Hard use 2.2, 2.5, and 2.75 blocks of reach respectively; Normal waits at least one second between swings. It carries a diamond sword and wears the selected kit's armor.

When it hits back during hits 1–5, the player gets a fresh attempt. A returned hit after that records the score and moves to the next player. Grace can be changed from 0 to 100.

Knocking the bot below the arena into the void records `∞`, ranked above finite scores. Falling off yourself follows the same retry/score rule as a returned hit. Accepted direct sword hits count; projectiles and sweep attacks do not. A disconnect after the grace threshold records the current combo; an early disconnect leaves that player waiting for another turn. Players keep full hunger and do not lose health. Everyone sees the lime-green action bar `Combo: #` for the current attempt.

The event menu includes spectating, player management, and cancellation. Turn equipment and spawned projectiles are cleaned up between players, and the arena resets when the event ends. Scores survive restarts in `combo.yml`; inventories restore at the lobby from `combo-returns.yml`. This bot is an independent implementation inspired by the requested behavior; no SwightLock source was copied.
