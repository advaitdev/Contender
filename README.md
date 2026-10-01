# Contender

Contender runs PvP events on a Paper server: round robin tournaments, one-off duels, minigames, community votes and interviews. It was built for videos where a few contestants are secretly hacking, so it also handles the hacks and lets hackers sabotage everyone.

Everything is controlled from in-game dialogs. Players don't need a resource pack.

## Requirements

- Paper 26.2 and Java 25
- [FastAsyncWorldEdit](https://modrinth.com/plugin/fastasyncworldedit) (required, for arena copies)
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (optional)

Drop the jar from `dist/` into `plugins/` and start the server. Operators have both permissions by default.

| Permission | What it allows |
| --- | --- |
| `contender.master` | Running events and every director tool |
| `contender.admin` | Lobby, chat and PvP settings, and build bypasses |

## First-time setup

1. **Lobby.** Stand where players should arrive and run `/setlobby`.
2. **Roles.** Everyone starts as a contestant. Use `/role <player> director` for the crew and `/role <player> spectator` for people who only watch.
3. **Maps.** Build an arena, select it with the WorldEdit wand, then `/arena` → **New Map from Selection**. Set both spawns from the map's page. Contender pastes copies of it into its own world so many matches can run at once.
4. **Kits.** `/tournament` → **Setup Tools** → **Kits** → **Create New Kit**. Name it, then lay out the items and armor.

`/tournament` is the main menu. Everything below starts from there unless a command is given.

## Events

**Round Robin.** Everyone plays everyone. Matches run in parallel as players and arena copies free up. Each match won is worth 1 point; round difference breaks ties. You can pause, resume, end a match early or cancel one from the event menu.

**Duel.** `/duel` starts a single match outside any event.

**Minigames.** Pick one from **New Event**:

| Minigame | How it works |
| --- | --- |
| Mace Race | Bounce off checkpoint mobs with Wind Burst maces. Fastest time wins. |
| Combo | One turn each against a practice bot. Longest combo wins; knock the bot into the void for ∞. |
| Manhunt | Runners try to kill the dragon in a fresh End while hunters hunt them. One life each. |
| Last Man Standing | Free-for-all with a set number of lives. |
| King of the Hill | Stand on the hill to score. First to the target, or the most when time runs out. |
| Winner Stays On | One fight at a time. The winner keeps fighting until someone beats them. |
| Juggernaut | One player gets extra hearts and glows. Take them down to take their place; the longest time as Juggernaut wins. |

Minigames that need building have their own page in **Setup Tools** → **Minigames**:

- **Mace Race courses.** Choose **New Course**, name it, and you get a hotbar of building tools. Right-click the blaze rod to drop a checkpoint where you stand, even in midair. Sneak to place it on the block you're looking at instead. The other tools change a checkpoint's mob, move it, insert one before it, or remove it, and the bed sets the start. Checkpoints glow and are joined by a particle trail while you build. The last checkpoint is the finish. Courses made with the old named-mob setup show an **Import** button.
- **Manhunt.** Choose **Prepare a Fresh End** before each game. It takes a few seconds, and the End stays frozen until the game starts.

If the server restarts mid-event, a tournament comes back paused with its results so far, and a minigame comes back stopped with its standings. Everyone returns to the lobby with their own items.

## Watching

`/spectate` lists live matches and minigames. Spectators fly in Spectator mode inside the arena and show up as small allays to the fighters (hidden when close, or if the kit says so). `/lobby` leaves.

The **Board** (Setup Tools → Board & Lobby) places a floating bracket or leaderboard where you stand. Players can click a match on it to watch. **Display Colors** switches the color scheme for the board, holograms, vote numbers and fireworks: Ocean, Amethyst, Sunset, Gold, Mint, Crimson, Frost or Mono.

## Votes

**Start a Vote** in `/tournament` (or `/startvote`) opens the vote form, or start one directly with `/startvote <seconds> [live] [quick] [eliminate]`. Every candidate gets a number above their head, and players vote with `/vote <number>` or the vote menu. Nobody can vote for themselves.

- `live` shows running counts.
- `quick` skips the reveal and posts the result in chat.
- `eliminate` makes the loser a spectator.

Set the reveal spot with `/setvotestage`. When the vote closes, candidates gather in a circle there, the counts tick up together and a spotlight settles on whoever was voted out. `/votetimer` places a countdown hologram.

## Interviews

Set the two marks once with `/setinterviewerposition` and `/setintervieweeposition`. **Interview a Player** in `/tournament` (or `/interview <player>`) brings you and that player to them. **End Interview** (or `/uninterview`) sends both back where they were.

## Hackers

`/hackers` → **Choose Hackers** picks who's hacking (or use `/pickhacker <names>`). Hacks are reach, attack speed, damage, resistance, anti-knockback, speed, jump, step height, regeneration and no-fall. Each has presets from Subtle to Blatant.

There are two modes:

- **Hackers choose.** Hackers open `/hacks` and set their own.
- **Director chooses.** You pick each hacker's hacks in **Hacks for This Event**. They switch on when an event starts and off when it ends, and hackers can only look.

### Sabotage

Turn it on in `/hackers` → **Sabotage Settings**. Hackers open `/sabotage` during an event and set off something that hits everyone in it, themselves included unless you change that. You choose how long sabotages last (or the whole event), how many each hacker gets, the cooldown, how many can run at once, which ones are allowed and whether the hacker's name is revealed.

| Sabotage | Effect |
| --- | --- |
| Lag Spike | Everyone's ping jumps up. Hits land late. |
| Double Health | Everyone gets twice the hearts. |
| Glass Cannon | Hits deal double damage, but everyone has half the hearts. |
| Tiny Fighters | Everyone shrinks to half size. |
| Giants | Everyone grows taller, with longer reach. |
| Moon Gravity | Gravity drops. Every hit sends people flying. |
| Heavy Hits | Every hit knocks people much further. |
| Sugar Rush | Everyone moves a third faster. |
| Rusty Swords | Weapons take much longer to recharge. |
| Pogo | Everyone jumps twice as high and takes less fall damage. |
| Spotlight | Everyone glows through walls. |
| Vampire | Hitting someone heals you. |
| Fragile | Everyone takes 50% more damage. |
| Butterfingers | Hotbars shuffle every few seconds. |
| Switcheroo | Opponents swap places every so often. |
| Nameless | Every nametag disappears. |
| Blackout | Screens go dark every few seconds. |
| Famished | Hunger drains fast, so healing stops. |

## Voice chat

With Simple Voice Chat installed, **Directors Heard Everywhere** (`/options` → Chat & Voice) lets the crew talk to everyone. Players in Spectator mode can only be heard if `spectator_interaction=true` is set in `plugins/voicechat/voicechat-server.properties`. `/contender voice` checks the setup.

## Commands

| Command | Use |
| --- | --- |
| `/tournament` | Main menu: events, setup tools, hacker controls |
| `/duel` | Start a one-off match |
| `/arena` | Maps, spawns and arena copies |
| `/spectate`, `/lobby` | Watch something, go back |
| `/bracket` | Choose what your tab list shows |
| `/options` | Settings |
| `/role`, `/tier` | Player roles, MCTiers lookups |
| `/vote`, `/startvote`, `/endvote` | Votes |
| `/votetimer`, `/setvotestage` | Vote displays |
| `/hacks`, `/hackers`, `/pickhacker`, `/sabotage` | Hacks and sabotage |
| `/interview`, `/uninterview` | Interviews |
| `/setinterviewerposition`, `/setintervieweeposition` | Interview marks |
| `/endduel`, `/cancelduel` | End a match now (keeping the score) or cancel it |
| `/cancelall` | Stop everything and send everyone to the lobby |
| `/contender status`, `/contender reload` | Health check, reload config |

## Building

```sh
mvn package
```

The jar is written to `target/`. Tests run with `mvn test`.
