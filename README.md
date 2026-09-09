# BardBot

Character profiles, virtue tracking and noble houses for Bardonia, in Discord.

The tribunal awards Bards four virtues: Honor, Merit, Glory and Fame. Those scores build a
character profile, unlock titles a Bard wears in their name, and earn renown for their noble house,
which the houses compete on every month.

## How it works

**Virtue is permanent.** Every award is kept forever in an append only log. Nothing is ever edited
or deleted, so a mistake is corrected by awarding the opposite amount and the record shows both.
When a number is eventually argued about, that log is what settles it.

**Renown is a window over the same awards.** A house's renown is the virtue its members were
awarded between the first of the month and now, in the guild's own timezone. Nothing is wiped when
the month turns: the window simply moves, and renown reads zero on its own. That is why a
correction made in March also fixes February's figure, and why moving house does not take last
month's renown along.

**Every figure is summed when it is asked for.** Scores, totals, leaderboards and house renown are
all read from the log rather than kept as counters beside it, so no two of them can disagree.

## Commands

Anyone:

| Command | What it does |
| --- | --- |
| `/profile view` | A Bard's character sheet, virtues and house |
| `/profile edit identity` | Name, gender, age, character image, wiki page |
| `/profile edit lore` | The character's story |
| `/virtue leaderboard` | Who stands where, in one virtue or the total |
| `/virtue goals` | What each score unlocks, and how far along you are |
| `/virtue history` | Every award a Bard has been given |
| `/title list`, `/title set` | The titles you hold, and which you wear |
| `/title roster` | Everyone holding a government title |
| `/house view`, `/house list` | Houses, their renown and their people |
| `/house leaderboard`, `/house winner` | Renown this month, all time, and last month's result |
| `/house accept`, `/decline`, `/leave` | Joining and leaving |
| `/help` | How all of it works |

House heads, on their own house: `/house edit`, `/house invite`, `/house expel`, and
`/house title add|remove|grant|revoke`.

Tribunal only:

| Command | What it does |
| --- | --- |
| Apps to Award Virtue | Right click a message to award the Bard who posted it |
| `/award` | Award without a message to point at |
| `/admin channel set` | Where awards, unlocks, renown and console output are posted |
| `/admin goal add\|remove\|list` | Virtue thresholds and the titles behind them |
| `/admin house add\|remove\|addhead\|removehead` | Founding and running houses |
| `/admin title grant\|revoke` | Government titles |
| `/admin leaderboard virtue\|house` | Plant a board that keeps itself up to date |
| `/admin help` | How all of that works |

### Awarding

The right click menu is the main route. Read something worth rewarding, right click the message,
Apps, Award Virtue, pick one of four buttons, then fill in the amount and reason. The bot replies
to that message with the award and the Bard's new scores. A slash command cannot be used as a reply
to a message, which is why this is a message command rather than `/award` with a message link.

Awards can be negative, and that is the correction path.

### Titles

Three slots, filled by three different authorities. Noble titles come from a Bard's house and are
granted by its head. Virtue titles are unlocked by reaching a score and nobody grants them.
Government titles are given by the tribunal.

They read as one line, `Lord Azurov the Veteran of The Tribunal`. The words "the" and "of" are put
in by the bot, so no title contains them, and a slot left empty is skipped along with its joining
word.

Nothing is typed. `/title set` autocompletes to the titles that Bard actually holds. Titles are
worked out from whatever granted them rather than stored, so one cannot outlive its source: leaving
a house takes its titles with it, and a virtue title is held for exactly as long as the score
behind it.

### The monthly cycle

On the first of the month the standings are archived and posted to the renown channel. The archive
is written before the message is sent, so a restart cannot settle the same month twice, and the
roll catches up on every month it missed if the bot was down across a boundary.

`/house winner` reads that archive rather than recomputing, so a late correction cannot rewrite who
won.

## Setup

1. `cp .env.example .env` and fill in `DISCORD_TOKEN`. That file is gitignored; keep it that way.
2. `cp config.properties.example config.properties` and fill in `discord.guild.id` and
   `discord.roles.tribunal`. Set `renown.roll.zone` to whatever the guild considers its own time,
   since it decides where a month begins.
3. `mvn package`, then run the jar from `target/`.

Configuration is read from the filesystem beside the jar, not from inside it, so editing it and
restarting is enough. Runtime state is written to `data/`.

Which channels the bot posts in is not configured in the file. Go to the channel you want and run
`/admin channel set`, so it can be changed without a restart.

### Invite

Permissions are the smallest set where every command works: View Channels, Send Messages and Embed
Links, which is `19456`, plus the `applications.commands` scope.

```
https://discord.com/oauth2/authorize?client_id=YOUR_APPLICATION_ID&scope=bot%20applications.commands&permissions=19456
```

In the developer portal, turn the **Server Members Intent** on, so leaderboards can resolve a page
of names at once. Leave **Message Content Intent** off. The bot never reads messages it was not
explicitly handed.

## Security

The bot talks to Discord and nothing else. It has no Minecraft client, no database, and makes no
requests of its own: character images and house crests are links handed to Discord to fetch, so a
Bard editing their own profile cannot point the bot at anything.

That is enforced rather than promised. `NoNetworkTest` fails the build if any class in the project
reaches for the network.

The token lives only in the environment. Configuration and runtime state are gitignored, and
neither is ever quoted in a log or a reply.

## Development

- `mvn test` runs the suite, including the architecture test above.
- Java 21.

Three conventions worth knowing before adding a command.

**Commands declare themselves.** A command is one file implementing `SlashCommand`, naming itself,
its options and the feature it belongs to. Registration reads that list, so it cannot drift out of
step with what is actually handled, and a disabled feature's commands are never registered at all.

**Every name was typed by a person.** Character names, house names, mottos and titles reach Discord
through `Names.escaped`, which escapes the underscores and asterisks Discord reads as formatting.
There are three places where escaping is wrong rather than optional: inside a code fence, inside a
link label, and in anything Discord does not format at all, meaning embed titles, footers and
autocomplete choices. Use `Names.plain` for those.

**Nothing that can be derived is stored.** Virtue, renown, the family tree and the titles a Bard
holds are all computed where they are shown. That is what stops two figures disagreeing, and it is
why correcting an award fixes every number it touched without anything having to go and find them.

## Licence

Intentionally none yet. Adding one is a decision to make deliberately, before anyone else
contributes, because relicensing later needs every copyright holder to agree.
