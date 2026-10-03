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
4. **Kits.** `/tournament` → **Setup Tools** → **Kits** → **Create New Kit**. Name it, then lay out the items and armor. **Health Under Name** (off by default) shows each player's health as `17 ❤` under their name, out of 20, with one decimal place below 3. Extra hearts (a Juggernaut, or a hacker's) still read out of 20. Everyone playing or watching a game with that kit sees it.

Outside a game, players can't break or place blocks anywhere unless **Lobby Settings** (`/options`) allows it. Operators can still build with the admin build bypass. In a match or minigame the kit's **Break Blocks** and **Place Blocks** settings apply instead, and only blocks placed during that match can be broken.

`/tournament` is the main menu. Everything below starts from there unless a command is given.

## Events

**Round Robin.** Everyone plays everyone. Round 1 follows the roster order: 1st vs 2nd, 3rd vs 4th, and so on. Matches run in parallel as players and arena copies free up. Each match won is worth 1 point; round difference breaks ties. You can pause, resume, end a match early or cancel one from the event menu. If something goes wrong, open the match from **Matches**:

- **Change Result** fixes a finished match's score. The standings update right away.
- **Replay Match** clears a finished match so it's played again.
- **Change Score** corrects a live match's round wins.
- **Replay Round** plays a live match's round again. Mid-fight, the round restarts on a fresh arena. Between rounds, the round that just ended is replayed and its point is taken back. `/replayround [player]` does the same for any match, including one-off duels. With no name, it uses the match you're in or watching.

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

Players go in the order you type them in the Players box. That sets the Combo turn order, the Winner Stays On queue (the first two fight first) and the first Juggernaut. While Winner Stays On runs, the tab list follows the queue: the two fighters, then who's next in line.

Minigames that need building have their own page in **Setup Tools** → **Minigames**:

- **Mace Race courses.** Choose **New Course**, name it, and you get a hotbar of building tools. Right-click the blaze rod to drop a checkpoint where you stand, even in midair. Sneak to place it on the block you're looking at instead. The other tools change a checkpoint's mob, move it, insert one before it, or remove it, and the bed sets the start. Checkpoints glow and are joined by a particle trail while you build. The last checkpoint is the finish. Courses made with the old named-mob setup show an **Import** button.
- **Manhunt.** Choose **Prepare a Fresh End** before each game. It takes a few seconds, and the End stays frozen until the game starts.

When a minigame starts, directors and spectators who aren't busy are brought into the arena to watch, and they go back to the lobby when it ends. Turn this off with `minigames.bring-watchers: false` in `config.yml`.

During a minigame, **Manage Players** lets you correct a player's score (hill points, time as Juggernaut, Winner Stays On wins, a finished Combo turn, or lives left) or withdraw them.

If the server restarts mid-event, a tournament comes back paused with its results so far, and a minigame comes back stopped with its standings. Everyone returns to the lobby with their own items.

## Watching

`/spectate` lists live matches and minigames. Spectators fly in Spectator mode inside the arena. `/lobby` leaves.

The **Board** shows the bracket or leaderboard. Look at a wall and run `/setboard` (or Setup Tools → Board & Lobby → Place Board) to hang it flat on the wall, centered where you look. It never sinks into the floor as it grows. Look anywhere else and it stands on the floor in front of you. `/setboard remove` takes it down. Players can click a match on it to watch. Switching rounds, standings or pages only changes the board for the player who clicked. Everyone else keeps the live board, and **Back to live view** returns to it. **Display Colors** switches the color scheme for the board, holograms, vote timers and fireworks: Ocean, Amethyst, Sunset, Gold, Mint, Crimson, Frost or Mono.

## Votes

**Start a Vote** in `/tournament` (or `/startvote`) opens the vote form. Players vote with `/vote <name>` or by clicking a player in `/vote`. Nobody can vote for themselves.

Form options:

- **Show Counts While Voting** shows how many votes each player has in the vote menu. When it's off, nobody sees counts there, directors included.
- **Results**: the reveal ceremony, or chat only.
- **Voted-Out Player**:
  - Dies, then spectates (the default). Lightning strikes, they die where they stand (keeping their items), respawn on the same spot and become a spectator.
  - Becomes a spectator, without dying.
  - Stays a contestant.
- **Rooms**: see below.
- **Choose Players**: click a contestant to make them **Safe** (they vote but can't be voted for), click again for **Sitting Out** (they don't vote at all and wait in the judge room), and again to put them back in. **Everyone In** clears it.
- **Anonymous Votes** (No by default). While it's No, once voting closes, a small display over each voter shows who they voted for (or that they didn't vote), so everyone is held to their vote. With the ceremony they appear once the spotlight lands, so they don't give the result away. They stay for 10 seconds and follow the players, and the vote isn't over until they're gone.

With `/startvote <seconds>`, add `live`, `quick` (chat only), `spectate` or `keep` (instead of dying), `stay` or `nomove` (rooms), `anonymous` (hide who voted for whom), `safe:Alice,Bob` and `sitout:Carol`.

**Edit Votes.** While a vote runs, directors have an **Edit Votes** button in `/vote`. It lists who voted for whom with the totals. Click a voter to change their vote or take it away. Players aren't told. It's meant for testing.

**The ceremony.** Stand on the middle of your stage, facing the audience, and run `/setvotestage`. When voting closes, contestants walk into a straight line on the stage, facing the way you faced. The line runs along whichever of X or Z has more floor. The stage is the floor at the same height as the spot you set, up to a wall, a gap or a step, so a raised platform works well. A spotlight then sweeps back and forth along the line, slows down and stops on whoever got the most votes. On a tie it slows down over one of the tied players as if it's about to land, then pulls back up into the sky without picking anyone. The result goes in chat; there are no on-screen titles. Contestants further than 40 blocks away (or in another world) are teleported into line instead.

**Rooms.** Stand where contestants should vote, facing the way they should look, and run `/setvotingroom`. Do the same with `/setjudgeroom` for everyone who isn't voting: spectators, camera crew and directors. When a vote starts, players are spread out on the floor around each spot. Anyone in a match, interview or spectating stays put. The **Rooms** option decides what happens afterwards:

- **Move, Then Send Back** (the default) returns everyone to where they were once the results finish.
- **Move and Leave There** leaves them in the rooms.
- **Don't Move Anyone** skips the rooms.

**Timers.** Look at a wall and run `/votetimer` to hang a countdown (just `00:45`) flat on it. Look anywhere else and it floats in front of you. Add a size from 1 to 10 for a bigger or smaller one, for example `/votetimer 6`; the default is 4. You can place several. The last 10 seconds tick. Once the results are in (right after the spotlight, or a second or two after voting closes when there's no ceremony), each timer turns into the results: a player's head and their vote count for everyone who got votes, most first. With no votes at all it says so. `/votetimer remove` takes down the nearest timer, and `/votetimer clear` removes them all.

All of this is also in `/tournament` → **Setup Tools** → **Vote Setup**.

## Interviews

Set the two marks once with `/setinterviewerposition` and `/setintervieweeposition`. Each saves where you stand and which way you face. **Interview a Player** in `/tournament` (or `/interview <player>`) brings you and that player to them. **End Interview** (or `/uninterview`) sends both back where they were.

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
| `/vote [name]`, `/startvote`, `/endvote` | Votes |
| `/setboard`, `/votetimer`, `/setvotestage`, `/setvotingroom`, `/setjudgeroom` | The board, vote timers, the reveal stage and the voting rooms |
| `/hacks`, `/hackers`, `/pickhacker`, `/sabotage` | Hacks and sabotage |
| `/interview`, `/uninterview` | Interviews |
| `/setinterviewerposition`, `/setintervieweeposition` | Interview marks |
| `/endduel`, `/cancelduel`, `/replayround` | End a match now (keeping the score), cancel it, or replay a round |
| `/cancelall` | Stop everything and send everyone to the lobby |
| `/contender status`, `/contender reload` | Health check, reload config |

## Testing with fake players

`dist/FakePlayers.jar` adds server-side fake players for rehearsals. It's based on the UHCR FakePlayers tool. To Contender and every other plugin they're real players: they join, show in the tab list, get kits, fight, vote and run commands. Only the network connection is fake. Put it in `plugins/` on a test server (Paper 26.2) and take it out before the real event.

| Command | What it does |
| --- | --- |
| `/bots spawn 8` | Spawns Bot_1 to Bot_8. They join like real players, so they arrive at the lobby |
| `/bots spawn 8 16` | Spawns them spread out within 16 blocks of you instead |
| `/bots spawn Alice Bob` | Spawns bots with these names where you stand |
| `/bots wander on\|off` | Bots walk around near where they are |
| `/bots fight on\|off` | Bots chase and hit the nearest player |
| `/bots tphere`, `/bots gather <radius>` | Bring every bot to you, or spread them out |
| `/bots cmd <bot\|all> <command>` | Run a command as a bot, for example `/bots cmd all vote 2` |
| `/bots chat <bot\|all> <message>` | Send chat as a bot |
| `/bots remove [all\|name]` | Remove bots |
| `/bots chatlog chat` | Record what the bots see in chat to `plugins/FakePlayers/chat.log` |

Fake players start as contestants, so `/role` them like anyone else.

## Building

```sh
mvn package
```

The jar is written to `target/`. Tests run with `mvn test`.
